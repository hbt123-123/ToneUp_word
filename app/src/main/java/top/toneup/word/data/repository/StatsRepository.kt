package top.toneup.word.data.repository

import top.toneup.word.core.time.StudyDay
import top.toneup.word.core.time.TimeProvider
import top.toneup.word.data.local.ToneUpWordDatabase
import top.toneup.word.data.mapper.toInput
import top.toneup.word.data.settings.PackIds
import top.toneup.word.data.settings.UserSettings
import top.toneup.word.domain.model.CetLevel
import top.toneup.word.domain.srs.MasteryStatus
import top.toneup.word.domain.stats.DailyStudyPoint
import top.toneup.word.domain.stats.DailySummaryInput
import top.toneup.word.domain.stats.DailyTrendInput
import top.toneup.word.domain.stats.LevelProgress
import top.toneup.word.domain.stats.StatsCalculator
import top.toneup.word.domain.stats.StatsSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * Statistics and the learning calendar.
 *
 * Aggregation happens in SQL (`GROUP BY day_key`, `COUNT`, `SUM`) and the *rules* — streaks,
 * percentages, calendar filling — are pure functions in `StatsCalculator`, so the numbers can
 * be verified by unit tests instead of by looking at a screen.
 */
interface StatsRepository {

    fun observeStats(packId: String, settings: UserSettings): Flow<StatsSnapshot>

    /** One shot variant used by export and by tests. */
    suspend fun stats(packId: String, settings: UserSettings): StatsSnapshot
}

