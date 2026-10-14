package top.toneup.word.data.repository

import androidx.room.withTransaction
import top.toneup.word.core.json.JsonCodec
import top.toneup.word.core.result.AppError
import top.toneup.word.core.result.ErrorCode
import top.toneup.word.core.result.Outcome
import top.toneup.word.core.time.TimeProvider
import top.toneup.word.data.local.ToneUpWordDatabase
import top.toneup.word.data.local.dao.WordLiteRow
import top.toneup.word.data.local.entity.ImportHistoryEntity
import top.toneup.word.data.local.entity.WordEntity
import top.toneup.word.data.local.entity.WordPackEntity
import top.toneup.word.data.mapper.toDomain
import top.toneup.word.data.mapper.toEntity
import top.toneup.word.data.mapper.toWordWithProgress
import top.toneup.word.data.seed.BuiltInPackDefinition
import top.toneup.word.data.seed.BuiltInWordPacks
import top.toneup.word.data.seed.PackSource
import top.toneup.word.domain.exam.ExamWordSource
import top.toneup.word.domain.importer.ImportPreview
import top.toneup.word.domain.importer.ImportResult
import top.toneup.word.domain.importer.PackFormat
import top.toneup.word.domain.model.CetLevel
import top.toneup.word.domain.model.Word
import top.toneup.word.domain.model.WordWithProgress
import top.toneup.word.domain.search.ApproximateWordMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.random.Random

/**
 * Vocabulary content: the built-in demo packs plus everything the user imported.
 *
 * The repository is the only place that knows how a word list is stored, and it is designed
 * around one rule: **importing a list must never destroy study history**. Word rows therefore
 * keep their primary key when their content is updated, and progress rows are never touched
 * by an import.
 */
interface WordRepository {

    /** Seeds the built-in demo packs once; a no-op afterwards. */
    suspend fun ensureBuiltInPacks()

    fun observeWordCount(packId: String): Flow<Int>

    fun observePacks(): Flow<List<WordPackEntity>>

    /** Single pack lookup (import screen title, repository-side guards). */
    suspend fun packById(packId: String): WordPackEntity?

    fun observeImportHistory(limit: Int = 20): Flow<List<ImportHistoryEntity>>

    suspend fun hasWords(packId: String): Boolean

    suspend fun wordDetail(wordId: Long): WordWithProgress?

    /** Snapshot of the words used to build an exam paper. */
    suspend fun examSources(packId: String): List<ExamWordSource>

    /** All words of a pack, for export. */
    suspend fun allWords(packId: String): List<Word>

    /**
     * Writes a validated preview.
     *
     * @param packId stable id of the pack (derived from the file name), so importing the same
     *   file twice updates rather than duplicates. Word identity inside the transaction is
     *   `(pack_id, normalized_word)` (D2) — the same word in another pack is a different row.
     */
    suspend fun importPack(preview: ImportPreview, packId: String): Outcome<ImportResult>

    /** Removes the words of an imported pack (built-in packs are refused). */
    suspend fun deleteImportedPack(packId: String): Outcome<Unit>

    // --- Pack management (M2) + plain-text import (M3) --------------------------------------

    /**
     * Creates an empty user pack.
     *
     * The id is `"user-<now>-<random>"`: time alone would collide on a double tap, and a
     * full UUID would be unreadable in backups — six random hex digits on top of the timestamp
     * is enough for a single-device app.
     *
     * @param name learner-supplied display name; trimmed and required to be non-blank.
     * @return the new pack id, so the caller can navigate straight into the import screen.
     */
    suspend fun createPack(name: String, now: Long): Outcome<String>

    /** Renames a user pack (built-in packs keep their shipped name and are refused). */
    suspend fun renamePack(packId: String, name: String): Outcome<Unit>

    /**
     * Deletes a pack and every word in it; progress, review events, mistakes and exam
     * snapshots follow the foreign keys (CASCADE). Built-in packs are refused — they may be
     * cleared of progress but not removed.
     */
    suspend fun deletePack(packId: String): Outcome<Unit>

