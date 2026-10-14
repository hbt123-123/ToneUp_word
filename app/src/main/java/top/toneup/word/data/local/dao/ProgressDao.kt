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
 * Scope is the **pack id** since schema v2 (D1): `word_with_progress` exposes `word.pack_id`,
 * so the queue queries filter on the view directly; the two raw-join queries join `word`
 * precisely to reach that column (the progress table itself has no pack column).
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

    // --- Queues --------------------------------------------------------------------------

    /**
     * Words that are due now. Repeatedly failed words come first so "错题和长期未巩固词"
     * are met before ordinary due words; `due_at` keeps the rest in schedule order.
     */
    @Query(
        """
        SELECT * FROM word_with_progress
        WHERE pack_id = :packId
          AND status IS NOT NULL
          AND status != 'NEW'
          AND due_at IS NOT NULL
          AND due_at <= :now
        ORDER BY CASE WHEN wrong_count > 0 THEN 0 ELSE 1 END, due_at ASC
        LIMIT :limit
        """,
    )
    suspend fun dueWords(packId: String, now: Long, limit: Int): List<WordWithProgressView>

    /**
     * Words that are not due yet but are still fragile: repeatedly answered wrong or sitting
     * in RELEARNING. Used to top up a review queue when nothing else is due, so an
     * unconsolidated word comes back long before its (possibly very long) interval elapses.
     */
    @Query(
        """
        SELECT * FROM word_with_progress
        WHERE pack_id = :packId
          AND status IN ('RELEARNING', 'LEARNING')
          AND (wrong_count >= 2 OR status = 'RELEARNING')
          AND (due_at IS NULL OR due_at > :now)
        ORDER BY wrong_count DESC, COALESCE(last_reviewed_at, 0) ASC
        LIMIT :limit
        """,
    )
    suspend fun fragileWords(packId: String, now: Long, limit: Int): List<WordWithProgressView>

    /** Words never studied yet, oldest row id first (stable order, reproducible tests). */
    @Query(
        """
        SELECT * FROM word_with_progress
        WHERE pack_id = :packId AND status IS NULL
        ORDER BY word_id ASC
        LIMIT :limit
        """,
    )
    suspend fun newWords(packId: String, limit: Int): List<WordWithProgressView>

    /** Words with the most mistakes, regardless of schedule — used by mistake drills. */
    @Query(
        """
        SELECT * FROM word_with_progress
        WHERE pack_id = :packId AND wrong_count IS NOT NULL AND wrong_count > 0
        ORDER BY wrong_count DESC, COALESCE(last_reviewed_at, 0) ASC
        LIMIT :limit
        """,
    )
    suspend fun mostWrongWords(packId: String, limit: Int): List<WordWithProgressView>

    @Query("SELECT * FROM word_with_progress WHERE word_id IN (:wordIds) ORDER BY word_id ASC")
    suspend fun wordsByIds(wordIds: List<Long>): List<WordWithProgressView>

    /** Reactive version of [dueWords], used by the review centre ("今日待复习"). */
    @Query(
        """
        SELECT * FROM word_with_progress
        WHERE pack_id = :packId
          AND status IS NOT NULL
          AND status != 'NEW'
          AND due_at IS NOT NULL
          AND due_at <= :now
        ORDER BY CASE WHEN wrong_count > 0 THEN 0 ELSE 1 END, due_at ASC
        LIMIT :limit
        """,
    )
    fun observeDueWords(packId: String, now: Long, limit: Int): Flow<List<WordWithProgressView>>

    /** Reactive version of [fragileWords], used by the review centre ("未巩固词"). */
    @Query(
        """
        SELECT * FROM word_with_progress
        WHERE pack_id = :packId
          AND status IN ('RELEARNING', 'LEARNING')
          AND (wrong_count >= 2 OR status = 'RELEARNING')
        ORDER BY wrong_count DESC, COALESCE(last_reviewed_at, 0) ASC
        LIMIT :limit
        """,
    )
    fun observeFragileWords(packId: String, limit: Int): Flow<List<WordWithProgressView>>

    /** Every word the learner has touched, newest activity first (all-words browse list). */
    @Query(
        """
        SELECT * FROM word_with_progress
        WHERE pack_id = :packId AND status IS NOT NULL
          AND (:search IS NULL OR :search = '' OR word LIKE :like ESCAPE '!' OR meanings LIKE :like ESCAPE '!')
        ORDER BY COALESCE(last_reviewed_at, 0) DESC, word_id ASC
        LIMIT :limit
        """,
    )
    fun observeLearnedWords(
        packId: String,
        search: String?,
        like: String,
        limit: Int,
    ): Flow<List<WordWithProgressView>>

    // --- Counters ------------------------------------------------------------------------

    @Query(
        """
        SELECT COUNT(*) FROM word_with_progress
        WHERE pack_id = :packId AND status IS NOT NULL AND status != 'NEW'
          AND (due_at IS NULL OR due_at <= :now)
        """,
    )
    fun observeDueCount(packId: String, now: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM word_with_progress WHERE pack_id = :packId AND status IS NULL")
    fun observeNewCount(packId: String): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM word_with_progress
        WHERE pack_id = :packId AND status IS NOT NULL AND status != 'NEW'
          AND (due_at IS NULL OR due_at <= :now)
        """,
    )
    suspend fun dueCount(packId: String, now: Long): Int

    @Query("SELECT COUNT(*) FROM word_with_progress WHERE pack_id = :packId AND status IS NULL")
    suspend fun newCount(packId: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM word_with_progress
        WHERE pack_id = :packId AND status IS NOT NULL AND status != 'NEW'
        """,
    )
    fun observeLearnedCount(packId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM word_with_progress WHERE pack_id = :packId AND status = 'MASTERED'")
    fun observeMasteredCount(packId: String): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM word_with_progress
        WHERE pack_id = :packId AND status IN ('LEARNING', 'RELEARNING')
        """,
    )
    fun observeFragileCount(packId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM word_with_progress WHERE pack_id = :packId AND is_favorite = 1")
    fun observeFavoriteCount(packId: String): Flow<Int>

    @Query(
        """
        SELECT p.status AS status, COUNT(*) AS count
        FROM user_word_progress p JOIN word w ON w.id = p.word_id
        WHERE w.pack_id = :packId
        GROUP BY p.status
        """,
    )
    fun observeStatusCounts(packId: String): Flow<List<StatusCountRow>>

    /** Words already reviewed during the current study day; used for the completion rate. */
    @Query(
        """
        SELECT COUNT(*) FROM word_with_progress
        WHERE pack_id = :packId AND last_reviewed_at IS NOT NULL AND last_reviewed_at >= :since
        """,
    )
    suspend fun reviewedSince(packId: String, since: Long): Int

    /**
     * The words that keep coming back wrong, worst first.
     *
     * This is the measurable form of "薄弱词汇": the advice that names a concrete group of words to
     * review today is only useful if the group is real, so it is read from the stored wrong-answer
     * counts rather than guessed from word shapes (the vocabulary data carries no tense or
     * morphology, so a linguistic grouping would be invented).
     *
     * `pack_id` is read from `word`, because it is a property of the vocabulary, not of the
     * progress row — the progress table has no pack column.
     */
    @Query(
        """
        SELECT p.word_id FROM user_word_progress p
        INNER JOIN word w ON w.id = p.word_id
        WHERE w.pack_id = :packId AND p.wrong_count >= :minWrongCount
        ORDER BY p.wrong_count DESC, p.word_id ASC
        LIMIT :limit
        """,
    )
    suspend fun weakestWordIds(packId: String, minWrongCount: Int, limit: Int): List<Long>

    // --- Favourites ----------------------------------------------------------------------

    @Query(
        """
        SELECT * FROM word_with_progress
        WHERE pack_id = :packId AND is_favorite = 1
        ORDER BY COALESCE(last_reviewed_at, 0) DESC, word_id ASC
        """,
    )
    fun observeFavorites(packId: String): Flow<List<WordWithProgressView>>

    @Query(
        """
        SELECT * FROM word_with_progress
        WHERE pack_id = :packId AND is_favorite = 1
        ORDER BY COALESCE(last_reviewed_at, 0) DESC, word_id ASC
        """,
    )
    suspend fun favorites(packId: String): List<WordWithProgressView>

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
