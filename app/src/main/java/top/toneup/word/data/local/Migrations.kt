package top.toneup.word.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema upgrade policy.
 *
 *  * Destructive migration is **never** used: a learner's history is the most valuable thing
 *    in the app and must survive every upgrade.
 *  * Every schema change ships with a hand written [Migration] and with a schema snapshot in
 *    `app/schemas/`, which the migration test loads to verify the upgrade path on a real
 *    database file.
 *  * Version 1 is the first shipped schema. Version 2 (custom packs gateway, plan §4) adds
 *    the meaning-completion columns on `word`, the `meaning_cache` table and the `pack_id`
 *    dimension on every user-data table. Version 3 (plan §9) exposes the learner-edit columns
 *    (`user_note`, `en_definition`, `lookup_state`, `meaning_source`, `audio_url`) through the
 *    `word_with_progress` view so cards and the word-detail screen can read them.
 *
 * All SQL here must stay within SQLite 3.18 (Android 8.0): no UPSERT clause, no window
 * functions, no RETURNING, no `UPDATE ... FROM`.
 */
object Migrations {

    /** Current schema version, kept in sync with the `@Database(version = ...)` value. */
    const val CURRENT_VERSION: Int = 3

    /** level → reserved pack id, shared by session and summary backfills (D9/Q1). */
    private const val LEVEL_TO_PACK =
        "CASE level WHEN 'CET4' THEN 'builtin-cet4' WHEN 'CET6' THEN 'builtin-cet6' " +
            "ELSE 'legacy-other' END"

