package top.toneup.word.domain.model

/**
 * A vocabulary entry as the UI and domain layers see it — free of Room annotations.
 *
 * [meanings] and [collocations] are ordered lists; the first meaning is the "简要释义"
 * shown in list rows. [source] and [license] travel with every word so imported content
 * can always be attributed.
 */
data class Word(
    val id: Long = 0L,
    val word: String,
    val phonetic: String = "",
    val partOfSpeech: String = "",
    val meanings: List<String> = emptyList(),
    val exampleSentence: String = "",
    val exampleTranslation: String = "",
    val collocations: List<String> = emptyList(),
    val level: CetLevel = CetLevel.CET4,
    val source: String = "",
    val license: String = "",
    val audioUri: String? = null,
    val packId: String? = null,
    // --- plan §9 (schema v3): learner edits + gateway state -------------------------------
    /** The learner's own note on this word; empty until they write one. */
    val userNote: String = "",
    /** FreeDictionary English definition — auxiliary reading shown in small type under the
     *  standard meanings (Q2: it never replaces the Chinese meanings). */
    val enDefinition: String = "",
    /** FreeDictionary pronunciation URL; empty means "read with system TTS" (M6b). */
    val audioUrl: String = "",
    /** NONE / OK / PENDING / FAILED — the meaning-completion state machine (D4). */
    val lookupState: String = "OK",
    /** ecdict / freecdict / mt / manual / file — `manual` is a terminal state that automation
     *  never overwrites (D4), which is what makes hand-edited meanings safe to trust. */
    val meaningSource: String = "",
) {
    val primaryMeaning: String get() = meanings.firstOrNull().orEmpty()

    /** `n. 苹果；苹果树` style label used inside option buttons. */
    val meaningLabel: String
        get() = buildString {
            if (partOfSpeech.isNotBlank()) {
                append(partOfSpeech.trim())
                append(' ')
            }
            append(primaryMeaning)
        }.trim()

    /** Normalised key used for de-duplication during import. */
    val normalized: String get() = normalizeWordKey(word)

    companion object {
        /** Case/space/punctuation insensitive key; keeps `Apple` and `apple` the same word. */
        fun normalizeWordKey(raw: String): String =
            raw.trim().lowercase().replace(Regex("[\\s\\u00A0]+"), " ").trim()
    }
}

/** A word together with the current user's progress, used by list screens and the queue. */
data class WordWithProgress(
    val word: Word,
    val progress: top.toneup.word.domain.srs.WordProgress,
)
