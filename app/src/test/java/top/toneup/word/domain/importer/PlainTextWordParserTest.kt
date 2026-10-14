package top.toneup.word.domain.importer

import top.toneup.word.core.result.ErrorCode
import top.toneup.word.core.result.Outcome
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for the plain-text word parser (plan §6.1).
 *
 * The contract under test is "lenient separators, strict tokens, never crash": any paste
 * yields either a preview with honest counts or an [Outcome.Failure], never an exception.
 */
class PlainTextWordParserTest {

    private fun parse(text: String, dropSingleLetters: Boolean = true): Outcome<PlainTextImportPreview> =
        PlainTextWordParser.parse(text, dropSingleLetters)

    private fun preview(text: String, dropSingleLetters: Boolean = true): PlainTextImportPreview {
        val outcome = parse(text, dropSingleLetters)
        assertThat(outcome.isSuccess).isTrue()
        return requireNotNull(outcome.getOrNull()) { "expected a successful parse" }
    }

    /**
     * [count] distinct letter-only tokens ("aa", "ba", …). Starts past the 26 single letters
     * so the default single-letter noise filter never shrinks the batch; digits would make
     * each token invalid and defeat the purpose of a cap test.
     */
    private fun letterTokens(count: Int): String = (26 until 26 + count).joinToString(",") { index ->
        var n = index
        buildString {
            do {
                append(('a'.code + n % 26).toChar())
                n /= 26
            } while (n > 0)
        }
    }

    // --- Separators ------------------------------------------------------------------------

    @Test
    fun `commas separate tokens`() {
        val result = preview("apple,banana,cherry")
        assertThat(result.words).containsExactly("apple", "banana", "cherry").inOrder()
        assertThat(result.dropped).isEqualTo(0)
        assertThat(result.duplicates).isEqualTo(0)
    }

    @Test
    fun `semicolons separate tokens`() {
        assertThat(preview("apple;banana;cherry").words)
            .containsExactly("apple", "banana", "cherry").inOrder()
    }

    @Test
    fun `spaces separate tokens`() {
        assertThat(preview("apple banana cherry").words)
            .containsExactly("apple", "banana", "cherry").inOrder()
    }

    @Test
    fun `newlines separate tokens`() {
        assertThat(preview("apple\nbanana\r\ncherry\rpear").words)
            .containsExactly("apple", "banana", "cherry", "pear").inOrder()
    }

    @Test
    fun `tabs separate tokens`() {
        assertThat(preview("apple\tbanana").words).containsExactly("apple", "banana").inOrder()
    }

    @Test
    fun `mixed separators in one paste all work`() {
        val result = preview("apple, banana;cherry\nbanana\tpear,fig")
        assertThat(result.words).containsExactly("apple", "banana", "cherry", "pear", "fig").inOrder()
    }

    @Test
    fun `consecutive separators collapse into one break`() {
        val result = preview("apple,,;;\n\n  banana")
        assertThat(result.words).containsExactly("apple", "banana").inOrder()
        // The empty fragments between doubled separators are not tokens at all.
        assertThat(result.totalTokens).isEqualTo(2)
        assertThat(result.dropped).isEqualTo(0)
    }

    @Test
    fun `leading and trailing separators are ignored`() {
        val result = preview(",  \n apple ,banana ,\n")
        assertThat(result.words).containsExactly("apple", "banana").inOrder()
        assertThat(result.totalTokens).isEqualTo(2)
    }

    // --- Token validation ------------------------------------------------------------------

    @Test
    fun `a url is dropped as one token`() {
        val result = preview("see http://example.com for more")
        assertThat(result.words).containsExactly("see", "for", "more").inOrder()
        assertThat(result.dropped).isEqualTo(1)
    }

    @Test
    fun `chinese text is dropped and counted`() {
        val result = preview("你好 world 世界")
        assertThat(result.words).containsExactly("world").inOrder()
        assertThat(result.dropped).isEqualTo(2)
    }

    @Test
    fun `pure chinese input fails with the empty error`() {
        val outcome = parse("你好，世界")
        assertThat(outcome.isSuccess).isFalse()
        assertThat((outcome as Outcome.Failure).error.code).isEqualTo(ErrorCode.EMPTY)
    }

    @Test
    fun `digit tokens are dropped`() {
        val result = preview("apple 123 banana 4567")
        assertThat(result.words).containsExactly("apple", "banana").inOrder()
        assertThat(result.dropped).isEqualTo(2)
    }

    @Test
    fun `tokens with inner digits are dropped`() {
        val result = preview("apple h2o banana")
        assertThat(result.words).containsExactly("apple", "banana").inOrder()
        assertThat(result.dropped).isEqualTo(1)
    }

