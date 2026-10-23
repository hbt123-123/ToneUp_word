package top.toneup.word.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import top.toneup.word.data.local.entity.WrongAnswerEntity
import kotlinx.coroutines.flow.Flow

/**
 * The wrong-answer book — 错题本.
 *
 * One row per word lives in the `wrong_answer` table; this DAO adds the word columns through
 * the `word_with_progress` view so lists can be filtered and ordered entirely in SQL.
 *
 * Scope is the **plan id** since schema v4 (D4/D2): list queries filter on `wa.plan_id` and
 * join the canonical view, so a word imported into two packs of one plan appears once. Counts
 * join the view for the same reason — counting `wrong_answer` rows directly would count the
 * hidden duplicate rows too. `deleteEverythingForPack` still works on the pack, because restore
 * replaces data pack by pack.
 *
 * Filters are explicit parameters rather than assembled SQL strings, and search terms are
 * bound *and* escaped (`ESCAPE '!'`), so typing `%` looks for a literal percent sign instead
 * of matching every row.
 */
@Dao
interface MistakeBookDao {

    @Upsert
    suspend fun upsert(wrong: WrongAnswerEntity)

    @Query("SELECT * FROM wrong_answer WHERE word_id = :wordId")
    suspend fun byWordId(wordId: Long): WrongAnswerEntity?

    @Query("DELETE FROM wrong_answer WHERE word_id = :wordId")
    suspend fun delete(wordId: Long)

    @Query("DELETE FROM wrong_answer WHERE word_id IN (:wordIds)")
    suspend fun deleteAll(wordIds: List<Long>)

    @Query("DELETE FROM wrong_answer")
    suspend fun deleteEverything()

    /** Removes mistake rows of one pack only (used by restore). */
    @Query("DELETE FROM wrong_answer WHERE pack_id = :packId")
    suspend fun deleteEverythingForPack(packId: String)

    @Query("UPDATE wrong_answer SET word_id = :toWordId WHERE word_id = :fromWordId")
    suspend fun reassignWordId(fromWordId: Long, toWordId: Long)

    /** Marks a word as re-consolidated; the row stays so history filters keep working. */
    @Query("UPDATE wrong_answer SET resolved_at = :now, updated_at = :now WHERE word_id = :wordId")
    suspend fun markResolved(wordId: Long, now: Long)

    @Query("UPDATE wrong_answer SET resolved_at = NULL, updated_at = :now WHERE word_id = :wordId")
    suspend fun markUnresolved(wordId: Long, now: Long)

    @Query("UPDATE wrong_answer SET resolved_at = :now, updated_at = :now WHERE word_id IN (:wordIds)")
    suspend fun markResolvedAll(wordIds: List<Long>, now: Long)

    @Query("UPDATE wrong_answer SET is_favorite = :favorite, updated_at = :now WHERE word_id = :wordId")
    suspend fun setFavorite(wordId: Long, favorite: Boolean, now: Long)

    /** Main list: newest mistakes first. */
    @Query(
        """
        SELECT wa.word_id AS word_id, v.word AS word, v.phonetic AS phonetic,
               v.part_of_speech AS part_of_speech, v.meanings AS meanings,
               v.example_sentence AS example_sentence, v.level AS level,
               wa.pack_id AS pack_id, wa.plan_id AS plan_id,
               wa.wrong_count AS wrong_count, wa.fuzzy_count AS fuzzy_count,
               wa.first_wrong_at AS first_wrong_at, wa.last_wrong_at AS last_wrong_at,
               wa.last_source AS last_source, wa.correct_since_wrong AS correct_since_wrong,
               wa.resolved_at AS resolved_at, wa.is_favorite AS favorite,
               v.status AS status, v.mastery_level AS mastery_level, v.due_at AS due_at,
               v.last_reviewed_at AS last_reviewed_at, v.last_answer AS last_answer
        FROM wrong_answer wa
        JOIN word_with_progress v ON v.word_id = wa.word_id
        WHERE wa.plan_id = :planId
          AND (:includeResolved = 1 OR wa.resolved_at IS NULL)
          AND (:favoriteOnly = 0 OR wa.is_favorite = 1)
          AND (:minWrongCount <= 0 OR wa.wrong_count >= :minWrongCount)
          AND (:sinceMillis <= 0 OR wa.last_wrong_at >= :sinceMillis)
          AND (:search IS NULL OR :search = '' OR v.word LIKE :like ESCAPE '!' OR v.meanings LIKE :like ESCAPE '!')
        ORDER BY wa.last_wrong_at DESC, wa.wrong_count DESC
        """,
    )
    fun observeMistakes(
        planId: String,
        includeResolved: Int,
        favoriteOnly: Int,
        minWrongCount: Int,
        sinceMillis: Long,
        search: String?,
        like: String,
    ): Flow<List<WrongAnswerRow>>

