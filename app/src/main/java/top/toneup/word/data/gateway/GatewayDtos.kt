package top.toneup.word.data.gateway

import kotlinx.serialization.Serializable

/**
 * Wire models for the three gateway endpoints (网关使用文档.md §1–§3).
 *
 * Every field has a default: a gateway that omits a key still parses, because the app must
 * degrade to "fewer fields" rather than to an exception (failures are values).
 */

// --- §1 ECDICT dictionary --------------------------------------------------------------------

@Serializable
data class DictResponse(
    val found: Boolean = false,
    val entry: DictEntry? = null,
    /** Only useful for input suggestions later; ignored by the cascade (plan §7.2). */
    val suggestions: List<DictSuggestion> = emptyList(),
)

@Serializable
data class DictEntry(
    val word: String = "",
    val phonetic: String = "",
    /** English definition, newline separated. */
    val definition: String = "",
    /** Chinese translation, newline separated — the standard meaning (Q2). */
    val translation: String = "",
    val pos: String = "",
    val tag: String = "",
    val exchange: String = "",
)

@Serializable
data class DictSuggestion(
    val word: String = "",
    val phonetic: String = "",
    val translation: String = "",
)

// --- §2 FreeDictionary -----------------------------------------------------------------------

/**
 * `/en/{word}` answer in **both** shapes the gateway can return:
 *
 *  1. the dictionaryapi array of entries; or
 *  2. a single `ecdict-fallback` object when the upstream failed.
 *
 * A single DTO covers the two forms because the fallback object is a strict subset of an entry
 * (`source` + bare `phonetic`, meanings without part of speech); the custom serializer only has
 * to normalise "one object" into "a list of one" (plan §7.1, lenient polymorphic parsing).
 */
@Serializable(with = EnResponseSerializer::class)
data class EnResponse(val entries: List<EnEntry>)

@Serializable
data class EnEntry(
    val word: String = "",
    val phonetics: List<EnPhonetic> = emptyList(),
    val meanings: List<EnMeaning> = emptyList(),
    /** `"ecdict-fallback"` when the gateway degraded to its own dictionary. */
    val source: String = "",
    /** Bare phonetic of the fallback form (the array form uses [phonetics] instead). */
    val phonetic: String = "",
) {
    val isFallback: Boolean get() = source == "ecdict-fallback"
}

@Serializable
data class EnPhonetic(
    val text: String = "",
    val audio: String = "",
)

@Serializable
data class EnMeaning(
    val partOfSpeech: String = "",
    val definitions: List<EnDefinition> = emptyList(),
    /** dictionaryapi also puts synonyms next to the part of speech; both are collected. */
    val synonyms: List<String> = emptyList(),
    /**
     * Single definition carried directly by the gateway's `ecdict-fallback` shape, which has
     * `meanings: [{ "definition": "..." }]` instead of the full `definitions[]` array.
     */
    val definition: String = "",
)

@Serializable
data class EnDefinition(
    val definition: String = "",
    val example: String = "",
    val synonyms: List<String> = emptyList(),
)

// --- §3 chat completion (OpenAI compatible) ---------------------------------------------------

@Serializable
data class ChatMessage(
    val role: String = "",
    val content: String = "",
)

@Serializable
data class ChatRequest(
    val messages: List<ChatMessage>,
    val model: String = GatewayDefaults.MT_MODEL,
    val stream: Boolean = false,
)

@Serializable
data class ChatResponse(
    val choices: List<ChatChoice> = emptyList(),
)

@Serializable
data class ChatChoice(
    val index: Int = 0,
    val message: ChatMessage = ChatMessage(),
)
