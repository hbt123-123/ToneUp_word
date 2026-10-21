package top.toneup.word

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import top.toneup.word.ui.ClipboardHash
import top.toneup.word.ui.ExternalImportCallbacks
import top.toneup.word.ui.ExternalImportViewModel
import top.toneup.word.ui.MainViewModel
import top.toneup.word.ui.ToneUpWordApp
import top.toneup.word.ui.ViewModelFactories
import top.toneup.word.data.update.AppInstallRequest

/**
 * The only Activity of the app.
 *
 * Everything else is Compose: one `setContent` call, one navigation graph, and a window that
 * draws edge to edge. `enableEdgeToEdge()` is the supported way to handle the status/navigation
 * bars from Android 8.0 upwards (the call no-ops gracefully on older platform behaviour),
 * and every screen consumes insets through Scaffold rather than by hard-coding paddings.
 *
 * It also owns the two external text intake paths (M5, plan §8): Share / Process-Text intents
 * (`singleTop` + `onNewIntent`) and the focus-time clipboard probe. Both feed the same
 * Activity-scoped [ExternalImportViewModel] whose sheet floats above the whole graph.
 */
class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer

    /** Activity-scoped: the intent handlers and the clipboard probe must hit the instance the composition renders. */
    private lateinit var externalImport: ExternalImportViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        container = (application as ToneUpWordApplication).container
        externalImport = ViewModelProvider(
            this,
            ExternalImportViewModel.factory(container),
        )[ExternalImportViewModel::class.java]

        setContent {
            val mainViewModel: MainViewModel = viewModel(
                factory = ViewModelFactories.main(container),
            )
            val state = mainViewModel.state.collectAsStateWithLifecycle()
            val importState = externalImport.state.collectAsStateWithLifecycle()
            val importPacks = externalImport.packs.collectAsStateWithLifecycle()
            val importActivePackId = externalImport.activePackId.collectAsStateWithLifecycle()
            val pendingUpdate = mainViewModel.pendingUpdate.collectAsStateWithLifecycle()

            ToneUpWordApp(
                container = container,
                state = state.value,
                // The start-up line's "skip" action: a tap leaves the screen immediately instead of
                // waiting for the timer in the ViewModel.
                onDismissStartupTip = mainViewModel::dismissStartupTip,
                externalImportState = importState.value,
                externalImportPacks = importPacks.value,
                externalImportActivePackId = importActivePackId.value,
                externalImportCallbacks = ExternalImportCallbacks(
                    onConfirmImport = externalImport::confirmImport,
                    onCreatePackAndImport = externalImport::createPackAndImport,
                    onDismiss = externalImport::dismiss,
                ),
                pendingUpdate = pendingUpdate.value,
                onInstallUpdate = { launchPendingUpdateInstall() },
                onDismissUpdate = mainViewModel::dismissPendingUpdate,
            )
        }

        // Cold-start share (e.g. "send to app" from the recents screen).
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    /**
     * Clipboard probe timing (plan §8.1 / R3): read only when the window has focus *and* the
     * Activity is RESUMED. That is both the Android 10+ foreground rule and the Android 12+
     * trick that avoids the system "已粘贴" notice for background access — an unfocused or
     * background Activity never opens the clip here.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && hasWindowFocus()) detectClipboardWords()
    }

    /** Both intent filters share one path; anything not addressed to them is ignored. */
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            else -> null
        }?.toString()?.takeIf { it.isNotBlank() } ?: return
        // Consume the payload: a later recreation of this Activity must not replay the share
        // (the system may redeliver the original intent on process death — that is fine).
        intent.removeExtra(Intent.EXTRA_TEXT)
        intent.removeExtra(Intent.EXTRA_PROCESS_TEXT)
        externalImport.onSharedText(text)
    }

    /** Reads the clipboard and hands importable text to the sheet (privacy rules below). */
    private fun detectClipboardWords() {
        if (lifecycle.currentState != Lifecycle.State.RESUMED) return
        // Never pop a second sheet under (or instead of) the one already on screen.
        if (externalImport.isSheetVisible()) return
        lifecycleScope.launch {
            val settings = runCatching { container.settingsRepository.current() }.getOrNull() ?: return@launch
            // Read-only consumption of the setting — the toggle itself belongs to the settings
            // screen (M4-UI lane); this key is never written here.
            if (!settings.clipboardDetectEnabled) return@launch
            val text = readClipboardText() ?: return@launch
            if (text.isBlank()) return@launch
            // Seen before (imported or dismissed earlier) → stay quiet, do not nag.
            if (ClipboardHash.of(text) == settings.lastClipboardHash) return@launch
            // Privacy: the text goes into local parsing only; nothing is logged or uploaded.
            externalImport.onClipboardText(text)
        }
    }

    /** [ClipboardManager] access is permission-free but can throw on OEM/background quirks — never crash. */
    private fun readClipboardText(): String? = try {
        getSystemService(ClipboardManager::class.java)
            ?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
    } catch (throwable: Throwable) {
        null
    }

    /**
     * "立即安装" tap: hand the downloaded APK to the system installer, or first route through
     * the "install unknown apps" grant when it is missing. The APK stays on disk, so after the
     * user grants and returns, a second tap installs without re-downloading.
     */
    private fun launchPendingUpdateInstall() {
        lifecycleScope.launch {
            when (val request = runCatching { container.appUpdateRepository.installRequest() }
                .getOrNull() ?: return@launch) {
                is AppInstallRequest.Ready ->
                    runCatching { startActivity(request.intent) }

                is AppInstallRequest.NeedPermission ->
                    runCatching { startActivity(request.intent) }

                AppInstallRequest.Unavailable -> Unit
            }
        }
    }
}
