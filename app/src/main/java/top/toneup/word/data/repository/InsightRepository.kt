package top.toneup.word.data.repository

import top.toneup.word.core.time.TimeProvider
import top.toneup.word.data.local.ToneUpWordDatabase
import top.toneup.word.domain.insight.ExamTypeAccuracy
import top.toneup.word.domain.insight.LearningSignals
import top.toneup.word.domain.insight.MistakeGroup
import top.toneup.word.domain.insight.SignalsCalculator
import top.toneup.word.domain.model.CetLevel
import top.toneup.word.domain.stats.StatsSnapshot
import java.time.Instant
import java.time.ZoneId

/**
 * Builds the inputs the analysis rules read.
 *
 * Split from the rules themselves on purpose: this class does the (impure) reading and windowing,
 * while [top.toneup.word.domain.insight.InsightEngine] stays a pure function that can be tested
 * with hand-written inputs. The trend windows are derived from the daily summaries the statistics
 * screen already uses, so the app has exactly one definition of a study day.
 *
 * Honest note on two approximations, both chosen to avoid inventing data:
 *  * `masteredLapsedLast14Days` joins the answer log against the word's **current** status, because
 *    the database stores one progress row per word. A word that lapsed and was later re-mastered is
 *    therefore not counted — the measure is conservative, never inflated.
 *  * `totalActiveDays` counts the days inside the statistics window (12 weeks). For the only rule
 *    that reads it (the "not enough data yet" gate, which needs 3 days) that is exact.
 */
interface InsightRepository {
    /** Reads every signal for [packId]; [snapshot] is passed in so the trends are not recomputed. */
    suspend fun signals(packId: String, snapshot: StatsSnapshot): LearningSignals
}

class DefaultInsightRepository(
    private val db: ToneUpWordDatabase,
    private val time: TimeProvider,
) : InsightRepository {

    override suspend fun signals(packId: String, snapshot: StatsSnapshot): LearningSignals {
        val zone: ZoneId = time.zone()
        val now = time.nowMillis()
        // Today in the learner's own zone: the trend rows are keyed by study day, so the window
        // boundaries must be computed the same way the summaries were.
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

        val trend = SignalsCalculator.fromTrend(snapshot.monthly, today)

        val firstExposureLast7 = db.reviewEventDao()
            .countFirstExposureAnswersSince(packId, now - SEVEN_DAYS_MILLIS)
        val lapsedMastered = db.reviewEventDao()
            .countLapsesOfMasteredWords(packId, now - FOURTEEN_DAYS_MILLIS)
        val weakestIds = db.progressDao()
            .weakestWordIds(packId, minWrongCount = WEAK_WORD_MIN_WRONG, limit = WEAK_WORD_LIMIT)
        val examTypes = db.examDao()
            .typeAccuracySince(packId, now - THIRTY_DAYS_MILLIS)
            .map { row ->
                val attempts = row.attempts
                val correct = row.correct ?: 0
                ExamTypeAccuracy(
                    label = examTypeLabel(row.type),
                    attempts = attempts,
                    accuracyPercent = if (attempts <= 0) 0 else (correct * 100) / attempts,
                )
            }

        return LearningSignals(
            totalAnswerEvents = snapshot.totalReviewEvents,
            totalActiveDays = snapshot.calendar.count { it.hasActivity },
            answersLast7Days = trend.answersLast7Days,
            accuracyLast7Days = trend.accuracyLast7Days,
            accuracyPrevious7Days = trend.accuracyPrevious7Days,
            newWordAnswersLast7Days = firstExposureLast7,
            activeDaysLast7Days = trend.activeDaysLast7Days,
            activeDaysPrevious14Days = trend.activeDaysPrevious14Days,
            medianMinutesPerDayLast7Days = trend.medianMinutesPerDayLast7Days,
            dueToday = dueTodayFor(packId),
            fragileCount = snapshot.fragileCount,
            masteredCount = snapshot.masteredCount,
            masteredLapsedLast14Days = lapsedMastered,
            examTypesLast30Days = examTypes,
            weakestMistakeGroup = weakestIds.takeIf { it.size >= WEAK_WORD_MIN_GROUP }?.let { ids ->
                MistakeGroup(
                    label = "反复出错的单词",
                    wordCount = ids.size,
                    wordIds = ids,
                )
            },
        )
    }

    /**
     * Words that are due right now.
     *
     * Counted from the progress rows rather than read from the dashboard's plan, because the plan is
     * scoped to the currently selected pack while this method is called with an explicit pack id.
     */
    private suspend fun dueTodayFor(packId: String): Int =
        db.progressDao().dueCount(packId, time.nowMillis())

    /** Question types are stored as stable ids; the UI shows the same labels the exam screens use. */
    private fun examTypeLabel(type: String): String = when (type.uppercase()) {
        "EN_TO_ZH" -> "英译中"
        "ZH_TO_EN" -> "中译英"
        "SPELLING" -> "拼写填空"
        "MULTIPLE_CHOICE" -> "四选一"
        else -> type
    }

    private companion object {
        const val SEVEN_DAYS_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val FOURTEEN_DAYS_MILLIS = 14L * 24 * 60 * 60 * 1000
        const val THIRTY_DAYS_MILLIS = 30L * 24 * 60 * 60 * 1000

        /** A word wrong at least this often counts as "repeatedly wrong" for the advice. */
        const val WEAK_WORD_MIN_WRONG = 3

        /** How many such words to collect before naming them in a suggestion. */
        const val WEAK_WORD_MIN_GROUP = 8
        const val WEAK_WORD_LIMIT = 50
    }
}
