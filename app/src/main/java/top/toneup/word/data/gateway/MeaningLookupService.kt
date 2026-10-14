package top.toneup.word.data.gateway

import retrofit2.HttpException
import top.toneup.word.core.result.AppError
import top.toneup.word.core.result.Outcome
import top.toneup.word.core.time.TimeProvider
import top.toneup.word.data.local.dao.MeaningCacheDao
import top.toneup.word.data.local.dao.WordDao
import top.toneup.word.data.local.entity.MeaningCacheEntity
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder

/**
 * The ECDICT → FreeDictionary → LLM cascade (plan §7.2).
 *
 * Rules implemented here:
 *  * **First hit wins.** ECDICT hit → one request total; FreeDictionary hit → two; the LLM only
 *    ever runs when both previous sources came back empty ("仅在前两者都空时才走").
 *  * **Chinese-first standard meaning.** ECDICT `translation` and the LLM answer are Chinese;
 *    FreeDictionary only contributes auxiliary fields (en definition / example / synonyms /
 *    audio) — plan Q2.
 *  * **Cache before network.** A fresh `OK` row is served from `meaning_cache`, a fresh
 *    `FAILED` row is a negative cache (no gateway hit within 24h) unless the caller forces a
 *    re-lookup. One gateway hit per token per day at most (D3).
 *  * **Errors are values.** 401/failed config → [AppError.config]; timeout/5xx/IO →
 *    [AppError.network] (`retryable = true`) which *aborts* the cascade and writes **no**
 *    negative cache — a flaky network must not poison the cache for 24h.
 *  * A completed cascade with no answer at all writes the negative cache and returns
 *    [AppError.notFound].
 */
