package top.toneup.word.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import top.toneup.word.data.local.entity.UserWordProgressEntity
import kotlinx.coroutines.flow.Flow

/**
 * Spaced-repetition state and every query the queues are built from.
 *
 * Notes on SQLite compatibility: only statements available in SQLite 3.18 (the version
 * shipped with Android 8.0) are used — no UPSERT clause, no window functions, no RETURNING.
 *
 * Scope is the **plan id** since schema v4: `word_with_progress` exposes `word.plan_id` and
 * collapses duplicate words inside a plan to one canonical row, so filtering the view on
 * `plan_id` is both the scope and the de-duplication (D2). Queries never need the `word` join
 * to reach a scope column any more.
 */
@Dao
interface ProgressDao {

    @Upsert
    suspend fun upsert(progress: UserWordProgressEntity)

    @Upsert
    suspend fun upsertAll(progress: List<UserWordProgressEntity>)

    @Query("SELECT * FROM user_word_progress WHERE word_id = :wordId")
    suspend fun byWordId(wordId: Long): UserWordProgressEntity?

    @Query("SELECT * FROM user_word_progress WHERE word_id IN (:wordIds)")
    suspend fun byWordIds(wordIds: List<Long>): List<UserWordProgressEntity>

    @Query("SELECT * FROM word_with_progress WHERE word_id = :wordId")
    suspend fun wordWithProgress(wordId: Long): WordWithProgressView?

    /**
     * Hands a progress row from one word row to its surviving sibling (D10). Only called by
     * `deletePack`, and only when the target row has no progress row of its own.
     */
    @Query("UPDATE user_word_progress SET word_id = :toWordId WHERE word_id = :fromWordId")
    suspend fun reassignWordId(fromWordId: Long, toWordId: Long)

    // --- Queues --------------------------------------------------------------------------

    /**
     * Words that are due now. Repeatedly failed words come first so "错题和长期未巩固词"
     * are met before ordinary due words; `due_at` keeps the rest in schedule order.
     */
    @Query(
        """
        SELECT * FROM word_with_progress
        WHERE plan_id = :planId
          AND status IS NOT NULL
          AND status != 'NEW'
          AND due_at IS NOT NULL
          AND due_at <= :now
        ORDER BY CASE WHEN wrong_count > 0 THEN 0 ELSE 1 END, due_at ASC
        LIMIT :limit
        """,
    )
    suspend fun dueWords(planId: String, now: Long, limit: Int): List<WordWithProgressView>

    /**
     * Words that are not due yet but are still fragile: repeatedly answered wrong or sitting
     * in RELEARNING. Used to top up a review queue when nothing else is due, so an
     * unconsolidated word comes back long before its (possibly very long) interval elapses.
     */
    @Query(
        """
        SELECT * FROM word_with_progress
        WHERE plan_id = :planId
          AND status IN ('RELEARNING', 'LEARNING')
          AND (wrong_count >= 2 OR status = 'RELEARNING')
          AND (due_at IS NULL OR due_at > :now)
        ORDER BY wrong_count DESC, COALESCE(last_reviewed_at, 0) ASC
        LIMIT :limit
        """,
    )
    suspend fun fragileWords(planId: String, now: Long, limit: Int): List<WordWithProgressView>

    /** Words never studied yet, oldest row id first (stable order, reproducible tests). */
    @Query(
        """
        SELECT * FROM word_with_progress
        WHERE plan_id = :planId AND status IS NULL
        ORDER BY word_id ASC
        LIMIT :limit
        """,
    )
    suspend fun newWords(planId: String, limit: Int): List<WordWithProgressView>

    /** Words with the most mistakes, regardless of schedule — used by mistake drills. */
    @Query(
        """
        SELECT * FROM word_with_progress
        WHERE plan_id = :planId AND wrong_count IS NOT NULL AND wrong_count > 0
        ORDER BY wrong_count DESC, COALESCE(last_reviewed_at, 0) ASC
        LIMIT :limit
        """,
    )
    suspend fun mostWrongWords(planId: String, limit: Int): List<WordWithProgressView>

    @Query("SELECT * FROM word_with_progress WHERE word_id IN (:wordIds) ORDER BY word_id ASC")
    suspend fun wordsByIds(wordIds: List<Long>): List<WordWithProgressView>

    /** Reactive version of [dueWords], used by the review centre ("今日待复习"). */
    @Query(
        """
        SELECT * FROM word_with_progress
        WHERE plan_id = :planId
          AND status IS NOT NULL
          AND status != 'NEW'
          AND due_at IS NOT NULL
          AND due_at <= :now
        ORDER BY CASE WHEN wrong_count > 0 THEN 0 ELSE 1 END, due_at ASC
        LIMIT :limit
        """,
    )
    fun observeDueWords(planId: String, now: Long, limit: Int): Flow<List<WordWithProgressView>>

    /** Reactive version of [fragileWords], used by the review centre ("未巩固词"). */
    @Query(
        """
        SELECT * FROM word_with_progress
        WHERE plan_id = :planId
          AND status IN ('RELEARNING', 'LEARNING')
          AND (wrong_count >= 2 OR status = 'RELEARNING')
        ORDER BY wrong_count DESC, COALESCE(last_reviewed_at, 0) ASC
        LIMIT :limit
        """,
    )
    fun observeFragileWords(planId: String, limit: Int): Flow<List<WordWithProgressView>>

