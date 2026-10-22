package top.toneup.word.ui.speech

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.toneup.word.data.speech.AudioPlayback
import top.toneup.word.data.speech.AudioUrlPlayer
import top.toneup.word.data.speech.SpeechContent
import top.toneup.word.data.speech.SpeechRecovery
import top.toneup.word.data.speech.SpeechRequest
import top.toneup.word.data.speech.SpeechService
import top.toneup.word.data.speech.SpeechStatus
import top.toneup.word.data.speech.isStreamableAudioUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The four states the speak button can be in. */
enum class SpeechPhase { IDLE, LOADING, PLAYING, FAILED }

/**
 * UI state of one speak button.
 *
 * [message] and [recovery] are only non-null in [SpeechPhase.FAILED]; the UI must always render a
 * message *and* a way out, never a dead button.
 */
@Immutable
data class SpeechUiState(
    val phase: SpeechPhase = SpeechPhase.IDLE,
    val content: SpeechContent? = null,
    val message: String? = null,
    val recovery: SpeechRecovery? = null,
) {
    val isLoading: Boolean get() = phase == SpeechPhase.LOADING
    val isPlaying: Boolean get() = phase == SpeechPhase.PLAYING
    val hasFailed: Boolean get() = phase == SpeechPhase.FAILED
}

/**
 * Turns [SpeechService] into a single UI state.
 *
 * Guarantees that matter for the product requirements:
 *  * **a request always ends in a visible state** — success returns to IDLE, and every failure path
 *    produces FAILED with a readable reason plus a recovery action;
 *  * **a stuck engine cannot spin forever** — readiness is awaited with a timeout
 *    ([timeoutMillis]), after which the learner is told to check the system settings;
 *  * **double taps cannot overlap** — a new request cancels the previous one and stops playback;
 *  * **a word's own recording is preferred but never required** — when `audio_url` names an
 *    http(s) stream it is played first (plan §9 / M6b); *any* streaming failure falls through to
 *    system TTS without ever showing FAILED, so a dead URL is inaudible: the learner just hears
 *    the engine instead. FAILED stays reserved for "TTS itself did not work".
 */
