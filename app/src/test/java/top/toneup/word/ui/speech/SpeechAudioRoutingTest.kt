package top.toneup.word.ui.speech

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import top.toneup.word.data.speech.AudioPlayback
import top.toneup.word.data.speech.SpeechContent
import top.toneup.word.data.speech.SpeechRateMode
import top.toneup.word.data.speech.SpeechRequest
import top.toneup.word.data.speech.SpeechService
import top.toneup.word.data.speech.SpeechStatus
import top.toneup.word.domain.model.Word

private const val AUDIO_URL = "https://cdn.example/audio/apple.mp3"

/**
 * Tests for the `audio_url` routing added by M6b: a word's own recording is played first, and
 * every streaming failure falls back to system TTS *silently*.
 *
 * The word "silently" is the whole requirement, so it is asserted directly: the phases the button
 * goes through are watched for the duration of a request and [SpeechPhase.FAILED] must never
 * appear unless TTS itself failed. Nothing here touches Android or the network — the streamer is
 * a fake behind [AudioPlayback], because `MediaPlayer` has no JVM behaviour to test.
 */
class SpeechAudioRoutingTest {

    /** A scriptable engine, mirroring the one in [SpeechControllerTest]. */
    private class FakeSpeechService(
        initialStatus: SpeechStatus,
    ) : SpeechService {
        private val _status = MutableStateFlow(initialStatus)
        override val status: StateFlow<SpeechStatus> = _status

        var speakCount = 0
            private set
        var succeedOnSpeak = true

        override fun speak(
            request: SpeechRequest,
            onStart: () -> Unit,
            onDone: () -> Unit,
            onError: (SpeechStatus) -> Unit,
        ) {
            speakCount++
            if (succeedOnSpeak) {
                onStart()
                onDone()
            } else {
                onError(SpeechStatus.Failed(7))
            }
        }

        override fun stop() = Unit

        override fun release() = Unit
    }

    /** A scriptable streamer; every call is recorded so overlap with TTS can be proven absent. */
    private class FakeAudioPlayback : AudioPlayback {
        var playCount = 0
            private set
        var stopCount = 0
            private set
        var lastUrl: String? = null
            private set

        /** When false the stream "fails" after starting; when true it plays through. */
        var succeeds = true

        /** When true `play` throws, as a defensive test of the controller's catch-all. */
        var throws = false

        /** When set, `play` suspends until the test opens the gate (so PLAYING is observable). */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun play(url: String, onStart: () -> Unit): Boolean {
            playCount++
            lastUrl = url
            if (throws) throw java.io.IOException("connection reset")
            onStart()
            gate?.await()
            return succeeds
        }

        override fun stop() {
            stopCount++
        }
    }

    private val ready = SpeechStatus.Ready(
        engine = "com.example.tts",
        language = "en",
        voice = "voice-1",
        rateFromSystem = true,
        pitchFromSystem = false,
    )

    private val word = Word(
        id = 7L,
        word = "apple",
        exampleSentence = "An apple a day.",
        audioUrl = AUDIO_URL,
    )

    private fun wordRequest(audioUrl: String = "") = SpeechRequest(
        text = "apple",
        localeTag = "en",
        content = SpeechContent.WORD,
        rateMode = SpeechRateMode.SYSTEM,
        audioUrl = audioUrl,
    )

    // --- happy path: the recording wins ----------------------------------------

    @Test
    fun `a word with a recording plays it and never touches tts`() = runTest {
        val service = FakeSpeechService(ready)
        val player = FakeAudioPlayback()
        val controller = SpeechController(service, this, audio = player)

        controller.speak(wordRequest(audioUrl = AUDIO_URL))
        advanceUntilIdle()

        assertThat(player.playCount).isEqualTo(1)
        assertThat(player.lastUrl).isEqualTo(AUDIO_URL)
        assertThat(service.speakCount).isEqualTo(0)
        assertThat(controller.state.value.phase).isEqualTo(SpeechPhase.IDLE)
        assertThat(controller.state.value.message).isNull()
    }