    /**
     * v1 → v2: custom packs + meaning-completion gateway (plan §4.1–§4.3).
     *
     * Shape of the upgrade:
     *  1. `word` gains the seven §4.1 gateway columns, existing rows get their lookup state
     *     backfilled (has meanings/example → `OK`+`file`, empty → `PENDING` for the gateway).
     *  2. `word.pack_id` is backfilled so every row belongs to a pack (D2), then the unique
     *     identity moves from `(level, normalized_word)` to `(pack_id, normalized_word)` —
     *     the same word may now live in several packs, each with its own progress.
     *  3. `meaning_cache` is created (§4.2, plan DDL verbatim; the entity declares no extra
     *     index so schema and DDL match exactly).
     *  4. `review_event` / `wrong_answer` / `study_session` / `exam_session` gain `pack_id`;
     *     event/mistake rows inherit it from their word row (FK CASCADE guarantees the word
     *     exists), session rows are mapped from their stored `level`.
     *  5. `daily_study_summary` is rebuilt with the composite primary key
     *     `(day_key, pack_id)`; the `level` column is kept as a migration/display column.
     *
     * Unattributable historical rows go to the reserved id `legacy-other` (D9/Q1): kept, never
     * shown in any pack's statistics.
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // --- §4.1 word: gateway columns ------------------------------------------------
            db.execSQL("ALTER TABLE word ADD COLUMN user_note TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE word ADD COLUMN lookup_state TEXT NOT NULL DEFAULT 'OK'")
            db.execSQL("ALTER TABLE word ADD COLUMN meaning_source TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE word ADD COLUMN en_definition TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE word ADD COLUMN audio_url TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE word ADD COLUMN lookup_attempts INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE word ADD COLUMN last_lookup_at INTEGER NOT NULL DEFAULT 0")
            // Existing rows: meanings+example present → OK/ecdict-file; empty → PENDING.
            db.execSQL(
                "UPDATE word SET lookup_state = " +
                    "CASE WHEN meanings = '[]' AND example_sentence = '' THEN 'PENDING' ELSE 'OK' END",
            )
            db.execSQL("UPDATE word SET meaning_source = CASE WHEN lookup_state = 'OK' THEN 'file' ELSE '' END")
            // D2: guarantee a non-empty pack_id before swapping the unique identity.
            db.execSQL(
                "UPDATE word SET pack_id = " +
                    "CASE level WHEN 'CET4' THEN 'builtin-cet4' WHEN 'CET6' THEN 'builtin-cet6' " +
                    "ELSE 'legacy-other' END " +
                    "WHERE pack_id IS NULL OR pack_id = ''",
            )
            db.execSQL("DROP INDEX index_word_level_normalized_word")
            db.execSQL("CREATE UNIQUE INDEX index_word_pack_normalized_word ON word(pack_id, normalized_word)")
            db.execSQL("CREATE INDEX index_word_normalized ON word(normalized_word)")

            // --- §4.2 meaning_cache --------------------------------------------------------
            db.execSQL(
                "CREATE TABLE meaning_cache (" +
                    "normalized_word TEXT NOT NULL PRIMARY KEY, " +
                    "payload TEXT NOT NULL, " +
                    "source TEXT NOT NULL, " +
                    "status TEXT NOT NULL, " +
                    "fetched_at INTEGER NOT NULL)",
            )

            // --- §4.3 pack_id dimension ----------------------------------------------------
            db.execSQL("ALTER TABLE review_event ADD COLUMN pack_id TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                "UPDATE review_event SET pack_id = " +
                    "COALESCE((SELECT pack_id FROM word WHERE word.id = review_event.word_id), '')",
            )
            db.execSQL("CREATE INDEX index_review_event_pack_id ON review_event(pack_id)")

            db.execSQL("ALTER TABLE wrong_answer ADD COLUMN pack_id TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                "UPDATE wrong_answer SET pack_id = " +
                    "COALESCE((SELECT pack_id FROM word WHERE word.id = wrong_answer.word_id), '')",
            )
            db.execSQL("CREATE INDEX index_wrong_answer_pack_id ON wrong_answer(pack_id)")

            db.execSQL("ALTER TABLE study_session ADD COLUMN pack_id TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE study_session SET pack_id = $LEVEL_TO_PACK")
            db.execSQL("CREATE INDEX index_study_session_pack_id ON study_session(pack_id)")

            db.execSQL("ALTER TABLE exam_session ADD COLUMN pack_id TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE exam_session SET pack_id = $LEVEL_TO_PACK")
            db.execSQL("CREATE INDEX index_exam_session_pack_id ON exam_session(pack_id)")

            // --- §4.3 daily_study_summary: rebuild with PK (day_key, pack_id) --------------
            // Column list mirrors DailyStudySummaryEntity field order; `level` is preserved so
            // old rows remain interpretable (mapping only, never a query scope again).
            db.execSQL(
                "CREATE TABLE daily_study_summary_new (" +
                    "day_key TEXT NOT NULL, " +
                    "pack_id TEXT NOT NULL, " +
                    "level TEXT NOT NULL, " +
                    "studied_words INTEGER NOT NULL, " +
                    "new_words INTEGER NOT NULL, " +
                    "review_words INTEGER NOT NULL, " +
                    "mastered_count INTEGER NOT NULL, " +
                    "fuzzy_count INTEGER NOT NULL, " +
                    "unknown_count INTEGER NOT NULL, " +
                    "review_events INTEGER NOT NULL, " +
                    "study_duration_ms INTEGER NOT NULL, " +
                    "sessions_completed INTEGER NOT NULL, " +
                    "exams_completed INTEGER NOT NULL, " +
                    "exam_correct INTEGER NOT NULL, " +
                    "exam_total INTEGER NOT NULL, " +
                    "updated_at INTEGER NOT NULL, " +
                    "PRIMARY KEY(day_key, pack_id))",
            )
            db.execSQL(
                "INSERT INTO daily_study_summary_new (" +
                    "day_key, pack_id, level, studied_words, new_words, review_words, " +
                    "mastered_count, fuzzy_count, unknown_count, review_events, " +
                    "study_duration_ms, sessions_completed, exams_completed, exam_correct, " +
                    "exam_total, updated_at) " +
                    "SELECT day_key, " +
                    "CASE level WHEN 'CET4' THEN 'builtin-cet4' WHEN 'CET6' THEN 'builtin-cet6' " +
                    "ELSE 'legacy-other' END, " +
                    "level, studied_words, new_words, review_words, " +
                    "mastered_count, fuzzy_count, unknown_count, review_events, " +
                    "study_duration_ms, sessions_completed, exams_completed, exam_correct, " +
                    "exam_total, updated_at " +
                    "FROM daily_study_summary",
            )
            db.execSQL("DROP TABLE daily_study_summary")
            // The old indices (day_key, level) were dropped together with the table.
            db.execSQL("ALTER TABLE daily_study_summary_new RENAME TO daily_study_summary")
            db.execSQL("CREATE INDEX index_daily_study_summary_day_key ON daily_study_summary(day_key)")
            db.execSQL("CREATE INDEX index_daily_study_summary_pack_id ON daily_study_summary(pack_id)")
        }
    }

    /**
     * v2 → v3: expose the learner-edit columns through `word_with_progress` (plan §9).
     *
     * Schema v2 added `user_note` / `en_definition` / `lookup_state` / `meaning_source` /
     * `audio_url` on `word`, but the view the cards and the word-detail screen read still
     * projected the pre-gateway column set, so a note written on a card could not be rendered
     * again. A view holds no data of its own, so the upgrade is one `DROP` + one `CREATE` with
     * exactly the SQL the `@DatabaseView` annotation declares ([WORD_WITH_PROGRESS_VIEW_SQL]) —
     * pure DDL, idempotent, SQLite 3.18 compatible; no row is read or written.
     */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP VIEW IF EXISTS word_with_progress")
            // Room validates the upgraded view against its schema bundle byte-for-byte
            // (backticked name, trimmed annotation body) — keep this string identical to
            // `createSql` in `app/schemas/.../3.json`.
            db.execSQL("CREATE VIEW `word_with_progress` AS " + WORD_WITH_PROGRESS_VIEW_SQL.trim())
        }
    }

    /**
     * The `word_with_progress` definition, statement-for-statement identical to the SQL inside
     * `WordWithProgressView`'s `@DatabaseView` annotation: Room validates the view's column set
     * against the entity after the migration, so the two sources must stay in lockstep.
     */
    private const val WORD_WITH_PROGRESS_VIEW_SQL = """
        SELECT
            w.id                AS word_id,
            w.word              AS word,
            w.phonetic          AS phonetic,
            w.part_of_speech    AS part_of_speech,
            w.meanings          AS meanings,
            w.example_sentence  AS example_sentence,
            w.example_translation AS example_translation,
            w.collocations      AS collocations,
            w.level             AS level,
            w.source            AS source,
            w.license           AS license,
            w.audio_uri         AS audio_uri,
            w.pack_id           AS pack_id,
            w.user_note         AS user_note,
            w.en_definition     AS en_definition,
            w.lookup_state      AS lookup_state,
            w.meaning_source    AS meaning_source,
            w.audio_url         AS audio_url,
            p.status            AS status,
            p.mastery_level     AS mastery_level,
            p.repetitions       AS repetitions,
            p.interval_days     AS interval_days,
            p.ease_factor       AS ease_factor,
            p.due_at            AS due_at,
            p.last_reviewed_at  AS last_reviewed_at,
            p.last_answer       AS last_answer,
            p.lapse_count       AS lapse_count,
            p.wrong_count       AS wrong_count,
            p.fuzzy_count       AS fuzzy_count,
            p.consecutive_correct_count AS consecutive_correct_count,
            p.is_favorite       AS is_favorite,
            p.introduced_at     AS introduced_at
        FROM word w
        LEFT JOIN user_word_progress p ON p.word_id = w.id
    """

    /**
     * All migrations known to this build, oldest first.
     *
     * Declared *after* the individual migrations on purpose: object properties initialise in
     * declaration order, so referencing one above would capture an uninitialised value.
     */
    val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