    /** Every word the learner has touched, newest activity first (all-words browse list). */
    @Query(
        """
        SELECT * FROM word_with_progress
        WHERE plan_id = :planId AND status IS NOT NULL
          AND (:search IS NULL OR :search = '' OR word LIKE :like ESCAPE '!' OR meanings LIKE :like ESCAPE '!')
        ORDER BY COALESCE(last_reviewed_at, 0) DESC, word_id ASC
        LIMIT :limit
        """,
    )
    fun observeLearnedWords(
        planId: String,
        search: String?,
        like: String,
        limit: Int,
    ): Flow<List<WordWithProgressView>>

    // --- Counters ------------------------------------------------------------------------

    @Query(
        """
        SELECT COUNT(*) FROM word_with_progress
        WHERE plan_id = :planId AND status IS NOT NULL AND status != 'NEW'
          AND (due_at IS NULL OR due_at <= :now)
        """,
    )
    fun observeDueCount(planId: String, now: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM word_with_progress WHERE plan_id = :planId AND status IS NULL")
    fun observeNewCount(planId: String): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM word_with_progress
        WHERE plan_id = :planId AND status IS NOT NULL AND status != 'NEW'
          AND (due_at IS NULL OR due_at <= :now)
        """,
    )
    suspend fun dueCount(planId: String, now: Long): Int

    @Query("SELECT COUNT(*) FROM word_with_progress WHERE plan_id = :planId AND status IS NULL")
    suspend fun newCount(planId: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM word_with_progress
        WHERE plan_id = :planId AND status IS NOT NULL AND status != 'NEW'
        """,
    )
    fun observeLearnedCount(planId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM word_with_progress WHERE plan_id = :planId AND status = 'MASTERED'")
    fun observeMasteredCount(planId: String): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM word_with_progress
        WHERE plan_id = :planId AND status IN ('LEARNING', 'RELEARNING')
        """,
    )
    fun observeFragileCount(planId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM word_with_progress WHERE plan_id = :planId AND is_favorite = 1")
    fun observeFavoriteCount(planId: String): Flow<Int>

    /** Status histogram of a plan, read from the canonical view so duplicates count once. */
    @Query(
        """
        SELECT status AS status, COUNT(*) AS count
        FROM word_with_progress
        WHERE plan_id = :planId AND status IS NOT NULL
        GROUP BY status
        """,
    )
    fun observeStatusCounts(planId: String): Flow<List<StatusCountRow>>

    /** Words already reviewed during the current study day; used for the completion rate. */
    @Query(
        """
        SELECT COUNT(*) FROM word_with_progress
        WHERE plan_id = :planId AND last_reviewed_at IS NOT NULL AND last_reviewed_at >= :since
        """,
    )
    suspend fun reviewedSince(planId: String, since: Long): Int

    /**
     * The words that keep coming back wrong, worst first.
     *
     * This is the measurable form of "薄弱词汇": the advice that names a concrete group of words to
     * review today is only useful if the group is real, so it is read from the stored wrong-answer
     * counts rather than guessed from word shapes (the vocabulary data carries no tense or
     * morphology, so a linguistic grouping would be invented).
     *
     * Read from the canonical view, so a word shared by two packs inside the plan is named once.
     */
    @Query(
        """
        SELECT word_id FROM word_with_progress
        WHERE plan_id = :planId AND wrong_count >= :minWrongCount
        ORDER BY wrong_count DESC, word_id ASC
        LIMIT :limit
        """,
    )
    suspend fun weakestWordIds(planId: String, minWrongCount: Int, limit: Int): List<Long>

    // --- Favourites ----------------------------------------------------------------------

    @Query(
        """
        SELECT * FROM word_with_progress
        WHERE plan_id = :planId AND is_favorite = 1
        ORDER BY COALESCE(last_reviewed_at, 0) DESC, word_id ASC
        """,
    )
    fun observeFavorites(planId: String): Flow<List<WordWithProgressView>>

    @Query(
        """
        SELECT * FROM word_with_progress
        WHERE plan_id = :planId AND is_favorite = 1
        ORDER BY COALESCE(last_reviewed_at, 0) DESC, word_id ASC
        """,
    )
    suspend fun favorites(planId: String): List<WordWithProgressView>

    @Query("UPDATE user_word_progress SET is_favorite = :favorite, updated_at = :now WHERE word_id = :wordId")
    suspend fun setFavorite(wordId: Long, favorite: Boolean, now: Long)

    @Query("UPDATE user_word_progress SET is_favorite = :favorite, updated_at = :now WHERE word_id IN (:wordIds)")
    suspend fun setFavoriteAll(wordIds: List<Long>, favorite: Boolean, now: Long)

    // --- Maintenance ---------------------------------------------------------------------

    @Query("DELETE FROM user_word_progress")
    suspend fun deleteAll()

    /** Removes progress for one pack only (used by restore and by pack cleanup). */
    @Query("DELETE FROM user_word_progress WHERE word_id IN (SELECT id FROM word WHERE pack_id = :packId)")
    suspend fun deleteForPack(packId: String)
}
