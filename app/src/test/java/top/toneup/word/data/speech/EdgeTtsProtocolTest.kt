package top.toneup.word.data.speech

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The pure parts of the Edge read-aloud protocol.
 *
 * The network call itself cannot be unit-tested here, but the failure-prone pieces can: SSML is
 * XML, so an unescaped `&` in a word ("R&D") would make the service reject the whole request; the
 * binary frame contains a big-endian header length, so off-by-one slicing silently corrupts audio;
 * and the turn-end marker is the only finish signal (without it the player would hang until the
 * timeout and never fall back to system TTS).
 */
class EdgeTtsProtocolTest {

    @Test
    fun `ssml escapes xml entities`() {
        val ssml = EdgeTtsProtocol.ssml("R&D <tag> \"quote\" it's", "en", SpeechRateMode.NORMAL)

        assertThat(ssml).contains("R&amp;D &lt;tag&gt; &quot;quote&quot; it&apos;s")
        assertThat(ssml).doesNotContain("R&D")
    }

    @Test
    fun `ssml picks the voice by locale`() {
        assertThat(EdgeTtsProtocol.ssml("apple", "en-US", SpeechRateMode.NORMAL))
            .contains("en-US-AriaNeural")
        assertThat(EdgeTtsProtocol.ssml("苹果", "zh-CN", SpeechRateMode.NORMAL))
            .contains("zh-CN-XiaoxiaoNeural")
    }

    @Test
    fun `slow reading lowers the prosody rate`() {
        assertThat(EdgeTtsProtocol.rateFor(SpeechRateMode.SLOW)).isEqualTo("-20%")
        assertThat(EdgeTtsProtocol.rateFor(SpeechRateMode.NORMAL)).isEqualTo("+0%")
        // SYSTEM means "no adjustment": Edge cannot read the OS rate setting.
        assertThat(EdgeTtsProtocol.rateFor(SpeechRateMode.SYSTEM)).isEqualTo("+0%")
    }

    @Test
    fun `audio frame payload starts after the header`() {
        val audio = byteArrayOf(1, 2, 3, 4)
        val header = "Path:audio\r\n".toByteArray(Charsets.UTF_8)
        val headerLength = header.size
        val frame = byteArrayOf(
            ((headerLength shr 8) and 0xFF).toByte(),
            (headerLength and 0xFF).toByte(),
            *header,
            *audio,
        )

        assertThat(EdgeTtsProtocol.audioFromFrame(frame)).isEqualTo(audio)
    }

    @Test
    fun `frames without audio are ignored`() {
        val header = "Path:response\r\n".toByteArray(Charsets.UTF_8)
        val headerLength = header.size
        val frame = byteArrayOf(
            ((headerLength shr 8) and 0xFF).toByte(),
            (headerLength and 0xFF).toByte(),
            *header,
            9,
        )

        assertThat(EdgeTtsProtocol.audioFromFrame(frame)).isNull()
        assertThat(EdgeTtsProtocol.audioFromFrame(byteArrayOf(1))).isNull()
    }

    @Test
    fun `turn end is the only finish marker`() {
        assertThat(EdgeTtsProtocol.isTurnEnd("X-RequestId:1\r\nPath:turn.end\r\n")).isTrue()
        assertThat(EdgeTtsProtocol.isTurnEnd("Path:turn.started")).isFalse()
    }

    @Test
    fun `connection url carries the drm token`() {
        val url = EdgeTtsProtocol.connectionUrl("ABC", 1_700_000_000_000L)

        assertThat(url).contains("TrustedClientToken=${EdgeTtsProtocol.TRUSTED_CLIENT_TOKEN}")
        assertThat(url).contains("ConnectionId=ABC")
        assertThat(url).contains("Sec-MS-GEC=")
        assertThat(url).contains("Sec-MS-GEC-Version=")
    }

    @Test
    fun `drm token is stable inside a five minute window`() {
        // 1_700_000_000 * 1000 ms lands 200 s into a window, so +99 s stays and +101 s rolls over.
        val base = 1_700_000_000_000L
        val token = EdgeTtsProtocol.secMsGec(base)

        assertThat(token).hasLength(64)
        assertThat(EdgeTtsProtocol.secMsGec(base + 99_000L)).isEqualTo(token)
        assertThat(EdgeTtsProtocol.secMsGec(base + 101_000L)).isNotEqualTo(token)
    }
}