    /** Same filter set ordered by mistake count — the "高错误次数" tab. */
    @Query(
        """
        SELECT wa.word_id AS word_id, v.word AS word, v.phonetic AS phonetic,
               v.part_of_speech AS part_of_speech, v.meanings AS meanings,
               v.example_sentence AS example_sentence, v.level AS level,
               wa.pack_id AS pack_id, wa.plan_id AS plan_id,
               wa.wrong_count AS wrong_count, wa.fuzzy_count AS fuzzy_count,
               wa.first_wrong_at AS first_wrong_at, wa.last_wrong_at AS last_wrong_at,
               wa.last_source AS last_source, wa.correct_since_wrong AS correct_since_wrong,
               wa.resolved_at AS resolved_at, wa.is_favorite AS favorite,
               v.status AS status, v.mastery_level AS mastery_level, v.due_at AS due_at,
               v.last_reviewed_at AS last_reviewed_at, v.last_answer AS last_answer
        FROM wrong_answer wa
        JOIN word_with_progress v ON v.word_id = wa.word_id
        WHERE wa.plan_id = :planId
          AND (:includeResolved = 1 OR wa.resolved_at IS NULL)
          AND (:favoriteOnly = 0 OR wa.is_favorite = 1)
          AND (:minWrongCount <= 0 OR wa.wrong_count >= :minWrongCount)
          AND (:sinceMillis <= 0 OR wa.last_wrong_at >= :sinceMillis)
          AND (:search IS NULL OR :search = '' OR v.word LIKE :like ESCAPE '!' OR v.meanings LIKE :like ESCAPE '!')
        ORDER BY wa.wrong_count DESC, wa.last_wrong_at DESC
        """,
    )
    fun observeMistakesByCount(
        planId: String,
        includeResolved: Int,
        favoriteOnly: Int,
        minWrongCount: Int,
        sinceMillis: Long,
        search: String?,
        like: String,
    ): Flow<List<WrongAnswerRow>>

    /** Ids of the current filter selection, used by batch actions such as 批量重新练习. */
    @Query(
        """
        SELECT wa.word_id FROM wrong_answer wa
        JOIN word_with_progress v ON v.word_id = wa.word_id
        WHERE wa.plan_id = :planId
          AND (:includeResolved = 1 OR wa.resolved_at IS NULL)
          AND (:favoriteOnly = 0 OR wa.is_favorite = 1)
          AND (:minWrongCount <= 0 OR wa.wrong_count >= :minWrongCount)
          AND (:sinceMillis <= 0 OR wa.last_wrong_at >= :sinceMillis)
          AND (:search IS NULL OR :search = '' OR v.word LIKE :like ESCAPE '!' OR v.meanings LIKE :like ESCAPE '!')
        ORDER BY wa.last_wrong_at DESC
        """,
    )
    suspend fun mistakeIdsForFilter(
        planId: String,
        includeResolved: Int,
        favoriteOnly: Int,
        minWrongCount: Int,
        sinceMillis: Long,
        search: String?,
        like: String,
    ): List<Long>

    /** Active mistakes of a plan, de-duplicated through the canonical view (D2). */
    @Query(
        """
        SELECT COUNT(*) FROM wrong_answer wa
        JOIN word_with_progress v ON v.word_id = wa.word_id
        WHERE wa.plan_id = :planId AND wa.resolved_at IS NULL
        """,
    )
    fun observeActiveCount(planId: String): Flow<Int>

    /** Every record ever created for this plan, resolved or not (the 历史错题 counter). */
    @Query(
        """
        SELECT COUNT(*) FROM wrong_answer wa
        JOIN word_with_progress v ON v.word_id = wa.word_id
        WHERE wa.plan_id = :planId
        """,
    )
    fun observeTotalCount(planId: String): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM wrong_answer wa
        JOIN word_with_progress v ON v.word_id = wa.word_id
        WHERE wa.plan_id = :planId AND wa.resolved_at IS NULL
        """,
    )
    suspend fun activeCount(planId: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM wrong_answer wa
        JOIN word_with_progress v ON v.word_id = wa.word_id
        WHERE wa.plan_id = :planId AND wa.resolved_at IS NULL AND wa.wrong_count >= :minWrongCount
        """,
    )
    fun observeHighWrongCount(planId: String, minWrongCount: Int): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM wrong_answer wa
        JOIN word_with_progress v ON v.word_id = wa.word_id
        WHERE wa.plan_id = :planId AND wa.resolved_at IS NULL AND wa.last_wrong_at >= :since
        """,
    )
    fun observeRecentlyWrong(planId: String, since: Long): Flow<Int>
}
