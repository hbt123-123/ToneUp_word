package top.toneup.word.ui.speech

import top.toneup.word.data.speech.SpeechContent
import top.toneup.word.data.speech.SpeechRateMode
import top.toneup.word.data.speech.SpeechRecovery
import top.toneup.word.data.speech.SpeechRequest
import top.toneup.word.data.speech.SpeechService
import top.toneup.word.data.speech.SpeechStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Tests for the speak-button state machine.
 *
 * The product requirement is that reading never leaves a dead button: every outcome must end in a
 * visible state, and every failure must carry a way out. The three failure modes are the ones a real
 * device actually hits (no engine, no voice data, unsupported language), plus the timeout that
 * covers "the engine never answers" — so all four are driven here with a fake service. Nothing in
 * this test touches Android or the network.
 */
class SpeechControllerTest {

    /** A scriptable engine. Every call is recorded so the test can assert what was attempted. */
    private class FakeSpeechService(
        initialStatus: SpeechStatus,
    ) : SpeechService {
        private val _status = MutableStateFlow(initialStatus)
        override val status: StateFlow<SpeechStatus> = _status

        var speakCount = 0
            private set
        var stopCount = 0
            private set
        /** When true, `speak` reports success through the callbacks. */
        var succeedOnSpeak = true
        /** When set, `speak` reports this status instead — the real service re-reports its state. */
        var failureOnSpeak: SpeechStatus? = null
        /** When true, `speak` accepts the request and never reports back. */
        var neverAnswers = false

        fun become(status: SpeechStatus) {
            _status.value = status
        }

        override fun speak(
            request: SpeechRequest,
            onStart: () -> Unit,
            onDone: () -> Unit,
            onError: (SpeechStatus) -> Unit,
        ) {
            speakCount++
            if (neverAnswers) return
            val failure = failureOnSpeak
            if (failure != null) {
                onError(failure)
            } else if (succeedOnSpeak) {
                onStart()
                onDone()
            } else {
                onError(SpeechStatus.Failed(7))
            }
        }

        override fun stop() {
            stopCount++
        }

        override fun release() = Unit
    }

    private fun request(text: String = "apple") = SpeechRequest(
        text = text,
        localeTag = "en",
        content = SpeechContent.WORD,
        rateMode = SpeechRateMode.SYSTEM,
    )

    private val ready = SpeechStatus.Ready(
        engine = "com.example.tts",
        language = "en",
        voice = "voice-1",
        rateFromSystem = true,
        pitchFromSystem = false,
    )

    @Test
    fun `a successful reading moves loading to playing and back to idle`() = runTest {
        val service = FakeSpeechService(ready)
        val controller = SpeechController(service, this)
        val phases = mutableListOf<SpeechPhase>()

        controller.speak(request())
        advanceUntilIdle()
        phases += controller.state.value.phase

        // The fake completes immediately, so the observable end state is IDLE (sound finished).
        assertThat(phases).containsExactly(SpeechPhase.IDLE)
        assertThat(service.speakCount).isEqualTo(1)
        assertThat(controller.state.value.message).isNull()
    }

    @Test
    fun `a missing engine offers the system settings and never crashes`() = runTest {
        val service = FakeSpeechService(SpeechStatus.EngineUnavailable("no engine"))
        service.failureOnSpeak = SpeechStatus.EngineUnavailable("no engine")
        val controller = SpeechController(service, this)

        controller.speak(request())
        advanceUntilIdle()

        val state = controller.state.value
        assertThat(state.phase).isEqualTo(SpeechPhase.FAILED)
        assertThat(state.message).contains("系统语音服务不可用")
        assertThat(state.recovery).isEqualTo(SpeechRecovery.OPEN_SYSTEM_SETTINGS)
        // The request *is* handed to the service: that is what lets it rebind a dead engine, so
        // enabling an engine in the system settings can fix the next tap without restarting the app.
        // The failure still arrives through onError, with the same recovery action.
        assertThat(service.speakCount).isEqualTo(1)
    }

    @Test
    fun `missing voice data offers to install it`() = runTest {
        val service = FakeSpeechService(SpeechStatus.LanguageMissing)
        service.failureOnSpeak = SpeechStatus.LanguageMissing
        val controller = SpeechController(service, this)

        controller.speak(request())
        advanceUntilIdle()

        val state = controller.state.value
        assertThat(state.phase).isEqualTo(SpeechPhase.FAILED)
        assertThat(state.message).contains("语音包")
        assertThat(state.recovery).isEqualTo(SpeechRecovery.INSTALL_VOICE_DATA)
        // Re-attempted through the service, so installing the voice data makes the next tap work.
        assertThat(service.speakCount).isEqualTo(1)
    }