    /**
     * Adds bare words to an existing pack in one transaction (M3 paste import).
     *
     * `(pack_id, normalized_word)` already present → skipped (never [androidx.room.OnConflictStrategy.REPLACE]:
     * that would delete the word row and CASCADE away the learner's progress). New rows enter
     * with empty content and `lookup_state='PENDING'` for the gateway to fill later (D4) —
     * importing never requires the network. The pack's `word_count` is refreshed in the same
     * transaction.
     *
     * @param words validated, de-duplicated word tokens (from `PlainTextWordParser`); the
     *   repository still normalises defensively because it is the last line before SQL.
     */
    suspend fun addWordsToPack(packId: String, words: List<String>, now: Long): Outcome<PackAddResult>

    /**
     * Vocabulary lookup for the word browser.
     *
     * Matches the English word and the Chinese meanings; prefix matches on the word sort first so
     * typing "ab" surfaces `abandon` before a word that merely contains "ab" in a meaning.
     */
    suspend fun search(packId: String, query: String, limit: Int = SEARCH_LIMIT): List<WordWithProgress>

    /**
     * Close spellings of [query] — the "你是不是想找" list.
     *
     * Only consulted when the exact search came up short (see [APPROX_STRICT_THRESHOLD]): a learner
     * who already found what they wanted must not be handed a list of unrelated words. Returns an
     * empty list for a query that is not a plain English word, for a pack whose length band holds
     * nothing, and for anything the edit distance rejects — never a guess about a word that does
     * not exist in the local vocabulary.
     */
    suspend fun searchApproximate(
        packId: String,
        query: String,
        strictMatchCount: Int,
        limit: Int = APPROX_LIMIT,
    ): List<WordWithProgress>

    companion object {
        /** Bounded result size: the browser pages through the list instead of loading 10k rows. */
        const val SEARCH_LIMIT = 200

        /** At most this many "did you mean" suggestions. */
        const val APPROX_LIMIT = 12

        /**
         * The exact search is considered "good enough" from this many hits upwards.
         *
         * Above it the learner already has something to choose from, and a correction list would
         * only add noise.
         */
        const val APPROX_STRICT_THRESHOLD = 8

        /**
         * Hard cap on the rows the edit-distance pass may see.
         *
         * The length band already keeps this small; the cap is what guarantees that a pathological
         * query cannot turn into a full-table scan in Kotlin.
         */
        const val APPROX_CANDIDATE_LIMIT = 4_000
    }
}

/** Progress of the built-in pack seeding, surfaced on the cold-start screen. */
data class SeedProgress(
    val packName: String,
    val processed: Int,
    val total: Int,
) {
    val fraction: Float get() = if (total <= 0) 0f else (processed.toFloat() / total).coerceIn(0f, 1f)
}

/** Result of one plain-text import: what landed, what was already there, what was asked for. */
data class PackAddResult(
    /** Words that became new rows. */
    val inserted: Int,
    /** Words already present in this pack (`(pack_id, normalized_word)` identity, D2). */
    val skipped: Int,
    /** Words the caller submitted. */
    val total: Int,
)

