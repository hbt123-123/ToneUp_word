package top.toneup.word.data.repository

import androidx.room.withTransaction
import top.toneup.word.core.json.JsonCodec
import top.toneup.word.core.result.AppError
import top.toneup.word.core.result.ErrorCode
import top.toneup.word.core.result.Outcome
import top.toneup.word.core.time.TimeProvider
import top.toneup.word.data.local.ToneUpWordDatabase
import top.toneup.word.data.local.entity.DailyStudySummaryEntity
import top.toneup.word.data.mapper.toDomain
import top.toneup.word.data.settings.PackIds
import top.toneup.word.domain.importer.CsvCodec
import top.toneup.word.domain.model.CetLevel
import top.toneup.word.domain.model.Word
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Which data a user wants to export. */
enum class ExportScope(val label: String, val description: String) {
    WORD_LIST("当前词库", "仅导出词条：单词、音标、释义、例句、搭配"),
    STUDY_RECORDS("学习记录", "导出掌握状态、复习计划、错题与每日统计"),
    FULL_BACKUP("完整备份", "词库 + 学习记录 + 错题 + 答题日志，可完整恢复"),
}

/** Counts reported after a restore. */
data class RestoreResult(
    val restoredWords: Int,
    val restoredProgress: Int,
    val restoredMistakes: Int,
    val restoredEvents: Int,
    val restoredDays: Int,
    val skippedWords: Int,
) {
    val summary: String
        get() = buildString {
            append("已恢复 $restoredProgress 条学习进度、$restoredMistakes 条错题、$restoredDays 天统计")
            if (skippedWords > 0) append("；$skippedWords 个单词在当前词库中不存在，已跳过")
        }
}

/**
 * Export and restore.
 *
 * Writing is always done through the Storage Access Framework by the caller: this class only
 * produces and consumes *text*, so it never needs a storage permission and never decides
 * where a file goes. Restoring replaces study data for the **packs** present in the backup,
 * which is stated in the confirmation dialog before it runs.
 *
 * Schema v2 (D1/D2): every export is scoped by pack id, and every DTO carries an optional
 * `packId` (default `""` so v1 backup files still parse). On restore an empty `packId` is
 * resolved with `defaultPackIdFor(level)` — the exact inverse of what migration 1→2 did to
 * live rows — and word identity is `(pack_id, normalized_word)` with a legacy
 * `(level, normalized_word)` fallback for rows written by a pre-v2 restore.
 */
interface BackupRepository {

    suspend fun export(scope: ExportScope, packId: String): Outcome<String>

    /** File name suggestion shown in the system save dialog. */
    fun suggestedFileName(scope: ExportScope, packId: String): String

    suspend fun restore(content: String): Outcome<RestoreResult>

    /** Deletes every learning result but keeps vocabulary and settings. */
    suspend fun clearStudyProgress()

    /** Deletes everything, including imported vocabulary (built-in packs are re-seeded). */
    suspend fun clearAllData()
}