class DefaultStatsRepository(
    private val db: ToneUpWordDatabase,
    private val time: TimeProvider,
) : StatsRepository {

    override fun observeStats(packId: String, settings: UserSettings): Flow<StatsSnapshot> {
        val zone = time.zone()
        val now = time.nowMillis()
        val today = StudyDay.localDate(now, zone)
        val yearAgoKey = today.minusDays(CALENDAR_DAYS - 1).toString()

        val core = combine(
            db.dailySummaryDao().observeSince(packId, yearAgoKey),
            db.reviewEventDao().observeTrendsSince(packId, yearAgoKey),
            db.progressDao().observeStatusCounts(packId),
            db.wordDao().observeCount(packId),
            db.progressDao().observeDueCount(packId, now),
        ) { summaries, trends, statuses, wordCount, dueCount ->
            CoreStats(
                summaries = summaries.map { it.toInput() },
                trends = trends.map { DailyTrendInput(it.dayKey, it.total, it.correct, it.fuzzy, it.wrong) },
                statusCounts = statuses.associate { row ->
                    top.toneup.word.data.mapper.masteryStatusFromId(row.status) to row.count
                },
                wordCount = wordCount,
                dueCount = dueCount,
            )
        }

        return combine(
            core,
            db.mistakeBookDao().observeActiveCount(packId),
            db.mistakeBookDao().observeHighWrongCount(packId, DefaultMistakeRepository.HIGH_WRONG_THRESHOLD),
            db.progressDao().observeFavoriteCount(packId),
            db.examDao().observeSubmittedCount(),
        ) { coreStats, activeMistakes, highMistakes, favorites, examCount ->
            Partial(
                core = coreStats,
                activeMistakes = activeMistakes,
                highMistakes = highMistakes,
                favorites = favorites,
                examCount = examCount,
                examAverage = null,
            )
        }.combine(db.examDao().observeAverageAccuracy(packId)) { partial, average ->
            partial.copy(examAverage = StatsCalculator.examAveragePercent(average))
        }.map { partial ->
            build(partial, packId, settings, today)
        }
    }

    override suspend fun stats(packId: String, settings: UserSettings): StatsSnapshot {
        val zone = time.zone()
        val now = time.nowMillis()
        val today = StudyDay.localDate(now, zone)
        val yearAgoKey = today.minusDays(CALENDAR_DAYS - 1).toString()

        val core = CoreStats(
            summaries = db.dailySummaryDao().since(packId, yearAgoKey).map { it.toInput() },
            trends = db.reviewEventDao().trendsSince(packId, yearAgoKey)
                .map { DailyTrendInput(it.dayKey, it.total, it.correct, it.fuzzy, it.wrong) },
            statusCounts = db.progressDao().observeStatusCounts(packId).first().associate {
                top.toneup.word.data.mapper.masteryStatusFromId(it.status) to it.count
            },
            wordCount = db.wordDao().count(packId),
            dueCount = db.progressDao().dueCount(packId, now),
        )
        val partial = Partial(
            core = core,
            activeMistakes = db.mistakeBookDao().activeCount(packId),
            highMistakes = 0,
            favorites = db.progressDao().observeFavoriteCount(packId).first(),
            examCount = db.examDao().observeSubmittedCount().first(),
            examAverage = StatsCalculator.examAveragePercent(db.examDao().averageAccuracy(packId)),
        )
        return build(partial, packId, settings, today)
    }

    private fun build(
        partial: Partial,
        packId: String,
        settings: UserSettings,
        today: LocalDate,
    ): StatsSnapshot {
        // Display level follows the pack (D1): builtin packs show their CET label, imports "自定义".
        val level = PackIds.levelForPackId(packId)
        val core = partial.core
        val calendarFrom = today.minusDays(CALENDAR_DAYS - 1)
        val calendar = StatsCalculator.buildSeries(core.summaries, core.trends, calendarFrom, today)
        val monthly = calendar.takeLast(30)
        val weekly = calendar.takeLast(7)

        val activeDays = calendar.filter { it.hasActivity }.map { it.date }
        val totalReviewEvents = calendar.sumOf { it.reviewEvents }
        val totalCorrect = calendar.sumOf { it.correct }
        val totalMinutes = StatsCalculator.toMinutes(calendar.sumOf { it.durationMillis })
        val todayPoint: DailyStudyPoint = calendar.lastOrNull() ?: DailyStudyPoint(
            dayKey = today.toString(),
            date = today,
            studiedWords = 0,
            reviewEvents = 0,
            correct = 0,
            fuzzy = 0,
            wrong = 0,
            durationMillis = 0L,
        )

        val learned = core.statusCounts.entries
            .filter { it.key != MasteryStatus.NEW }
            .sumOf { it.value }
        val mastered = core.statusCounts[MasteryStatus.MASTERED] ?: 0
        val fragile = (core.statusCounts[MasteryStatus.LEARNING] ?: 0) +
            (core.statusCounts[MasteryStatus.RELEARNING] ?: 0)

        return StatsSnapshot(
            levelProgress = LevelProgress(
                level = level,
                totalWords = core.wordCount,
                learnedWords = learned,
                masteredWords = mastered,
                fragileWords = fragile,
            ),
            totalLearnedWords = learned,
            totalReviewEvents = totalReviewEvents,
            masteredCount = mastered,
            fragileCount = fragile,
            favoriteCount = partial.favorites,
            activeMistakeCount = partial.activeMistakes,
            highMistakeCount = partial.highMistakes,
            streakDays = StatsCalculator.currentStreak(activeDays, today),
            longestStreakDays = StatsCalculator.longestStreak(activeDays),
            todayMinutes = todayPoint.durationMinutes,
            totalMinutes = totalMinutes,
            accuracyPercent = StatsCalculator.percent(totalCorrect, totalReviewEvents),
            reviewCompletionPercent = StatsCalculator.reviewCompletion(
                reviewedToday = todayPoint.reviewEvents,
                stillDue = core.dueCount,
            ),
            examCount = partial.examCount,
            examAveragePercent = partial.examAverage,
            weekly = weekly,
            monthly = monthly,
            calendar = calendar,
            statusCounts = core.statusCounts,
            stageGoal = StatsCalculator.stageGoal(
                level = level,
                totalWords = core.wordCount,
                learnedWords = learned,
                dailyGoal = settings.dailyNewWordGoal,
            ),
        )
    }

    private data class CoreStats(
        val summaries: List<DailySummaryInput>,
        val trends: List<DailyTrendInput>,
        val statusCounts: Map<MasteryStatus, Int>,
        val wordCount: Int,
        val dueCount: Int,
    )

    private data class Partial(
        val core: CoreStats,
        val activeMistakes: Int,
        val highMistakes: Int,
        val favorites: Int,
        val examCount: Int,
        val examAverage: Int?,
    )

    companion object {
        /** 12 weeks of calendar data: enough for a term, small enough to render instantly. */
        const val CALENDAR_DAYS = 84L
    }
}
