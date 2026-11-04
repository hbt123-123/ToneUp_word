package top.toneup.word.data.speech

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.coroutines.resume

/**
 * The online half of the reading feature: synthesize with Edge's read-aloud voices and play the
 * resulting MP3 in memory.
 *
 * Contract (same spirit as [AudioPlayback]):
 *  * returns `true` only when the stream played through; every failure — offline, DNS error,
 *    4xx/5xx, protocol timeout, unusable audio — returns `false` and the caller silently falls
 *    back to system TTS;
 *  * nothing is written to disk: the MP3 stays in memory and is played through
 *    [MediaDataSource], so online reading adds no cache and no storage;
 *  * [stop] is idempotent and safe while idle, and a cancelled request always releases its player.
 */
interface EdgeTts {
    suspend fun speak(request: SpeechRequest, onStart: () -> Unit = {}): Boolean
    fun stop()
}

class EdgeTtsPlayer(
    private val client: OkHttpClient = OkHttpClient(),
    private val synthesizeTimeoutMillis: Long = SYNTHESIZE_TIMEOUT_MILLIS,
    private val prepareTimeoutMillis: Long = PREPARE_TIMEOUT_MILLIS,
    private val playTimeoutMillis: Long = PLAY_TIMEOUT_MILLIS,
    /** Clock used only to derive the DRM token; injected so tests stay deterministic. */
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) : EdgeTts {

    private val lock = Any()
    private var current: MediaPlayer? = null
    private var waiting: CancellableContinuation<Boolean>? = null

    override suspend fun speak(request: SpeechRequest, onStart: () -> Unit): Boolean {
        if (request.text.isBlank()) return false
        val audio = synthesize(request) ?: return false
        return play(audio, onStart)
    }

    /**
     * Runs the two-frame protocol and collects the audio chunks.
     *
     * The timeout bounds a hung socket; a missing `turn.end` frame is as fatal as a network error
     * because the caller must be able to fall back quickly.
     */
    private suspend fun synthesize(request: SpeechRequest): ByteArray? {
        val audio = withTimeoutOrNull(synthesizeTimeoutMillis) {
            val chunks = ByteArrayOutputStream()
            val done = CompletableDeferred<ByteArray?>()
            val connectionId = randomId()
            val requestId = randomId()
            val wsRequest = Request.Builder()
                .url(EdgeTtsProtocol.connectionUrl(connectionId, nowMillis()))
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                // The service ties the DRM token to a per-browser `muid` cookie; without it the
                // handshake is also rejected.
                .header("Cookie", "muid=$connectionId")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0",
                )
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            val socket = client.newWebSocket(wsRequest, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(EdgeTtsProtocol.configurationMessage())
                    webSocket.send(
                        EdgeTtsProtocol.ssmlMessage(
                            text = request.text,
                            localeTag = request.localeTag,
                            rateMode = request.rateMode,
                            requestId = requestId,
                        ),
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (EdgeTtsProtocol.isTurnEnd(text)) {
                        webSocket.close(1000, null)
                        done.complete(chunks.toByteArray().takeIf { it.isNotEmpty() })
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: okhttp3.ByteString) {
                    EdgeTtsProtocol.audioFromFrame(bytes.toByteArray())?.let { chunks.write(it) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    done.complete(null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    // A close before `turn.end` must settle the wait instead of letting the
                    // outer timeout expire (that would delay the TTS fallback by seconds).
                    done.complete(null)
                }
            })
            try {
                done.await()
            } finally {
                socket.cancel()
            }
        }
        return audio
    }

    private suspend fun play(audio: ByteArray, onStart: () -> Unit): Boolean {
        val player = MediaPlayer()
        synchronized(lock) {
            current?.let { runCatching { it.release() } }
            current = player
        }
        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            player.setAudioAttributes(attributes)
            player.setDataSource(BytesMediaSource(audio))

            val prepared = withTimeoutOrNull(prepareTimeoutMillis) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    track(continuation)
                    player.setOnPreparedListener { settle(continuation, true) }
                    player.setOnErrorListener { _, _, _ ->
                        settle(continuation, false)
                        true
                    }
                    try {
                        player.prepareAsync()
                    } catch (error: Exception) {
                        settle(continuation, false)
                    }
                }
            } ?: false
            if (!prepared) return false

            player.setOnPreparedListener(null)
            player.setOnErrorListener(null)
            player.start()
            onStart()
            return withTimeoutOrNull(playTimeoutMillis) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    track(continuation)
                    player.setOnCompletionListener { settle(continuation, true) }
                    player.setOnErrorListener { _, _, _ ->
                        settle(continuation, false)
                        true
                    }
                }
            } ?: false
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            return false
        } finally {
            synchronized(lock) {
                if (waiting?.isActive != true) waiting = null
                if (current === player) current = null
            }
            runCatching { player.release() }
        }
    }

    override fun stop() {
        synchronized(lock) {
            waiting?.let { continuation -> if (continuation.isActive) continuation.resume(false) }
            waiting = null
            current?.let { player ->
                runCatching { player.stop() }
                runCatching { player.release() }
            }
            current = null
        }
    }

    private fun track(continuation: CancellableContinuation<Boolean>) {
        synchronized(lock) { waiting = continuation }
    }

    private fun settle(continuation: CancellableContinuation<Boolean>, value: Boolean) {
        synchronized(lock) {
            if (waiting === continuation) waiting = null
            if (continuation.isActive) continuation.resume(value)
        }
    }

    private fun randomId(): String = UUID.randomUUID().toString().replace("-", "").uppercase()

    /** Exposes the in-memory MP3 to `MediaPlayer` without touching the filesystem. */
    private class BytesMediaSource(private val data: ByteArray) : MediaDataSource() {
        override fun read(position: Long, buffer: ByteArray?, offset: Int, size: Int): Int {
            if (buffer == null || position >= data.size) return -1
            val count = minOf(size, data.size - position.toInt())
            System.arraycopy(data, position.toInt(), buffer, offset, count)
            return count
        }

        override fun getSize(): Long = data.size.toLong()

        override fun close() = Unit
    }

    companion object {
        /** How long the online engine may take to deliver audio before TTS takes over. */
        const val SYNTHESIZE_TIMEOUT_MILLIS = 10_000L

        private const val PREPARE_TIMEOUT_MILLIS = 8_000L

        /** Upper bound for one read; a sentence is far shorter, but a stuck decoder must not leak. */
        private const val PLAY_TIMEOUT_MILLIS = 60_000L
    }
}