    @Test
    fun `apostrophes and hyphens are kept inside words`() {
        val result = preview("don't state-of-the-art co-op")
        assertThat(result.words).containsExactly("don't", "state-of-the-art", "co-op").inOrder()
        assertThat(result.dropped).isEqualTo(0)
    }

    @Test
    fun `a leading hyphen or apostrophe is dropped`() {
        val result = preview("apple -banana 'quoted")
        assertThat(result.words).containsExactly("apple").inOrder()
        assertThat(result.dropped).isEqualTo(2)
    }

    @Test
    fun `an over-long token is dropped`() {
        val longWord = "a".repeat(PlainTextWordParser.MAX_WORD_LENGTH + 1)
        val result = preview("apple $longWord banana")
        assertThat(result.words).containsExactly("apple", "banana").inOrder()
        assertThat(result.dropped).isEqualTo(1)
    }

    @Test
    fun `a token of exactly the maximum length is kept`() {
        val maxWord = "a".repeat(PlainTextWordParser.MAX_WORD_LENGTH)
        val result = preview("apple $maxWord")
        assertThat(result.words).containsExactly("apple", maxWord).inOrder()
        assertThat(result.dropped).isEqualTo(0)
    }

    @Test
    fun `punctuation-only fragments are dropped`() {
        val result = preview("apple !!! ... banana ???")
        assertThat(result.words).containsExactly("apple", "banana").inOrder()
        assertThat(result.dropped).isEqualTo(3)
    }

    // --- Case-insensitive de-duplication ----------------------------------------------------

    @Test
    fun `the same word in different cases counts as one duplicate`() {
        val result = preview("Apple apple APPLE aPpLe")
        assertThat(result.words).containsExactly("Apple").inOrder()
        assertThat(result.duplicates).isEqualTo(3)
    }

    @Test
    fun `first-seen casing wins`() {
        val result = preview("banana BANANA Banana")
        assertThat(result.words).containsExactly("banana").inOrder()
    }

    @Test
    fun `duplicates are counted separately from dropped tokens`() {
        val result = preview("apple Apple 123 banana")
        assertThat(result.words).containsExactly("apple", "banana").inOrder()
        assertThat(result.duplicates).isEqualTo(1)
        assertThat(result.dropped).isEqualTo(1)
        assertThat(result.totalTokens).isEqualTo(4)
    }

    // --- Single-letter noise filter -----------------------------------------------------------

    @Test
    fun `single letters are dropped by default`() {
        val result = preview("a apple b banana")
        assertThat(result.words).containsExactly("apple", "banana").inOrder()
        assertThat(result.dropped).isEqualTo(2)
    }

    @Test
    fun `single letters are kept when the filter is off`() {
        val result = preview("a apple", dropSingleLetters = false)
        assertThat(result.words).containsExactly("a", "apple").inOrder()
        assertThat(result.dropped).isEqualTo(0)
    }

    // --- Edge cases --------------------------------------------------------------------------

    @Test
    fun `blank input fails with the empty error`() {
        for (blank in listOf("", "   ", "\n\t\r ")) {
            val outcome = parse(blank)
            assertThat(outcome.isSuccess).isFalse()
            assertThat((outcome as Outcome.Failure).error.code).isEqualTo(ErrorCode.EMPTY)
        }
    }

    @Test
    fun `separators-only input fails with the empty error`() {
        val outcome = parse(",;\n\t  ")
        assertThat(outcome.isSuccess).isFalse()
        assertThat((outcome as Outcome.Failure).error.code).isEqualTo(ErrorCode.EMPTY)
    }

    @Test
    fun `over the word cap fails with the too-large error`() {
        val text = letterTokens(PlainTextWordParser.MAX_WORDS + 1)
        val outcome = parse(text)
        assertThat(outcome.isSuccess).isFalse()
        assertThat((outcome as Outcome.Failure).error.code).isEqualTo(ErrorCode.TOO_LARGE)
    }

    @Test
    fun `exactly at the word cap succeeds`() {
        val result = preview(letterTokens(PlainTextWordParser.MAX_WORDS))
        assertThat(result.words).hasSize(PlainTextWordParser.MAX_WORDS)
        assertThat(result.dropped).isEqualTo(0)
    }

    @Test
    fun `total tokens counts valid dropped and duplicate fragments alike`() {
        val result = preview("apple Apple 123 banana http://x.com")
        assertThat(result.totalTokens).isEqualTo(5)
        assertThat(result.importableCount).isEqualTo(2)
        assertThat(result.isEmpty).isFalse()
    }
}
