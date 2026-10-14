package top.toneup.word.data.repository

import android.content.Context
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import top.toneup.word.core.json.JsonCodec
import top.toneup.word.core.result.AppError
import top.toneup.word.core.result.ErrorCode
import top.toneup.word.core.result.Outcome
import top.toneup.word.core.time.TimeProvider
import top.toneup.word.data.gateway.GatewayDefaults
import top.toneup.word.data.gateway.LookupPayloadCodec
import top.toneup.word.data.gateway.LookupResult
import top.toneup.word.data.gateway.MeaningLookupService
import top.toneup.word.data.gateway.MeaningStates
import top.toneup.word.data.local.ToneUpWordDatabase
import top.toneup.word.data.local.entity.MeaningCacheEntity
import top.toneup.word.data.local.entity.WordEntity
import top.toneup.word.data.settings.SettingsRepository
import top.toneup.word.work.MeaningSyncWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/** Result of one meaning-sync run. */
data class BatchReport(
    /** Words attempted in this run (a batch stops early only on [abortedByConfig]). */
    val processed: Int,
    /** True when the gateway rejected the configuration (HTTP 401) — stop the whole queue. */
    val abortedByConfig: Boolean,
)

/**
 * Orchestration surface for gateway meaning completion (plan §7).
 *
 * Public API consumed by the Worker, the settings screen ("重试全部"), the word detail page
 * ("重试本词") and the import flow — signatures are stable for M4-UI/M5.
 *
 * Transactions live here (data/AGENTS.md: Repository is the only transaction boundary);
 * [MeaningLookupService] owns `meaning_cache` reads/writes for the lookup itself, and
 * [applyLookupToWord] re-asserts the cache row in the same transaction that touches `word`
 * so a crash can never leave the two out of sync.
 */
interface MeaningSyncRepository {

    /** Live count of words still waiting for the gateway (PENDING + retryable FAILED). */
    fun observePendingCount(): Flow<Int>

    /** Enqueues the unique `meaning-sync` run (plan D5). Safe to call repeatedly. */
    suspend fun enqueueAll(): Outcome<Unit>

    /**
     * Immediate lookup + write-back for one word (word-detail "重试").
     * Bypasses the *negative* cache so a manual retry always reaches the gateway.
     */
    suspend fun lookupWordNow(wordId: Long): Outcome<LookupResult>

    /** One batch of the cascade with throttling; returns how many words were attempted. */
    suspend fun processBatch(batchLimit: Int = 50): Int = runBatch(batchLimit).processed

    /** Same as [processBatch] but reports whether the run was aborted by a config error. */
    suspend fun runBatch(batchLimit: Int = 50): BatchReport

    /**
     * Transactionally writes a lookup result onto a word row plus its shared cache entry.
     *
     * Write guards (plan §7.2): `meaning_source = 'manual'` is never touched, and a row that is
     * neither `PENDING`/`FAILED` nor missing a source (i.e. complete from a file or built-in
     * pack) is left alone too.
     */
    suspend fun applyLookupToWord(wordId: Long, result: LookupResult, now: Long)
}

