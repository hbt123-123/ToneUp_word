package top.toneup.word.data.speech

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

/**
 * Tests for [speechLocaleCandidates], the language fallback chain used by
 * `SystemTtsSpeechService`.
 *
 * The bug being guarded: the service asked the engine for the generic tag `"en"` and treated
 * `LANG_NOT_SUPPORTED` as fatal, so vendor engines that only ship `en-US`/`en-GB` were unusable.
 * The chain must reach those variants while still ending with the engine's own default — and it
 * must not invent a foreign-language fallback, because that would replace the honest
 * "install the English voice data" prompt with garbled audio.
 */
class SpeechLocaleFallbackTest {

    @Test
    fun `english tries regional variants after the bare request`() {
        val chain = speechLocaleCandidates(Locale.forLanguageTag("en"), engineDefault = null)

        assertThat(chain).containsExactly(Locale.ENGLISH, Locale.US, Locale.UK).inOrder()
    }

    @Test
    fun `a rejected regional english request still reaches the sibling region`() {
        val chain = speechLocaleCandidates(Locale.forLanguageTag("en-GB"), engineDefault = null)

        assertThat(chain.first()).isEqualTo(Locale.UK)
        assertThat(chain).contains(Locale.US)
        assertThat(chain).contains(Locale.ENGLISH)
    }

    @Test
    fun `chinese tries simplified chinese then the bare language`() {
        val chain = speechLocaleCandidates(Locale.forLanguageTag("zh-CN"), engineDefault = null)

        assertThat(chain).containsExactly(Locale.SIMPLIFIED_CHINESE, Locale.CHINESE).inOrder()
    }

    @Test
    fun `the engine default comes last and only for the same language`() {
        val englishWithAustralianDefault =
            speechLocaleCandidates(Locale.forLanguageTag("en"), Locale.forLanguageTag("en-AU"))
        assertThat(englishWithAustralianDefault.last()).isEqualTo(Locale.forLanguageTag("en-AU"))

        // A Chinese-only engine must not be offered for English text: speaking "apple" with a
        // Chinese voice is not a fallback, it is a different bug.
        val englishWithChineseEngine =
            speechLocaleCandidates(Locale.forLanguageTag("en"), Locale.forLanguageTag("zh-CN"))
        assertThat(englishWithChineseEngine).doesNotContain(Locale.SIMPLIFIED_CHINESE)
    }

    @Test
    fun `duplicates are removed so each locale is tried once`() {
        val chain = speechLocaleCandidates(Locale.US, engineDefault = Locale.US)

        assertThat(chain).containsNoDuplicates()
        assertThat(chain.count { it == Locale.US }).isEqualTo(1)
    }

    @Test
    fun `a missing request yields no candidates instead of an exception`() {
        assertThat(speechLocaleCandidates(null, null)).isEmpty()
        assertThat(speechLocaleCandidates(Locale.ROOT, null)).isEmpty()
        // An empty request still uses the engine default: it is the only thing we know it can read.
        assertThat(speechLocaleCandidates(null, Locale.US)).containsExactly(Locale.US)
    }
}