    @Test
    fun `online playback reports playing and then returns to idle`() = runTest {
        val service = FakeSpeechService(ready)
        val player = FakeAudioPlayback().apply { gate = CompletableDeferred() }
        val controller = SpeechController(service, this, audio = player)

        controller.speak(wordRequest(audioUrl = AUDIO_URL))
        advanceUntilIdle()
        // The recording is streaming: same loading→playing semantics as TTS.
        assertThat(controller.state.value.phase).isEqualTo(SpeechPhase.PLAYING)
        assertThat(service.speakCount).isEqualTo(0)

        player.gate?.complete(Unit)
        advanceUntilIdle()

        assertThat(controller.state.value.phase).isEqualTo(SpeechPhase.IDLE)
        assertThat(controller.state.value.message).isNull()
        assertThat(service.speakCount).isEqualTo(0)
    }

    // --- silent fallback -------------------------------------------------------

    @Test
    fun `a failed recording silently falls back to tts and never shows failed`() = runTest {
        val service = FakeSpeechService(ready)
        val player = FakeAudioPlayback().apply { succeeds = false }
        val controller = SpeechController(service, this, audio = player)

        val phases = mutableListOf<SpeechPhase>()
        val watcher = launch { controller.state.collect { phases += it.phase } }

        controller.speak(wordRequest(audioUrl = AUDIO_URL))
        advanceUntilIdle()
        watcher.cancel()

        // TTS took over and finished normally: no failure is ever shown for a dead recording.
        assertThat(service.speakCount).isEqualTo(1)
        assertThat(controller.state.value.phase).isEqualTo(SpeechPhase.IDLE)
        assertThat(controller.state.value.message).isNull()
        assertThat(phases).doesNotContain(SpeechPhase.FAILED)
        assertThat(phases).contains(SpeechPhase.LOADING)
    }

    @Test
    fun `a thrown streamer error also falls back to tts`() = runTest {
        val service = FakeSpeechService(ready)
        val player = FakeAudioPlayback().apply { throws = true }
        val controller = SpeechController(service, this, audio = player)

        controller.speak(wordRequest(audioUrl = AUDIO_URL))
        advanceUntilIdle()

        assertThat(player.playCount).isEqualTo(1)
        assertThat(service.speakCount).isEqualTo(1)
        assertThat(controller.state.value.phase).isEqualTo(SpeechPhase.IDLE)
        assertThat(controller.state.value.message).isNull()
    }

    @Test
    fun `an empty audio url goes straight to tts without touching the player`() = runTest {
        val service = FakeSpeechService(ready)
        val player = FakeAudioPlayback()
        val controller = SpeechController(service, this, audio = player)

        controller.speak(wordRequest(audioUrl = ""))
        advanceUntilIdle()

        assertThat(player.playCount).isEqualTo(0)
        assertThat(service.speakCount).isEqualTo(1)
        assertThat(controller.state.value.phase).isEqualTo(SpeechPhase.IDLE)
    }

    @Test
    fun `a non-http scheme never reaches the player`() = runTest {
        val service = FakeSpeechService(ready)
        val player = FakeAudioPlayback()
        val controller = SpeechController(service, this, audio = player)

        // Rejected by the scheme check before any player is built → plain TTS. Each tap runs to
        // completion before the next: a second `speak` cancels the in-flight request (by design,
        // covered by the mutual-exclusion tests below), which would otherwise collapse two
        // back-to-back taps into a single TTS call.
        controller.speak(wordRequest(audioUrl = "ftp://cdn.example/apple.mp3"))
        advanceUntilIdle()
        controller.speak(wordRequest(audioUrl = "content://media/external/audio/1"))
        advanceUntilIdle()

        assertThat(player.playCount).isEqualTo(0)
        assertThat(service.speakCount).isEqualTo(2)
        assertThat(controller.state.value.phase).isEqualTo(SpeechPhase.IDLE)
    }

    // --- FAILED stays reserved for TTS -----------------------------------------

