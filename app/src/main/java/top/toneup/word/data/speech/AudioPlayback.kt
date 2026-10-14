package top.toneup.word.data.speech

/**
 * Streams a word's own pronunciation recording over the network (plan §9 / M6b).
 *
 * Why this is an interface: `android.media.MediaPlayer` cannot be exercised on the JVM, so the
 * routing inside `SpeechController` is tested against a fake of this contract while the app wires
 * the real [AudioUrlPlayer] by default.
 *
 * Contract:
 *  * [play] returns `true` only when the stream played through to the end; **every** failure —
 *    unusable scheme, connection error, HTTP error, prepare timeout — returns `false`, and it
 *    never throws. The caller silently falls back to system TTS, so a dead URL is inaudible to
 *    the learner (no error state, no retry prompt);
 *  * [onStart] fires exactly once, when audible playback actually begins;
 *  * after [play] returns (true, false or cancellation) the underlying player is always released,
 *    so streaming audio and TTS can never overlap; [stop] is idempotent and safe when idle.
 */
interface AudioPlayback {
    /** Streams [url] once; see the contract above. */
    suspend fun play(url: String, onStart: () -> Unit = {}): Boolean

    /** Stops and releases any in-flight player (double tap, word switch, leaving the screen). */
    fun stop()
}

/**
 * The only schemes worth handing to `MediaPlayer` are `http://` / `https://`.
 *
 * Empty URLs (the common case: a word whose meaning has never been completed) and everything else
 * — `file://`, `content://`, `ftp://` — fail here *before* a player is constructed, which keeps
 * the plain-TTS path byte-for-byte identical to the pre-M6b behaviour.
 */
fun isStreamableAudioUrl(url: String): Boolean =
    url.startsWith("http://", ignoreCase = true) ||
        url.startsWith("https://", ignoreCase = true)
