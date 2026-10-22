package top.toneup.word.data.speech

import java.util.Locale

/**
 * The speech engine, behind an interface.
 *
 * Why this exists: the state machine in [SystemTtsSpeechService] (bind, negotiate a language, pin a
 * voice, speak, recover from a dead engine) is the part that is easy to get wrong and impossible to
 * exercise on the JVM — `android.speech.tts.TextToSpeech` needs a device. Everything that touches
 * the platform now sits behind this seam, so the state machine can be driven by a fake engine in a
 * plain JUnit test and every transition can be asserted without a phone.
 *
 * Nothing in this file imports `android.*`: the vocabulary is expressed in platform-neutral terms
 * and the Android adapter translates.
 */

/** Outcome of asking an engine for a language. Mirrors `TextToSpeech`'s `LANG_*` values. */
object SpeechLanguageResult {

    /** The engine can speak this language. */
    const val AVAILABLE = 0

    /** The engine knows the language but its voice data is not installed. */
    const val MISSING_DATA = -1

    /** The engine has no such language at all. */
    const val NOT_SUPPORTED = -2
}

/** One voice the engine offers. */
data class SpeechVoice(
    val name: String,
    val locale: Locale,
    val quality: Int,
    /** True when the voice streams from the network instead of being installed on the device. */
    val requiresNetwork: Boolean,
)

/** The result of binding an engine. [errorCode] is the platform code when the binding failed. */
data class SpeechInitResult(val success: Boolean, val errorCode: Int? = null)

/** Utterance progress, reported by the engine while it speaks. */
interface SpeechUtteranceListener {

    fun onStart()

    fun onDone()

    /** [errorCode] is the platform error code, when the platform gave one. */
    fun onError(errorCode: Int?)
}

/**
 * A bound speech engine.
 *
 * Every method is total: the Android adapter swallows platform exceptions, so the caller never has
 * to guard a call and the state machine never has to reason about a crash.
 */
interface SpeechEngine {

    /** The engine's package name, for the diagnostics panel. */
    val engineName: String?

    /** The engine's own default language. */
    val defaultLanguage: Locale?

    /** The engine's own default voice name, for the diagnostics panel. */
    val defaultVoiceName: String?

    /** The voices installed for this engine; empty when the engine does not expose them. */
    val voices: List<SpeechVoice>

    /** Returns one of the [SpeechLanguageResult] constants. */
    fun setLanguage(locale: Locale): Int

    /** Returns true when the voice was accepted. */
    fun setVoice(voice: SpeechVoice): Boolean

    fun setSpeechRate(rate: Float)

    fun setPitch(pitch: Float)

    /** Returns true when the utterance was accepted for playback. */
    fun speak(text: String, utteranceId: String, listener: SpeechUtteranceListener): Boolean

    fun stop()

    fun shutdown()
}

/**
 * Creates and binds an engine.
 *
 * `create` returns `null` when the platform refused to create an engine at all (no speech service
 * installed). The binding result is reported through [create]'s callback, which may fire **before**
 * `create` returns — the caller must not lose it.
 */
fun interface SpeechEngineFactory {
    fun create(onInit: (SpeechInitResult) -> Unit): SpeechEngine?
}
