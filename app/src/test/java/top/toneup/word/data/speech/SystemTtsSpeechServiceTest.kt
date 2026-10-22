package top.toneup.word.data.speech

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import top.toneup.word.core.time.TimeProvider
import java.time.ZoneId
import java.util.Locale

/**
 * Tests for [SystemTtsSpeechService], the platform-free state machine behind the read-aloud button.
 *
 * This test is the reason the engine sits behind [SpeechEngine]: binding, negotiating a language,
 * pinning a voice, speaking and recovering from a dead engine are all Android-specific *in
 * practice* and all plain logic in theory. With a fake engine, every transition can be asserted on
 * the JVM — no device, no Robolectric, no wall-clock waiting.
 *
 * What is guarded here:
 *  * a successful binding speaks the request exactly once;
 *  * a failed binding is reported, and a later request rebinds the engine and is then spoken;
 *  * the rebind respects a cooldown, so a burst of taps cannot hammer the speech service;
 *  * the generic "en" tag falls back to a regional variant the engine actually has;
 *  * an engine that claims a language it has no voice for is reported as missing data instead of
 *    being read with a foreign voice;
 *  * a playback failure reaches the caller.
 */
class SystemTtsSpeechServiceTest {

    /** A scriptable engine: every call is recorded, nothing touches Android. */
    private class FakeEngine(
        override val engineName: String = "com.example.tts",
        override val defaultLanguage: Locale? = Locale.US,
        override val defaultVoiceName: String? = "voice-default",
        override val voices: List<SpeechVoice> = listOf(
            SpeechVoice("voice-en-us", Locale.US, 400, requiresNetwork = false),
        ),
        val initSuccess: Boolean = true,
        val acceptSpeech: Boolean = true,
        val supportedLocales: Set<String> = setOf("en", "en-US"),
    ) : SpeechEngine {

        val languageCalls = mutableListOf<Locale>()
        val voiceCalls = mutableListOf<SpeechVoice>()
        var speakCount = 0
        var rate: Float? = null
        var pitch: Float? = null
        var stopped = false
        var shutDown = false
        private var listener: SpeechUtteranceListener? = null

        override fun setLanguage(locale: Locale): Int {
            languageCalls += locale
            return if (locale.toLanguageTag() in supportedLocales) {
                SpeechLanguageResult.AVAILABLE
            } else {
                SpeechLanguageResult.NOT_SUPPORTED
            }
        }

        override fun setVoice(voice: SpeechVoice): Boolean {
            voiceCalls += voice
            return true
        }

        override fun setSpeechRate(rate: Float) {
            this.rate = rate
        }

        override fun setPitch(pitch: Float) {
            this.pitch = pitch
        }

        override fun speak(text: String, utteranceId: String, listener: SpeechUtteranceListener): Boolean {
            speakCount++
            this.listener = listener
            return acceptSpeech
        }

        override fun stop() {
            stopped = true
        }

        override fun shutdown() {
            shutDown = true
        }

        fun startSpeaking() {
            listener?.onStart()
        }

        fun finishSpeaking() {
            listener?.onDone()
        }

        fun failSpeaking(errorCode: Int?) {
            listener?.onError(errorCode)
        }
    }

    /** Hands out the engines in order; a `null` entry means "the platform refused to create one". */
    private class FakeFactory(private val engines: List<FakeEngine?>) : SpeechEngineFactory {

        var createCount = 0

        override fun create(onInit: (SpeechInitResult) -> Unit): SpeechEngine? {
            val engine = engines.getOrNull(createCount)
            createCount++
            if (engine == null) return null
            onInit(
                SpeechInitResult(
                    success = engine.initSuccess,
                    errorCode = if (engine.initSuccess) null else -1,
                ),
            )
            return engine
        }
    }

    /** A clock the test moves by hand. */
    private class FakeTime(var now: Long = 1_000L) : TimeProvider {
        override fun nowMillis(): Long = now
        override fun zone(): ZoneId = ZoneId.of("UTC")
    }

    /** System preferences the test dictates. */
    private class FakeDefaults(
        val rate: Float? = null,
        val pitch: Float? = null,
        val engine: String? = null,
        val localeTag: String? = null,
    ) : SpeechDefaultsReader {
        override fun read(): SystemSpeechDefaults = SystemSpeechDefaults(
            rate = rate,
            pitch = pitch,
            engine = engine,
            localeTag = localeTag,
        )
    }

    private fun service(
        factory: SpeechEngineFactory,
        time: FakeTime = FakeTime(),
        defaults: SpeechDefaultsReader = FakeDefaults(),
    ): SystemTtsSpeechService = SystemTtsSpeechService(
        engineFactory = factory,
        defaultsReader = defaults,
        onDiagnostic = {},
        time = time,
    )

    private fun request(localeTag: String = "en") = SpeechRequest(
        text = "apple",
        localeTag = localeTag,
        content = SpeechContent.WORD,
        rateMode = SpeechRateMode.SYSTEM,
    )

    // --- the happy path ---------------------------------------------------------------------

