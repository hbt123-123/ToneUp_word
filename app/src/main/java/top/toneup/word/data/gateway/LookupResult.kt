package top.toneup.word.data.gateway

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where a completed lookup got its data from (plan D4 vocabulary: `ecdict / freecdict / mt`).
 *
 * [id] is what lands in `word.meaning_source` and `meaning_cache.source`; the `@SerialName`
 * keeps the `meaning_cache.payload` JSON in the same stable spelling.
 */
@Serializable
enum class LookupSource(val id: String) {
    @SerialName("ecdict")
    ECDICT("ecdict"),

    @SerialName("freecdict")
    FREEDICT("freecdict"),

    @SerialName("mt")
    MT("mt"),
}

/**
 * One completed gateway lookup, and the exact shape persisted in `meaning_cache.payload`.
 *
 * [meanings] is the **standard** meaning and is always Chinese when a source could provide one
 * (ECDICT `translation`, or the LLM fallback — Q2). FreeDictionary contributes auxiliary fields
 * only: English definition, example, synonyms, audio. It is normal for a FreeDictionary hit to
 * leave [meanings] empty; the word row then simply keeps whatever it had.
 */
@Serializable
data class LookupResult(
    val phonetic: String = "",
    val partOfSpeech: String = "",
    /** Standard (Chinese-first) meanings, one entry per line of the source answer. */
    val meanings: List<String> = emptyList(),
    val enDefinition: String = "",
    val example: String = "",
    val synonyms: List<String> = emptyList(),
    val audioUrl: String = "",
    val source: LookupSource = LookupSource.ECDICT,
)

/** Payload codec for the `meaning_cache.payload` column. Response-only encoding. */
object LookupPayloadCodec {

    fun encode(result: LookupResult, json: Json): String = json.encodeToString(LookupResult.serializer(), result)

    /** Never throws: a corrupt payload degrades to `null`, i.e. "refetch". */
    fun decode(raw: String?, json: Json): LookupResult? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(LookupResult.serializer(), raw) }.getOrNull()
    }
}

/**
 * Parses the LLM fallback answer (`词性. 释义`, optionally several lines) into a part of speech
 * plus the Chinese meaning lines. A reply that does not follow the template is taken verbatim:
 * a slightly messy meaning beats no meaning at all.
 */
internal fun parseMtContent(content: String): Pair<String, List<String>> {
    val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return "" to emptyList()
    val match = MT_LINE.find(lines.first())
    if (match == null) return "" to lines
    val partOfSpeech = match.groupValues[1].trim()
    val first = match.groupValues[2].trim().ifEmpty { lines.first() }
    return partOfSpeech to (listOf(first) + lines.drop(1))
}

/** `n. 名词` / `v.to make` — an abbreviation or word, a dot, then the meaning. */
private val MT_LINE = Regex("""^([A-Za-z][A-Za-z .]{0,15}?)\.\s*(.+)$""")
