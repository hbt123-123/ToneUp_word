package top.toneup.word.data.repository

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import top.toneup.word.core.result.Outcome
import top.toneup.word.core.time.SystemTimeProvider
import top.toneup.word.core.time.TimeProvider
import top.toneup.word.data.update.ApkDownloader
import top.toneup.word.data.update.ApkInstaller
import top.toneup.word.data.update.AppInstallRequest
import top.toneup.word.data.update.AppUpdateCheckResult
import top.toneup.word.data.update.AppUpdateDefaults
import top.toneup.word.data.update.AppUpdateStoredState
import top.toneup.word.data.update.AppUpdateStore
import top.toneup.word.data.update.DataStoreAppUpdateStore
import top.toneup.word.data.update.GitHubReleaseHttpSource
import top.toneup.word.data.update.GitHubReleaseSource
import top.toneup.word.data.update.OkHttpApkDownloader
import top.toneup.word.data.update.PendingAppUpdate
import top.toneup.word.data.update.RemoteRelease
import top.toneup.word.domain.update.AppUpdatePolicy
import top.toneup.word.domain.update.AppVersion
import top.toneup.word.domain.update.UpdateDecision
import top.toneup.word.work.AppUpdateWorker
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Orchestration for the in-app update pipeline: GitHub Releases check → silent resumable
 * download → install prompt.
 *
 * Contract with the rest of the app:
 *  * `MainViewModel` calls [enqueueCheckOnLaunch] on every process start (throttled) and
 *    observes [observePendingInstall] to raise the install dialog;
 *  * `AppUpdateWorker` calls [checkAndDownload] with network constraints and exponential
 *    backoff — a killed worker resumes from the partial file;
 *  * the install tap goes through [installRequest] so the Activity never touches files.
 */
interface AppUpdateRepository {

    /** Emits a non-null update as soon as an intact APK for a newer version is on disk. */
    fun observePendingInstall(): Flow<PendingAppUpdate?>

    /** Throttled (≥12h), idempotent enqueue of the unique check+download work. */
    suspend fun enqueueCheckOnLaunch()

    /** One full run: fetch release → decide → download (resume) → persist. Worker entry point. */
    suspend fun checkAndDownload(): AppUpdateCheckResult

    /** "下次再说": suppress this version until a newer one appears. Keeps the APK on disk. */
    suspend fun dismissPendingInstall()

    /** Resolved install intent (or the unknown-sources settings screen) for the pending APK. */
    suspend fun installRequest(): AppInstallRequest
}