class DefaultBackupRepository(
    private val db: ToneUpWordDatabase,
    private val time: TimeProvider,
) : BackupRepository {

    override fun suggestedFileName(scope: ExportScope, packId: String): String {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(time.nowMillis()))
        // Pack ids are already filename-safe slugs (`builtin-cet4`, `imported-…`); normalise
        // anyway so an exotic imported id can never produce an invalid file name.
        val slug = packId.lowercase().replace(NON_SLUG, "-").trim('-').ifEmpty { "pack" }
        return when (scope) {
            ExportScope.WORD_LIST -> "toneup-$slug-words-$stamp.csv"
            ExportScope.STUDY_RECORDS -> "toneup-$slug-records-$stamp.json"
            ExportScope.FULL_BACKUP -> "toneup-backup-$stamp.json"
        }
    }

    override suspend fun export(scope: ExportScope, packId: String): Outcome<String> = try {
        val now = time.nowMillis()
        val payload = when (scope) {
            ExportScope.WORD_LIST -> exportWordListCsv(packId)
            ExportScope.STUDY_RECORDS -> JsonCodec.prettyJson.encodeToString(
                StudyRecordsExport.serializer(),
                buildStudyRecords(packId, now),
            )

            ExportScope.FULL_BACKUP -> JsonCodec.prettyJson.encodeToString(
                FullBackup.serializer(),
                buildFullBackup(now),
            )
        }
        Outcome.Success(payload)
    } catch (throwable: Throwable) {
        Outcome.Failure(AppError.io(throwable.javaClass.simpleName))
    }

    private suspend fun exportWordListCsv(packId: String): String {
        val header = listOf(
            "id", "word", "phonetic", "partOfSpeech", "meanings",
            "exampleSentence", "exampleTranslation", "collocations",
            "level", "source", "license", "audioUri",
        )
        val rows = mutableListOf(header)
        db.wordDao().allForPack(packId).forEach { entity ->
            val word = entity.toDomain()
            rows += listOf(
                word.id.toString(),
                word.word,
                word.phonetic,
                word.partOfSpeech,
                word.meanings.joinToString(" | "),
                word.exampleSentence,
                word.exampleTranslation,
                word.collocations.joinToString(" | "),
                word.level.id,
                word.source,
                word.license,
                word.audioUri.orEmpty(),
            )
        }
        return CsvCodec.write(rows)
    }

    private suspend fun buildStudyRecords(packId: String, now: Long): StudyRecordsExport {
        val progress = db.progressDao().wordsByIds(db.wordDao().idsForPack(packId))
            .filter { it.status != null }
            .map { row ->
                ProgressExport(
                    word = row.word,
                    level = row.level,
                    packId = row.packId ?: packId,
                    status = row.status.orEmpty(),
                    masteryLevel = row.masteryLevel ?: 0,
                    repetitions = row.repetitions ?: 0,
                    intervalDays = row.intervalDays ?: 0,
                    easeFactor = row.easeFactor ?: 0.0,
                    dueAt = row.dueAt ?: 0L,
                    lastReviewedAt = row.lastReviewedAt,
                    lastAnswer = row.lastAnswer,
                    lapseCount = row.lapseCount ?: 0,
                    wrongCount = row.wrongCount ?: 0,
                    fuzzyCount = row.fuzzyCount ?: 0,
                    consecutiveCorrectCount = row.consecutiveCorrectCount ?: 0,
                    isFavorite = (row.isFavorite ?: 0) == 1,
                )
            }
        val mistakes = db.wordDao().idsForPack(packId).mapNotNull { wordId ->
            val entity = db.mistakeBookDao().byWordId(wordId) ?: return@mapNotNull null
            MistakeExport(
                wrongCount = entity.wrongCount,
                fuzzyCount = entity.fuzzyCount,
                firstWrongAt = entity.firstWrongAt,
                lastWrongAt = entity.lastWrongAt,
                lastSource = entity.lastSource,
                correctSinceWrong = entity.correctSinceWrong,
                resolvedAt = entity.resolvedAt,
                isFavorite = entity.isFavorite,
                word = db.wordDao().byId(wordId)?.word.orEmpty(),
                level = entity.level,
                packId = entity.packId.ifEmpty { packId },
            )
        }
        val days = db.dailySummaryDao().allForPack(packId).map { it.toDayExport() }
        return StudyRecordsExport(
            format = FORMAT_STUDY_RECORDS,
            version = BACKUP_VERSION,
            exportedAtMillis = now,
            exportedAtIso = Instant.ofEpochMilli(now).toString(),
            level = PackIds.levelForPackId(packId).id,
            packId = packId,
            progress = progress,
            mistakes = mistakes,
            days = days,
        )
    }

    private suspend fun buildFullBackup(now: Long): FullBackup {
        val packIds = db.wordDao().observePackIds().first()
        val words = mutableListOf<WordExport>()
        packIds.forEach { packId ->
            db.wordDao().allForPack(packId).forEach { entity ->
                val word = entity.toDomain()
                words += WordExport(
                    word = word.word,
                    phonetic = word.phonetic,
                    partOfSpeech = word.partOfSpeech,
                    meanings = word.meanings,
                    exampleSentence = word.exampleSentence,
                    exampleTranslation = word.exampleTranslation,
                    collocations = word.collocations,
                    level = word.level.id,
                    packId = packId,
                    source = word.source,
                    license = word.license,
                    audioUri = word.audioUri,
                )
            }
        }

        val progress = mutableListOf<ProgressExport>()
        val mistakes = mutableListOf<MistakeExport>()
        packIds.forEach { packId ->
            val records = buildStudyRecords(packId, now)
            progress += records.progress
            mistakes += records.mistakes
        }

        val events = db.reviewEventDao().let { dao ->
            packIds.flatMap { packId -> dao.allForPack(packId) }
        }.map { entity ->
            EventExport(
                word = db.wordDao().byId(entity.wordId)?.word.orEmpty(),
                level = entity.level,
                packId = entity.packId,
                grade = entity.grade,
                source = entity.source,
                answeredAt = entity.answeredAt,
                dayKey = entity.dayKey,
                previousIntervalDays = entity.previousIntervalDays,
                newIntervalDays = entity.newIntervalDays,
            )
        }

        val days = packIds.flatMap { packId -> db.dailySummaryDao().allForPack(packId) }
            .map { it.toDayExport() }

        return FullBackup(
            format = FORMAT_FULL_BACKUP,
            version = BACKUP_VERSION,
            exportedAtMillis = now,
            exportedAtIso = Instant.ofEpochMilli(now).toString(),
            note = "ToneUpWord 完整备份：词库、学习进度、错题、答题日志与每日统计。恢复时会替换对应词库的学习数据。",
            words = words,
            progress = progress,
            mistakes = mistakes,
            events = events,
            days = days,
        )
    }

    override suspend fun restore(content: String): Outcome<RestoreResult> {
        if (content.isBlank()) return Outcome.Failure(AppError.empty())
        val backup = runCatching { JsonCodec.json.decodeFromString<FullBackup>(content) }.getOrNull()
        if (backup == null) {
            // Also accept a study-records-only file, which contains everything but the words.
            val records = runCatching {
                JsonCodec.json.decodeFromString<StudyRecordsExport>(content)
            }.getOrNull() ?: return Outcome.Failure(AppError.parse("无法识别的备份文件"))
            return restoreRecords(records)
        }

        val now = time.nowMillis()
        return try {
            val result = db.withTransaction {
                // 1. Words: matched by (pack_id, normalised text) so ids stay stable; a v1
                //    backup resolves its pack from the level, and a row written by a pre-v2
                //    restore (`restored-*` pack) is found through the legacy level match.
                backup.words.forEach { word ->
                    val level = CetLevel.fromId(word.level)
                    val packId = word.packId.ifEmpty { PackIds.defaultPackIdFor(level) }
                    val normalized = Word.normalizeWordKey(word.word)
                    if (findWordId(packId, level.id, normalized) == null) {
                        db.wordDao().insert(
                            top.toneup.word.data.local.entity.WordEntity(
                                packId = packId,
                                externalId = null,
                                word = word.word,
                                normalizedWord = normalized,
                                phonetic = word.phonetic,
                                partOfSpeech = word.partOfSpeech,
                                meanings = JsonCodec.encodeStrings(word.meanings),
                                exampleSentence = word.exampleSentence,
                                exampleTranslation = word.exampleTranslation,
                                collocations = JsonCodec.encodeStrings(word.collocations),
                                level = level.id,
                                source = word.source,
                                license = word.license,
                                audioUri = word.audioUri,
                                createdAt = now,
                                updatedAt = now,
                            ),
                        )
                    }
                }

                // 2. Replace study data for the packs present in the backup so a restore is
                //    idempotent (restoring twice does not double the counters).
                val packsInBackup = (
                    backup.progress.map { it.resolvedPackId() } +
                        backup.mistakes.map { it.resolvedPackId() } +
                        backup.events.map { it.resolvedPackId() }
                    ).distinct()
                packsInBackup.forEach { packId ->
                    db.mistakeBookDao().deleteEverythingForPack(packId)
                    db.reviewEventDao().deleteForPack(packId)
                    db.progressDao().deleteForPack(packId)
                }

                var restoredProgress = 0
                var skipped = 0
                backup.progress.forEach { record ->
                    val wordId = findWordId(
                        packId = record.resolvedPackId(),
                        level = CetLevel.fromId(record.level).id,
                        normalized = Word.normalizeWordKey(record.word),
                    )
                    if (wordId == null) {
                        skipped++
                    } else {
                        db.progressDao().upsert(
                            top.toneup.word.data.local.entity.UserWordProgressEntity(
                                wordId = wordId,
                                status = record.status,
                                masteryLevel = record.masteryLevel,
                                repetitions = record.repetitions,
                                intervalDays = record.intervalDays,
                                easeFactor = record.easeFactor,
                                dueAt = record.dueAt,
                                lastReviewedAt = record.lastReviewedAt,
                                lastAnswer = record.lastAnswer,
                                lapseCount = record.lapseCount,
                                wrongCount = record.wrongCount,
                                fuzzyCount = record.fuzzyCount,
                                consecutiveCorrectCount = record.consecutiveCorrectCount,
                                isFavorite = record.isFavorite,
                                introducedAt = record.lastReviewedAt,
                                updatedAt = now,
                            ),
                        )
                        restoredProgress++
                    }
                }

                var restoredMistakes = 0
                backup.mistakes.forEach { record ->
                    val packId = record.resolvedPackId()
                    val wordId = findWordId(
                        packId = packId,
                        level = CetLevel.fromId(record.level).id,
                        normalized = Word.normalizeWordKey(record.word),
                    ) ?: return@forEach
                    db.mistakeBookDao().upsert(
                        top.toneup.word.data.local.entity.WrongAnswerEntity(
                            wordId = wordId,
                            wrongCount = record.wrongCount,
                            fuzzyCount = record.fuzzyCount,
                            firstWrongAt = record.firstWrongAt,
                            lastWrongAt = record.lastWrongAt,
                            lastSource = record.lastSource,
                            correctSinceWrong = record.correctSinceWrong,
                            resolvedAt = record.resolvedAt,
                            isFavorite = record.isFavorite,
                            level = record.level,
                            updatedAt = now,
                            packId = packId,
                        ),
                    )
                    restoredMistakes++
                }

                var restoredEvents = 0
                backup.events.forEach { event ->
                    val packId = event.resolvedPackId()
                    val wordId = findWordId(
                        packId = packId,
                        level = CetLevel.fromId(event.level).id,
                        normalized = Word.normalizeWordKey(event.word),
                    ) ?: return@forEach
                    db.reviewEventDao().insert(
                        top.toneup.word.data.local.entity.ReviewEventEntity(
                            wordId = wordId,
                            sessionId = null,
                            grade = event.grade,
                            source = event.source,
                            answeredAt = event.answeredAt,
                            dayKey = event.dayKey,
                            level = event.level,
                            packId = packId,
                            previousIntervalDays = event.previousIntervalDays,
                            newIntervalDays = event.newIntervalDays,
                            responseTimeMs = null,
                        ),
                    )
                    restoredEvents++
                }

                backup.days.forEach { day ->
                    val packId = day.resolvedPackId()
                    db.dailySummaryDao().upsert(
                        DailyStudySummaryEntity(
                            dayKey = day.dayKey,
                            packId = packId,
                            level = day.level,
                            studiedWords = day.studiedWords,
                            newWords = day.newWords,
                            reviewWords = day.reviewWords,
                            masteredCount = day.masteredCount,
                            fuzzyCount = day.fuzzyCount,
                            unknownCount = day.unknownCount,
                            reviewEvents = day.reviewEvents,
                            studyDurationMs = day.durationMillis,
                            sessionsCompleted = day.sessionsCompleted,
                            examsCompleted = day.examsCompleted,
                            examCorrect = day.examCorrect,
                            examTotal = day.examTotal,
                            updatedAt = now,
                        ),
                    )
                }

                RestoreResult(
                    restoredWords = backup.words.size,
                    restoredProgress = restoredProgress,
                    restoredMistakes = restoredMistakes,
                    restoredEvents = restoredEvents,
                    restoredDays = backup.days.size,
                    skippedWords = skipped,
                )
            }
            Outcome.Success(result)
        } catch (throwable: Throwable) {
            Outcome.Failure(AppError.database(throwable.javaClass.simpleName))
        }
    }

    private suspend fun restoreRecords(records: StudyRecordsExport): Outcome<RestoreResult> {
        val now = time.nowMillis()
        // v1 files have no packId on the envelope: derive it exactly like migration 1→2 did.
        val packId = records.packId.ifEmpty { PackIds.defaultPackIdFor(CetLevel.fromId(records.level)) }
        return try {
            val result = db.withTransaction {
                var restoredProgress = 0
                var skipped = 0
                var restoredMistakes = 0

                db.progressDao().deleteForPack(packId)
                db.mistakeBookDao().deleteEverythingForPack(packId)

                records.progress.forEach { record ->
                    val wordId = findWordId(
                        packId = record.packId.ifEmpty { packId },
                        level = CetLevel.fromId(record.level).id,
                        normalized = Word.normalizeWordKey(record.word),
                    )
                    if (wordId == null) {
                        skipped++
                    } else {
                        db.progressDao().upsert(
                            top.toneup.word.data.local.entity.UserWordProgressEntity(
                                wordId = wordId,
                                status = record.status,
                                masteryLevel = record.masteryLevel,
                                repetitions = record.repetitions,
                                intervalDays = record.intervalDays,
                                easeFactor = record.easeFactor,
                                dueAt = record.dueAt,
                                lastReviewedAt = record.lastReviewedAt,
                                lastAnswer = record.lastAnswer,
                                lapseCount = record.lapseCount,
                                wrongCount = record.wrongCount,
                                fuzzyCount = record.fuzzyCount,
                                consecutiveCorrectCount = record.consecutiveCorrectCount,
                                isFavorite = record.isFavorite,
                                introducedAt = record.lastReviewedAt,
                                updatedAt = now,
                            ),
                        )
                        restoredProgress++
                    }
                }
                records.mistakes.forEach { record ->
                    val recordPackId = record.packId.ifEmpty { packId }
                    val wordId = findWordId(
                        packId = recordPackId,
                        level = CetLevel.fromId(record.level).id,
                        normalized = Word.normalizeWordKey(record.word),
                    ) ?: return@forEach
                    db.mistakeBookDao().upsert(
                        top.toneup.word.data.local.entity.WrongAnswerEntity(
                            wordId = wordId,
                            wrongCount = record.wrongCount,
                            fuzzyCount = record.fuzzyCount,
                            firstWrongAt = record.firstWrongAt,
                            lastWrongAt = record.lastWrongAt,
                            lastSource = record.lastSource,
                            correctSinceWrong = record.correctSinceWrong,
                            resolvedAt = record.resolvedAt,
                            isFavorite = record.isFavorite,
                            level = record.level,
                            updatedAt = now,
                            packId = recordPackId,
                        ),
                    )
                    restoredMistakes++
                }
                records.days.forEach { day ->
                    val dayPackId = day.packId.ifEmpty { packId }
                    db.dailySummaryDao().upsert(
                        DailyStudySummaryEntity(
                            dayKey = day.dayKey,
                            packId = dayPackId,
                            level = day.level,
                            studiedWords = day.studiedWords,
                            newWords = day.newWords,
                            reviewWords = day.reviewWords,
                            masteredCount = day.masteredCount,
                            fuzzyCount = day.fuzzyCount,
                            unknownCount = day.unknownCount,
                            reviewEvents = day.reviewEvents,
                            studyDurationMs = day.durationMillis,
                            sessionsCompleted = day.sessionsCompleted,
                            examsCompleted = day.examsCompleted,
                            examCorrect = day.examCorrect,
                            examTotal = day.examTotal,
                            updatedAt = now,
                        ),
                    )
                }
                RestoreResult(
                    restoredWords = 0,
                    restoredProgress = restoredProgress,
                    restoredMistakes = restoredMistakes,
                    restoredEvents = 0,
                    restoredDays = records.days.size,
                    skippedWords = skipped,
                )
            }
            Outcome.Success(result)
        } catch (throwable: Throwable) {
            Outcome.Failure(AppError.database(throwable.javaClass.simpleName))
        }
    }

    override suspend fun clearStudyProgress() {
        db.withTransaction {
            db.mistakeBookDao().deleteEverything()
            db.reviewEventDao().deleteAll()
            db.progressDao().deleteAll()
            db.studySessionDao().deleteAll()
            db.examDao().deleteAll()
            db.dailySummaryDao().deleteAll()
        }
    }

    override suspend fun clearAllData() {
        db.withTransaction {
            clearStudyProgress()
            // Every row of every pack: built-in packs are re-seeded on the next cold start.
            db.wordDao().deleteAll()
        }
    }

    /**
     * Word identity on restore: `(pack_id, normalized_word)` first (D2), then the legacy
     * `(level, normalized_word)` match so a file written before schema v2 — or rows a pre-v2
     * restore parked under a `restored-*` pack — still finds its row instead of being skipped.
     */
    private suspend fun findWordId(packId: String, level: String, normalized: String): Long? =
        db.wordDao().byNormalized(packId, normalized)?.id
            ?: db.wordDao().byNormalizedInLevel(level, normalized)?.id

    private fun DailyStudySummaryEntity.toDayExport(): DayExport = DayExport(
        dayKey = dayKey,
        level = level,
        packId = packId,
        studiedWords = studiedWords,
        newWords = newWords,
        reviewWords = reviewWords,
        masteredCount = masteredCount,
        fuzzyCount = fuzzyCount,
        unknownCount = unknownCount,
        reviewEvents = reviewEvents,
        durationMillis = studyDurationMs,
        sessionsCompleted = sessionsCompleted,
        examsCompleted = examsCompleted,
        examCorrect = examCorrect,
        examTotal = examTotal,
    )

    // --- Serialised shapes ------------------------------------------------------------------

    @Serializable
    private data class WordExport(
        val word: String,
        val phonetic: String = "",
        val partOfSpeech: String = "",
        val meanings: List<String> = emptyList(),
        val exampleSentence: String = "",
        val exampleTranslation: String = "",
        val collocations: List<String> = emptyList(),
        val level: String = CetLevel.CET4.id,
        /** Empty in v1 backups → resolved from [level] on restore. */
        val packId: String = "",
        val source: String = "",
        val license: String = "",
        val audioUri: String? = null,
    )

    @Serializable
    private data class ProgressExport(
        val word: String,
        val level: String,
        /** Empty in v1 backups → resolved from [level] on restore. */
        val packId: String = "",
        val status: String,
        val masteryLevel: Int,
        val repetitions: Int,
        val intervalDays: Int,
        val easeFactor: Double,
        val dueAt: Long,
        val lastReviewedAt: Long?,
        val lastAnswer: String?,
        val lapseCount: Int,
        val wrongCount: Int,
        val fuzzyCount: Int,
        val consecutiveCorrectCount: Int,
        val isFavorite: Boolean,
    )

    @Serializable
    private data class MistakeExport(
        /** Filled while exporting; ignored when restoring (the word text is used instead). */
        val wordId: Long = 0L,
        val wrongCount: Int,
        val fuzzyCount: Int,
        val firstWrongAt: Long,
        val lastWrongAt: Long,
        val lastSource: String = "STUDY",
        val correctSinceWrong: Int = 0,
        val resolvedAt: Long? = null,
        val isFavorite: Boolean = false,
        val word: String = "",
        val level: String = CetLevel.CET4.id,
        /** Empty in v1 backups → resolved from [level] on restore. */
        val packId: String = "",
    )

    @Serializable
    private data class EventExport(
        val word: String,
        val level: String,
        /** Empty in v1 backups → resolved from [level] on restore. */
        val packId: String = "",
        val grade: String,
        val source: String,
        val answeredAt: Long,
        val dayKey: String,
        val previousIntervalDays: Int = 0,
        val newIntervalDays: Int = 0,
    )

    @Serializable
    private data class DayExport(
        val dayKey: String,
        val level: String,
        /** Empty in v1 backups → resolved from [level] on restore. */
        val packId: String = "",
        val studiedWords: Int = 0,
        val newWords: Int = 0,
        val reviewWords: Int = 0,
        val masteredCount: Int = 0,
        val fuzzyCount: Int = 0,
        val unknownCount: Int = 0,
        val reviewEvents: Int = 0,
        val durationMillis: Long = 0L,
        val sessionsCompleted: Int = 0,
        val examsCompleted: Int = 0,
        val examCorrect: Int = 0,
        val examTotal: Int = 0,
    )

    @Serializable
    private data class StudyRecordsExport(
        val format: String,
        val version: Int,
        val exportedAtMillis: Long,
        val exportedAtIso: String,
        val level: String,
        /** Empty in v1 backups → resolved from [level] on restore. */
        val packId: String = "",
        val progress: List<ProgressExport> = emptyList(),
        val mistakes: List<MistakeExport> = emptyList(),
        val days: List<DayExport> = emptyList(),
    )

    @Serializable
    private data class FullBackup(
        val format: String,
        val version: Int,
        val exportedAtMillis: Long,
        val exportedAtIso: String,
        val note: String = "",
        val words: List<WordExport> = emptyList(),
        val progress: List<ProgressExport> = emptyList(),
        val mistakes: List<MistakeExport> = emptyList(),
        val events: List<EventExport> = emptyList(),
        val days: List<DayExport> = emptyList(),
    )

    companion object {
        const val BACKUP_VERSION = 1
        const val FORMAT_STUDY_RECORDS = "ToneUpWord 学习记录 (JSON)"
        const val FORMAT_FULL_BACKUP = "ToneUpWord 完整备份 (JSON)"

        /** File-name slug normaliser for pack ids. */
        private val NON_SLUG = Regex("[^a-z0-9]+")
    }

    /**
     * Pack id of a v2 DTO; a v1 file (no `packId` field) resolves exactly the way migration
     * 1→2 resolved its own rows — builtin packs for CET levels, `legacy-other` for anything
     * else — so restoring an old backup lands in the same pack the old `level` scope had.
     */
    private fun ProgressExport.resolvedPackId(): String =
        packId.ifEmpty { PackIds.defaultPackIdFor(CetLevel.fromId(level)) }

    private fun MistakeExport.resolvedPackId(): String =
        packId.ifEmpty { PackIds.defaultPackIdFor(CetLevel.fromId(level)) }

    private fun EventExport.resolvedPackId(): String =
        packId.ifEmpty { PackIds.defaultPackIdFor(CetLevel.fromId(level)) }

    private fun DayExport.resolvedPackId(): String =
        packId.ifEmpty { PackIds.defaultPackIdFor(CetLevel.fromId(level)) }
}
