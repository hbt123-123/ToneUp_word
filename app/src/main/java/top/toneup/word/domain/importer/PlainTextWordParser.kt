package top.toneup.word.domain.importer

import top.toneup.word.core.result.AppError
import top.toneup.word.core.result.ErrorCode
import top.toneup.word.core.result.Outcome
import top.toneup.word.domain.model.Word

/**
 * Parses pasted plain text into a word list (M3 paste import; the M5 clipboard detector and
 * Share-intent sheet reuse the same engine).
 *
 * Design goals, in order:
 *  1. **Never crash.** Any text — a URL, a Chinese paragraph, emoji, a 10 MB paste — produces
 *     either a preview or an [Outcome.Failure]; never an exception.
 *  2. **Be lenient about separators, strict about tokens.** Comma, semicolon, whitespace,
 *     newline, tab — anything separates; but only `^[A-Za-z][A-Za-z'-]*$` tokens of at most
 *     [MAX_WORD_LENGTH] characters count as words, so noise is *filtered, not imported*.
 *  3. **Honest counts.** Dropped and duplicate tokens are counted and shown before the write,
 *     exactly like the file importer's preview — the learner always sees what will happen.
 *
 * De-duplication is case-insensitive via [Word.normalizeWordKey] and keeps the first-seen
 * casing, so "Apple, apple" imports one word stored as "Apple".
 */
object PlainTextWordParser {

    /** Hard cap per paste, aligned with [WordPackParser.MAX_RECORDS]. */
    const val MAX_WORDS: Int = 20_000

    /** Maximum length of a single word, aligned with [WordPackParser.MAX_WORD_LENGTH]. */
    const val MAX_WORD_LENGTH: Int = 64

    /**
     * Runs of comma, semicolon or whitespace (including newlines and tabs) separate tokens.
     *
     * Full-width punctuation (，；) is *not* a separator on purpose: those characters bind a
     * CJK run into one token, which is then dropped by the token rule and counted as filtered
     * — the learner sees that the Chinese around their words was ignored, nothing sneaks in.
     */
    private val SEPARATOR = Regex("[,;\\s\\n\\r\\t]+")

    /** A word token: starts with a letter, then letters / apostrophes / hyphens only. */
    private val TOKEN = Regex("^[A-Za-z][A-Za-z'-]*$")

    /**
     * Splits [text] into unique, valid English word tokens.
     *
     * @param dropSingleLetters filters bare `a`-`z` tokens (a configurable noise filter per
     *   plan §6.1): single letters in pasted lists are almost always list markers or stray
     *   fragments, not vocabulary. Pasting a list that genuinely contains single letters can
     *   switch this off.
     */
    fun parse(text: String, dropSingleLetters: Boolean = true): Outcome<PlainTextImportPreview> {
        if (text.isBlank()) return Outcome.Failure(AppError.empty())

        val seen = HashSet<String>()
        val words = ArrayList<String>()
        var dropped = 0
        var duplicates = 0
        var totalTokens = 0

        for (rawToken in text.split(SEPARATOR)) {
            val token = rawToken.trim()
            // Leading/trailing separators produce empty fragments — they are not tokens.
            if (token.isEmpty()) continue
            totalTokens++
            val invalid = token.length > MAX_WORD_LENGTH ||
                !TOKEN.matches(token) ||
                (dropSingleLetters && token.length == 1)
            if (invalid) {
                dropped++
                continue
            }
            if (!seen.add(Word.normalizeWordKey(token))) {
                duplicates++
                continue
            }
            words += token
        }

        if (words.isEmpty()) {
            return Outcome.Failure(
                AppError(
                    code = ErrorCode.EMPTY,
                    message = "没有可导入的单词（共 $totalTokens 个词片段，都被过滤了）。",
                ),
            )
        }
        if (words.size > MAX_WORDS) {
            return Outcome.Failure(
                AppError(
                    code = ErrorCode.TOO_LARGE,
                    message = "识别出 ${words.size} 个单词，超过单次上限 $MAX_WORDS 个，请拆分后再导入。",
                ),
            )
        }
        return Outcome.Success(
            PlainTextImportPreview(
                words = words,
                dropped = dropped,
                duplicates = duplicates,
                totalTokens = totalTokens,
            ),
        )
    }
}
