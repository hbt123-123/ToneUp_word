package top.toneup.word.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import top.toneup.word.AppContainer
import top.toneup.word.data.gateway.GatewayDefaults
import top.toneup.word.data.local.ToneUpWordDatabase
import top.toneup.word.data.settings.DataStoreSettingsRepository
import top.toneup.word.data.settings.UserSettings

/**
 * The gateway settings slice of [ProfileViewModel] (plan D7/§10).
 *
 * The rule under guard is "an invalid gateway address never reaches the repository":
 * `http://` (or anything else that is not `https://`) must be rejected by the pure
 * [gatewayUrlError] check *and* by the ViewModel setter, so no code path can persist a
 * cleartext gateway even if the screen's inline validation is bypassed.
 *
 * The ViewModel's coroutines run on the real main dispatcher here (not a virtual-time test
 * scheduler): a DataStore write only completes when its own IO scope finishes, so the
 * assertions poll the persisted value instead of trusting a scheduler barrier.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26])
class GatewaySettingsTest {

    private lateinit var database: ToneUpWordDatabase
    private lateinit var container: AppContainer
    private lateinit var settings: DataStoreSettingsRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.IO)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ToneUpWordDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        container = AppContainer(context, database)
        settings = DataStoreSettingsRepository(context)
        // DataStore is shared between tests in the same process: start from a known state.
        runBlocking { settings.resetToDefaults() }
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    // --- helpers ---------------------------------------------------------------------------

    /**
     * Polls the persisted settings until [predicate] holds (or fails after a real-time
     * timeout). A DataStore write lands on its own IO scope, so no scheduler barrier can
     * prove it finished — only a re-read can.
     */
    private suspend fun awaitStored(predicate: (UserSettings) -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (predicate(settings.current())) return
            delay(POLL_INTERVAL_MILLIS)
        }
        fail("the setting was not persisted within ${TIMEOUT_MILLIS}ms")
    }

    // --- gatewayUrlError (pure) --------------------------------------------------------------

    @Test
    fun `gatewayUrlError accepts https addresses and rejects everything else`() {
        assertThat(gatewayUrlError(GatewayDefaults.BASE_URL)).isNull()
        assertThat(gatewayUrlError("https://example.com")).isNull()
        assertThat(gatewayUrlError("  https://example.com/  ")).isNull()

        assertThat(gatewayUrlError("http://example.com")).isEqualTo("仅支持 https 地址")
        assertThat(gatewayUrlError("example.com")).isEqualTo("仅支持 https 地址")
        assertThat(gatewayUrlError("ftp://example.com")).isEqualTo("仅支持 https 地址")
        assertThat(gatewayUrlError("")).isEqualTo("仅支持 https 地址")
        assertThat(gatewayUrlError("   ")).isEqualTo("仅支持 https 地址")
    }

    // --- ProfileViewModel setters -------------------------------------------------------------

    @Test
    fun `an http gateway address is never persisted`() = runBlocking {
        val viewModel = ProfileViewModel(container)

        viewModel.setGatewayBaseUrl("http://insecure.example.com")
        // The setter must not even enqueue a write; give any hypothetical coroutine time
        // to (incorrectly) land, then assert the default is still stored.
        delay(SETTLE_MILLIS)

        assertThat(settings.current().gatewayBaseUrl).isEqualTo(GatewayDefaults.BASE_URL)
    }

    @Test
    fun `an https gateway address is persisted trimmed`() = runBlocking {
        val viewModel = ProfileViewModel(container)

        viewModel.setGatewayBaseUrl("  https://my-gateway.example.com  ")
        awaitStored { it.gatewayBaseUrl == "https://my-gateway.example.com" }

        assertThat(settings.current().gatewayBaseUrl).isEqualTo("https://my-gateway.example.com")
    }

    @Test
    fun `the gateway key and the completion toggles are written through`() = runBlocking {
        val viewModel = ProfileViewModel(container)

        viewModel.setGatewayKey("  rotated-key  ")
        viewModel.setSyncOnlyUnmetered(true)
        viewModel.setClipboardDetectEnabled(false)
        awaitStored {
            it.gatewayKey == "rotated-key" && it.syncOnlyUnmetered && !it.clipboardDetectEnabled
        }

        val stored = settings.current()
        assertThat(stored.gatewayKey).isEqualTo("rotated-key")
        assertThat(stored.syncOnlyUnmetered).isTrue()
        assertThat(stored.clipboardDetectEnabled).isFalse()
    }

    @Test
    fun `restoreGatewayDefaults brings address, key and paths back`() = runBlocking {
        val viewModel = ProfileViewModel(container)
        viewModel.setGatewayBaseUrl("https://my-gateway.example.com")
        awaitStored { it.gatewayBaseUrl == "https://my-gateway.example.com" }
        viewModel.setDictPath("/custom-dict")
        awaitStored { it.dictPath == "/custom-dict" }

        viewModel.restoreGatewayDefaults()
        awaitStored {
            it.gatewayBaseUrl == GatewayDefaults.BASE_URL &&
                it.dictPath == GatewayDefaults.DICT_PATH
        }

        val stored = settings.current()
        assertThat(stored.gatewayKey).isEqualTo(GatewayDefaults.KEY)
        assertThat(stored.enPath).isEqualTo(GatewayDefaults.EN_PATH)
        assertThat(stored.mtPath).isEqualTo(GatewayDefaults.MT_PATH)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        const val POLL_INTERVAL_MILLIS = 50L
        /** How long the "must not persist" test waits for a (forbidden) write to appear. */
        const val SETTLE_MILLIS = 300L
    }
}
