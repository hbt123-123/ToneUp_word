package top.toneup.word.data.speech

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Tests for the scheme policy of [AudioUrlPlayer] (M6b).
 *
 * `MediaPlayer` has no JVM behaviour, so what can be verified here is everything that must happen
 * *before* any platform object exists: which URLs are even attempted, and that an unusable one
 * resolves to `false` immediately rather than burning the prepare deadline. The live streaming
 * path (buffering, HTTP errors, audible playback) is device-only — see the M6b hand-off notes.
 */
class AudioUrlPlayerTest {

    @Test
    fun `only http and https urls are streamable`() {
        assertThat(isStreamableAudioUrl("https://cdn.example/apple.mp3")).isTrue()
        assertThat(isStreamableAudioUrl("http://cdn.example/apple.mp3")).isTrue()
        assertThat(isStreamableAudioUrl("HTTPS://cdn.example/apple.mp3")).isTrue()

        assertThat(isStreamableAudioUrl("")).isFalse()
        assertThat(isStreamableAudioUrl("   ")).isFalse()
        assertThat(isStreamableAudioUrl("file:///sdcard/apple.mp3")).isFalse()
        assertThat(isStreamableAudioUrl("content://media/external/audio/1")).isFalse()
        assertThat(isStreamableAudioUrl("ftp://cdn.example/apple.mp3")).isFalse()
        assertThat(isStreamableAudioUrl("apple.mp3")).isFalse()
    }

    @Test
    fun `an empty url fails without ever creating a player`() = runTest {
        var started = false

        val played = AudioUrlPlayer().play("") { started = true }

        assertThat(played).isFalse()
        assertThat(started).isFalse()
        // No prepare was even attempted: the wait never began, so no deadline was consumed.
        assertThat(currentTime).isEqualTo(0L)
    }

    @Test
    fun `a non-http scheme fails without ever creating a player`() = runTest {
        var started = false

        val played = AudioUrlPlayer().play("ftp://cdn.example/apple.mp3") { started = true }

        assertThat(played).isFalse()
        assertThat(started).isFalse()
        assertThat(currentTime).isEqualTo(0L)
    }

    @Test
    fun `an unreachable stream gives up when the prepare deadline passes`() = runTest {
        var started = false

        // On the JVM the platform player never reports readiness, which exercises exactly the
        // "connected too slowly" failure: the request resolves to false once the deadline passes.
        val played = AudioUrlPlayer().play("https://cdn.example/apple.mp3") { started = true }

        assertThat(played).isFalse()
        assertThat(started).isFalse()
        assertThat(currentTime).isEqualTo(AudioUrlPlayer.PREPARE_TIMEOUT_MILLIS)
    }
}