class SpeechController(
    private val service: SpeechService,
    private val scope: CoroutineScope,
    private val timeoutMillis: Long = INIT_TIMEOUT_MILLIS,
    private val audio: AudioPlayback = AudioUrlPlayer(),
) {

    private val _state = MutableStateFlow(SpeechUiState())
    val state: StateFlow<SpeechUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var lastRequest: SpeechRequest? = null

    /**
     * Counts requests.
     *
     * The engine reports progress through callbacks that can arrive after the learner has moved
     * on (a slow engine, a rebind, a cancelled request). The epoch tells a late callback that it
     * belongs to a request that is no longer current, so it cannot flip the button of a newer one.
     */
    private var epoch = 0

    /** The watchdog that turns "the engine never answers" into a visible failure. */
    private var watchdog: Job? = null

    fun speak(request: SpeechRequest) {
        if (request.text.isBlank()) return
        job?.cancel()
        cancelWatchdog()
        service.stop()
        audio.stop() // streaming audio and TTS are mutually exclusive, at every entry point
        lastRequest = request
        val requestEpoch = ++epoch
        _state.value = SpeechUiState(phase = SpeechPhase.LOADING, content = request.content)

        job = scope.launch {
            if (playOwnRecording(request)) {
                _state.value = SpeechUiState(phase = SpeechPhase.IDLE, content = request.content)
                return@launch
            }
            // Nothing playable (or the stream failed): silently carry on with system TTS. The
            // LOADING refresh also covers "the recording started, then died mid-stream".
            _state.value = SpeechUiState(phase = SpeechPhase.LOADING, content = request.content)
            // Every non-timeout state is handed back to the service, because the service is the
            // single retry authority: it rebinds a dead engine at most once per request
            // (`SystemTtsSpeechService.speak`) and re-runs the language candidate chain. That is
            // what makes the next tap succeed after the learner installs voice data or enables an
            // engine in the system settings — refusing here would repeat a latched error until the
            // process is killed. If the retry fails, the service reports the same status through
            // onError, so the message and recovery action are unchanged.
            when (awaitUsable()) {
                null -> fail(MESSAGE_TIMEOUT, SpeechRecovery.OPEN_SYSTEM_SETTINGS)
                else -> {
                    // The request has been handed over, and nothing bounds the wait for the engine
                    // to start speaking: the watchdog turns "the button spins forever" into a
                    // failure the learner can act on. It is cancelled as soon as the engine proves
                    // it is alive (onStart), so a long example sentence is never cut off.
                    startWatchdog(requestEpoch)
                    service.speak(
                        request = request,
                        onStart = {
                            if (requestEpoch == epoch) {
                                cancelWatchdog()
                                _state.value = SpeechUiState(phase = SpeechPhase.PLAYING, content = request.content)
                            }
                        },
                        onDone = {
                            if (requestEpoch == epoch) {
                                cancelWatchdog()
                                _state.value = SpeechUiState(phase = SpeechPhase.IDLE, content = request.content)
                            }
                        },
                        onError = { failure ->
                            if (requestEpoch == epoch) {
                                cancelWatchdog()
                                failFor(failure)
                            }
                        },
                    )
                }
            }
        }
    }

    /** Fails the request when the engine has not started speaking within the grace period. */
    private fun startWatchdog(requestEpoch: Int) {
        cancelWatchdog()
        watchdog = scope.launch {
            delay(SPEECH_START_TIMEOUT_MILLIS)
            if (requestEpoch == epoch && _state.value.phase == SpeechPhase.LOADING) {
                fail(MESSAGE_TIMEOUT, SpeechRecovery.RETRY)
            }
        }
    }

    private fun cancelWatchdog() {
        watchdog?.cancel()
        watchdog = null
    }

    /**
     * Plays [request]'s own recording when there is one; `false` means "no usable recording, use
     * TTS" — for an empty/non-http URL, a stream error, the prepare timeout, or a thrown error.
     *
     * A cancelled request rethrows instead of falling back: cancellation means the learner already
     * moved on (stop / another word), and speaking the old request now would play over them.
     */
    private suspend fun playOwnRecording(request: SpeechRequest): Boolean {
        if (!isStreamableAudioUrl(request.audioUrl)) return false
        return try {
            audio.play(request.audioUrl) {
                _state.value = SpeechUiState(phase = SpeechPhase.PLAYING, content = request.content)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            false // defensive: AudioPlayback promises not to throw, but a fallback is always safe
        }
    }

    /** Re-runs the last request; used by the "重试" action in the failed state. */
    fun retry() {
        lastRequest?.let { request ->
            // Clear the failed state before re-queuing so the button shows Loading again.
            _state.value = SpeechUiState(phase = SpeechPhase.LOADING, content = request.content)
            speak(request)
        }
    }

    fun stop() {
        job?.cancel()
        cancelWatchdog()
        service.stop()
        audio.stop()
        _state.value = SpeechUiState(phase = SpeechPhase.IDLE, content = _state.value.content)
    }

    fun dismissError() {
        cancelWatchdog()
        _state.value = SpeechUiState(phase = SpeechPhase.IDLE, content = _state.value.content)
    }

    /**
     * Waits for the engine to become usable.
     *
     * Returns null on timeout, the current non-initialising status when it is not usable, or
     * [SpeechStatus.Ready].
     */
    private suspend fun awaitUsable(): SpeechStatus? {
        val current = service.status.value
        if (current !is SpeechStatus.Initialising) return current
        return withTimeoutOrNull(timeoutMillis) {
            service.status.first { it !is SpeechStatus.Initialising }
        }
    }

    private fun failFor(status: SpeechStatus) {
        fail(messageFor(status), status.recovery ?: SpeechRecovery.RETRY)
    }

    private fun fail(message: String, recovery: SpeechRecovery) {
        _state.value = SpeechUiState(
            phase = SpeechPhase.FAILED,
            content = _state.value.content,
            message = message,
            recovery = recovery,
        )
    }

    private fun messageFor(status: SpeechStatus): String = when (status) {
        is SpeechStatus.EngineUnavailable -> "系统语音服务不可用，可能是引擎未安装或被停用。"
        SpeechStatus.LanguageMissing -> "缺少英语语音包，安装后即可朗读。"
        SpeechStatus.LanguageUnsupported -> "当前语音引擎不支持该语言，可以更换引擎后再试。"
        is SpeechStatus.Failed -> "朗读失败，请重试。"
        SpeechStatus.Initialising -> MESSAGE_TIMEOUT
        is SpeechStatus.Ready -> ""
    }

    companion object {
        /** How long to wait for the engine before telling the learner something is wrong. */
        const val INIT_TIMEOUT_MILLIS = 3_000L

        /**
         * How long the engine may take to start speaking before the request is failed.
         *
         * The engine has already accepted the request by then, so this is not a "is it alive"
         * check but a "it accepted the work and then went quiet" check — the case that used to
         * leave the button spinning forever.
         */
        const val SPEECH_START_TIMEOUT_MILLIS = 5_000L

        internal const val MESSAGE_TIMEOUT = "系统语音服务没有响应，请检查系统的文字转语音设置。"
    }
}

/**
 * Creates a controller bound to the composition.
 *
 * The service is owned by `AppContainer` (it is expensive to create and outlives a screen), so only
 * the coroutine scope is tied to the composition — leaving the screen cancels an in-flight request
 * but never shuts down the engine behind other screens' backs.
 */
@Composable
fun rememberSpeechController(service: SpeechService): SpeechController {
    val scope = rememberCoroutineScope()
    val currentService by rememberUpdatedState(service)
    return androidx.compose.runtime.remember(currentService, scope) {
        SpeechController(currentService, scope)
    }
}

/** Convenience for screens. */
@Composable
fun SpeechController.uiState(): SpeechUiState {
    val state by state.collectAsStateWithLifecycle()
    return state
}
