package top.toneup.word.data.repository

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.toneup.word.core.result.AppError
import top.toneup.word.core.result.ErrorCode
import top.toneup.word.core.result.Outcome
import top.toneup.word.core.time.TimeProvider
import top.toneup.word.data.update.ApkDownloader
import top.toneup.word.data.update.AppInstallRequest
import top.toneup.word.data.update.AppUpdateCheckResult
import top.toneup.word.data.update.AppUpdateStoredState
import top.toneup.word.data.update.AppUpdateStore
import top.toneup.word.data.update.GitHubReleaseSource
import top.toneup.word.data.update.PendingAppUpdate
import top.toneup.word.data.update.RemoteRelease
import java.io.File
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Orchestration semantics of [DefaultAppUpdateRepository]: the version decision, the
 * already-downloaded / dismissed shortcuts, cache-eviction re-download, and the pending
 * install emission. Network and file transfer are fakes; only the `installRequest` glue
 * (FileProvider) is left to the platform.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AppUpdateRepositoryTest {

    private val now = 1_768_450_800_000L
    private val time = object : TimeProvider {
        override fun nowMillis(): Long = now
        override fun zone(): ZoneId = ZoneOffset.UTC
    }

    private lateinit var apkFile: File
    private lateinit var store: FakeStore
    private lateinit var source: FakeReleaseSource
    private lateinit var downloader: FakeDownloader

    private val installed = "1.0.3"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        apkFile = File(File(context.cacheDir, "updates"), "toneup-update.apk")
        apkFile.parentFile?.mkdirs()
        apkFile.delete()
        store = FakeStore()
        source = FakeReleaseSource(release("v1.0.4"))
        downloader = FakeDownloader()
    }

    private fun repository(): DefaultAppUpdateRepository = DefaultAppUpdateRepository(
        context = ApplicationProvider.getApplicationContext(),
        time = time,
        releaseSource = source,
        downloader = downloader,
        store = store,
        installedVersion = installed,
        apkFile = apkFile,
        canRequestInstall = { true },
    )

    private fun release(tag: String) = RemoteRelease(
        tagName = tag,
        apkUrl = "https://example.com/app.apk",
        apkSize = 10L,
        releasePageUrl = "https://example.com/tag/$tag",
    )

    // --- check + download -------------------------------------------------------------------

    @Test
    fun `a newer release is downloaded and stored as pending`() = runTest {
        val result = repository().checkAndDownload()

        assertThat(result).isEqualTo(AppUpdateCheckResult.Downloaded)
        assertThat(downloader.calls).isEqualTo(1)
        assertThat(store.value.downloadedTag).isEqualTo("v1.0.4")
        assertThat(store.value.downloadedVersion).isEqualTo("1.0.4")
        assertThat(store.value.downloadedSize).isEqualTo(10L)
        assertThat(store.value.lastCheckedMillis).isEqualTo(now)
    }

    @Test
    fun `an up-to-date release does nothing but still marks the check`() = runTest {
        source.release = release("v1.0.3")

        val result = repository().checkAndDownload()

        assertThat(result).isEqualTo(AppUpdateCheckResult.NothingToDo)
        assertThat(downloader.calls).isEqualTo(0)
        assertThat(store.value.lastCheckedMillis).isEqualTo(now)
    }

    @Test
    fun `a dismissed release is not re-downloaded`() = runTest {
        store.setDismissed("v1.0.4")

        val result = repository().checkAndDownload()

        assertThat(result).isEqualTo(AppUpdateCheckResult.NothingToDo)
        assertThat(downloader.calls).isEqualTo(0)
    }

    @Test
    fun `a release newer than the dismissed one downloads again`() = runTest {
        store.setDismissed("v1.0.4")

        val result = repository().checkAndDownload() // remote = v1.0.4; make it newer:
        // (re-check with a newer tag to exercise the boundary)
        source.release = release("v1.0.5")
        val newer = repository().checkAndDownload()

        assertThat(result).isEqualTo(AppUpdateCheckResult.NothingToDo)
        assertThat(newer).isEqualTo(AppUpdateCheckResult.Downloaded)
    }

    @Test
    fun `an already downloaded tag with an intact file is ready without re-download`() = runTest {
        downloader.writeFull = true
        repository().checkAndDownload() // first run downloads v1.0.4
        downloader.calls = 0

        val result = repository().checkAndDownload()

        assertThat(result).isEqualTo(AppUpdateCheckResult.AlreadyReady)
        assertThat(downloader.calls).isEqualTo(0)
    }

    @Test
    fun `a vanished apk file re-downloads the same tag`() = runTest {
        repository().checkAndDownload()
        apkFile.delete()
        downloader.calls = 0

        val result = repository().checkAndDownload()

        assertThat(result).isEqualTo(AppUpdateCheckResult.Downloaded)
        assertThat(downloader.calls).isEqualTo(1)
    }

    @Test
    fun `a download failure surfaces the error and leaves the store clean`() = runTest {
        downloader.failure = AppError.network("socket closed")

        val result = repository().checkAndDownload()

        assertThat(result).isInstanceOf(AppUpdateCheckResult.Failed::class.java)
        assertThat((result as AppUpdateCheckResult.Failed).error.retryable).isTrue()
        assertThat(store.value.downloadedTag).isNull()
    }

    @Test
    fun `a failed check does not mark the throttle timestamp`() = runTest {
        source.error = AppError.network("offline")

        val result = repository().checkAndDownload()

        assertThat(result).isInstanceOf(AppUpdateCheckResult.Failed::class.java)
        assertThat((result as AppUpdateCheckResult.Failed).error.code).isEqualTo(ErrorCode.NETWORK)
        assertThat(store.value.lastCheckedMillis).isNull()
    }

    @Test
    fun `an unparseable remote tag is ignored`() = runTest {
        source.release = release("hotfix")

        val result = repository().checkAndDownload()

        assertThat(result).isEqualTo(AppUpdateCheckResult.NothingToDo)
        assertThat(downloader.calls).isEqualTo(0)
    }

    // --- pending install emission ------------------------------------------------------------

    @Test
    fun `a downloaded apk emits a pending install with a display version`() = runTest {
        val repo = repository()
        repo.checkAndDownload()

        val pending = repo.observePendingInstall().first()

        assertThat(pending).isNotNull()
        assertThat(pending!!.versionName).isEqualTo("1.0.4")
        assertThat(pending.tagName).isEqualTo("v1.0.4")
        assertThat(pending.apk.isFile).isTrue()
    }

    @Test
    fun `dismissing hides the prompt`() = runTest {
        val repo = repository()
        repo.checkAndDownload()
        repo.dismissPendingInstall()

        val pending = repo.observePendingInstall().first()

        assertThat(pending).isNull()
        // The APK itself is kept: a later change of mind must not re-download.
        assertThat(apkFile.isFile).isTrue()
    }

    @Test
    fun `a cache-evicted apk emits nothing`() = runTest {
        val repo = repository()
        repo.checkAndDownload()
        apkFile.delete()

        val pending = repo.observePendingInstall().first()

        assertThat(pending).isNull()
    }

    // --- install request ----------------------------------------------------------------------

    @Test
    fun `install without a downloaded apk is unavailable`() = runTest {
        assertThat(repository().installRequest()).isEqualTo(AppInstallRequest.Unavailable)
    }

    // --- fakes --------------------------------------------------------------------------------

    private class FakeReleaseSource(
        var release: RemoteRelease?,
        var error: AppError? = null,
    ) : GitHubReleaseSource {
        var calls = 0

        override suspend fun fetchLatest(): Outcome<RemoteRelease> {
            calls++
            error?.let { return Outcome.Failure(it) }
            return release?.let { Outcome.Success(it) } ?: Outcome.Failure(AppError.unknown("no release"))
        }
    }

    private class FakeDownloader : ApkDownloader {
        var calls = 0
        var failure: AppError? = null
        var writeFull: Boolean = true

        override suspend fun download(url: String, target: File, expectedSize: Long): Outcome<Unit> {
            calls++
            failure?.let { return Outcome.Failure(it) }
            target.parentFile?.mkdirs()
            if (writeFull) target.writeBytes(ByteArray(expectedSize.toInt()))
            return Outcome.Success(Unit)
        }
    }

    private class FakeStore(initial: AppUpdateStoredState = AppUpdateStoredState()) : AppUpdateStore {
        val flow = MutableStateFlow(initial)
        val value: AppUpdateStoredState get() = flow.value

        override fun state(): Flow<AppUpdateStoredState> = flow
        override suspend fun read(): AppUpdateStoredState = flow.value
        override suspend fun markChecked(nowMillis: Long) {
            flow.value = flow.value.copy(lastCheckedMillis = nowMillis)
        }

        override suspend fun setDownloaded(tag: String, version: String, size: Long) {
            flow.value = flow.value.copy(downloadedTag = tag, downloadedVersion = version, downloadedSize = size)
        }

        override suspend fun setDismissed(tag: String) {
            flow.value = flow.value.copy(dismissedTag = tag)
        }
    }
}