class DefaultAppUpdateRepository(
    private val context: Context,
    private val time: TimeProvider,
    private val releaseSource: GitHubReleaseSource,
    private val downloader: ApkDownloader,
    private val store: AppUpdateStore,
    /** The installed `versionName`; constructor-injected so tests never read a package manager. */
    private val installedVersion: String,
    /** Injectable so tests run on a plain temp dir instead of a real cache dir. */
    private val apkFile: File = File(File(context.cacheDir, AppUpdateDefaults.UPDATE_DIR), AppUpdateDefaults.APK_FILE_NAME),
    private val canRequestInstall: () -> Boolean = { context.packageManager.canRequestPackageInstalls() },
) : AppUpdateRepository {

    override fun observePendingInstall(): Flow<PendingAppUpdate?> = store.state()
        .map { state ->
            val tag = state.downloadedTag ?: return@map null
            if (isDismissed(state)) return@map null
            val size = state.downloadedSize ?: return@map null
            val file = apkFile
            // Cache eviction (or a hand-cleared dir) turns the pending update into a no-op;
            // the next check re-downloads instead of showing an install that would fail.
            if (!file.isFile || file.length() != size) return@map null
            PendingAppUpdate(
                versionName = state.downloadedVersion?.takeIf { it.isNotBlank() }
                    ?: tag.removePrefix("v"),
                tagName = tag,
                apk = file,
            )
        }
        .flowOn(Dispatchers.IO)

    override suspend fun enqueueCheckOnLaunch() {
        val state = store.read()
        if (!AppUpdatePolicy.shouldCheckNow(
                state.lastCheckedMillis,
                time.nowMillis(),
                AppUpdateDefaults.MIN_CHECK_INTERVAL_MILLIS,
            )
        ) {
            return
        }
        // A pending install with an intact file means everything is already fetched — the
        // prompt flow above will emit; a check would only burn API quota.
        if (state.downloadedTag != null && pendingFileIntact(state.downloadedSize)) return
        try {
            val request = OneTimeWorkRequestBuilder<AppUpdateWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, AppUpdateDefaults.BACKOFF_SECONDS, TimeUnit.SECONDS)
                .addTag(AppUpdateDefaults.WORK_TAG)
                .build()
            // KEEP: an already-running or finished check is never restarted by a re-enqueue.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(AppUpdateDefaults.WORK_NAME, ExistingWorkPolicy.KEEP, request)
        } catch (_: Throwable) {
            // WorkManager unavailable — the update simply waits for the next app start.
        }
    }

    override suspend fun checkAndDownload(): AppUpdateCheckResult {
        val release = when (val fetched = releaseSource.fetchLatest()) {
            is Outcome.Failure -> return AppUpdateCheckResult.Failed(fetched.error)
            is Outcome.Success -> fetched.value
        }
        val state = store.read()
        return when (AppUpdatePolicy.decide(release.tagName, installedVersion, state.downloadedTag, state.dismissedTag)) {
            UpdateDecision.UpToDate,
            UpdateDecision.Dismissed,
            UpdateDecision.Unknown,
            -> {
                store.markChecked(time.nowMillis())
                AppUpdateCheckResult.NothingToDo
            }

            UpdateDecision.Ready ->
                if (pendingFileIntact(release.apkSize)) {
                    store.markChecked(time.nowMillis())
                    AppUpdateCheckResult.AlreadyReady
                } else {
                    // The cached file vanished (cache eviction): re-download the same tag.
                    download(release)
                }

            UpdateDecision.Download -> download(release)
        }
    }

    override suspend fun dismissPendingInstall() {
        val tag = store.read().downloadedTag ?: return
        store.setDismissed(tag)
    }

    override suspend fun installRequest(): AppInstallRequest {
        val state = store.read()
        val size = state.downloadedSize
        if (state.downloadedTag == null || size == null || !pendingFileIntact(size)) {
            return AppInstallRequest.Unavailable
        }
        return if (canRequestInstall()) {
            AppInstallRequest.Ready(ApkInstaller.installIntent(context, apkFile))
        } else {
            AppInstallRequest.NeedPermission(ApkInstaller.unknownSourcesSettingsIntent(context))
        }
    }

    private suspend fun download(release: RemoteRelease): AppUpdateCheckResult {
        when (val result = downloader.download(release.apkUrl, apkFile, release.apkSize)) {
            is Outcome.Failure -> return AppUpdateCheckResult.Failed(result.error)
            is Outcome.Success -> Unit
        }
        store.setDownloaded(
            tag = release.tagName,
            version = AppVersion.parse(release.tagName)?.let(AppVersion::format)
                ?: release.tagName.removePrefix("v"),
            size = release.apkSize,
        )
        // Only a *successful* run counts against the throttle window.
        store.markChecked(time.nowMillis())
        return AppUpdateCheckResult.Downloaded
    }

    private fun pendingFileIntact(expectedSize: Long?): Boolean {
        if (expectedSize == null) return false
        return apkFile.isFile && apkFile.length() == expectedSize
    }

    private suspend fun isDismissed(state: AppUpdateStoredState): Boolean {
        val downloaded = AppVersion.parse(state.downloadedTag) ?: return false
        val dismissed = AppVersion.parse(state.dismissedTag) ?: return false
        return !AppVersion.isNewer(downloaded, dismissed)
    }
}

/** One place building the production graph, shared by `AppContainer` and the worker. */
object AppUpdateFactory {

    fun create(context: Context): AppUpdateRepository =
        create(context.applicationContext, installedVersion = readInstalledVersion(context))

    fun create(context: Context, installedVersion: String): AppUpdateRepository {
        val appContext = context.applicationContext
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        return DefaultAppUpdateRepository(
            context = appContext,
            time = SystemTimeProvider(),
            releaseSource = GitHubReleaseHttpSource(client),
            downloader = OkHttpApkDownloader(client),
            store = DataStoreAppUpdateStore(appContext),
            installedVersion = installedVersion,
        )
    }

    fun readInstalledVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")
}
