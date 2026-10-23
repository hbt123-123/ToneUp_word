package top.toneup.word.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.toneup.word.data.local.Migrations
import top.toneup.word.data.local.ToneUpWordDatabase
import top.toneup.word.data.settings.PlanIds
import java.io.File

/**
 * Hand-rolled 3→4 migration test (plan hierarchy, D3).
 *
 * Same shape as [Migration2To3Test]: a real v3 database is built from the exported `3.json`
 * create SQL, seeded with a learner's history, then reopened through Room with
 * [Migrations.ALL_MIGRATIONS]. Room's generated `validateMigration` runs on open, so any
 * column, index or view mismatch between [Migrations.MIGRATION_3_4] and the v4 entities fails
 * the test immediately.
 *
 * Contract under test:
 *  * `plan` exists with the built-in `plan-default` row;
 *  * every pre-upgrade pack, word, event, mistake and session lands in the default plan (D3),
 *    so the learner's study scope is unchanged;
 *  * the per-pack daily summaries of one day are summed into one default-plan bucket;
 *  * `word_with_progress` projects a word shared by two packs only once, and picks the copy
 *    that owns the progress row (D2).
 *
 * SQLite 3.18 (API 26) path, same as the other database tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class Migration3To4Test {

    private val testDb = "migration-3-4-test.db"
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(testDb)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(testDb)
    }

    // --- v3 schema from the exported snapshot ---------------------------------------------

    private fun schemaJson(): JSONObject {
        val file = listOf(
            File("schemas/$DB_CLASS/3.json"),
            File("app/schemas/$DB_CLASS/3.json"),
        ).firstOrNull { it.exists() }
            ?: error("3.json not found; run the test from the module or repo root")
        return JSONObject(file.readText(Charsets.UTF_8)).getJSONObject("database")
    }

    /** CREATE statements of every v3 table, index and view, with the placeholders substituted. */
    private fun v3CreateSql(db: JSONObject): List<String> {
        val statements = mutableListOf<String>()
        val entities = db.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            statements += entity.getString("createSql")
                .replace("\${TABLE_NAME}", entity.getString("tableName"))
            val indices = entity.optJSONArray("indices") ?: JSONArray()
            for (j in 0 until indices.length()) {
                statements += indices.getJSONObject(j).getString("createSql")
                    .replace("\${TABLE_NAME}", entity.getString("tableName"))
            }
        }
        val views = db.optJSONArray("views") ?: JSONArray()
        for (i in 0 until views.length()) {
            val view = views.getJSONObject(i)
            statements += view.getString("createSql")
                .replace("\${VIEW_NAME}", view.getString("viewName"))
        }
        return statements
    }

    /** Creates the v3 database file exactly as a fresh install at version 3 would, then seeds it. */
    private fun createAndSeedV3Database() {
        val schema = schemaJson()
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(testDb)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    v3CreateSql(schema).forEach(db::execSQL)
                    val setup = schema.getJSONArray("setupQueries")
                    for (i in 0 until setup.length()) {
                        db.execSQL(setup.getString(i))
                    }
                    seedV3(db)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    error("the v3 database must be created, not upgraded (got $oldVersion→$newVersion)")
                }
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { it.writableDatabase }
    }

    /** Opens the v3 file with Room; the first query runs [Migrations.ALL_MIGRATIONS]. */
    private fun openMigrated(): ToneUpWordDatabase =
        Room.databaseBuilder(context, ToneUpWordDatabase::class.java, testDb)
            .addMigrations(*Migrations.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    // --- seed ------------------------------------------------------------------------------

    /**
     * A v3 snapshot with history in two packs: `apple` lives in both (one copy carries the
     * progress row), and the same day has a per-pack summary row for each pack.
     */
    private fun seedV3(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO word_pack (id, name, level, description, source, license, version, " +
                "is_built_in, word_count, created_at, updated_at) VALUES " +
                "('builtin-cet4', '四级演示词库', 'CET4', '', 'seed', 'CC0', '1', 1, 1, 100, 100), " +
                "('builtin-cet6', '六级演示词库', 'CET6', '', 'seed', 'CC0', '1', 1, 2, 100, 100), " +
                "('user-1', '我的词库', 'OTHER', '', 'user', '', '1', 0, 0, 100, 100)",
        )
        val wordColumns =
            "INSERT INTO word (id, pack_id, external_id, word, normalized_word, phonetic, " +
                "part_of_speech, meanings, example_sentence, example_translation, collocations, " +
                "level, source, license, audio_uri, created_at, updated_at, user_note, " +
                "lookup_state, meaning_source, en_definition, audio_url, lookup_attempts, " +
                "last_lookup_at) VALUES "
        db.execSQL(
            wordColumns +
                "(1, 'builtin-cet4', NULL, 'apple', 'apple', '/apple/', 'n.', '[\"苹果\"]', '', " +
                "'', '[]', 'CET4', 'seed', 'CC0', NULL, 100, 100, '', 'OK', 'file', '', '', 0, 0), " +
                "(2, 'builtin-cet6', NULL, 'apple', 'apple', '/apple/', 'n.', '[\"苹果\"]', '', " +
                "'', '[]', 'CET6', 'seed', 'CC0', NULL, 100, 100, '', 'OK', 'file', '', '', 0, 0), " +
                "(3, 'builtin-cet6', NULL, 'banana', 'banana', '', '', '[]', '', '', '[]', 'CET6', " +
                "'seed', 'CC0', NULL, 100, 100, '', 'PENDING', '', '', '', 0, 0)",
        )
        // The progress row belongs to the CET-4 copy: the canonical pick must follow it.
        db.execSQL(
            "INSERT INTO user_word_progress (word_id, status, mastery_level, repetitions, " +
                "interval_days, ease_factor, due_at, last_reviewed_at, last_answer, lapse_count, " +
                "wrong_count, fuzzy_count, consecutive_correct_count, is_favorite, introduced_at, " +
                "updated_at) VALUES (1, 'REVIEW', 2, 1, 3, 2.5, 300, 200, 'UNKNOWN', 0, 1, 0, 0, 1, 100, 300)",
        )
        db.execSQL(
            "INSERT INTO review_event (id, word_id, session_id, grade, source, answered_at, " +
                "day_key, level, previous_interval_days, new_interval_days, response_time_ms, pack_id) " +
                "VALUES (1, 2, NULL, 'UNKNOWN', 'STUDY', 200, '2026-10-20', 'CET6', 0, 1, NULL, 'builtin-cet6')",
        )
        db.execSQL(
            "INSERT INTO wrong_answer (word_id, wrong_count, fuzzy_count, first_wrong_at, " +
                "last_wrong_at, last_source, correct_since_wrong, resolved_at, is_favorite, level, " +
                "updated_at, pack_id) " +
                "VALUES (3, 1, 0, 200, 200, 'STUDY', 0, NULL, 0, 'CET6', 200, 'builtin-cet6')",
        )
        db.execSQL(
            "INSERT INTO study_session (id, level, pack_id, status, started_at, updated_at, " +
                "ended_at, planned_new_count, planned_review_count, queue, queue_position, " +
                "immediate_queue, immediate_repeat_counts, answered_count, mastered_count, " +
                "fuzzy_count, unknown_count, new_words_seen, review_words_seen) VALUES " +
                "(1, 'CET6', 'builtin-cet6', 'ACTIVE', 200, 200, NULL, 1, 0, '[]', 0, '[]', '{}', " +
                "0, 0, 0, 0, 0, 0)",
        )
        db.execSQL(
            "INSERT INTO exam_session (id, level, pack_id, status, created_at, started_at, " +
                "submitted_at, question_count, duration_minutes, remaining_seconds, score, " +
                "correct_count, accuracy_percent, time_spent_ms, type_breakdown, seed) VALUES " +
                "(1, 'OTHER', 'user-1', 'SUBMITTED', 200, 200, 300, 1, 1, 0, 100, 1, 100, 1000, " +
                "'{}', 7)",
        )
        db.execSQL(
            "INSERT INTO daily_study_summary (day_key, pack_id, level, studied_words, new_words, " +
                "review_words, mastered_count, fuzzy_count, unknown_count, review_events, " +
                "study_duration_ms, sessions_completed, exams_completed, exam_correct, exam_total, " +
                "updated_at) VALUES " +
                "('2026-10-20', 'builtin-cet4', 'CET4', 1, 1, 0, 0, 0, 0, 1, 1000, 1, 0, 0, 0, 200), " +
                "('2026-10-20', 'builtin-cet6', 'CET6', 2, 0, 2, 1, 0, 0, 3, 2000, 0, 1, 1, 1, 300)",
        )
    }

    /** Creates the v3 database, seeds it, closes it, and reopens it through Room+migrations. */
    private fun migrateSeededV3(): ToneUpWordDatabase {
        createAndSeedV3Database()
        val migrated = openMigrated()
        // Forces the migration to run now so a failure surfaces inside the test, not later.
        migrated.openHelper.writableDatabase
        return migrated
    }

    // --- assertions ------------------------------------------------------------------------

    private fun queryString(db: SupportSQLiteDatabase, sql: String): String? =
        db.query(sql).use { cursor -> if (!cursor.moveToFirst()) null else cursor.getString(0) }

    private fun queryLong(db: SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            cursor.getLong(0)
        }

    @Test
    fun `migration 3 to 4 moves every row into the default plan`() {
        val database = migrateSeededV3()
        try {
            val db = database.openHelper.writableDatabase

            assertThat(db.version).isEqualTo(Migrations.CURRENT_VERSION)
            assertThat(Migrations.CURRENT_VERSION).isEqualTo(4)

            // 1. The plan exists and is the built-in one.
            assertThat(queryString(db, "SELECT name FROM plan WHERE id = 'plan-default'")).isEqualTo("默认计划")
            assertThat(queryLong(db, "SELECT is_built_in FROM plan WHERE id = 'plan-default'")).isEqualTo(1L)
            assertThat(queryLong(db, "SELECT COUNT(*) FROM plan")).isEqualTo(1L)

            // 2. Packs and words stay in their pack, all inside the default plan.
            assertThat(queryString(db, "SELECT plan_id FROM word_pack WHERE id = 'builtin-cet6'"))
                .isEqualTo(PlanIds.DEFAULT_PLAN)
            assertThat(queryString(db, "SELECT plan_id FROM word WHERE id = 2"))
                .isEqualTo(PlanIds.DEFAULT_PLAN)
            assertThat(queryString(db, "SELECT pack_id FROM word WHERE id = 2")).isEqualTo("builtin-cet6")
            assertThat(queryLong(db, "SELECT COUNT(*) FROM word")).isEqualTo(3L)
            assertThat(queryLong(db, "SELECT COUNT(*) FROM word WHERE plan_id = 'plan-default'")).isEqualTo(3L)

            // 3. Events and mistakes follow their word's plan.
            assertThat(queryString(db, "SELECT plan_id FROM review_event WHERE id = 1"))
                .isEqualTo(PlanIds.DEFAULT_PLAN)
            assertThat(queryString(db, "SELECT pack_id FROM review_event WHERE id = 1"))
                .isEqualTo("builtin-cet6")
            assertThat(queryString(db, "SELECT plan_id FROM wrong_answer WHERE word_id = 3"))
                .isEqualTo(PlanIds.DEFAULT_PLAN)

            // 4. Sessions follow their pack's plan (even for a pack of a user id).
            assertThat(queryString(db, "SELECT plan_id FROM study_session WHERE id = 1"))
                .isEqualTo(PlanIds.DEFAULT_PLAN)
            assertThat(queryString(db, "SELECT plan_id FROM exam_session WHERE id = 1"))
                .isEqualTo(PlanIds.DEFAULT_PLAN)

            // 5. One daily bucket per day: the two per-pack rows are summed.
            assertThat(queryLong(db, "SELECT COUNT(*) FROM daily_study_summary")).isEqualTo(1L)
            assertThat(queryLong(db, "SELECT studied_words FROM daily_study_summary"))
                .isEqualTo(3L)
            assertThat(queryLong(db, "SELECT review_events FROM daily_study_summary")).isEqualTo(4L)
            assertThat(queryLong(db, "SELECT study_duration_ms FROM daily_study_summary"))
                .isEqualTo(3_000L)
            assertThat(queryLong(db, "SELECT exam_total FROM daily_study_summary")).isEqualTo(1L)
            assertThat(queryLong(db, "SELECT updated_at FROM daily_study_summary")).isEqualTo(300L)
            assertThat(queryString(db, "SELECT plan_id FROM daily_study_summary"))
                .isEqualTo(PlanIds.DEFAULT_PLAN)

            // 6. The view projects the shared word once, and through the copy with progress.
            assertThat(queryLong(db, "SELECT COUNT(*) FROM word_with_progress")).isEqualTo(2L)
            assertThat(queryLong(db, "SELECT word_id FROM word_with_progress WHERE word = 'apple'"))
                .isEqualTo(1L)
            assertThat(queryString(db, "SELECT status FROM word_with_progress WHERE word = 'apple'"))
                .isEqualTo("REVIEW")
            assertThat(queryString(db, "SELECT status FROM word_with_progress WHERE word = 'banana'"))
                .isNull()
        } finally {
            database.close()
        }
    }

    @Test
    fun `the plan scoped queries read the migrated history`() {
        val database = migrateSeededV3()
        try {
            runBlocking {
                // One plan-wide pool, de-duplicated by the canonical view.
                assertThat(database.wordDao().planWordCount(PlanIds.DEFAULT_PLAN)).isEqualTo(2)
                assertThat(database.wordDao().hasWordsInPlan(PlanIds.DEFAULT_PLAN)).isTrue()
                assertThat(database.wordDao().hasWordsInPlan("plan-empty")).isFalse()

                // The old per-pack rows are still visible through the plan-scoped counters.
                assertThat(database.reviewEventDao().totalCount(PlanIds.DEFAULT_PLAN)).isEqualTo(1)
                assertThat(database.mistakeBookDao().activeCount(PlanIds.DEFAULT_PLAN)).isEqualTo(1)
                assertThat(
                    database.dailySummaryDao().totals(PlanIds.DEFAULT_PLAN, "2026-10-20").studiedWords,
                ).isEqualTo(3)
            }
        } finally {
            database.close()
        }
    }

    private companion object {
        const val DB_CLASS = "top.toneup.word.data.local.ToneUpWordDatabase"
    }
}
