package top.toneup.word.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
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
import java.io.File

/**
 * Hand-rolled 1→2 migration test (plan §13-R1).
 *
 * Builds a real v1 database from the exported `1.json` create SQL (loaded from `app/schemas`
 * on disk — AGP 9 does not merge test-source-set assets into the Robolectric asset root),
 * seeds a learner's history, then opens the file with Room and [Migrations.ALL_MIGRATIONS].
 * Room's generated `validateMigration` runs on open and fails loudly if the migrated schema
 * does not match the v2 entity definitions, so this doubles as a schema validation.
 *
 * Contract under test:
 *  * word ids are preserved and `pack_id` is backfilled from `level`;
 *  * gateway columns exist with the right lookup-state backfill;
 *  * review events / mistakes inherit their word's pack;
 *  * sessions and daily rollups map `level` → reserved pack id;
 *  * the new unique identity `(pack_id, normalized_word)` is enforced.
 *
 * SQLite 3.18 (API 26) path, same as the other database tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class Migration1To2Test {

    private val testDb = "migration-test.db"
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

    // --- v1 schema from the exported snapshot ---------------------------------------------

    private fun schemaJson(): JSONObject {
        val file = listOf(
            File("schemas/$DB_CLASS/1.json"),
            File("app/schemas/$DB_CLASS/1.json"),
        ).firstOrNull { it.exists() }
            ?: error("1.json not found; run the test from the module or repo root")
        return JSONObject(file.readText(Charsets.UTF_8)).getJSONObject("database")
    }

    /** CREATE statements of every v1 table, index and view, with `${TABLE_NAME}` substituted. */
    private fun v1CreateSql(db: JSONObject): List<String> {
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

    /** Creates the v1 database file exactly as a fresh install at version 1 would, then seeds it. */
    private fun createAndSeedV1Database() {
        val schema = schemaJson()
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(testDb)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    v1CreateSql(schema).forEach(db::execSQL)
                    val setup = schema.getJSONArray("setupQueries")
                    for (i in 0 until setup.length()) {
                        db.execSQL(setup.getString(i))
                    }
                    seedV1(db)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    error("the v1 database must be created, not upgraded (got $oldVersion→$newVersion)")
                }
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { it.writableDatabase }
    }

    /** Opens the v1 file with Room; the first query runs [Migrations.ALL_MIGRATIONS]. */
    private fun openMigrated(): ToneUpWordDatabase =
        Room.databaseBuilder(context, ToneUpWordDatabase::class.java, testDb)
            .addMigrations(*Migrations.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    // --- seed ------------------------------------------------------------------------------

    /** Seeds a v1 database with CET-4 / CET-6 / legacy-level words and one of each data row. */
    private fun seedV1(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO word (id, pack_id, word, normalized_word, phonetic, part_of_speech, meanings, " +
                "example_sentence, example_translation, collocations, level, source, license, " +
                "created_at, updated_at) " +
                "VALUES (1, NULL, 'apple', 'apple', '/apple/', 'n.', '[\"苹果\"]', " +
                "'An apple a day.', '每天一个苹果。', '[]', 'CET4', 'seed', 'CC0', 100, 100)",
        )
        db.execSQL(
            "INSERT INTO word (id, pack_id, word, normalized_word, phonetic, part_of_speech, meanings, " +
                "example_sentence, example_translation, collocations, level, source, license, " +
                "created_at, updated_at) " +
                "VALUES (2, NULL, 'banana', 'banana', '', '', '[]', '', '', '[]', 'CET6', 'seed', 'CC0', 100, 100)",
        )
        db.execSQL(
            "INSERT INTO word (id, pack_id, word, normalized_word, phonetic, part_of_speech, meanings, " +
                "example_sentence, example_translation, collocations, level, source, license, " +
                "created_at, updated_at) " +
                "VALUES (3, NULL, 'custom', 'custom', '', '', '[\"自定义\"]', '', '', '[]', 'OTHER', 'seed', 'CC0', 100, 100)",
        )
        db.execSQL(
            "INSERT INTO review_event (id, word_id, session_id, grade, source, answered_at, day_key, " +
                "level, previous_interval_days, new_interval_days, response_time_ms) " +
                "VALUES (1, 1, NULL, 'UNKNOWN', 'STUDY', 200, '2026-01-15', 'CET4', 0, 0, NULL)",
        )
        db.execSQL(
            "INSERT INTO wrong_answer (word_id, wrong_count, fuzzy_count, first_wrong_at, last_wrong_at, " +
                "last_source, correct_since_wrong, resolved_at, is_favorite, level, updated_at) " +
                "VALUES (1, 2, 0, 200, 200, 'UNKNOWN', 0, NULL, 0, 'CET4', 200)",
        )
        db.execSQL(
            "INSERT INTO study_session (id, level, status, started_at, updated_at, ended_at, " +
                "planned_new_count, planned_review_count, queue, queue_position, immediate_queue, " +
                "immediate_repeat_counts, answered_count, mastered_count, fuzzy_count, unknown_count, " +
                "new_words_seen, review_words_seen) " +
                "VALUES (1, 'CET4', 'ACTIVE', 300, 300, NULL, 10, 0, '[]', 0, '[]', '{}', 0, 0, 0, 0, 0, 0)",
        )
        db.execSQL(
            "INSERT INTO exam_session (id, level, status, created_at, started_at, submitted_at, " +
                "question_count, duration_minutes, remaining_seconds, score, correct_count, " +
                "accuracy_percent, time_spent_ms, type_breakdown, seed) " +
                "VALUES (1, 'CET6', 'SUBMITTED', 400, 400, 500, 20, 15, 0, 80, 16, 80, 100, NULL, 42)",
        )
        db.execSQL(
            "INSERT INTO daily_study_summary (day_key, level, studied_words, new_words, review_words, " +
                "mastered_count, fuzzy_count, unknown_count, review_events, study_duration_ms, " +
                "sessions_completed, exams_completed, exam_correct, exam_total, updated_at) " +
                "VALUES ('2026-01-15', 'CET4', 3, 2, 1, 1, 0, 2, 3, 60000, 1, 0, 0, 0, 500)",
        )
        db.execSQL(
            "INSERT INTO daily_study_summary (day_key, level, studied_words, new_words, review_words, " +
                "mastered_count, fuzzy_count, unknown_count, review_events, study_duration_ms, " +
                "sessions_completed, exams_completed, exam_correct, exam_total, updated_at) " +
                "VALUES ('2026-01-15', 'MISC', 1, 1, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 500)",
        )
    }

    /** Creates the v1 database, seeds it, closes it, and reopens it through Room+migrations. */
    private fun migrateSeededV1(): ToneUpWordDatabase {
        createAndSeedV1Database()
        val migrated = openMigrated()
        // Forces the migration to run now so a failure surfaces inside the test, not later.
        migrated.openHelper.writableDatabase
        return migrated
    }

    // --- assertions ------------------------------------------------------------------------

    private fun queryLong(db: SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            cursor.getLong(0)
        }

    private fun queryString(db: SupportSQLiteDatabase, sql: String): String? =
        db.query(sql).use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getString(0)
        }

    @Test
    fun `migration 1 to 2 preserves words and backfills pack ids from level`() {
        val database = migrateSeededV1()
        try {
            val db = database.openHelper.writableDatabase

            assertThat(queryLong(db, "SELECT id FROM word WHERE normalized_word = 'apple'")).isEqualTo(1L)
            assertThat(queryString(db, "SELECT pack_id FROM word WHERE id = 1")).isEqualTo("builtin-cet4")
            assertThat(queryString(db, "SELECT pack_id FROM word WHERE id = 2")).isEqualTo("builtin-cet6")
            assertThat(queryString(db, "SELECT pack_id FROM word WHERE id = 3")).isEqualTo("legacy-other")

            // Gateway columns exist and the lookup state is backfilled from content presence.
            assertThat(queryString(db, "SELECT lookup_state FROM word WHERE id = 1")).isEqualTo("OK")
            assertThat(queryString(db, "SELECT meaning_source FROM word WHERE id = 1")).isEqualTo("file")
            assertThat(queryString(db, "SELECT lookup_state FROM word WHERE id = 2")).isEqualTo("PENDING")
            assertThat(queryString(db, "SELECT meaning_source FROM word WHERE id = 2")).isEqualTo("")
            assertThat(queryLong(db, "SELECT lookup_attempts FROM word WHERE id = 1")).isEqualTo(0L)
            assertThat(queryLong(db, "SELECT last_lookup_at FROM word WHERE id = 1")).isEqualTo(0L)
            assertThat(queryString(db, "SELECT user_note FROM word WHERE id = 1")).isEqualTo("")
            assertThat(queryString(db, "SELECT en_definition FROM word WHERE id = 1")).isEqualTo("")
            assertThat(queryString(db, "SELECT audio_url FROM word WHERE id = 1")).isEqualTo("")

            assertThat(db.version).isEqualTo(Migrations.CURRENT_VERSION)
        } finally {
            database.close()
        }
    }

    @Test
    fun `migration 1 to 2 moves user data to the pack of its word`() {
        val database = migrateSeededV1()
        try {
            val db = database.openHelper.writableDatabase

            // Review events and mistakes inherit the word's pack.
            assertThat(queryString(db, "SELECT pack_id FROM review_event WHERE id = 1"))
                .isEqualTo("builtin-cet4")
            assertThat(queryString(db, "SELECT pack_id FROM wrong_answer WHERE word_id = 1"))
                .isEqualTo("builtin-cet4")

            // Sessions keep their mapped pack. The rollups end up in one plan bucket: the whole
            // chain runs 1 → 4 here, and 3 → 4 sums the per-pack rows of a day into `plan-default`.
            assertThat(queryString(db, "SELECT pack_id FROM study_session WHERE id = 1"))
                .isEqualTo("builtin-cet4")
            assertThat(queryString(db, "SELECT pack_id FROM exam_session WHERE id = 1"))
                .isEqualTo("builtin-cet6")
            assertThat(queryLong(db, "SELECT COUNT(*) FROM daily_study_summary")).isEqualTo(1L)
            assertThat(queryString(db, "SELECT plan_id FROM daily_study_summary"))
                .isEqualTo("plan-default")
            // Counts are carried over untouched (3 + 1 studied words of the two v1 rows).
            assertThat(queryLong(db, "SELECT studied_words FROM daily_study_summary"))
                .isEqualTo(4L)
        } finally {
            database.close()
        }
    }

    @Test
    fun `migration 1 to 2 installs the new unique identity and the meaning cache`() {
        val database = migrateSeededV1()
        try {
            val db = database.openHelper.writableDatabase

            // The old (level, normalized) unique index is gone; the new one is enforced.
            val duplicate = runCatching {
                db.execSQL(
                    "INSERT INTO word (id, pack_id, word, normalized_word, phonetic, part_of_speech, " +
                        "meanings, example_sentence, example_translation, collocations, level, source, " +
                        "license, created_at, updated_at) " +
                        "VALUES (4, 'builtin-cet4', 'Apple', 'apple', '', '', '[]', '', '', '[]', 'CET4', " +
                        "'x', 'CC0', 1, 1)",
                )
            }.exceptionOrNull()
            assertThat(duplicate).isNotNull()

            // …but the same spelling may exist in another pack now.
            db.execSQL(
                "INSERT INTO word (id, pack_id, word, normalized_word, phonetic, part_of_speech, " +
                    "meanings, example_sentence, example_translation, collocations, level, source, " +
                    "license, created_at, updated_at) " +
                    "VALUES (5, 'builtin-cet6', 'apple', 'apple', '', '', '[]', '', '', '[]', 'CET6', " +
                    "'x', 'CC0', 1, 1)",
            )
            assertThat(queryLong(db, "SELECT COUNT(*) FROM word WHERE normalized_word = 'apple'"))
                .isEqualTo(2L)

            // meaning_cache exists and accepts a row.
            db.execSQL(
                "INSERT INTO meaning_cache (normalized_word, payload, source, status, fetched_at) " +
                    "VALUES ('apple', '{}', 'ecdict', 'OK', 1)",
            )
            assertThat(queryLong(db, "SELECT COUNT(*) FROM meaning_cache")).isEqualTo(1L)
        } finally {
            database.close()
        }
    }

    private companion object {
        const val DB_CLASS = "top.toneup.word.data.local.ToneUpWordDatabase"
    }
}
