package top.toneup.word.data.repository

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.toneup.word.core.result.AppError
import top.toneup.word.core.result.Outcome
import top.toneup.word.core.time.TimeProvider
import top.toneup.word.data.update.ApkDownloader
import top.toneup.word.data.update.AppInstallRequest
import top.toneup.word.data.update.AppUpdateStoredState
import top.toneup.word.data.update.AppUpdateStore
import top.toneup.word.data.update.ManifestResult
import top.toneup.word.data.update.UpdateManifestSource
import top.toneup.word.data.update.UpdatePhase
import top.toneup.word.domain.update.RemoteUpdate
import java.io.File
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Orchestration semantics of [DefaultAppUpdateRepository]: versionCode decision, silent download,
 * throttling, ready-state recovery from disk, the dismissed-version rule, and rejection of a
 * manifest whose checksum/downloader fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AppUpdateRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 1_768_450_800_000L
    private val time = object : TimeProvider {
        override fun nowMillis(): Long = now
        override fun zone(): ZoneId = ZoneOffset.UTC
    }

    private lateinit var store: FakeStore
    private lateinit var source: FakeManifestSource
    private lateinit var downloader: FakeDownloader
    private lateinit var updateDir: File

    private val installed = "1.0.3"
    private val installedCode = 103

    @Before
    fun setUp() {
        updateDir = File(tmp.root, "updates")
        store = FakeStore()
        source = FakeManifestSource()
        downloader = FakeDownloader()
    }

    private fun remote(version: String = "1.0.4", code: Int = 104) = RemoteUpdate(
        version = version,
        versionCode = code,
        apkUrl = "https://github.com/hbt123-123/ToneUp_word/releases/latest/download/ToneUpWord.apk",
        sha256 = "a".repeat(64),
        size = 10L,
        notes = "更新说明",
    )

    private fun repository(
        installedVersion: String = installed,
        installedVersionCode: Int = installedCode,
    ): DefaultAppUpdateRepository = DefaultAppUpdateRepository(
        context = ApplicationProvider.getApplicationContext(),
        time = time,
        source = source,
        downloader = downloader,
        store = store,
        installedVersion = installedVersion,
        installedVersionCode = installedVersionCode,
        updateDir = updateDir,
        canRequestInstall = { true },
    )

    private fun readyFile(code: Int = 104, length: Int = 10): File {
        updateDir.mkdirs()
        return File(updateDir, "$code.apk").apply { writeBytes(ByteArray(length)) }
    }

    // --- check + silent download -------------------------------------------------------------

    @Test
    fun `a newer manifest is downloaded silently and becomes ready`() = runTest {
        val repo = repository()

        repo.autoCheck()

        assertThat(source.calls).isEqualTo(1)
        assertThat(downloader.calls).isEqualTo(1)
        assertThat(repo.state.value.phase).isEqualTo(UpdatePhase.Ready)
        assertThat(repo.state.value.version).isEqualTo("1.0.4")
        assertThat(repo.state.value.versionCode).isEqualTo(104)
        assertThat(store.value.readyVersionCode).isEqualTo(104)
        assertThat(store.value.readyVersionName).isEqualTo("1.0.4")
        assertThat(store.value.lastCheckedMillis).isEqualTo(now)
    }

    @Test
    fun `an up-to-date manifest does nothing but still marks the check`() = runTest {
        source.update = remote("1.0.3", 103)

        val repo = repository()
        repo.autoCheck()

        assertThat(downloader.calls).isEqualTo(0)
        assertThat(repo.state.value.phase).isEqualTo(UpdatePhase.Latest)
        assertThat(store.value.lastCheckedMillis).isEqualTo(now)
    }

    @Test
    fun `a dismissed versionCode is not downloaded`() = runTest {
        store.setDismissed(104)

        val repo = repository()
        repo.autoCheck()

        assertThat(downloader.calls).isEqualTo(0)
        assertThat(repo.state.value.phase).isEqualTo(UpdatePhase.Latest)
    }

    @Test
    fun `a release newer than the dismissed one downloads again`() = runTest {
        store.setDismissed(104)
        source.update = remote("1.0.5", 105)

        val repo = repository()
        repo.autoCheck()

        assertThat(downloader.calls).isEqualTo(1)
        assertThat(repo.state.value.phase).isEqualTo(UpdatePhase.Ready)
        assertThat(store.value.readyVersionCode).isEqualTo(105)
    }

    @Test
    fun `a failed check leaves the throttle untouched and records the dead host`() = runTest {
        source.failure = AppError.network("offline")

        val repo = repository()
        repo.autoCheck()

        assertThat(repo.state.value.phase).isEqualTo(UpdatePhase.Idle)
        assertThat(store.value.lastCheckedMillis).isNull()
        assertThat(store.value.deadHosts).containsKey("bad.host")
        assertThat(downloader.calls).isEqualTo(0)
    }

    @Test
    fun `an unparseable version is treated as up to date`() = runTest {
        source.update = remote("hotfix", 0)

        val repo = repository()
        repo.autoCheck()

        assertThat(downloader.calls).isEqualTo(0)
        assertThat(repo.state.value.phase).isEqualTo(UpdatePhase.Latest)
    }

    @Test
    fun `the launch check is throttled for 24 hours`() = runTest {
        store.markChecked(now - 60 * 60 * 1000)

        repository().autoCheck()

        assertThat(source.calls).isEqualTo(0)
    }

    @Test
    fun `a manual check ignores the throttle`() = runTest {
        store.markChecked(now)

        val repo = repository()
        repo.checkNow()

        assertThat(source.calls).isEqualTo(1)
        // Manual checks stop at "available"; the learner decides whether to download.
        assertThat(repo.state.value.phase).isEqualTo(UpdatePhase.Available)
        assertThat(downloader.calls).isEqualTo(0)
    }

    // --- ready state -------------------------------------------------------------------------

    @Test
    fun `a previous verified file survives a restart and no download happens`() = runTest {
        readyFile()
        store.setReady(104, "1.0.4", 10)

        val repo = repository()
        repo.init()
        repo.autoCheck()

        assertThat(repo.state.value.phase).isEqualTo(UpdatePhase.Ready)
        assertThat(repo.state.value.version).isEqualTo("1.0.4")
        assertThat(downloader.calls).isEqualTo(0)
    }

    @Test
    fun `a recorded download whose file vanished is fetched again`() = runTest {
        store.setReady(104, "1.0.4", 10)

        val repo = repository()
        repo.autoCheck()

        assertThat(downloader.calls).isEqualTo(1)
        assertThat(repo.state.value.phase).isEqualTo(UpdatePhase.Ready)
    }

    @Test
    fun `init prunes files for the installed version`() = runTest {
        val old = readyFile(103)
        val fresh = readyFile(104)

        repository().init()

        assertThat(old.exists()).isFalse()
        assertThat(fresh.exists()).isTrue()
    }

    @Test
    fun `a truncated ready file is discarded`() = runTest {
        readyFile(104, length = 5)
        store.setReady(104, "1.0.4", 10)

        val repo = repository()
        repo.init()

        assertThat(File(updateDir, "104.apk").exists()).isFalse()
        assertThat(repo.state.value.phase).isEqualTo(UpdatePhase.Idle)
        assertThat(store.value.readyVersionCode).isEqualTo(0)
    }

    @Test
    fun `skip deletes the ready file and suppresses that versionCode`() = runTest {
        readyFile()
        store.setReady(104, "1.0.4", 10)

        val repo = repository()
        repo.init()
        repo.skipVersion()

        assertThat(File(updateDir, "104.apk").exists()).isFalse()
        assertThat(store.value.readyVersionCode).isEqualTo(0)
        assertThat(store.value.dismissedVersionCode).isEqualTo(104)
        assertThat(repo.state.value.phase).isEqualTo(UpdatePhase.Idle)
    }

    // --- install request ---------------------------------------------------------------------

    @Test
    fun `install without a downloaded apk is unavailable`() = runTest {
        assertThat(repository().installRequest()).isEqualTo(AppInstallRequest.Unavailable)
    }

    // --- fakes -------------------------------------------------------------------------------

    private class FakeManifestSource(
        var update: RemoteUpdate = RemoteUpdate(
            version = "1.0.4",
            versionCode = 104,
            apkUrl = "https://github.com/hbt123-123/ToneUp_word/releases/latest/download/ToneUpWord.apk",
            sha256 = "a".repeat(64),
            size = 10L,
            notes = "",
        ),
    ) : UpdateManifestSource {
        var calls = 0
        var failure: AppError? = null

        override suspend fun fetch(
            preferredHost: String,
            deadHosts: Map<String, Long>,
            nowMillis: Long,
        ): ManifestResult {
            calls++
            failure?.let {
                return ManifestResult.Failure(
                    error = it,
                    aliveHosts = emptySet(),
                    deadHosts = setOf("bad.host"),
                )
            }
            return ManifestResult.Success(update, preferredHost)
        }
    }

    private class FakeDownloader : ApkDownloader {
        var calls = 0
        var failure: AppError? = null

        override suspend fun download(
            update: RemoteUpdate,
            hosts: List<String>,
            targetDir: File,
            onProgress: (received: Long, total: Long) -> Unit,
        ): Outcome<File> {
            calls++
            failure?.let { return Outcome.Failure(it) }
            targetDir.mkdirs()
            val file = File(targetDir, "${update.versionCode}.apk")
            file.writeBytes(ByteArray(update.size.toInt()))
            onProgress(file.length(), file.length())
            return Outcome.Success(file)
        }
    }

    private class FakeStore(
        initial: AppUpdateStoredState = AppUpdateStoredState(),
    ) : AppUpdateStore {
        private val flow = MutableStateFlow(initial)
        val value: AppUpdateStoredState get() = flow.value

        override fun state(): Flow<AppUpdateStoredState> = flow

        override suspend fun read(): AppUpdateStoredState = flow.value

        override suspend fun markChecked(nowMillis: Long) {
            flow.value = flow.value.copy(lastCheckedMillis = nowMillis)
        }

        override suspend fun setDismissed(versionCode: Int) {
            flow.value = flow.value.copy(dismissedVersionCode = versionCode)
        }

        override suspend fun setOkHost(host: String) {
            flow.value = flow.value.copy(okHost = host)
        }

        override suspend fun setDeadHosts(deadHosts: Map<String, Long>) {
            flow.value = flow.value.copy(deadHosts = deadHosts)
        }

        override suspend fun setReady(versionCode: Int, versionName: String, size: Long) {
            flow.value = flow.value.copy(
                readyVersionCode = versionCode,
                readyVersionName = versionName,
                readySize = size,
            )
        }

        override suspend fun clearReady() {
            flow.value = flow.value.copy(
                readyVersionCode = 0,
                readyVersionName = "",
                readySize = 0,
            )
        }
    }
}