class MeaningLookupService(
    private val clients: GatewayClientProvider,
    private val cacheDao: MeaningCacheDao,
    private val wordDao: WordDao,
    private val time: TimeProvider,
    private val json: Json,
) {

    /** Looks a word row up by id and runs the cascade on its normalized token. */
    suspend fun lookup(wordId: Long): Outcome<LookupResult> {
        val word = wordDao.byId(wordId)
            ?: return Outcome.Failure(AppError.database("单词不存在（id=$wordId）"))
        return lookupToken(word.normalizedWord)
    }

    /**
     * Cascade lookup for one normalized token.
     *
     * @param ignoreNegativeCache force a gateway hit even when the last attempt was recorded as
     *        a miss — used by the manual "重试" entry points only (plan §7.2).
     */
    suspend fun lookupToken(
        normalized: String,
        ignoreNegativeCache: Boolean = false,
    ): Outcome<LookupResult> {
        val token = normalized.trim()
        if (token.isEmpty()) return Outcome.Failure(AppError.database("查询词为空"))

        val now = time.nowMillis()
        // --- 0. cache (positive and negative) -------------------------------------------
        cacheDao.byNormalized(token)?.let { cached ->
            val fresh = now - cached.fetchedAt < GatewayDefaults.CACHE_TTL_MILLIS
            if (fresh) {
                when {
                    cached.status == MeaningStates.CACHE_OK ->
                        LookupPayloadCodec.decode(cached.payload, json)?.let { return Outcome.Success(it) }

                    cached.status == MeaningStates.CACHE_FAILED && !ignoreNegativeCache ->
                        return Outcome.Failure(AppError.notFound("负缓存：$token"))
                }
            }
        }

        val session = when (val opened = clients.session()) {
            is Outcome.Success -> opened.value
            is Outcome.Failure -> return opened
        }

        // --- 1-3. cascade -----------------------------------------------------------------
        val stages: List<suspend () -> StageOutcome> = listOf(
            { dictStage(session, token) },
            { freeDictStage(session, token) },
            { llmStage(session, token) },
        )
        for (stage in stages) {
            when (val outcome = stage()) {
                is StageOutcome.Hit -> {
                    cacheDao.upsert(
                        MeaningCacheEntity(
                            normalizedWord = token,
                            payload = LookupPayloadCodec.encode(outcome.result, json),
                            source = outcome.result.source.id,
                            status = MeaningStates.CACHE_OK,
                            fetchedAt = now,
                        ),
                    )
                    return Outcome.Success(outcome.result)
                }
                is StageOutcome.Miss -> continue
                is StageOutcome.Fail -> return Outcome.Failure(outcome.error)
            }
        }

        // --- 4. all sources answered "no such word" → negative cache ---------------------
        cacheDao.upsert(
            MeaningCacheEntity(
                normalizedWord = token,
                payload = "",
                source = "",
                status = MeaningStates.CACHE_FAILED,
                fetchedAt = now,
            ),
        )
        return Outcome.Failure(AppError.notFound("网关未收录：$token"))
    }

    // --- cascade stages ----------------------------------------------------------------------

    /** §1 ECDICT: `found=true` with an entry is a hit; everything else falls through. */
    private suspend fun dictStage(session: GatewaySession, token: String): StageOutcome {
        val url = absoluteUrl(session.config, session.config.dictPath)
        return when (val call = callGateway { session.api.dict(url, token) }) {
            is CallOutcome.Miss404 -> StageOutcome.Miss
            is CallOutcome.Error -> StageOutcome.Fail(call.error)
            is CallOutcome.Ok -> {
                val entry = call.value.entry
                if (!call.value.found || entry == null) {
                    StageOutcome.Miss
                } else {
                    StageOutcome.Hit(
                        LookupResult(
                            phonetic = entry.phonetic.trim(),
                            partOfSpeech = entry.pos.trim(),
                            meanings = splitLines(entry.translation),
                            enDefinition = squash(entry.definition),
                            source = LookupSource.ECDICT,
                        ),
                    )
                }
            }
        }
    }

    /** §2 FreeDictionary (and its `ecdict-fallback` shape): aux fields only (plan Q2). */
    private suspend fun freeDictStage(session: GatewaySession, token: String): StageOutcome {
        val path = session.config.enPath.replace("{word}", encodePathSegment(token))
        return when (val call = callGateway { session.api.en(absoluteUrl(session.config, path)) }) {
            is CallOutcome.Miss404 -> StageOutcome.Miss
            is CallOutcome.Error -> StageOutcome.Fail(call.error)
            is CallOutcome.Ok -> {
                val entry = call.value.entries.firstOrNull()
                if (entry == null) StageOutcome.Miss
                else StageOutcome.Hit(freeDictResult(entry))
            }
        }
    }

    /** §3 LLM fallback: generates the Chinese standard meaning when both dictionaries are empty. */
    private suspend fun llmStage(session: GatewaySession, token: String): StageOutcome {
        val request = ChatRequest(
            messages = listOf(ChatMessage(role = "user", content = GatewayDefaults.mtPrompt(token))),
        )
        val url = absoluteUrl(session.config, session.config.mtPath)
        return when (val call = callGateway { session.api.chat(url, request) }) {
            is CallOutcome.Miss404 -> StageOutcome.Miss
            is CallOutcome.Error -> StageOutcome.Fail(call.error)
            is CallOutcome.Ok -> {
                val content = call.value.choices.firstOrNull()?.message?.content.orEmpty().trim()
                if (content.isEmpty()) {
                    StageOutcome.Miss
                } else {
                    val (partOfSpeech, meanings) = parseMtContent(content)
                    StageOutcome.Hit(
                        LookupResult(
                            partOfSpeech = partOfSpeech,
                            meanings = meanings,
                            source = LookupSource.MT,
                        ),
                    )
                }
            }
        }
    }

    /** Extracts the auxiliary fields of one dictionaryapi / ecdict-fallback entry. */
    private fun freeDictResult(entry: EnEntry): LookupResult {
        val phonetics = entry.phonetics
        // dictionaryapi nests `definitions[]`; the fallback shape stores one bare `definition`
        // per meaning — both end up in the same list, structured entries first.
        val definitions = entry.meanings.flatMap { it.definitions } +
            entry.meanings.filter { it.definition.isNotBlank() }
                .map { EnDefinition(definition = it.definition) }
        val phonetic = phonetics.map { it.text.trim() }.firstOrNull { it.isNotEmpty() }
            ?: entry.phonetic.trim()
        val audioUrl = phonetics.map { it.audio.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val enDefinition = definitions.map { it.definition.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val example = definitions.map { it.example.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val partOfSpeech = entry.meanings.map { it.partOfSpeech.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val synonyms = (entry.meanings.flatMap { it.synonyms } + definitions.flatMap { it.synonyms })
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return LookupResult(
            phonetic = phonetic,
            partOfSpeech = partOfSpeech,
            // Deliberately empty: FreeDictionary has no Chinese standard meaning (Q2). The word
            // row keeps whatever standard meaning it already had and gets the English side here.
            meanings = emptyList(),
            enDefinition = squash(enDefinition),
            example = example,
            synonyms = synonyms,
            audioUrl = audioUrl,
            source = LookupSource.FREEDICT,
        )
    }

    // --- plumbing ----------------------------------------------------------------------------

    private fun absoluteUrl(config: GatewayConfig, path: String): String =
        config.baseUrl.trim().trimEnd('/') + "/" + path.trimStart('/')

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun splitLines(value: String): List<String> =
        value.lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun squash(value: String): String = value.trim().replace(WHITESPACE, " ")

    /** Runs one HTTP call and turns every failure into a value (no exceptions escape). */
    private suspend fun <T> callGateway(block: suspend () -> T): CallOutcome<T> = try {
        CallOutcome.Ok(block())
    } catch (t: Throwable) {
        when {
            // 404 = "no such word / no such path" → a stage miss, not an error (plan §7.1).
            t is HttpException && t.code() == 404 -> CallOutcome.Miss404
            t is HttpException && t.code() == 401 -> CallOutcome.Error(AppError.config("HTTP 401"))
            t is HttpException -> CallOutcome.Error(AppError.network("HTTP ${t.code()}"))
            t is SocketTimeoutException -> CallOutcome.Error(AppError.network("读取超时"))
            t is IOException -> CallOutcome.Error(AppError.network(t.message))
            t is SerializationException -> CallOutcome.Error(AppError.network("响应无法解析"))
            else -> CallOutcome.Error(AppError.unknown(t.message ?: t::class.java.simpleName))
        }
    }

    private sealed interface CallOutcome<out T> {
        data class Ok<T>(val value: T) : CallOutcome<T>
        data object Miss404 : CallOutcome<Nothing>
        data class Error(val error: AppError) : CallOutcome<Nothing>
    }

    private sealed interface StageOutcome {
        data class Hit(val result: LookupResult) : StageOutcome
        data object Miss : StageOutcome
        data class Fail(val error: AppError) : StageOutcome
    }

    private companion object {
        val WHITESPACE = Regex("""\s+""")
    }
}
