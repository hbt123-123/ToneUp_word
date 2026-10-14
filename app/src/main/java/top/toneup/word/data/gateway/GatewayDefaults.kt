package top.toneup.word.data.gateway

/**
 * Hard-coded defaults for the self-hosted definition gateway (plan Q3, 网关使用文档.md).
 *
 * The key lives here as a plain constant on purpose: the app is offline-first, ships no account
 * system and accepts that a client-side secret can be recovered from an APK. The value is still
 * overridable from the settings screen, and if it ever leaks it is rotated server-side
 * (网关使用文档.md §5). Never log it — the HTTP stack redacts the `Authorization` header instead.
 */
object GatewayDefaults {

    /** Public gateway address. [httpsOnly] validation rejects anything else (plan D7). */
    const val BASE_URL = "https://gateway.lztfirefly.top"

    /** Default `GATEWAY_KEY` (Authorization: Bearer). */
    const val KEY = "nutA1n2kzY65wtRl8qn8nuTRoKBRSnXZ"

    // --- paths (all user-overridable) ------------------------------------------------------
    /** ECDICT dictionary lookup; query parameter `word`. */
    const val DICT_PATH = "/dict"

    /** FreeDictionary lookup; `{word}` is replaced with the URL-encoded token. */
    const val EN_PATH = "/en/{word}"

    /** OpenAI-compatible chat completion used for the Chinese-meaning fallback. */
    const val MT_PATH = "/v1/chat/completions"

    // --- behaviour ---------------------------------------------------------------------------
    /** Positive *and* negative cache window: one gateway hit per token per 24h at most. */
    const val CACHE_TTL_MILLIS: Long = 24L * 60L * 60L * 1000L

    const val CONNECT_TIMEOUT_SECONDS: Long = 10L
    const val READ_TIMEOUT_SECONDS: Long = 20L

    /** A `FAILED` word is retried automatically only while it has attempts left (plan §7.3). */
    const val MAX_LOOKUP_ATTEMPTS = 3

    /** Words per worker run and gap between two words: 100k requests/day budget (plan D5). */
    const val BATCH_LIMIT = 50
    const val THROTTLE_MILLIS: Long = 250L

    /** Unique WorkManager name for the batch (plan D5). */
    const val WORK_NAME = "meaning-sync"
    const val WORK_TAG = "meaning-sync"

    /** Explicit so the gateway never has to pick a default itself (chat endpoint, plan §7.2). */
    const val MT_MODEL = "tencent/Hunyuan-MT-7B"

    /**
     * Prompt template for the LLM fallback — a constant so tests can assert on the exact body.
     * The answer format `词性. 释义` is what [parseMtContent] expects.
     */
    fun mtPrompt(word: String): String = "把单词「$word」的常用词性和中文释义翻译出来，一行，格式：词性. 释义"
}

/**
 * The `lookup_state` / `meaning_source` / `meaning_cache.status` vocabulary of schema v2 (D4).
 * Stored as stable strings and referenced by SQL literals in the DAOs.
 */
object MeaningStates {
    const val NONE = "NONE"
    const val OK = "OK"
    const val PENDING = "PENDING"
    const val FAILED = "FAILED"

    /** Sources; `manual` (user edit) is never overwritten by automation. */
    const val SOURCE_ECDICT = "ecdict"
    const val SOURCE_FREEDICT = "freecdict" // plan spelling, mirrored by the entity docs
    const val SOURCE_MT = "mt"
    const val SOURCE_MANUAL = "manual"

    /** `meaning_cache.status` values (negative caching). */
    const val CACHE_OK = "OK"
    const val CACHE_FAILED = "FAILED"
}