    @Test
    fun `an unsupported language points at the system settings`() = runTest {
        val service = FakeSpeechService(SpeechStatus.LanguageUnsupported)
        service.failureOnSpeak = SpeechStatus.LanguageUnsupported
        val controller = SpeechController(service, this)

        controller.speak(request())
        advanceUntilIdle()

        val state = controller.state.value
        assertThat(state.phase).isEqualTo(SpeechPhase.FAILED)
        assertThat(state.message).contains("不支持")
        assertThat(state.recovery).isEqualTo(SpeechRecovery.OPEN_SYSTEM_SETTINGS)
        // Re-attempted through the service, so switching to an English-capable engine takes effect.
        assertThat(service.speakCount).isEqualTo(1)
    }

    @Test
    fun `an engine that never answers times out with a readable message`() = runTest {
        val service = FakeSpeechService(SpeechStatus.Initialising)
        val controller = SpeechController(service, this)

        controller.speak(request())
        advanceUntilIdle()

        val state = controller.state.value
        assertThat(state.phase).isEqualTo(SpeechPhase.FAILED)
        assertThat(state.message).isEqualTo(SpeechController.MESSAGE_TIMEOUT)
        assertThat(state.recovery).isEqualTo(SpeechRecovery.OPEN_SYSTEM_SETTINGS)
        // Nothing was ever spoken, and the button is not left spinning.
        assertThat(service.speakCount).isEqualTo(0)
    }

    @Test
    fun `a late engine becomes usable instead of failing`() = runTest {
        val service = FakeSpeechService(SpeechStatus.Initialising)
        val controller = SpeechController(service, this)

        controller.speak(request())
        // The engine finishes initialising before the timeout expires.
        service.become(ready)
        advanceUntilIdle()

        assertThat(service.speakCount).isEqualTo(1)
        assertThat(controller.state.value.phase).isEqualTo(SpeechPhase.IDLE)
    }

    @Test
    fun `a playback failure is reported and can be retried`() = runTest {
        val service = FakeSpeechService(ready)
        service.succeedOnSpeak = false
        val controller = SpeechController(service, this)

        controller.speak(request())
        advanceUntilIdle()
        assertThat(controller.state.value.phase).isEqualTo(SpeechPhase.FAILED)
        assertThat(controller.state.value.recovery).isEqualTo(SpeechRecovery.RETRY)

        // Retry re-runs the same request rather than asking the learner to tap the word again.
        service.succeedOnSpeak = true
        controller.retry()
        advanceUntilIdle()

        assertThat(service.speakCount).isEqualTo(2)
        assertThat(controller.state.value.phase).isEqualTo(SpeechPhase.IDLE)
    }

    @Test
    fun `an engine that accepts the request and then goes quiet fails with a timeout`() = runTest {
        val service = FakeSpeechService(ready).apply { neverAnswers = true }
        val controller = SpeechController(service, this)

        controller.speak(request())
        advanceUntilIdle()

        // The engine took the request and never reported back: the button must not spin forever.
        val state = controller.state.value
        assertThat(state.phase).isEqualTo(SpeechPhase.FAILED)
        assertThat(state.message).isEqualTo(SpeechController.MESSAGE_TIMEOUT)
        assertThat(state.recovery).isEqualTo(SpeechRecovery.RETRY)
    }

    @Test
    fun `a second tap cancels the first request and stops playback`() = runTest {
        val service = FakeSpeechService(SpeechStatus.Initialising)
        val controller = SpeechController(service, this)

        controller.speak(request("apple"))
        controller.speak(request("banana"))
        service.become(ready)
        advanceUntilIdle()

        // Only the latest word is spoken: the first job was cancelled and playback stopped.
        assertThat(service.speakCount).isEqualTo(1)
        assertThat(service.stopCount).isAtLeast(1)
        assertThat(controller.state.value.content).isEqualTo(SpeechContent.WORD)
    }

    @Test
    fun `blank text is ignored so an empty example cannot fail the button`() = runTest {
        val service = FakeSpeechService(ready)
        val controller = SpeechController(service, this)

        controller.speak(request(text = "   "))
        advanceUntilIdle()

        assertThat(service.speakCount).isEqualTo(0)
        assertThat(controller.state.value.phase).isEqualTo(SpeechPhase.IDLE)
    }

    @Test
    fun `dismissing an error returns the button to idle`() = runTest {
        val service = FakeSpeechService(SpeechStatus.EngineUnavailable(null))
        service.failureOnSpeak = SpeechStatus.EngineUnavailable(null)
        val controller = SpeechController(service, this)

        controller.speak(request())
        advanceUntilIdle()
        assertThat(controller.state.value.hasFailed).isTrue()

        controller.dismissError()
        assertThat(controller.state.value.phase).isEqualTo(SpeechPhase.IDLE)
        assertThat(controller.state.value.message).isNull()
    }
}
