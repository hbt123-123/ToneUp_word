package top.toneup.word.data.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * The Android adapter: the **only** file in the speech package that touches `android.speech.tts`.
 *
 * Its whole job is translation — platform constants in, platform-neutral vocabulary out — plus one
 * promise: nothing here throws. `TextToSpeech` calls are wrapped, so a vendor engine that throws
 * (or returns a code nobody documented) degrades into "the request did not work" instead of a crash
 * on the main thread.
 */
class AndroidSpeechEngineFactory(private val context: Context) : SpeechEngineFactory {

    override fun create(onInit: (SpeechInitResult) -> Unit): SpeechEngine? {
        val appContext = context.applicationContext
        return runCatching {
            TextToSpeech(appContext) { status ->
                onInit(
                    SpeechInitResult(
                        success = status == TextToSpeech.SUCCESS,
                        errorCode = status.takeIf { it != TextToSpeech.SUCCESS },
                    ),
                )
            }
        }.getOrNull()?.let { tts -> AndroidSpeechEngine(tts) }
    }
}

/** A bound [TextToSpeech] seen through the [SpeechEngine] seam. */
private class AndroidSpeechEngine(private val tts: TextToSpeech) : SpeechEngine {

    override val engineName: String?
        get() = runCatching { tts.defaultEngine }.getOrNull()

    override val defaultLanguage: Locale?
        get() = runCatching { tts.defaultLanguage }.getOrNull()

    override val defaultVoiceName: String?
        get() = runCatching { tts.defaultVoice?.name }.getOrNull()

    override val voices: List<SpeechVoice>
        get() = runCatching { tts.voices.map { voice -> voice.toSpeechVoice() } }
            .getOrDefault(emptyList())
    override fun setLanguage(locale: Locale): Int = runCatching { tts.setLanguage(locale) }
        .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        .let { code ->
            when (code) {
                TextToSpeech.LANG_MISSING_DATA -> SpeechLanguageResult.MISSING_DATA
                TextToSpeech.LANG_NOT_SUPPORTED -> SpeechLanguageResult.NOT_SUPPORTED
                // LANG_AVAILABLE, LANG_COUNTRY_AVAILABLE and LANG_COUNTRY_VAR_AVAILABLE all mean
                // "the engine will speak this"; the country is a detail, not a failure.
                else -> SpeechLanguageResult.AVAILABLE
            }
        }

    override fun setVoice(voice: SpeechVoice): Boolean {
        // The platform voice is looked up by name: the seam never exposes `android.speech.tts.Voice`.
        val platform = runCatching { tts.voices.firstOrNull { it.name == voice.name } }.getOrNull()
            ?: return false
        return runCatching { tts.setVoice(platform) == TextToSpeech.SUCCESS }.getOrDefault(false)
    }

    override fun setSpeechRate(rate: Float) {
        runCatching { tts.setSpeechRate(rate) }
    }

    override fun setPitch(pitch: Float) {
        runCatching { tts.setPitch(pitch) }
    }

    override fun speak(text: String, utteranceId: String, listener: SpeechUtteranceListener): Boolean {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                listener.onStart()
            }

            override fun onDone(utteranceId: String?) {
                listener.onDone()
            }

            @Deprecated("Kept for API levels below 21 behaviour parity")
            override fun onError(utteranceId: String?) {
                listener.onError(null)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                listener.onError(errorCode)
            }
        })
        return runCatching {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.SUCCESS
        }.getOrDefault(false)
    }

    override fun stop() {
        runCatching { tts.stop() }
    }

    override fun shutdown() {
        runCatching { tts.shutdown() }
    }
}

/** The platform voice, in the seam's vocabulary. */
private fun Voice.toSpeechVoice() = SpeechVoice(
    name = name,
    locale = locale,
    quality = quality,
    requiresNetwork = isNetworkConnectionRequired,
)
