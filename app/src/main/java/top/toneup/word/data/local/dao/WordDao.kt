package top.toneup.word.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import top.toneup.word.data.local.entity.WordEntity
import top.toneup.word.data.local.entity.WordPackEntity
import kotlinx.coroutines.flow.Flow

/**
 * Vocabulary access.
 *
 * Every statement uses bound parameters; no SQL is ever assembled from user input, which is
 * what keeps file imports safe. `LIKE` searches escape the user's wildcards explicitly.
 *
 * Two scopes coexist since schema v4:
 *  * **plan** — the study scope (home, review, exams, statistics). Plan-scoped queries read
 *    `word_with_progress`, whose canonical-row de-dup collapses a word shared by several packs
 *    inside one plan into a single row (D2).
 *  * **pack** — the content/management unit (import, re-import identity, delete). A word still
 *    belongs to exactly one pack physically, identity is `(pack_id, normalized_word)`.
 *
 * The `level` column remains pack metadata only — it never scopes a query.
 */
@Dao
interface WordDao {

    // --- Packs ---------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPack(pack: WordPackEntity)

    /** Packs of one plan; user content first (that is what the learner added), built-ins last. */
    @Query("SELECT * FROM word_pack WHERE plan_id = :planId ORDER BY is_built_in ASC, name ASC")
    fun observePacks(planId: String): Flow<List<WordPackEntity>>

    /** Every pack, plan-agnostic (backup, clear-all, migration maintenance). */
    @Query("SELECT * FROM word_pack ORDER BY is_built_in ASC, name ASC")
    fun observeAllPacks(): Flow<List<WordPackEntity>>

    @Query("SELECT * FROM word_pack WHERE id = :id")
    suspend fun packById(id: String): WordPackEntity?

    @Query("UPDATE word_pack SET word_count = :count, updated_at = :now WHERE id = :packId")
    suspend fun updatePackWordCount(packId: String, count: Int, now: Long)

    @Query("SELECT COUNT(*) FROM word_pack WHERE is_built_in = 0")
    suspend fun importedPackCount(): Int

    /** Moves one pack into another plan (D9); the words follow in the same transaction. */
    @Query("UPDATE word_pack SET plan_id = :planId, updated_at = :now WHERE id = :packId")
    suspend fun updatePackPlan(packId: String, planId: String, now: Long)

    /** Moves every word row of a pack, so the de-dup subquery sees the new scope at once. */
    @Query("UPDATE word SET plan_id = :planId, updated_at = :now WHERE pack_id = :packId")
    suspend fun updateWordsPlan(packId: String, planId: String, now: Long)

    // --- Words ---------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(word: WordEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(words: List<WordEntity>): List<Long>

    @Update
    suspend fun update(word: WordEntity)

    /**
     * Batched update used by the built-in pack upgrade path.
     *
     * `@Update` writes by primary key, so a word that the learner already has keeps its row id —
     * and with it every progress row, review event, mistake record and exam snapshot that points
     * at it. `@Insert(onConflict = REPLACE)` would delete and re-insert the row and silently
     * destroy all of that, which is why it is never used here.
     */
    @Update
    suspend fun updateAll(words: List<WordEntity>)

    /**
     * Identity map of one pack: `normalized_word -> id`.
     *
     * Read once before a pack upgrade so the seeding loop can decide insert-vs-update for
     * thousands of words without a per-word query.
     */
    @Query("SELECT id, normalized_word FROM word WHERE pack_id = :packId")
    suspend fun idByNormalized(packId: String): List<WordIdKey>

    /** Search a plan's vocabulary by word or meaning (used by the word browser). */
    @Query(
        """
        SELECT * FROM word_with_progress
        WHERE plan_id = :planId
          AND (:search = '' OR word LIKE :like ESCAPE '!' OR meanings LIKE :like ESCAPE '!')
        ORDER BY CASE WHEN word LIKE :prefix ESCAPE '!' THEN 0 ELSE 1 END, word ASC
        LIMIT :limit
        """,
    )
    suspend fun search(planId: String, search: String, like: String, prefix: String, limit: Int): List<WordWithProgressView>

