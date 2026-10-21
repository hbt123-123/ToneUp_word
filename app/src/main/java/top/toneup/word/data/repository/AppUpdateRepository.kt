package top.toneup.word.data.repository

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import top.toneup.word.core.result.Outcome
import top.toneup.word.core.time.SystemTimeProvider
import top.toneup.word.core.time.TimeProvider
import top.toneup.word.data.update.ApkDownloader
import top.toneup.word.data.update.ApkInstaller
import top.toneup.word.data.update.AppInstallRequest
import top.toneup.word.data.update.AppUpdateDefaults
import top.toneup.word.data.update.AppUpdateStoredState
import top.toneup.word.data.update.AppUpdateStore
import top.toneup.word.data.update.AppUpdateUiState
import top.toneup.word.data.update.DataStoreAppUpdateStore
import top.toneup.word.data.update.ManifestResult
import top.toneup.word.data.update.OkHttpApkDownloader
import top.toneup.word.data.update.OkHttpUpdateManifestSource
import top.toneup.word.data.update.UpdateManifestSource
import top.toneup.word.data.update.UpdatePhase
import top.toneup.word.data.update.buildUpdateHttpClient
import top.toneup.word.domain.update.AppUpdatePolicy
import top.toneup.word.domain.update.RemoteUpdate
import top.toneup.word.domain.update.UpdateDecision
import java.io.File

/**
 * Orchestration for the in-app update pipeline: manifest check → silent resumable download →
 * SHA-256 verification → install prompt.
 *
 * The observable contract is [state]; the UI never touches files or HTTP. Both the automatic
 * launch check and the manual "检查更新" row call the same [checkNow]/[autoCheck] path, so the
 * two entry points cannot disagree.
 *
 * Ready state is derived from disk (`cacheDir/updates/<versionCode>.apk`), not from a flag, so a
 * killed process or an evicted cache degrades to "no update" instead of a broken install.
 */
interface AppUpdateRepository {
    val state: StateFlow<AppUpdateUiState>

    /** Restores a previously downloaded APK and prunes files for the installed version. */
    suspend fun init()

    /** Throttled (24 h) launch check; a successful manifest fetch extends the window. */
    suspend fun autoCheck()

    /** Manual check, never throttled. */
    suspend fun checkNow()

    /** "立即更新": download (resumable) and, when verified, raise an install request. */
    suspend fun downloadAndInstall()

    /** "跳过此版本": suppress this versionCode and delete its downloaded APK. */
    suspend fun skipVersion()

    /** "立即安装": raise an install request for the verified APK. */
    suspend fun installReady()

    /** One-shot install request, consumed by MainActivity after it launches the Intent. */
    fun consumeInstallRequest()

    /** Resolved install intent (or the unknown-sources settings screen) for the pending APK. */
    suspend fun installRequest(): AppInstallRequest
}