    @Test
    fun `when both the recording and tts fail the button still fails visibly`() = runTest {
        val service = FakeSpeechService(ready).apply { succeedOnSpeak = false }
        val player = FakeAudioPlayback().apply { succeeds = false }
        val controller = SpeechController(service, this, audio = player)

        controller.speak(wordRequest(audioUrl = AUDIO_URL))
        advanceUntilIdle()

        assertThat(player.playCount).isEqualTo(1)
        assertThat(service.speakCount).isEqualTo(1)
        assertThat(controller.state.value.phase).isEqualTo(SpeechPhase.FAILED)
        assertThat(controller.state.value.recovery)
            .isEqualTo(top.toneup.word.data.speech.SpeechRecovery.RETRY)
    }

    @Test
    fun `retrying after a tts failure tries the recording again`() = runTest {
        val service = FakeSpeechService(ready).apply { succeedOnSpeak = false }
        val player = FakeAudioPlayback().apply { succeeds = false }
        val controller = SpeechController(service, this, audio = player)

        controller.speak(wordRequest(audioUrl = AUDIO_URL))
        advanceUntilIdle()
        assertThat(controller.state.value.phase).isEqualTo(SpeechPhase.FAILED)

        service.succeedOnSpeak = true
        player.succeeds = true
        controller.retry()
        advanceUntilIdle()

        assertThat(player.playCount).isEqualTo(2)
        assertThat(service.speakCount).isEqualTo(1) // the retry succeeded online, TTS not needed
        assertThat(controller.state.value.phase).isEqualTo(SpeechPhase.IDLE)
    }

    // --- only the headword carries a recording ---------------------------------

    @Test
    fun `only the headword request carries the recording url`() {
        assertThat(word.asSpeechRequest(SpeechRateMode.SYSTEM).audioUrl).isEqualTo(AUDIO_URL)
        assertThat(word.exampleAsSpeechRequest(SpeechRateMode.SYSTEM).audioUrl).isEmpty()
        assertThat(word.meaningAsSpeechRequest(SpeechRateMode.SYSTEM).audioUrl).isEmpty()
    }

    @Test
    fun `an example sentence uses tts even when the word has a recording`() = runTest {
        val service = FakeSpeechService(ready)
        val player = FakeAudioPlayback()
        val controller = SpeechController(service, this, audio = player)

        controller.speak(word.exampleAsSpeechRequest(SpeechRateMode.SYSTEM))
        advanceUntilIdle()

        assertThat(player.playCount).isEqualTo(0)
        assertThat(service.speakCount).isEqualTo(1)
        assertThat(controller.state.value.phase).isEqualTo(SpeechPhase.IDLE)
    }

    // --- mutual exclusion ------------------------------------------------------

    @Test
    fun `stopping mid-recording stops the player and does not fall back to tts`() = runTest {
        val service = FakeSpeechService(ready)
        val player = FakeAudioPlayback().apply { gate = CompletableDeferred() }
        val controller = SpeechController(service, this, audio = player)

        controller.speak(wordRequest(audioUrl = AUDIO_URL))
        advanceUntilIdle()
        assertThat(controller.state.value.phase).isEqualTo(SpeechPhase.PLAYING)

        controller.stop()
        advanceUntilIdle()

        assertThat(player.stopCount).isAtLeast(1)
        assertThat(controller.state.value.phase).isEqualTo(SpeechPhase.IDLE)
        // A cancelled request must never start talking again behind the learner's back.
        assertThat(service.speakCount).isEqualTo(0)
    }

    @Test
    fun `a second tap stops the streaming player before speaking the next word`() = runTest {
        val service = FakeSpeechService(ready)
        val player = FakeAudioPlayback().apply { gate = CompletableDeferred() }
        val controller = SpeechController(service, this, audio = player)

        controller.speak(wordRequest(audioUrl = AUDIO_URL))
        advanceUntilIdle()
        assertThat(controller.state.value.phase).isEqualTo(SpeechPhase.PLAYING)

        player.gate = null // the next word has no recording of its own
        controller.speak(wordRequest())
        advanceUntilIdle()

        assertThat(player.stopCount).isAtLeast(1)
        assertThat(service.speakCount).isEqualTo(1)
        assertThat(controller.state.value.phase).isEqualTo(SpeechPhase.IDLE)
        assertThat(controller.state.value.content).isEqualTo(SpeechContent.WORD)
    }
}
