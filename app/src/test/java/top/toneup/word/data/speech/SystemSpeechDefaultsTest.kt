package top.toneup.word.data.speech

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for [parseSystemSpeechFactor], the decoder behind "跟随系统" speech rate/pitch.
 *
 * The bug being guarded: AOSP's TextToSpeechSettings stores `tts_default_rate`/`tts_default_pitch`
 * as an integer **percentage** (`100` = normal speed), while the first implementation only accepted
 * a 0.5–2.0 multiplier and therefore rejected the value on every real device. Both encodings must
 * work, and anything unreadable must stay `null` (meaning "leave the engine default alone") rather
 * than being guessed.
 */
class SystemSpeechDefaultsTest {

    // --- integer percent: what the system settings screen actually writes ---

    @Test
    fun `percent 100 is normal speed`() {
        assertThat(parseSystemSpeechFactor("100")).isEqualTo(1.0f)
        assertThat(parseSystemSpeechFactor("100.0")).isEqualTo(1.0f)
    }

    @Test
    fun `percent below normal is slower`() {
        assertThat(parseSystemSpeechFactor("80")).isEqualTo(0.8f)
        assertThat(parseSystemSpeechFactor("50")).isEqualTo(0.5f)
        assertThat(parseSystemSpeechFactor("150")).isEqualTo(1.5f)
        // 10% is the bottom of AOSP's own slider and must survive the decode.
        assertThat(parseSystemSpeechFactor("10")).isEqualTo(0.1f)
    }

    @Test
    fun `percent above two hundred stays a percentage`() {
        assertThat(parseSystemSpeechFactor("250")).isEqualTo(2.5f)
        assertThat(parseSystemSpeechFactor("400")).isEqualTo(4.0f)
    }

    // --- multiplier: hand-written / OEM values that were already understood ---

    @Test
    fun `a decimal multiplier is taken as is`() {
        assertThat(parseSystemSpeechFactor("1.2")).isEqualTo(1.2f)
        assertThat(parseSystemSpeechFactor("0.5")).isEqualTo(0.5f)
        assertThat(parseSystemSpeechFactor("2.0")).isEqualTo(2.0f)
        assertThat(parseSystemSpeechFactor(" 1.5 ")).isEqualTo(1.5f)
    }

    // --- unreadable values: must not override the engine default ---

    @Test
    fun `junk is unreadable`() {
        assertThat(parseSystemSpeechFactor(null)).isNull()
        assertThat(parseSystemSpeechFactor("")).isNull()
        assertThat(parseSystemSpeechFactor("   ")).isNull()
        assertThat(parseSystemSpeechFactor("fast")).isNull()
        assertThat(parseSystemSpeechFactor("1.0f")).isNull()
        assertThat(parseSystemSpeechFactor("0")).isNull()
        assertThat(parseSystemSpeechFactor("-1")).isNull()
        assertThat(parseSystemSpeechFactor("NaN")).isNull()
        assertThat(parseSystemSpeechFactor("Infinity")).isNull()
        // Decimals outside the multiplier range are not percentages either: the settings screen only
        // writes whole percentages, so 0.4 must not become 0.4% (0.004x, inaudible).
        assertThat(parseSystemSpeechFactor("0.4")).isNull()
        assertThat(parseSystemSpeechFactor("2.5")).isNull()
        // Below the system slider's 10% floor: a corrupted value must not become an inaudible rate.
        assertThat(parseSystemSpeechFactor("3")).isNull()
        assertThat(parseSystemSpeechFactor("9")).isNull()
    }

    @Test
    fun `a value beyond the percent ceiling is rejected instead of being clamped`() {
        // 400% is the top of the system slider; 401 cannot be a real setting and is not a plausible
        // multiplier either, so it is treated as corrupted rather than trusted.
        assertThat(parseSystemSpeechFactor("401")).isNull()
        assertThat(parseSystemSpeechFactor("100000")).isNull()
    }

    @Test
    fun `an empty defaults value means use the engine defaults`() {
        assertThat(SystemSpeechDefaults().rate).isNull()
        assertThat(SystemSpeechDefaults().pitch).isNull()
    }
}
