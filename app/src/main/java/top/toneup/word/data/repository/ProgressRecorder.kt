package top.toneup.word.data.repository

import androidx.room.withTransaction
import top.toneup.word.core.time.StudyDay
import top.toneup.word.core.time.TimeProvider
import top.toneup.word.data.local.ToneUpWordDatabase
import top.toneup.word.data.local.entity.DailyStudySummaryEntity
import top.toneup.word.data.local.entity.ReviewEventEntity
import top.toneup.word.data.local.entity.WrongAnswerEntity
import top.toneup.word.data.mapper.newProgressFor
import top.toneup.word.data.mapper.toDomain
import top.toneup.word.data.mapper.toEntity
import top.toneup.word.data.mapper.toId
import top.toneup.word.data.settings.PackIds
import top.toneup.word.domain.model.CetLevel
import top.toneup.word.domain.srs.ReviewGrade
import top.toneup.word.domain.srs.SpacedRepetition
import top.toneup.word.domain.srs.WordProgress

/** Where an answer came from. Stored on every review event and used by the statistics. */
enum class AnswerSource(val id: String) {
    STUDY("STUDY"),
    IMMEDIATE("IMMEDIATE"),
    DRILL("DRILL"),
    EXAM("EXAM"),
}

/**
 * Writes one answer to the database.
 *
 * This is the single place where a learner's answer changes the world, and it runs inside a
 * Room transaction so that progress, the answer log, the mistake book and the daily rollup
 * can never drift apart — even if the process is killed in the middle of a study session.
 *
 * The schedule itself is computed by [SpacedRepetition]; this class only persists its
 * decision.
 */