class DefaultWordRepository(
    private val db: ToneUpWordDatabase,
    private val time: TimeProvider,
    private val packLoader: PackSource,
    /** Reports seeding progress so the cold-start screen can show real numbers. */
    private val onSeedProgress: (SeedProgress) -> Unit = {},
) : WordRepository {

    /**
     * Seeds or upgrades the built-in vocabulary.
     *
     * Why this is not a simple "insert if empty" any more:
     *  * a shipped pack is versioned, and a **newer asset must reach devices that already installed
     *    the app**. The previous implementation only inserted when the pack was empty, so an
     *    existing installation could never receive a bigger word list;
     *  * the word list is now thousands of entries, so the work is chunked (with a `yield()`
     *    between chunks so the loading screen keeps drawing) instead of one long transaction;
     *  * re-seeding must never destroy learning data. Existing rows are matched on
     *    `(pack_id, normalized_word)` and **updated in place**, which keeps the row id every
     *    progress/mistake/event row points at. Nothing is ever deleted here.
     */
    override suspend fun ensureBuiltInPacks() {
        val definitions = runCatching { packLoader.definitions() }.getOrDefault(emptyList())
        if (definitions.isEmpty()) {
            seedInlineDemoPacks()
            return
        }

        definitions.forEach { definition ->
            val existingPack = db.wordDao().packById(definition.id)
            val storedCount = db.wordDao().countInPack(definition.id)
            val upToDate = existingPack != null &&
                existingPack.version == definition.version.toString() &&
                (definition.expectedWordCount <= 0 || storedCount >= definition.expectedWordCount)
            if (upToDate) return@forEach

            when (val loaded = runCatching { packLoader.load(definition) }.getOrNull()) {
                is Outcome.Success -> seedPack(definition, loaded.value)
                // The asset is missing or invalid: keep the app usable with the in-code demo set
                // for this level rather than leaving the learner with an empty list.
                else -> seedInlineDemoPacks()
            }
        }
    }

    /** Writes one validated pack: insert new words, update existing ones in place. */
    private suspend fun seedPack(definition: BuiltInPackDefinition, preview: ImportPreview) {
        val level = definition.level
        val records = preview.records
        val now = time.nowMillis()

        // One identity lookup for the whole pack instead of a query per word.
        val existingIds: Map<String, Long> = db.wordDao().idByNormalized(definition.id)
            .associate { it.normalizedWord to it.id }

        var processed = 0
        var inserted = 0
        var updated = 0
        onSeedProgress(SeedProgress(definition.name, 0, records.size))

        records.chunked(SEED_CHUNK_SIZE).forEach { chunk ->
            val toInsert = ArrayList<WordEntity>(chunk.size)
            val toUpdate = ArrayList<WordEntity>(chunk.size)
            chunk.forEach { record ->
                val normalized = Word.normalizeWordKey(record.word)
                val existingId = existingIds[normalized]
                val entity = record.toEntity(definition.id, now)
                if (existingId == null) {
                    toInsert += entity
                } else {
                    toUpdate += entity.copy(id = existingId, createdAt = now)
                }
            }

            db.withTransaction {
                if (toInsert.isNotEmpty()) {
                    db.wordDao().insertAll(toInsert)
                    inserted += toInsert.size
                }
                if (toUpdate.isNotEmpty()) {
                    db.wordDao().updateAll(toUpdate)
                    updated += toUpdate.size
                }
            }

            processed += chunk.size
            onSeedProgress(SeedProgress(definition.name, processed, records.size))
            // Let the loading screen draw between chunks: seeding thousands of rows must not look
            // like a frozen frame.
            yield()
        }

        val existingPack = db.wordDao().packById(definition.id)
        db.wordDao().upsertPack(
            WordPackEntity(
                id = definition.id,
                name = definition.name,
                level = level.id,
                description = "${records.size} 条词汇（$inserted 新增 / $updated 更新）",
                source = records.firstOrNull()?.source.orEmpty().ifBlank { BuiltInWordPacks.SOURCE },
                license = records.firstOrNull()?.license.orEmpty().ifBlank { BuiltInWordPacks.LICENSE },
                version = definition.version.toString(),
                isBuiltIn = true,
                wordCount = db.wordDao().countInPack(definition.id),
                createdAt = existingPack?.createdAt ?: now,
                updatedAt = now,
            ),
        )
    }

    /**
     * Fallback path: the tiny in-code demo packs.
     *
     * Kept so a build with no bundled asset (or a corrupt one) still starts with a usable word
     * list, and so the demo data that the UI tests rely on keeps working.
     */
    private suspend fun seedInlineDemoPacks() {
        val now = time.nowMillis()
        BuiltInWordPacks.all.forEach { (packId, words) ->
            val level = if (packId.contains("cet6")) CetLevel.CET6 else CetLevel.CET4
            val existingPack = db.wordDao().packById(packId)
            val existingIds: Map<String, Long> = db.wordDao().idByNormalized(packId)
                .associate { it.normalizedWord to it.id }
            val missing = words.filter { Word.normalizeWordKey(it.word) !in existingIds }
            if (missing.isEmpty() && existingPack != null) return@forEach

            db.withTransaction {
                if (missing.isNotEmpty()) {
                    db.wordDao().insertAll(
                        missing.map { seed ->
                            WordEntity(
                                packId = packId,
                                externalId = null,
                                word = seed.word,
                                normalizedWord = Word.normalizeWordKey(seed.word),
                                phonetic = seed.phonetic,
                                partOfSpeech = seed.partOfSpeech,
                                meanings = JsonCodec.encodeStrings(seed.meanings),
                                exampleSentence = seed.exampleSentence,
                                exampleTranslation = seed.exampleTranslation,
                                collocations = JsonCodec.encodeStrings(seed.collocations),
                                level = level.id,
                                source = BuiltInWordPacks.SOURCE,
                                license = BuiltInWordPacks.LICENSE,
                                audioUri = null,
                                createdAt = now,
                                updatedAt = now,
                            )
                        },
                    )
                }
                db.wordDao().upsertPack(
                    WordPackEntity(
                        id = packId,
                        name = if (level == CetLevel.CET6) "六级演示词库" else "四级演示词库",
                        level = level.id,
                        description = "${words.size} 个自建演示词条",
                        source = BuiltInWordPacks.SOURCE,
                        license = BuiltInWordPacks.LICENSE,
                        version = BuiltInWordPacks.VERSION.toString(),
                        isBuiltIn = true,
                        wordCount = db.wordDao().countInPack(packId),
                        createdAt = existingPack?.createdAt ?: now,
                        updatedAt = now,
                    ),
                )
            }
        }
    }

    override suspend fun search(
        packId: String,
        query: String,
        limit: Int,
    ): List<WordWithProgress> {
        val trimmed = query.trim()
        val like = likePattern(trimmed)
        val prefix = prefixPattern(trimmed)
        return db.wordDao().search(packId, trimmed, like, prefix, limit).map { it.toWordWithProgress() }
    }

    override suspend fun searchApproximate(
        packId: String,
        query: String,
        strictMatchCount: Int,
        limit: Int,
    ): List<WordWithProgress> {
        if (strictMatchCount >= WordRepository.APPROX_STRICT_THRESHOLD) return emptyList()
        if (!ApproximateWordMatcher.isApplicable(query)) return emptyList()

        val needle = Word.normalizeWordKey(query)
        if (needle.isEmpty()) return emptyList()
        val maxDistance = ApproximateWordMatcher.maxDistanceFor(needle.length)

        val rows = db.wordDao().byWordLengthBand(
            packId = packId,
            minLength = (needle.length - maxDistance).coerceAtLeast(1),
            maxLength = needle.length + maxDistance,
            limit = WordRepository.APPROX_CANDIDATE_LIMIT,
        )
        if (rows.isEmpty()) return emptyList()

        val byNormalized = rows.associateBy { Word.normalizeWordKey(it.word) }
        val ranked = withContext(Dispatchers.Default) {
            ApproximateWordMatcher.rank(
                query = needle,
                candidates = byNormalized.keys.toList(),
                limit = limit,
                maxDistance = maxDistance,
            )
        }
        return ranked.mapNotNull { match -> byNormalized[match.word]?.toWordWithProgress() }
    }

    /**
     * SQL `LIKE` pattern with `!` as the escape character.
     *
     * A learner typing `%` or `_` must search for those characters literally; without escaping,
     * `%` would match the whole word list.
     */
    private fun likePattern(term: String): String {
        if (term.isEmpty()) return "%"
        val escaped = term.replace("!", "!!").replace("%", "!%").replace("_", "!_")
        return "%$escaped%"
    }

    /** Prefix pattern used to rank word-prefix matches above meaning matches. */
    private fun prefixPattern(term: String): String {
        if (term.isEmpty()) return ""
        val escaped = term.replace("!", "!!").replace("%", "!%").replace("_", "!_")
        return "$escaped%"
    }

    override fun observeWordCount(packId: String): Flow<Int> = db.wordDao().observeCount(packId)

    override fun observePacks(): Flow<List<WordPackEntity>> = db.wordDao().observePacks()

    override suspend fun packById(packId: String): WordPackEntity? = db.wordDao().packById(packId)

    override fun observeImportHistory(limit: Int): Flow<List<ImportHistoryEntity>> =
        db.importHistoryDao().observeRecent(limit)

    override suspend fun hasWords(packId: String): Boolean = db.wordDao().hasWords(packId)

    override suspend fun wordDetail(wordId: Long): WordWithProgress? =
        db.progressDao().wordWithProgress(wordId)?.toWordWithProgress()

    override suspend fun examSources(packId: String): List<ExamWordSource> =
        db.wordDao().liteForPack(packId).map { it.toExamSource() }

    override suspend fun allWords(packId: String): List<Word> =
        db.wordDao().allForPack(packId).map { it.toDomain() }

    override suspend fun importPack(preview: ImportPreview, packId: String): Outcome<ImportResult> {
        val now = time.nowMillis()
        return try {
            val result = db.withTransaction {
                var inserted = 0
                var updated = 0
                preview.records.forEach { record ->
                    val normalized = Word.normalizeWordKey(record.word)
                    // D2: identity is (pack_id, normalized_word) — the same word in another
                    // pack is a different row with its own progress.
                    val existing = db.wordDao().byNormalized(packId, normalized)
                    if (existing == null) {
                        db.wordDao().insert(record.toEntity(packId, now))
                        inserted++
                    } else {
                        // Content is refreshed, the row id (and with it every progress row,
                        // review event and exam snapshot) is preserved.
                        db.wordDao().update(
                            existing.copy(
                                packId = packId,
                                externalId = record.externalId ?: existing.externalId,
                                word = record.word,
                                normalizedWord = normalized,
                                phonetic = record.phonetic,
                                partOfSpeech = record.partOfSpeech,
                                meanings = JsonCodec.encodeStrings(record.meanings),
                                exampleSentence = record.exampleSentence,
                                exampleTranslation = record.exampleTranslation,
                                collocations = JsonCodec.encodeStrings(record.collocations),
                                source = record.source,
                                license = record.license,
                                audioUri = record.audioUri,
                                updatedAt = now,
                            ),
                        )
                        updated++
                    }
                }

                val level = preview.levelCounts.maxByOrNull { it.value }?.key ?: CetLevel.CET4
                val existingPack = db.wordDao().packById(packId)
                db.wordDao().upsertPack(
                    WordPackEntity(
                        id = packId,
                        name = preview.packName,
                        level = level.id,
                        description = "导入自 ${preview.fileName}",
                        source = preview.records.firstOrNull()?.source.orEmpty(),
                        license = preview.records.firstOrNull()?.license.orEmpty(),
                        version = "1.0",
                        isBuiltIn = false,
                        wordCount = db.wordDao().countInPack(packId),
                        createdAt = existingPack?.createdAt ?: now,
                        updatedAt = now,
                    ),
                )

                ImportResult(
                    fileName = preview.fileName,
                    format = preview.format,
                    packId = packId,
                    inserted = inserted,
                    updated = updated,
                    skipped = preview.duplicateRows,
                    failed = preview.invalidCount,
                    totalRows = preview.totalRows,
                    level = level,
                )
            }

            db.importHistoryDao().insert(
                ImportHistoryEntity(
                    fileName = preview.fileName,
                    format = preview.format.id,
                    importedAt = now,
                    totalRows = result.totalRows,
                    inserted = result.inserted,
                    updated = result.updated,
                    skipped = result.skipped,
                    failed = result.failed,
                    level = result.level.id,
                    packId = packId,
                    status = "SUCCESS",
                    message = "",
                ),
            )
            Outcome.Success(result)
        } catch (throwable: Throwable) {
            // The transaction rolled back, so existing data is untouched. The failure is still
            // recorded, outside the failed transaction, so the user can see what happened.
            runCatching {
                db.importHistoryDao().insert(
                    ImportHistoryEntity(
                        fileName = preview.fileName,
                        format = preview.format.id,
                        importedAt = now,
                        totalRows = preview.totalRows,
                        inserted = 0,
                        updated = 0,
                        skipped = preview.duplicateRows,
                        failed = preview.invalidCount,
                        level = CetLevel.CET4.id,
                        packId = packId,
                        status = "ROLLED_BACK",
                        message = throwable.javaClass.simpleName,
                    ),
                )
            }
            Outcome.Failure(AppError.database(throwable.javaClass.simpleName))
        }
    }

    override suspend fun deleteImportedPack(packId: String): Outcome<Unit> = deletePack(packId)

    override suspend fun deletePack(packId: String): Outcome<Unit> = try {
        val pack = db.wordDao().packById(packId)
        when {
            pack == null -> Outcome.Failure(AppError(ErrorCode.DATABASE, "词库不存在或已被删除。"))
            pack.isBuiltIn -> Outcome.Failure(
                AppError(ErrorCode.DATABASE, "内置演示词库不能删除，只能清空学习进度。"),
            )

            else -> {
                db.withTransaction {
                    // Progress, review events and exam snapshots follow the foreign keys.
                    db.wordDao().deleteForPack(packId)
                    // Then the pack row itself — a deleted pack must disappear from the list.
                    db.wordDao().deletePackRow(packId)
                }
                Outcome.Success(Unit)
            }
        }
    } catch (throwable: Throwable) {
        Outcome.Failure(AppError.database(throwable.javaClass.simpleName))
    }

    override suspend fun createPack(name: String, now: Long): Outcome<String> = try {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return Outcome.Failure(AppError(ErrorCode.EMPTY, "词库名称不能为空。"))
        }
        if (trimmed.length > MAX_PACK_NAME_LENGTH) {
            return Outcome.Failure(
                AppError(ErrorCode.TOO_LARGE, "词库名称过长，请控制在 $MAX_PACK_NAME_LENGTH 个字符内。"),
            )
        }
        // Timestamp + short random suffix: a double tap must not collide, and the id stays
        // short enough to read in a backup file.
        val id = "user-$now-${Random.nextInt(0x1000000).toString(16).padStart(6, '0')}"
        db.wordDao().upsertPack(
            WordPackEntity(
                id = id,
                name = trimmed,
                level = CetLevel.OTHER.id,
                description = "",
                isBuiltIn = false,
                wordCount = 0,
                createdAt = now,
                updatedAt = now,
            ),
        )
        Outcome.Success(id)
    } catch (throwable: Throwable) {
        Outcome.Failure(AppError.database(throwable.javaClass.simpleName))
    }

    override suspend fun renamePack(packId: String, name: String): Outcome<Unit> = try {
        val trimmed = name.trim()
        when {
            trimmed.isEmpty() -> Outcome.Failure(AppError(ErrorCode.EMPTY, "词库名称不能为空。"))
            trimmed.length > MAX_PACK_NAME_LENGTH -> Outcome.Failure(
                AppError(ErrorCode.TOO_LARGE, "词库名称过长，请控制在 $MAX_PACK_NAME_LENGTH 个字符内。"),
            )

            else -> {
                val pack = db.wordDao().packById(packId)
                when {
                    pack == null -> Outcome.Failure(AppError(ErrorCode.DATABASE, "词库不存在或已被删除。"))
                    pack.isBuiltIn -> Outcome.Failure(
                        AppError(ErrorCode.DATABASE, "内置演示词库不能重命名。"),
                    )

                    else -> {
                        // `upsertPack` writes by primary key; the pack row carries no children
                        // (word rows do not reference it with a foreign key), so replacing it
                        // touches nothing else.
                        db.wordDao().upsertPack(
                            pack.copy(name = trimmed, updatedAt = time.nowMillis()),
                        )
                        Outcome.Success(Unit)
                    }
                }
            }
        }
    } catch (throwable: Throwable) {
        Outcome.Failure(AppError.database(throwable.javaClass.simpleName))
    }

    override suspend fun addWordsToPack(
        packId: String,
        words: List<String>,
        now: Long,
    ): Outcome<PackAddResult> = try {
        val pack = db.wordDao().packById(packId)
            ?: return Outcome.Failure(AppError(ErrorCode.DATABASE, "词库不存在或已被删除。"))
        if (words.isEmpty()) {
            return Outcome.Failure(AppError.empty())
        }
        db.withTransaction {
            // One identity lookup for the whole batch instead of a query per word.
            val existing: Set<String> = db.wordDao().idByNormalized(packId)
                .mapTo(HashSet()) { it.normalizedWord }
            val seenInBatch = HashSet<String>()
            val toInsert = ArrayList<WordEntity>(words.size)
            var skipped = 0
            words.forEach { raw ->
                val trimmed = raw.trim()
                val normalized = Word.normalizeWordKey(trimmed)
                // D2 identity: already in this pack, or already submitted in this very batch —
                // either way skip. Never REPLACE: that would delete the row and CASCADE away
                // progress, mistakes and exam snapshots.
                if (trimmed.isEmpty() || normalized in existing || !seenInBatch.add(normalized)) {
                    skipped++
                    return@forEach
                }
                toInsert += WordEntity(
                    packId = packId,
                    externalId = null,
                    word = trimmed,
                    normalizedWord = normalized,
                    phonetic = "",
                    partOfSpeech = "",
                    meanings = "[]",
                    level = pack.level,
                    source = "",
                    license = "",
                    audioUri = null,
                    createdAt = now,
                    updatedAt = now,
                    // D4: waiting for the gateway; the import itself never needs the network.
                    lookupState = "PENDING",
                    meaningSource = "",
                )
            }
            // IGNORE (not REPLACE): a race with a concurrent write keeps the existing row —
            // and its progress — instead of destroying it.
            val rowIds = if (toInsert.isEmpty()) emptyList() else db.wordDao().insertAll(toInsert)
            val inserted = rowIds.count { it >= 0 }
            db.wordDao().updatePackWordCount(packId, db.wordDao().countInPack(packId), now)
            Outcome.Success(PackAddResult(inserted = inserted, skipped = skipped, total = words.size))
        }
    } catch (throwable: Throwable) {
        Outcome.Failure(AppError.database(throwable.javaClass.simpleName))
    }

    private companion object {
        /**
         * Rows per seeding transaction. Large enough to keep the insert count low, small enough
         * that a `yield()` between chunks lets the loading screen draw and that a cancellation
         * loses at most one chunk.
         */
        const val SEED_CHUNK_SIZE = 400

        /** Generous for a pack name; long enough to describe, short enough to render. */
        const val MAX_PACK_NAME_LENGTH = 40
    }
}

internal fun WordLiteRow.toExamSource(): ExamWordSource = ExamWordSource(
    wordId = id,
    word = word,
    phonetic = phonetic,
    partOfSpeech = partOfSpeech,
    meanings = JsonCodec.decodeStrings(meanings),
)

/** Format label used by the import history UI. */
internal fun formatLabel(formatId: String): String =
    PackFormat.entries.firstOrNull { it.id == formatId }?.label ?: formatId
