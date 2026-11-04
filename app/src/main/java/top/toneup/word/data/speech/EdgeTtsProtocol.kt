package top.toneup.word.data.speech

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The wire protocol of Microsoft Edge's read-aloud service (WSS).
 *
 * Kept free of Android types so the message frames can be unit-tested on the JVM: the fragile
 * parts (SSML escaping, the binary audio-frame header, the timestamp header) are pure functions,
 * and only the socket/audio plumbing lives in [EdgeTtsPlayer].
 *
 * The service is the same one the Edge browser's "朗读" uses. It needs no key beyond the public
 * client token; the app only sends the text it is about to read.
 */
object EdgeTtsProtocol {

    /** Public token used by every Edge read-aloud consumer. */
    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    /** MP3 at 24 kHz mono 48 kbps — small enough to stream, clear enough for words. */
    const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"

    private const val ENDPOINT =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"

    /** Newline used inside the protocol headers (the service expects CRLF). */
    private const val CRLF = "\r\n"

    /**
     * Browser version advertised with the DRM token.
     *
     * The service validates that the token was produced for a recent Edge build, so this value has
     * to move forward with the reference client (currently Edge 143).
     */
    private const val SEC_MS_GEC_VERSION = "1-143.0.3650.75"

    /** Seconds between 1601-01-01 (Windows file time) and 1970-01-01 (Unix time). */
    private const val WINDOWS_EPOCH_OFFSET_SECONDS = 11_644_473_600L

    /** The DRM token only changes every five minutes (the service rounds the time the same way). */
    private const val SEC_MS_GEC_WINDOW_SECONDS = 300L

    /** Voice used for English content. */
    private const val VOICE_EN = "en-US-AriaNeural"

    /** Voice used for Chinese content. */
    private const val VOICE_ZH = "zh-CN-XiaoxiaoNeural"

    /** The language tag the request will actually be read in. */
    fun voiceFor(localeTag: String): String = when {
        localeTag.startsWith("zh", ignoreCase = true) -> VOICE_ZH
        else -> VOICE_EN
    }

    /**
     * Maps the app's coarse speed preference onto Edge's prosody percentage.
     *
     * `SYSTEM` means "no adjustment": Edge has no access to the operating system's TTS rate, so
     * leaving it at 0% is the honest translation.
     */
    fun rateFor(mode: SpeechRateMode): String = when (mode) {
        SpeechRateMode.SYSTEM -> "+0%"
        SpeechRateMode.SLOW -> "-20%"
        SpeechRateMode.NORMAL -> "+0%"
    }

    /** Escapes the five XML entities so a word like `R&D` cannot break the SSML document. */
    fun escapeSsml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /** Builds the SSML body for one request (newlines collapsed so prosody stays one sentence). */
    fun ssml(text: String, localeTag: String, rateMode: SpeechRateMode): String {
        val voice = voiceFor(localeTag)
        val xmlLang = if (localeTag.startsWith("zh", ignoreCase = true)) "zh-CN" else "en-US"
        val escaped = escapeSsml(text.replace(Regex("\\s+"), " ").trim())
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$xmlLang'>" +
            "<voice name='$voice'><prosody rate='${rateFor(rateMode)}' pitch='+0Hz'>$escaped</prosody></voice>" +
            "</speak>"
    }

    /** The `speech.config` frame that must precede the SSML frame. */
    fun configurationMessage(): String =
        "X-Timestamp:${timestamp()}$CRLF" +
            "Content-Type:application/json; charset=utf-8$CRLF" +
            "Path:speech.config$CRLF$CRLF" +
            "{\"context\":{\"synthesis\":{\"audio\":{" +
            "\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
            "\"outputFormat\":\"$OUTPUT_FORMAT\"}}}}"

    /** The SSML frame for [text]. */
    fun ssmlMessage(text: String, localeTag: String, rateMode: SpeechRateMode, requestId: String): String =
        "X-RequestId:$requestId$CRLF" +
            "Content-Type:application/ssml+xml$CRLF" +
            "X-Timestamp:${timestamp()}$CRLF" +
            "Path:ssml$CRLF$CRLF" +
            ssml(text, localeTag, rateMode)

    /**
     * WebSocket URL for one session; [connectionId] must be a UUID without braces.
     *
     * The service rejects a handshake that carries only [TRUSTED_CLIENT_TOKEN]: since 2024 it also
     * wants the `Sec-MS-GEC` DRM token (and its version), exactly like the Edge browser does.
     * Without them every request fails with HTTP 403, which the caller can only turn into a silent
     * fallback to system TTS — the online engine would never be heard.
     */
    fun connectionUrl(connectionId: String, nowMillis: Long): String =
        "$ENDPOINT?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
            "&ConnectionId=$connectionId" +
            "&Sec-MS-GEC=${secMsGec(nowMillis)}" +
            "&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"

    /**
     * The `Sec-MS-GEC` token: SHA-256 over the Windows file time (100 ns ticks, rounded down to
     * the current five-minute window) concatenated with [TRUSTED_CLIENT_TOKEN], hex uppercase.
     *
     * Pure on [nowMillis] so the algorithm is unit-testable. A device whose clock is off by more
     * than one window will fail the handshake; the caller then falls back to system TTS.
     */
    fun secMsGec(nowMillis: Long): String {
        val seconds = nowMillis / 1000L + WINDOWS_EPOCH_OFFSET_SECONDS
        val windowStart = seconds - (seconds % SEC_MS_GEC_WINDOW_SECONDS)
        val ticks = windowStart * 10_000_000L
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$ticks$TRUSTED_CLIENT_TOKEN".toByteArray(Charsets.US_ASCII))
        return digest.joinToString(separator = "") { byte ->
            "%02X".format(byte.toInt() and 0xFF)
        }
    }

    /** True when the text frame announces the end of the audio stream. */
    fun isTurnEnd(textMessage: String): Boolean = textMessage.contains("Path:turn.end")

    /**
     * Extracts the MP3 bytes from one binary WebSocket frame.
     *
     * The frame layout is `[2-byte big-endian header length][header][audio bytes]`, and the
     * header must name `Path:audio`. Returns `null` for any other frame (request/response
     * metadata is also delivered as binary), so a malformed frame can never be fed to the player.
     */
    fun audioFromFrame(frame: ByteArray): ByteArray? {
        if (frame.size < 2) return null
        val headerLength = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
        if (headerLength <= 0 || frame.size < 2 + headerLength) return null
        val header = String(frame, 2, headerLength, Charsets.UTF_8)
        if (!header.contains("Path:audio")) return null
        val start = 2 + headerLength
        if (start >= frame.size) return null
        return frame.copyOfRange(start, frame.size)
    }

    /** `X-Timestamp` header value, in the format the service expects (UTC). */
    private fun timestamp(): String {
        val format = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(System.currentTimeMillis()))
    }
}
