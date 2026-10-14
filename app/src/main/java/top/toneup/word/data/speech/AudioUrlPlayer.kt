package top.toneup.word.data.speech

import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Plays `audio_url` straight off the wire with the platform [MediaPlayer] (plan §9 / M6b).
 *
 * Design decisions that matter for the product:
 *  * **No disk footprint** — the stream is played as it arrives; nothing is downloaded, cached or
 *    written to storage, so the app stays offline-first-friendly and storage never grows with the
 *    word packs;
 *  * **Failures are values** — every error path (bad scheme, refused connection, 4xx/5xx, prepare
 *    timeout) resolves to `false` instead of an exception, because the caller's job is to fall
 *    back to system TTS without the learner ever noticing;
 *  * **Exactly one player, always released** — a single [current] handle is tracked under
 *    [lock]; both the end of [play] and [stop] go through the idempotent [Playback.release], so a
 *    request cancelled mid-flight (double tap, word switch, leaving the screen) cannot leak a
 *    playing stream behind the TTS that replaces it;
 *  * **Prepare has a deadline** — a stream that has not become playable within
 *    [PREPARE_TIMEOUT_MILLIS] is abandoned and reported as a failure; playback itself has no
 *    timeout (a slow network may legitimately take a while once audio is flowing).
 *
 * The player is created on the caller's thread (the composition's main dispatcher), so all
 * `MediaPlayer` callbacks arrive on that same thread and the continuation bookkeeping needs no
 * extra dispatching.
 */
class AudioUrlPlayer(
    private val prepareTimeoutMillis: Long = PREPARE_TIMEOUT_MILLIS,
) : AudioPlayback {

    private val lock = Any()

    /** The one live player, if any; cleared under [lock] before it is released. */
    private var current: Playback? = null

    /** The continuation currently waiting on [current], so [stop] can end a pending wait. */
    private var active: CancellableContinuation<Boolean>? = null

    override suspend fun play(url: String, onStart: () -> Unit): Boolean {
        // Checked before the first MediaPlayer exists: an empty or non-http(s) URL is not a
        // failure worth a player object, it is simply "use TTS".
        if (!isStreamableAudioUrl(url)) return false

        val playback = Playback(MediaPlayer())
        synchronized(lock) {
            current?.release()
            current = playback
        }
        try {
            val player = playback.mp
            // Built as separate statements rather than a chain: the value must exist even when a
            // caller provides its own attribute instance.
            val attributes = AudioAttributes.Builder()
            attributes.setUsage(AudioAttributes.USAGE_MEDIA)
            attributes.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            player.setAudioAttributes(attributes.build())
            player.setDataSource(url) // throws for an unusable URL → caught below → TTS fallback

            val prepared =
                withTimeoutOrNull(prepareTimeoutMillis) { awaitPrepared(player) } ?: false
            if (!prepared) return false

            // Phase listeners are swapped out so a late prepare event cannot settle the wrong wait.
            player.setOnPreparedListener(null)
            player.setOnErrorListener(null)
            player.start()
            onStart()
            return awaitCompletion(player)
        } catch (cancellation: CancellationException) {
            // The request was abandoned (double tap / stop / new word): propagate instead of
            // reporting a "failure" the caller would use to start TTS over the learner.
            throw cancellation
        } catch (e: Exception) {
            return false // never throws for a playback problem: bad URL, illegal state, …
        } finally {
            synchronized(lock) {
                if (active?.isActive != true) active = null
                if (current === playback) current = null
                playback.release()
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            active?.let { continuation ->
                if (continuation.isActive) continuation.resume(false)
            }
            active = null
            current?.release()
            current = null
        }
    }

    /** Suspends until the stream is buffered enough to start, or the prepare deadline passes. */
    private suspend fun awaitPrepared(player: MediaPlayer): Boolean =
        suspendCancellableCoroutine { continuation ->
            track(continuation)
            player.setOnPreparedListener { settle(continuation, true) }
            player.setOnErrorListener { _, _, _ ->
                settle(continuation, false)
                true // we consumed the error; no need for the framework's own recovery
            }
            try {
                player.prepareAsync()
            } catch (e: IllegalStateException) {
                settle(continuation, false)
            }
        }

    /**
     * Suspends until the stream ends or errors. Registered only after [MediaPlayer.start] on the
     * same thread, so the first completion callback cannot arrive before this continuation exists.
     */
    private suspend fun awaitCompletion(player: MediaPlayer): Boolean =
        suspendCancellableCoroutine { continuation ->
            track(continuation)
            player.setOnCompletionListener { settle(continuation, true) }
            player.setOnErrorListener { _, _, _ ->
                settle(continuation, false)
                true
            }
        }

    /** Publishes the wait so [stop] can end it while it is suspended. */
    private fun track(continuation: CancellableContinuation<Boolean>) {
        synchronized(lock) {
            active = continuation
        }
    }

    /** Resumes at most once — guards against a late callback after cancellation or a timeout. */
    private fun settle(continuation: CancellableContinuation<Boolean>, value: Boolean) {
        synchronized(lock) {
            if (active === continuation) active = null
            if (continuation.isActive) continuation.resume(value)
        }
    }

    /** Idempotent wrapper: [stop] and `finally` can both reach the same player. */
    private class Playback(val mp: MediaPlayer) {
        private var released = false

        fun release() {
            if (released) return
            released = true
            runCatching { mp.release() }
        }
    }

    companion object {
        /** How long a stream may take to become playable before we give up and use TTS. */
        const val PREPARE_TIMEOUT_MILLIS = 8_000L
    }
}
