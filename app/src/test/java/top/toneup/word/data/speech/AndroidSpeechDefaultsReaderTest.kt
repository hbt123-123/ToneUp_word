package top.toneup.word.data.speech

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Wiring test for [AndroidSpeechDefaultsReader]: the platform key names and the "unreadable means
 * engine default" contract, exercised through a real (Robolectric) `ContentResolver`.
 *
 * [SystemSpeechDefaultsTest] covers the encoding rules themselves; this test only proves the reader
 * looks at the values the system writes and never invents one. Vendor-ROM readability still cannot
 * be modelled here.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidSpeechDefaultsReaderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `percent values from the system settings are decoded`() {
        Settings.Secure.putString(context.contentResolver, "tts_default_rate", "80")
        Settings.Secure.putString(context.contentResolver, "tts_default_pitch", "100")

        val defaults = AndroidSpeechDefaultsReader(context).read()

        assertThat(defaults.rate).isEqualTo(0.8f)
        assertThat(defaults.pitch).isEqualTo(1.0f)
    }

    @Test
    fun `engine and locale are read verbatim and blanks become null`() {
        Settings.Secure.putString(context.contentResolver, "tts_default_synth", "com.example.tts")
        Settings.Secure.putString(context.contentResolver, "tts_default_locale", "en-GB")

        val defaults = AndroidSpeechDefaultsReader(context).read()

        assertThat(defaults.engine).isEqualTo("com.example.tts")
        assertThat(defaults.localeTag).isEqualTo("en-GB")

        Settings.Secure.putString(context.contentResolver, "tts_default_synth", "")
        assertThat(AndroidSpeechDefaultsReader(context).read().engine).isNull()
    }

    @Test
    fun `junk and absent values leave every field null instead of guessing`() {
        Settings.Secure.putString(context.contentResolver, "tts_default_rate", "not-a-number")
        Settings.Secure.putString(context.contentResolver, "tts_default_pitch", "0")
        Settings.Secure.putString(context.contentResolver, "tts_default_synth", "")
        Settings.Secure.putString(context.contentResolver, "tts_default_locale", "  ")

        val defaults = AndroidSpeechDefaultsReader(context).read()

        assertThat(defaults.rate).isNull()
        assertThat(defaults.pitch).isNull()
        assertThat(defaults.engine).isNull()
        assertThat(defaults.localeTag).isNull()
    }
}