class DefaultMeaningSyncRepository(
    private val context: Context,
    private val db: ToneUpWordDatabase,
    private val time: TimeProvider,
    private val lookupService: MeaningLookupService,
    private val settingsRepository: SettingsRepository,
    private val json: Json = JsonCodec.json,
) : MeaningSyncRepository {

    override fun observePendingCount(): Flow<Int> =
        db.wordDao().observeNeedingLookupCount(GatewayDefaults.MAX_LOOKUP_ATTEMPTS)

    override suspend fun enqueueAll(): Outcome<Unit> = try {
        val settings = settingsRepository.current()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (settings.syncOnlyUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
            .build()
        val request = OneTimeWorkRequestBuilder<MeaningSyncWorker>()
            .setConstraints(constraints)
            // Backoff so a failed run does not hammer the gateway; APPEND_OR_REPLACE keeps a
            // single logical queue (plan D5).
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(GatewayDefaults.WORK_TAG)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(GatewayDefaults.WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        Outcome.Success(Unit)
    } catch (t: Throwable) {
        Outcome.Failure(AppError.unknown(t.message ?: t::class.java.simpleName))
    }

    override suspend fun lookupWordNow(wordId: Long): Outcome<LookupResult> {
        val word = db.wordDao().byId(wordId)
            ?: return Outcome.Failure(AppError.database("单词不存在（id=$wordId）"))
        val outcome = lookupService.lookupToken(word.normalizedWord, ignoreNegativeCache = true)
        val result = outcome.getOrNull()
            ?: return outcome
        applyLookupToWord(wordId, result, time.nowMillis())
        return Outcome.Success(result)
    }

    override suspend fun runBatch(batchLimit: Int): BatchReport {
        val ids = db.wordDao().idsNeedingLookup(batchLimit, GatewayDefaults.MAX_LOOKUP_ATTEMPTS)
        if (ids.isEmpty()) return BatchReport(processed = 0, abortedByConfig = false)
        // byIds() does not promise ordering; a stable id order keeps batches reproducible.
        val words = db.wordDao().byIds(ids).sortedBy { it.id }

        var processed = 0
        for ((index, word) in words.withIndex()) {
            if (index > 0) delay(GatewayDefaults.THROTTLE_MILLIS) // 250ms between words (D5)
            processed++
            when (val outcome = lookupService.lookupToken(word.normalizedWord)) {
                is Outcome.Success -> applyLookupToWord(word.id, outcome.value, time.nowMillis())
                is Outcome.Failure -> when (outcome.error.code) {
                    // Wrong key / http URL: abort the entire batch immediately (plan D5).
                    ErrorCode.CONFIG -> return BatchReport(processed, abortedByConfig = true)
                    else -> recordFailure(word, outcome.error)
                }
            }
        }
        return BatchReport(processed, abortedByConfig = false)
    }

    override suspend fun applyLookupToWord(wordId: Long, result: LookupResult, now: Long) {
        db.withTransaction {
            val word = db.wordDao().byId(wordId) ?: return@withTransaction
            // D4: hand-edited words are never overwritten by automation.
            if (word.meaningSource == MeaningStates.SOURCE_MANUAL) return@withTransaction
            val eligible = word.lookupState == MeaningStates.PENDING ||
                word.lookupState == MeaningStates.FAILED ||
                word.meaningSource.isEmpty()
            if (!eligible) return@withTransaction

            db.wordDao().update(word.mergedWith(result, now))
            db.meaningCacheDao().upsert(
                MeaningCacheEntity(
                    normalizedWord = word.normalizedWord,
                    payload = LookupPayloadCodec.encode(result, json),
                    source = result.source.id,
                    status = MeaningStates.CACHE_OK,
                    fetchedAt = now,
                ),
            )
        }
    }

    /**
     * Field merge onto an existing row.
     *
     * The state machine always wins (`OK`, source, attempts, timestamp); content fields only
     * win when the lookup actually produced something — a FreeDictionary hit has no Chinese
     * meanings and an LLM hit has no phonetic, and neither may blank out data the row already
     * carries from its import.
     */
    private fun WordEntity.mergedWith(result: LookupResult, now: Long): WordEntity = copy(
        phonetic = result.phonetic.ifEmpty { phonetic },
        partOfSpeech = result.partOfSpeech.ifEmpty { partOfSpeech },
        meanings = if (result.meanings.isNotEmpty()) JsonCodec.encodeStrings(result.meanings) else meanings,
        enDefinition = result.enDefinition.ifEmpty { enDefinition },
        exampleSentence = result.example.ifEmpty { exampleSentence },
        audioUrl = result.audioUrl.ifEmpty { audioUrl },
        meaningSource = result.source.id,
        lookupState = MeaningStates.OK,
        // A success clears the attempt budget: only repeated failures are bounded by 3.
        lookupAttempts = 0,
        lastLookupAt = now,
    )

    private suspend fun recordFailure(word: WordEntity, error: AppError) {
        // Transport error → stay PENDING (retry on the next run); exhausted cascade → FAILED.
        val state = if (error.retryable) MeaningStates.PENDING else MeaningStates.FAILED
        db.wordDao().recordLookupFailure(word.id, state, time.nowMillis())
    }

    private companion object {
        const val BACKOFF_SECONDS = 30L
    }
}
