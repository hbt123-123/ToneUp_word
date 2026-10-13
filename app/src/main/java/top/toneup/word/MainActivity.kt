package top.toneup.word

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import top.toneup.word.ui.ToneUpWordApp
import top.toneup.word.ui.MainViewModel
import top.toneup.word.ui.ViewModelFactories

/**
 * The only Activity of the app.
 *
 * Everything else is Compose: one `setContent` call, one navigation graph, and a window that
 * draws edge to edge. `enableEdgeToEdge()` is the supported way to handle the status/navigation
 * bars from Android 8.0 upwards (the call no-ops gracefully on older platform behaviour),
 * and every screen consumes insets through Scaffold rather than by hard-coding paddings.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as ToneUpWordApplication).container

        setContent {
            val mainViewModel: MainViewModel = viewModel(
                factory = ViewModelFactories.main(container),
            )
            val state = mainViewModel.state.collectAsStateWithLifecycle()

            ToneUpWordApp(
                container = container,
                state = state.value,
                // The start-up line's "skip" action: a tap leaves the screen immediately instead of
                // waiting for the timer in the ViewModel.
                onDismissStartupTip = mainViewModel::dismissStartupTip,
            )
        }
    }
}