    /**
     * Candidate rows for the approximate ("你是不是想找…") search.
     *
     * The band is deliberately coarse — only a length window, ordered so the result never depends
     * on row order — because the real filtering is the edit distance computed in
     * `ApproximateWordMatcher`. A `LIKE` cannot do that job: it cannot ask for "within two edits".
     * The window is bounded by [limit], and a typical pack has far fewer words in any one window.
     */
    @Query(
        """
        SELECT * FROM word_with_progress
        WHERE plan_id = :planId AND length(word) BETWEEN :minLength AND :maxLength
        ORDER BY word ASC
        LIMIT :limit
        """,
    )
    suspend fun byWordLengthBand(
        planId: String,
        minLength: Int,
        maxLength: Int,
        limit: Int,
    ): List<WordWithProgressView>

    @Query("SELECT * FROM word WHERE id = :id")
    suspend fun byId(id: Long): WordEntity?

    @Query("SELECT * FROM word WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<WordEntity>

    /** Pack-scoped identity lookup (D2): the same word may exist in other packs. */
    @Query("SELECT * FROM word WHERE pack_id = :packId AND normalized_word = :normalized LIMIT 1")
    suspend fun byNormalized(packId: String, normalized: String): WordEntity?

    /**
     * Level-scoped fallback used only when restoring a backup written before schema v2:
     * such rows can carry a pack id the current database does not know (`restored-*`), so the
     * legacy `(level, normalized)` identity is the only handle that still matches.
     */
    @Query("SELECT * FROM word WHERE level = :level AND normalized_word = :normalized LIMIT 1")
    suspend fun byNormalizedInLevel(level: String, normalized: String): WordEntity?

    @Query("SELECT * FROM word WHERE pack_id = :packId AND normalized_word IN (:normalizedWords)")
    suspend fun byNormalizedWords(packId: String, normalizedWords: List<String>): List<WordEntity>

    /**
     * The row the learner actually sees for this word inside the plan (D2/D8): the one that
     * already owns a progress row, else the smallest id — the same rule the view applies.
     */
    @Query(
        """
        SELECT * FROM word
        WHERE plan_id = :planId AND normalized_word = :normalized
        ORDER BY (EXISTS(SELECT 1 FROM user_word_progress p WHERE p.word_id = word.id)) DESC, id ASC
        LIMIT 1
        """,
    )
    suspend fun canonicalInPlan(planId: String, normalized: String): WordEntity?

    /** Distinct words a plan studies — read from the canonical view, so duplicates collapse. */
    @Query("SELECT COUNT(*) FROM word_with_progress WHERE plan_id = :planId")
    fun observePlanWordCount(planId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM word_with_progress WHERE plan_id = :planId")
    suspend fun planWordCount(planId: String): Int

    @Query("SELECT COUNT(*) FROM word WHERE pack_id = :packId")
    suspend fun countInPack(packId: String): Int

    @Query("SELECT id FROM word WHERE pack_id = :packId ORDER BY id ASC")
    suspend fun idsForPack(packId: String): List<Long>

    @Query("SELECT * FROM word WHERE pack_id = :packId ORDER BY id ASC")
    suspend fun allForPack(packId: String): List<WordEntity>

    /** Canonical word ids of a plan, oldest row first (stable order, reproducible tests). */
    @Query("SELECT word_id FROM word_with_progress WHERE plan_id = :planId ORDER BY word_id ASC")
    suspend fun idsForPlan(planId: String): List<Long>

    /** Canonical rows of a plan (view rows, progress joined) — used by exports. */
    @Query("SELECT * FROM word_with_progress WHERE plan_id = :planId ORDER BY word_id ASC")
    suspend fun allForPlan(planId: String): List<WordWithProgressView>

    /**
     * Pack scopes present in `word` (NULL/'' filtered: they cannot be a scope), plan included.
     *
     * The full backup uses this as the union partner of [observeAllPacks]: historical rows can
     * carry a pack id without a `word_pack` row (`legacy-other`, `restored-*`).
     */
    @Query(
        """
        SELECT DISTINCT pack_id, plan_id FROM word
        WHERE pack_id IS NOT NULL AND pack_id != ''
        ORDER BY pack_id ASC, plan_id ASC
        """,
    )
    suspend fun packScopes(): List<WordPackScopeRow>

    /**
     * Cheap "is there anything to work with" probe used to distinguish an empty state
     * (no words imported yet) from a genuinely finished day.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM word_with_progress WHERE plan_id = :planId LIMIT 1)")
    suspend fun hasWordsInPlan(planId: String): Boolean

    /** Word rows for the exam generator, de-duplicated inside the plan (schema v4). */
    @Query(
        """
        SELECT word_id AS id, word, phonetic, part_of_speech, meanings, example_sentence, level
        FROM word_with_progress WHERE plan_id = :planId ORDER BY word_id ASC
        """,
    )
    suspend fun liteForPlan(planId: String): List<WordLiteRow>

    @Query("DELETE FROM word WHERE pack_id = :packId")
    suspend fun deleteForPack(packId: String)

    @Query("DELETE FROM word")
    suspend fun deleteAll()

    /**
     * Words of this pack that still have a same-named sibling inside the pack's plan, with the
     * sibling they should hand their progress to (D10). Used by `deletePack` so deleting a pack
     * never turns a studied word back into an unstudied one.
     */
    @Query(
        """
        SELECT w.id AS source_id, c.id AS target_id
        FROM word w
        INNER JOIN word c
          ON c.plan_id = w.plan_id AND c.normalized_word = w.normalized_word AND c.pack_id != w.pack_id
        WHERE w.pack_id = :packId AND w.id != c.id
        ORDER BY w.id ASC, c.id ASC
        """,
    )
    suspend fun duplicateSiblingIds(packId: String): List<WordIdPair>

    // === M4 gateway additions (MeaningSyncWorker / MeaningSyncRepository) =================
    // Appended in one block at the tail of the file to keep parallel lanes from clashing.
    // Do not renumber or reorder the queries above this line.

    /**
     * Work queue of one meaning-sync run (plan §7.3): every `PENDING` row, plus `FAILED` rows
     * that still have attempts left, minus anything the learner edited by hand.
     * Ordered by id so a batch is stable across runs.
     */
    @Query(
        """
        SELECT id FROM word
        WHERE (lookup_state = 'PENDING' OR (lookup_state = 'FAILED' AND lookup_attempts < :maxAttempts))
          AND meaning_source != 'manual'
        ORDER BY id ASC
        LIMIT :limit
        """,
    )
    suspend fun idsNeedingLookup(limit: Int, maxAttempts: Int): List<Long>

    /** Live "words still waiting for the gateway" counter for the settings screen (D5 trigger). */
    @Query(
        """
        SELECT COUNT(*) FROM word
        WHERE (lookup_state = 'PENDING' OR (lookup_state = 'FAILED' AND lookup_attempts < :maxAttempts))
          AND meaning_source != 'manual'
        """,
    )
    fun observeNeedingLookupCount(maxAttempts: Int): Flow<Int>

    /**
     * Records a failed attempt without touching hand-edited rows: `state` is `FAILED` for a
     * completed cascade that found nothing, and stays `PENDING` for a transport error so the
     * next run tries again (attempts still climb, which is what bounds `FAILED` retries).
     */
    @Query(
        """
        UPDATE word SET lookup_state = :state, lookup_attempts = lookup_attempts + 1, last_lookup_at = :now
        WHERE id = :id AND meaning_source != 'manual'
        """,
    )
    suspend fun recordLookupFailure(id: Long, state: String, now: Long)

    // === M2/M3 pack-management append =========================================================
    // Added by the pack-management lane; appended at the tail to avoid clashing with the
    // M4 block above. No existing method can remove a `word_pack` row, and nothing declares
    // a foreign key *to* `word_pack`, so the caller deletes the pack's words first and this
    // row last, inside one transaction (see `WordRepository.deletePack`).

    /** Removes a pack row. Callers must have deleted the pack's words already. */
    @Query("DELETE FROM word_pack WHERE id = :packId")
    suspend fun deletePackRow(packId: String)

    // === M6a card editing append (notes / hand-edited meanings, plan §9) ====================
    // Appended at the tail like the blocks above. Partial column updates only: an editor must
    // never rewrite the whole row, because a concurrent gateway write-back or a seeding pass
    // may own the other columns. Never `Insert(REPLACE)` on `word`.

    /** Writes the learner's note; touches `user_note` and `updated_at` only. */
    @Query("UPDATE word SET user_note = :note, updated_at = :now WHERE id = :id")
    suspend fun updateUserNote(id: Long, note: String, now: Long)

    /**
     * Stores a hand-edited meaning set as the terminal state (D4): `meaning_source='manual'`
     * makes every automated write-back skip this row from now on (see [idsNeedingLookup],
     * [recordLookupFailure] and `MeaningSyncRepository.applyLookupToWord`), and
     * `lookup_state='OK'` marks the row complete so no worker picks it up either.
     */
    @Query(
        """
        UPDATE word SET meanings = :meanings, meaning_source = 'manual', lookup_state = 'OK', updated_at = :now
        WHERE id = :id
        """,
    )
    suspend fun updateManualMeaning(id: Long, meanings: String, now: Long)
}