class DefaultAppUpdateRepository(
    private val context: Context,
    private val time: TimeProvider,
    private val source: UpdateManifestSource,
    private val downloader: ApkDownloader,
    private val store: AppUpdateStore,
    private val installedVersion: String,
    private val installedVersionCode: Int,
    /** Injectable so tests run on a plain temp dir instead of the real cache dir. */
    private val updateDir: File = File(context.cacheDir, AppUpdateDefaults.UPDATE_DIR),
    private val canRequestInstall: () -> Boolean = { context.packageManager.canRequestPackageInstalls() },
) : AppUpdateRepository {

    private val _state = MutableStateFlow(AppUpdateUiState(installedVersion = installedVersion))
    override val state: StateFlow<AppUpdateUiState> = _state.asStateFlow()

    private var remote: RemoteUpdate? = null

    override suspend fun init() = withContext(Dispatchers.IO) {
        pruneLocal()
        val stored = store.read()
        val ready = readyFile(stored)
        when {
            ready == null -> Unit
            stored.dismissedVersionCode > 0 && ready.versionCode <= stored.dismissedVersionCode -> {
                // The learner skipped this build; do not resurrect its file or prompt.
                ready.file.delete()
                store.clearReady()
            }
            else -> {
                val display = if (
                    stored.readyVersionCode == ready.versionCode && stored.readyVersionName.isNotBlank()
                ) {
                    stored.readyVersionName
                } else {
                    displayNameFor(ready.versionCode)
                }
                _state.update {
                    it.copy(
                        phase = UpdatePhase.Ready,
                        version = display,
                        versionCode = ready.versionCode,
                        size = stored.readySize,
                        received = ready.file.length(),
                        total = ready.file.length(),
                    )
                }
            }
        }
    }

    override suspend fun autoCheck() {
        val stored = store.read()
        if (!AppUpdatePolicy.shouldCheckNow(
                stored.lastCheckedMillis,
                time.nowMillis(),
                AppUpdateDefaults.AUTO_CHECK_INTERVAL_MILLIS,
            )
        ) {
            return
        }
        check(manual = false)
    }

    override suspend fun checkNow() = check(manual = true)

    private suspend fun check(manual: Boolean) {
        val current = _state.value
        if (current.phase == UpdatePhase.Checking ||
            current.phase == UpdatePhase.Downloading ||
            current.phase == UpdatePhase.Verifying
        ) {
            return
        }

        val previous = current
        _state.update { it.copy(phase = UpdatePhase.Checking, error = "", received = 0) }
        val stored = store.read()
        val now = time.nowMillis()
        val result = source.fetch(
            preferredHost = stored.okHost,
            deadHosts = stored.deadHosts.filterValues { it > now },
            nowMillis = now,
        )

        when (result) {
            is ManifestResult.Failure -> {
                // Only connection failures enter the cooldown; a 404 still proves the channel works.
                store.setDeadHosts(mergeDeadHosts(stored.deadHosts, result.deadHosts, now))
                val fallback = when (previous.phase) {
                    UpdatePhase.Ready -> UpdatePhase.Ready
                    UpdatePhase.Available -> UpdatePhase.Available
                    else -> UpdatePhase.Idle
                }
                _state.update {
                    it.copy(
                        phase = if (manual) UpdatePhase.Failed else fallback,
                        error = if (manual) result.error.message else "",
                    )
                }
            }

            is ManifestResult.Success -> {
                val update = result.update
                remote = update
                store.setOkHost(result.host)
                // Only a successful fetch counts against the 24 h window.
                store.markChecked(now)
                decideAndAct(update, stored, manual)
            }
        }
    }

    private suspend fun decideAndAct(
        update: RemoteUpdate,
        stored: AppUpdateStoredState,
        manual: Boolean,
    ) {
        val ready = readyFile(stored)
        // A ready file for this or a newer build wins: never download an older release over it.
        if (ready != null && ready.versionCode >= update.versionCode) {
            val display = if (
                stored.readyVersionCode == ready.versionCode && stored.readyVersionName.isNotBlank()
            ) {
                stored.readyVersionName
            } else {
                displayNameFor(ready.versionCode)
            }
            _state.update {
                it.copy(
                    phase = UpdatePhase.Ready,
                    version = display,
                    versionCode = ready.versionCode,
                    notes = update.notes,
                    size = ready.file.length(),
                    received = ready.file.length(),
                    total = ready.file.length(),
                    error = "",
                )
            }
            return
        }

        when (AppUpdatePolicy.decide(
            remote = update,
            installedVersion = installedVersion,
            installedVersionCode = installedVersionCode,
            downloadedVersionCode = stored.readyVersionCode,
            dismissedVersionCode = stored.dismissedVersionCode,
        )) {
            UpdateDecision.Unknown,
            UpdateDecision.UpToDate,
            UpdateDecision.Dismissed,
            -> _state.update {
                it.copy(
                    phase = UpdatePhase.Latest,
                    version = update.version,
                    versionCode = update.versionCode,
                    notes = update.notes,
                    size = update.size,
                    received = 0,
                    total = 0,
                    error = "",
                )
            }

            UpdateDecision.Ready -> {
                // Policy says the same build is on disk, but readyFile() disagreed (size/eviction):
                // download it again rather than showing an install that would fail.
                if (manual) showAvailable(update) else downloadSilently(update)
            }

            UpdateDecision.Download ->
                if (manual) showAvailable(update) else downloadSilently(update)
        }
    }

    private fun showAvailable(update: RemoteUpdate) {
        _state.update {
            it.copy(
                phase = UpdatePhase.Available,
                version = update.version,
                versionCode = update.versionCode,
                notes = update.notes,
                size = update.size,
                received = 0,
                total = 0,
                error = "",
            )
        }
    }

    private suspend fun downloadSilently(update: RemoteUpdate) {
        download(update, silent = true)
    }

    private suspend fun download(update: RemoteUpdate, silent: Boolean) {
        val hosts = orderedHosts(store.read().okHost, emptyMap(), time.nowMillis())
        _state.update {
            it.copy(
                phase = UpdatePhase.Downloading,
                version = update.version,
                versionCode = update.versionCode,
                notes = update.notes,
                size = update.size,
                received = 0,
                total = update.size,
                error = "",
            )
        }

        when (val result = downloader.download(update, hosts, updateDir) { got, total ->
            _state.update {
                it.copy(received = got, total = if (total > 0) total else update.size)
            }
        }) {
            is Outcome.Failure -> {
                _state.update {
                    it.copy(
                        // Automatic downloads fail quietly; the manual path explains the reason.
                        phase = if (silent) UpdatePhase.Available else UpdatePhase.Failed,
                        error = if (silent) "" else result.error.message,
                    )
                }
            }

            is Outcome.Success -> {
                val file = result.value
                store.setReady(update.versionCode, update.version, file.length())
                _state.update {
                    it.copy(
                        phase = UpdatePhase.Ready,
                        received = file.length(),
                        total = file.length(),
                        error = "",
                    )
                }
            }
        }
    }

    override suspend fun downloadAndInstall() {
        val current = _state.value
        if (current.phase == UpdatePhase.Ready) {
            installReady()
            return
        }
        val update = remote ?: return
        // Never download a release at or below the installed versionCode; the manifest is
        // normally trusted, but a stale "latest" must not become a downgrade.
        if (update.versionCode > 0 && update.versionCode <= installedVersionCode) return
        download(update, silent = false)
        // Only a verified file earns an install request; a failure stays on the Failed state.
        if (_state.value.phase == UpdatePhase.Ready) installReady()
    }

    override suspend fun skipVersion() {
        val versionCode = _state.value.versionCode
        if (versionCode > 0) {
            store.setDismissed(versionCode)
            val stored = store.read()
            val ready = readyFile(stored)
            if (ready != null && ready.versionCode == versionCode) {
                ready.file.delete()
                store.clearReady()
            }
            // Also drop an interrupted download for the skipped build.
            File(updateDir, "$versionCode.apk.part").takeIf { it.isFile }?.delete()
        }
        _state.update {
            it.copy(
                phase = UpdatePhase.Idle,
                installRequest = null,
                received = 0,
                total = 0,
                error = "",
            )
        }
        remote = null
    }

    override suspend fun installReady() {
        val request = withContext(Dispatchers.IO) { currentInstallRequest() }
        _state.update {
            it.copy(
                installRequest = request,
                phase = if (request is AppInstallRequest.Unavailable) UpdatePhase.Failed else UpdatePhase.Ready,
                error = if (request is AppInstallRequest.Unavailable) "安装包不存在，请重新下载" else "",
            )
        }
    }

    override fun consumeInstallRequest() {
        if (_state.value.installRequest != null) {
            _state.update { it.copy(installRequest = null) }
        }
    }

    override suspend fun installRequest(): AppInstallRequest =
        withContext(Dispatchers.IO) { currentInstallRequest() }

    private suspend fun currentInstallRequest(): AppInstallRequest {
        val stored = store.read()
        val ready = readyFile(stored) ?: return AppInstallRequest.Unavailable
        return if (canRequestInstall()) {
            AppInstallRequest.Ready(ApkInstaller.installIntent(context, ready.file))
        } else {
            AppInstallRequest.NeedPermission(ApkInstaller.unknownSourcesSettingsIntent(context))
        }
    }

    // --- local files -------------------------------------------------------------------------

    private data class ReadyFile(val versionCode: Int, val file: File)

    /**
     * The newest verified APK above the installed versionCode. Files whose recorded size no
     * longer matches are treated as cache corruption and deleted.
     */
    private suspend fun readyFile(stored: AppUpdateStoredState): ReadyFile? =
        withContext(Dispatchers.IO) {
            val files = runCatching { updateDir.listFiles() }.getOrNull() ?: return@withContext null
            var best: ReadyFile? = null
            for (file in files) {
                if (!file.isFile) continue
                val match = APK_NAME.matchEntire(file.name) ?: continue
                val versionCode = match.groupValues[1].toIntOrNull() ?: continue
                if (versionCode <= installedVersionCode) continue
                if (
                    stored.readyVersionCode == versionCode &&
                    stored.readySize > 0 &&
                    file.length() != stored.readySize
                ) {
                    file.delete()
                    store.clearReady()
                    continue
                }
                if (best == null || versionCode > best.versionCode) {
                    best = ReadyFile(versionCode, file)
                }
            }
            best
        }

    private suspend fun pruneLocal() = withContext(Dispatchers.IO) {
        val files = updateDir.listFiles() ?: return@withContext
        for (file in files) {
            if (!file.isFile) continue
            val match = LOCAL_NAME.matchEntire(file.name) ?: continue
            val versionCode = match.groupValues[1].toIntOrNull() ?: continue
            if (versionCode <= installedVersionCode) file.delete()
        }
        val stored = store.read()
        if (stored.readyVersionCode in 1..installedVersionCode) store.clearReady()
    }

    private fun orderedHosts(
        preferredHost: String,
        deadHosts: Map<String, Long>,
        nowMillis: Long,
    ): List<String> {
        val blocked = deadHosts.filterValues { it > nowMillis }.keys
        val all = listOf(AppUpdateDefaults.DIRECT_HOST) + AppUpdateDefaults.MIRROR_HOSTS
        val ordered = if (preferredHost in all) {
            listOf(preferredHost) + all.filter { it != preferredHost }
        } else {
            all
        }
        return ordered.filterNot { it in blocked }
    }

    private fun mergeDeadHosts(
        existing: Map<String, Long>,
        newlyDead: Set<String>,
        nowMillis: Long,
    ): Map<String, Long> {
        if (newlyDead.isEmpty()) return existing.filterValues { it > nowMillis }
        val until = nowMillis + AppUpdateDefaults.DEAD_HOST_COOLDOWN_MILLIS
        return existing.filterValues { it > nowMillis } + newlyDead.associateWith { until }
    }

    private fun displayNameFor(versionCode: Int): String =
        // versionCode is major*100 + minor*10 + patch by contract (docs/UPDATE_PROTOCOL.md).
        if (versionCode >= 100) {
            "${versionCode / 100}.${(versionCode / 10) % 10}.${versionCode % 10}"
        } else {
            versionCode.toString()
        }

    private companion object {
        val APK_NAME = Regex("^(\\d+)\\.apk$")
        val LOCAL_NAME = Regex("^(\\d+)\\.apk(?:\\.part)?$")
    }
}

/** One place building the production graph, shared by `AppContainer` and tests. */
object AppUpdateFactory {

    fun create(
        context: Context,
        installedVersion: String,
        installedVersionCode: Int,
    ): AppUpdateRepository {
        val appContext = context.applicationContext
        val client = buildUpdateHttpClient()
        return DefaultAppUpdateRepository(
            context = appContext,
            time = SystemTimeProvider(),
            source = OkHttpUpdateManifestSource(client),
            downloader = OkHttpApkDownloader(client),
            store = DataStoreAppUpdateStore(appContext),
            installedVersion = installedVersion,
            installedVersionCode = installedVersionCode,
        )
    }

    /** Reads the installed package's name/code without throwing on OEM package-manager quirks. */
    fun readInstalledVersion(context: Context): Pair<String, Int> = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            info.versionCode
        }
        info.versionName.orEmpty() to code
    }.getOrDefault("" to 0)
}