    @Test
    fun `a successful binding speaks the request exactly once`() {
        val engine = FakeEngine()
        val service = service(FakeFactory(listOf(engine)))

        assertThat(service.status.value).isInstanceOf(SpeechStatus.Ready::class.java)

        var started = false
        var done = false
        service.speak(request(), onStart = { started = true }, onDone = { done = true })

        assertThat(engine.speakCount).isEqualTo(1)
        engine.startSpeaking()
        assertThat(started).isTrue()
        engine.finishSpeaking()
        assertThat(done).isTrue()
    }

    @Test
    fun `the system rate and pitch are applied when they can be read`() {
        val engine = FakeEngine()
        val service = service(
            factory = FakeFactory(listOf(engine)),
            defaults = FakeDefaults(rate = 0.8f, pitch = 1.1f),
        )

        service.speak(request())

        assertThat(engine.rate).isEqualTo(0.8f)
        assertThat(engine.pitch).isEqualTo(1.1f)
    }

    // --- a dead engine ------------------------------------------------------------------------

    @Test
    fun `a failed binding reports the engine as unavailable`() {
        val engine = FakeEngine(initSuccess = false)
        val service = service(FakeFactory(listOf(engine)))

        assertThat(service.status.value).isEqualTo(SpeechStatus.EngineUnavailable("初始化返回错误码 -1"))

        var error: SpeechStatus? = null
        service.speak(request(), onError = { error = it })

        assertThat(error).isInstanceOf(SpeechStatus.EngineUnavailable::class.java)
        assertThat(engine.speakCount).isEqualTo(0)
    }

    @Test
    fun `a request after a failed binding rebinds the engine once and is then spoken`() {
        val failing = FakeEngine(initSuccess = false)
        val working = FakeEngine()
        val factory = FakeFactory(listOf(failing, working))
        val time = FakeTime()
        val service = service(factory, time)

        // The bind failed a moment ago, so this request is inside the cooldown and fails fast.
        service.speak(request())
        assertThat(factory.createCount).isEqualTo(1)

        // Once the cooldown has passed the next request rebinds, and the queued word is spoken.
        time.now += SystemTtsSpeechService.REBIND_COOLDOWN_MILLIS + 1
        service.speak(request())

        assertThat(factory.createCount).isEqualTo(2)
        assertThat(working.speakCount).isEqualTo(1)
        assertThat(failing.speakCount).isEqualTo(0)
        // The replaced engine is shut down, so repeated failures cannot leak binder connections.
        assertThat(failing.shutDown).isTrue()
    }

    // --- the language negotiation ---------------------------------------------------------------

    @Test
    fun `an engine that rejects the generic English tag still speaks with a regional variant`() {
        val engine = FakeEngine(supportedLocales = setOf("en-US"))
        val service = service(FakeFactory(listOf(engine)))

        service.speak(request())

        assertThat(engine.languageCalls.first()).isEqualTo(Locale.forLanguageTag("en"))
        assertThat(engine.languageCalls).contains(Locale.US)
        assertThat(engine.speakCount).isEqualTo(1)
    }

    @Test
    fun `a voice set without any matching voice is reported as missing data`() {
        val engine = FakeEngine(
            voices = listOf(SpeechVoice("voice-zh", Locale.SIMPLIFIED_CHINESE, 400, requiresNetwork = false)),
            supportedLocales = setOf("en"),
        )
        val service = service(FakeFactory(listOf(engine)))

        var error: SpeechStatus? = null
        service.speak(request(), onError = { error = it })

        assertThat(error).isEqualTo(SpeechStatus.LanguageMissing)
        assertThat(engine.speakCount).isEqualTo(0)
    }

    @Test
    fun `an engine that exposes no voices at all still speaks`() {
        val engine = FakeEngine(voices = emptyList(), supportedLocales = setOf("en"))
        val service = service(FakeFactory(listOf(engine)))

        service.speak(request())

        assertThat(engine.speakCount).isEqualTo(1)
    }

    @Test
    fun `the status reports the language and the voice the last read really used`() {
        val engine = FakeEngine()
        val service = service(FakeFactory(listOf(engine)))

        service.speak(request())

        val ready = service.status.value as SpeechStatus.Ready
        assertThat(ready.negotiatedLanguage).isEqualTo("en")
        assertThat(ready.negotiatedVoice).isEqualTo("voice-en-us")
        // The engine's own default is still reported next to it: the two can differ.
        assertThat(ready.language).isEqualTo("en-US")
        assertThat(ready.voice).isEqualTo("voice-default")
    }

    // --- playback ------------------------------------------------------------------------------

    @Test
    fun `a playback failure is reported to the caller`() {
        val engine = FakeEngine(acceptSpeech = false)
        val service = service(FakeFactory(listOf(engine)))

        var error: SpeechStatus? = null
        service.speak(request(), onError = { error = it })

        assertThat(error).isInstanceOf(SpeechStatus.Failed::class.java)
    }

    @Test
    fun `an engine error during playback is reported to the caller`() {
        val engine = FakeEngine()
        val service = service(FakeFactory(listOf(engine)))

        var error: SpeechStatus? = null
        service.speak(request(), onError = { error = it })
        engine.failSpeaking(7)

        assertThat(error).isEqualTo(SpeechStatus.Failed(7))
    }
}
