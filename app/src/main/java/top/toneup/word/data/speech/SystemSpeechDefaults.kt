package top.toneup.word.data.speech

import android.content.Context
import android.provider.Settings

/**
 * Reads the user's text-to-speech preferences from the operating system.
 *
 * Why this class exists: Android does **not** hand an app the user's speech rate, pitch, engine or
 * language. A `TextToSpeech` instance starts with the engine's own defaults, so an app that calls
 * `setSpeechRate(1f)` unconditionally silently overrides whatever the learner configured globally.
 *
 * The keys below are the documented `Settings.Secure` entries that the system's own
 * "Text-to-speech output" screen writes (`Settings.Secure.TTS_DEFAULT_SYNTH` /
 * `TTS_DEFAULT_RATE` / `TTS_DEFAULT_PITCH` / `TTS_DEFAULT_LOCALE`).
 *
 * Three deliberate rules:
 *  1. every read is wrapped — an unreadable or missing key yields `null`, never an exception and
 *     never a substituted value (some vendor ROMs restrict these keys, and a wrong guess is worse
 *     than the engine's own default);
 *  2. a value outside every accepted encoding is treated as unreadable, because a corrupted setting
 *     must not make speech unusable;
 *  3. the numeric encodings are decoded exactly as AOSP writes them — see
 *     [parseSystemSpeechFactor], which accepts **both** a multiplier (0.5–2.0) and the integer
 *     percentage (e.g. `100` = normal) that `TextToSpeechSettings` stores on real devices.
 *
 * NOTE: readability of these keys on a physical device has **not** been verified by an automated
 * test in this project (Robolectric does not model vendor ROM behaviour). The design therefore
 * degrades safely: [SystemSpeechDefaults] with all-null fields means "use the engine defaults".
 */
interface SpeechDefaultsReader {
    fun read(): SystemSpeechDefaults
}

class AndroidSpeechDefaultsReader(private val context: Context) : SpeechDefaultsReader {

    override fun read(): SystemSpeechDefaults {
        val resolver = context.contentResolver
        return SystemSpeechDefaults(
            rate = parseSystemSpeechFactor(readString(resolver, KEY_RATE)),
            pitch = parseSystemSpeechFactor(readString(resolver, KEY_PITCH)),
            engine = readString(resolver, KEY_ENGINE),
            localeTag = readString(resolver, KEY_LOCALE),
        )
    }

    private fun readString(resolver: android.content.ContentResolver, key: String): String? =
        runCatching { Settings.Secure.getString(resolver, key) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    private companion object {
        /** `Settings.Secure.TTS_DEFAULT_RATE` */
        const val KEY_RATE = "tts_default_rate"

        /** `Settings.Secure.TTS_DEFAULT_PITCH` */
        const val KEY_PITCH = "tts_default_pitch"

        /** `Settings.Secure.TTS_DEFAULT_SYNTH` */
        const val KEY_ENGINE = "tts_default_synth"

        /** `Settings.Secure.TTS_DEFAULT_LOCALE` */
        const val KEY_LOCALE = "tts_default_locale"
    }
}

/**
 * Decodes a stored speech rate/pitch into the multiplier `setSpeechRate`/`setPitch` expect.
 *
 * Two encodings are in the wild and both must be honoured, which is the whole bug this function
 * fixes:
 *
 *  * **multiplier** — values in [MIN_FACTOR]..[MAX_FACTOR] are used as-is (`1.2` means 1.2x). This
 *    is what a few OEM builds and every hand-written test write;
 *  * **integer percent** — AOSP's `TextToSpeechSettings` stores the seek-bar position rounded to an
 *    integer percentage, so a device at normal speed stores **`100`**, and slower rates store
 *    `80`, `60`… Values in [MIN_PERCENT]..[MAX_PERCENT] are divided by 100 (`100` → 1.0,
 *    `80` → 0.8). Without this, "跟随系统" was silently ignored on nearly every real phone.
 *
 * A value that fits neither encoding (text, zero, negative, absurdly small or large) returns `null`,
 * which callers translate into "do not call the setter at all" — the engine's own default then
 * applies. The one unavoidable ambiguity, a bare `1` or `2`, is read as a multiplier, which is the
 * only interpretation that keeps 1x/2x meaningful.
 *
 * The percentage branch additionally requires a **whole** number, because that is all the settings
 * screen ever writes: it keeps a stray decimal like `0.4` from being read as 0.4% (0.004x, which
 * would be inaudible) instead of being rejected as noise.
 */
internal fun parseSystemSpeechFactor(raw: String?): Float? {
    val parsed = raw?.trim()?.toFloatOrNull() ?: return null
    if (!parsed.isFinite() || parsed <= 0f) return null
    if (parsed in MIN_FACTOR..MAX_FACTOR) return parsed
    if (parsed in MIN_PERCENT..MAX_PERCENT && parsed % 1f == 0f) return parsed / 100f
    return null
}

/** Slowest accepted multiplier; Android's own UI stops here. */
private const val MIN_FACTOR = 0.5f

/** Fastest multiplier that is still a multiplier rather than a percentage. */
private const val MAX_FACTOR = 2.0f

/** Lowest integer percentage AOSP's settings screen writes (10% = 0.1x). */
private const val MIN_PERCENT = 10f

/** Highest integer percentage AOSP's settings screen writes (400% = 4x). */
private const val MAX_PERCENT = 400f