class ProgressRecorder(
    private val db: ToneUpWordDatabase,
    private val time: TimeProvider,
) {

    /**
     * @param wordId the word being answered
     * @param grade the learner's self-assessment (an exam answer is mapped by the caller)
     * @param source which screen produced the answer
     * @param sessionId study session this answer belongs to, if any
     * @param responseTimeMs how long the card was on screen, when known
     */
    suspend fun record(
        wordId: Long,
        grade: ReviewGrade,
        source: AnswerSource,
        sessionId: Long? = null,
        responseTimeMs: Long? = null,
    ): WordProgress = db.withTransaction {
        val now = time.nowMillis()
        val zone = time.zone()
        val dayKey = StudyDay.dayKey(now, zone)

        val existingEntity = db.progressDao().byWordId(wordId)
        val previous = existingEntity?.toDomain() ?: newProgressFor(wordId, now)
        val isFirstAnswerForWordToday = db.reviewEventDao().countForWordOnDay(wordId, dayKey) == 0
        // A word counts as "new" only when this is the very first time it is answered at all:
        // the progress row did not exist before this call. Deriving it from the row instead of
        // from the grade is what keeps one word from being booked as both a new word and a
        // review word on the same day.
        val isNewWord = existingEntity == null

        val updated = SpacedRepetition.apply(previous, grade, now)

        db.progressDao().upsert(updated.toEntity(now))
        val packId = scopeOf(wordId)
        db.reviewEventDao().insert(
            ReviewEventEntity(
                wordId = wordId,
                sessionId = sessionId,
                grade = grade.toId(),
                source = source.id,
                answeredAt = now,
                dayKey = dayKey,
                level = PackIds.levelForPackId(packId).id,
                packId = packId,
                previousIntervalDays = previous.intervalDays,
                newIntervalDays = updated.intervalDays,
                responseTimeMs = responseTimeMs,
            ),
        )

        updateMistakeBook(wordId, grade, source, now)
        bumpDailySummary(
            dayKey = dayKey,
            packId = packId,
            grade = grade,
            firstTouchToday = isFirstAnswerForWordToday,
            isNewWord = isNewWord,
            now = now,
        )
        updated
    }

    /**
     * Pack scope of a word (schema v2, D1): read from `word.pack_id`, which migration 1→2
     * guarantees to be non-empty for every row. The fallbacks only cover a word row that has
     * disappeared mid-transaction (FK CASCADE makes that impossible in practice) — keeping a
     * deterministic `builtin-cet4`/level-derived bucket instead of dropping the answer.
     */
    private suspend fun scopeOf(wordId: Long): String {
        val entity = db.wordDao().byId(wordId)
            ?: return PackIds.defaultPackIdFor(CetLevel.CET4)
        val packId = entity.packId
        if (!packId.isNullOrEmpty()) return packId
        return PackIds.defaultPackIdFor(CetLevel.fromId(entity.level))
    }

    /**
     * Mistake book rules:
     *  * 不认识 / exam mistakes increment the counter and re-open a resolved row;
     *  * 模糊 is tracked separately so "hard but known" is distinguishable from "wrong";
     *  * three consecutive 已掌握 answers auto-mark a row as re-consolidated (the row is kept
     *    for history, and the user can still do it manually from the review centre).
     */
    private suspend fun updateMistakeBook(
        wordId: Long,
        grade: ReviewGrade,
        source: AnswerSource,
        now: Long,
    ) {
        val existing = db.mistakeBookDao().byWordId(wordId)
        val packId = scopeOf(wordId)
        val levelId = PackIds.levelForPackId(packId).id
        when (grade) {
            ReviewGrade.UNKNOWN -> {
                val wrong = existing?.copy(
                    wrongCount = existing.wrongCount + 1,
                    lastWrongAt = now,
                    lastSource = source.id,
                    correctSinceWrong = 0,
                    resolvedAt = null,
                    level = levelId,
                    updatedAt = now,
                    packId = packId,
                ) ?: WrongAnswerEntity(
                    wordId = wordId,
                    wrongCount = 1,
                    fuzzyCount = 0,
                    firstWrongAt = now,
                    lastWrongAt = now,
                    lastSource = source.id,
                    correctSinceWrong = 0,
                    resolvedAt = null,
                    isFavorite = false,
                    level = levelId,
                    updatedAt = now,
                    packId = packId,
                )
                db.mistakeBookDao().upsert(wrong)
            }

            ReviewGrade.FUZZY -> {
                if (existing != null) {
                    db.mistakeBookDao().upsert(
                        existing.copy(
                            fuzzyCount = existing.fuzzyCount + 1,
                            lastWrongAt = now,
                            lastSource = source.id,
                            correctSinceWrong = 0,
                            updatedAt = now,
                        ),
                    )
                }
            }

            ReviewGrade.MASTERED -> {
                if (existing != null && existing.resolvedAt == null) {
                    val streak = existing.correctSinceWrong + 1
                    db.mistakeBookDao().upsert(
                        existing.copy(
                            correctSinceWrong = streak,
                            resolvedAt = if (streak >= AUTO_RESOLVE_STREAK) now else null,
                            updatedAt = now,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Read-modify-write of one day bucket; safe because the caller holds the transaction.
     *
     * Counting rules (each number means exactly one thing, so the statistics screen can be
     * honest about them):
     *  * `studiedWords` — distinct words whose first answer of the day this was;
     *  * `newWords` — of those, the ones that had never been answered before today's card;
     *  * `reviewWords` — of those, the ones that already had progress (a genuine review);
     *  * the grade counters count *answers*, not words, and therefore may exceed the above.
     */
    private suspend fun bumpDailySummary(
        dayKey: String,
        packId: String,
        grade: ReviewGrade,
        firstTouchToday: Boolean,
        isNewWord: Boolean,
        now: Long,
    ) {
        val existing = db.dailySummaryDao().get(dayKey, packId) ?: DailyStudySummaryEntity(
            dayKey = dayKey,
            packId = packId,
            level = PackIds.levelForPackId(packId).id,
        )
        db.dailySummaryDao().upsert(
            existing.copy(
                studiedWords = existing.studiedWords + if (firstTouchToday) 1 else 0,
                newWords = existing.newWords + if (firstTouchToday && isNewWord) 1 else 0,
                reviewWords = existing.reviewWords + if (firstTouchToday && !isNewWord) 1 else 0,
                masteredCount = existing.masteredCount + if (grade == ReviewGrade.MASTERED) 1 else 0,
                fuzzyCount = existing.fuzzyCount + if (grade == ReviewGrade.FUZZY) 1 else 0,
                unknownCount = existing.unknownCount + if (grade == ReviewGrade.UNKNOWN) 1 else 0,
                reviewEvents = existing.reviewEvents + 1,
                updatedAt = now,
            ),
        )
    }

    /** Adds the time spent in a finished session to the day it started on. */
    suspend fun addStudyDuration(startedAtMillis: Long, durationMillis: Long, packId: String) {
        if (durationMillis <= 0) return
        db.withTransaction {
            val now = time.nowMillis()
            val dayKey = StudyDay.dayKey(startedAtMillis, time.zone())
            val existing = db.dailySummaryDao().get(dayKey, packId) ?: DailyStudySummaryEntity(
                dayKey = dayKey,
                packId = packId,
                level = PackIds.levelForPackId(packId).id,
            )
            db.dailySummaryDao().upsert(
                existing.copy(
                    studyDurationMs = existing.studyDurationMs + durationMillis,
                    sessionsCompleted = existing.sessionsCompleted + 1,
                    updatedAt = now,
                ),
            )
        }
    }

    /** Adds an exam to the daily rollup (completed count and raw question score). */
    suspend fun addExamResult(
        submittedAtMillis: Long,
        packId: String,
        correct: Int,
        total: Int,
        durationMillis: Long,
    ) {
        db.withTransaction {
            val dayKey = StudyDay.dayKey(submittedAtMillis, time.zone())
            val existing = db.dailySummaryDao().get(dayKey, packId) ?: DailyStudySummaryEntity(
                dayKey = dayKey,
                packId = packId,
                level = PackIds.levelForPackId(packId).id,
            )
            db.dailySummaryDao().upsert(
                existing.copy(
                    examsCompleted = existing.examsCompleted + 1,
                    examCorrect = existing.examCorrect + correct,
                    examTotal = existing.examTotal + total,
                    studyDurationMs = existing.studyDurationMs + durationMillis.coerceAtLeast(0),
                    updatedAt = submittedAtMillis,
                ),
            )
        }
    }

    companion object {
        /** Consecutive 已掌握 answers that retire a mistake-book row automatically. */
        const val AUTO_RESOLVE_STREAK = 3
    }
}
