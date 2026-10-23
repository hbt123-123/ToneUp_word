package top.toneup.word.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import top.toneup.word.data.local.entity.DailyStudySummaryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Daily rollups.
 *
 * Rows are written with `INSERT OR REPLACE` because a day bucket is a leaf aggregate with no
 * children; the repository always reads-modifies-writes inside a transaction, so concurrent
 * answers cannot lose an increment.
 *
 * Scope is the **plan id** since schema v4: the primary key is `(day_key, plan_id)`, so one
 * plan keeps one bucket per day no matter how many of its packs were studied (D1).
 */
@Dao
interface DailySummaryDao {

    @Query("SELECT * FROM daily_study_summary WHERE day_key = :dayKey AND plan_id = :planId")
    suspend fun get(dayKey: String, planId: String): DailyStudySummaryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: DailyStudySummaryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(summaries: List<DailyStudySummaryEntity>)

    @Query("SELECT * FROM daily_study_summary WHERE day_key = :dayKey AND plan_id = :planId")
    fun observe(dayKey: String, planId: String): Flow<DailyStudySummaryEntity?>

    @Query(
        """
        SELECT * FROM daily_study_summary
        WHERE plan_id = :planId AND day_key >= :fromDay
        ORDER BY day_key ASC
        """,
    )
    fun observeSince(planId: String, fromDay: String): Flow<List<DailyStudySummaryEntity>>

    @Query(
        """
        SELECT * FROM daily_study_summary
        WHERE plan_id = :planId AND day_key >= :fromDay
        ORDER BY day_key ASC
        """,
    )
    suspend fun since(planId: String, fromDay: String): List<DailyStudySummaryEntity>

    @Query("SELECT * FROM daily_study_summary WHERE plan_id = :planId ORDER BY day_key ASC")
    suspend fun allForPlan(planId: String): List<DailyStudySummaryEntity>

    @Query(
        """
        SELECT
            SUM(studied_words) AS studied_words,
            SUM(review_events) AS review_events,
            SUM(study_duration_ms) AS study_duration_ms,
            SUM(mastered_count) AS mastered_count,
            SUM(fuzzy_count) AS fuzzy_count,
            SUM(unknown_count) AS unknown_count,
            SUM(sessions_completed) AS sessions_completed,
            SUM(exams_completed) AS exams_completed,
            SUM(exam_correct) AS exam_correct,
            SUM(exam_total) AS exam_total,
            COUNT(*) AS active_days
        FROM daily_study_summary
        WHERE plan_id = :planId AND day_key >= :fromDay
        """,
    )
    suspend fun totals(planId: String, fromDay: String): SummaryTotalsRow

    /**
     * Study days with any activity, newest first. The consecutive-day streak is computed
     * from this list by a pure function instead of in SQL, because the rule needs to treat
     * "today not started yet" differently from "streak broken".
     */
    @Query(
        """
        SELECT day_key FROM daily_study_summary
        WHERE plan_id = :planId AND (studied_words > 0 OR review_events > 0 OR exams_completed > 0)
        ORDER BY day_key DESC
        """,
    )
    suspend fun activeDayKeys(planId: String): List<String>

    @Query("DELETE FROM daily_study_summary")
    suspend fun deleteAll()
}
