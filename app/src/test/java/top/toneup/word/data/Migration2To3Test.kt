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
import java.io.File

/**
 * Hand-rolled 2→3 migration test (plan §9, M6a).
 *
 * Same shape as [Migration1To2Test]: a real v2 database is built from the exported `2.json`
 * create SQL, seeded with a learner's rows, then reopened through Room with
 * [Migrations.ALL_MIGRATIONS]. Room's generated `validateMigration` runs on open, so a view
 * whose column set does not match the v3 entity definition fails the test immediately.
 *
 * Contract under test:
 *  * every v2 row survives untouched (word content, gateway columns, progress join);
 *  * `word_with_progress` exposes the five new columns (`user_note`, `en_definition`,
 *    `lookup_state`, `meaning_source`, `audio_url`) with their column defaults;
 *  * a note and a hand-edited meaning written *after* the upgrade read back through the view;
 *  * `meaning_source='manual'` keeps blocking the automated write-back guards after the
 *    upgrade (`recordLookupFailure`, `idsNeedingLookup`).
 *
 * SQLite 3.18 (API 26) path, same as the other database tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class Migration2To3Test {

    private val testDb = "migration-2-3-test.db"
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

    // --- v2 schema from the exported snapshot ---------------------------------------------

    private fun schemaJson(): JSONObject {
        val file = listOf(
            File("schemas/$DB_CLASS/2.json"),
            File("app/schemas/$DB_CLASS/2.json"),
        ).firstOrNull { it.exists() }
            ?: error("2.json not found; run the test from the module or repo root")
        return JSONObject(file.readText(Charsets.UTF_8)).getJSONObject("database")
    }

    /** CREATE statements of every v2 table, index and view, with the placeholders substituted. */
    private fun v2CreateSql(db: JSONObject): List<String> {
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

    /** Creates the v2 database file exactly as a fresh install at version 2 would, then seeds it. */
    private fun createAndSeedV2Database() {
        val schema = schemaJson()
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(testDb)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    v2CreateSql(schema).forEach(db::execSQL)
                    val setup = schema.getJSONArray("setupQueries")
                    for (i in 0 until setup.length()) {
                        db.execSQL(setup.getString(i))
                    }
                    seedV2(db)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    error("the v2 database must be created, not upgraded (got $oldVersion→$newVersion)")
                }
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { it.writableDatabase }
    }

    /** Opens the v2 file with Room; the first query runs [Migrations.ALL_MIGRATIONS]. */
    private fun openMigrated(): ToneUpWordDatabase =
        Room.databaseBuilder(context, ToneUpWordDatabase::class.java, testDb)
            .addMigrations(*Migrations.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    // --- seed ------------------------------------------------------------------------------

    /**
     * A v2 snapshot: one word with content and a completed lookup, one word still waiting for
     * the gateway, and a progress row so the view's LEFT JOIN is exercised by the upgrade.
     */
    private fun seedV2(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO word (id, pack_id, word, normalized_word, phonetic, part_of_speech, meanings, " +
                "example_sentence, example_translation, collocations, level, source, license, " +
                "created_at, updated_at, user_note, lookup_state, meaning_source, en_definition, " +
                "audio_url, lookup_attempts, last_lookup_at) " +
                "VALUES (1, 'builtin-cet4', 'apple', 'apple', '/apple/', 'n.', '[\"苹果\"]', " +
                "'An apple a day.', '每天一个苹果。', '[]', 'CET4', 'seed', 'CC0', 100, 100, " +
                "'卡片笔记', 'OK', 'file', 'the fruit of the apple tree', " +
                "'https://cdn.example/apple.mp3', 0, 0)",
        )
        db.execSQL(
            "INSERT INTO word (id, pack_id, word, normalized_word, phonetic, part_of_speech, meanings, " +
                "example_sentence, example_translation, collocations, level, source, license, " +
                "created_at, updated_at, user_note, lookup_state, meaning_source, en_definition, " +
                "audio_url, lookup_attempts, last_lookup_at) " +
                "VALUES (2, 'builtin-cet4', 'banana', 'banana', '', '', '[]', '', '', '[]', 'CET4', " +
                "'seed', 'CC0', 100, 100, '', 'PENDING', '', '', '', 0, 0)",
        )
        db.execSQL(
            "INSERT INTO user_word_progress (word_id, status, mastery_level, repetitions, interval_days, " +
                "ease_factor, due_at, last_reviewed_at, last_answer, lapse_count, wrong_count, " +
                "fuzzy_count, consecutive_correct_count, is_favorite, introduced_at, updated_at) " +
                "VALUES (1, 'REVIEW', 2, 1, 3, 2.5, 300, 200, 'UNKNOWN', 0, 1, 0, 0, 1, 100, 300)",
        )
    }

    /** Creates the v2 database, seeds it, closes it, and reopens it through Room+migrations. */
    private fun migrateSeededV2(): ToneUpWordDatabase {
        createAndSeedV2Database()
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
    fun `migration 2 to 3 keeps every row and exposes the new view columns`() {
        val database = migrateSeededV2()
        try {
            val db = database.openHelper.writableDatabase

            assertThat(db.version).isEqualTo(Migrations.CURRENT_VERSION)

            // Existing rows are untouched: content, identity and the gateway columns alike.
            assertThat(queryString(db, "SELECT meanings FROM word WHERE id = 1")).isEqualTo("""["苹果"]""")
            assertThat(queryString(db, "SELECT pack_id FROM word WHERE id = 1")).isEqualTo("builtin-cet4")
            assertThat(queryLong(db, "SELECT updated_at FROM word WHERE id = 1")).isEqualTo(100L)
            assertThat(queryString(db, "SELECT user_note FROM word WHERE id = 1")).isEqualTo("卡片笔记")
            assertThat(queryString(db, "SELECT meaning_source FROM word WHERE id = 1")).isEqualTo("file")
            assertThat(queryString(db, "SELECT lookup_state FROM word WHERE id = 2")).isEqualTo("PENDING")
            // The progress row (and with it the LEFT JOIN the view depends on) survives.
            assertThat(queryLong(db, "SELECT mastery_level FROM user_word_progress WHERE word_id = 1"))
                .isEqualTo(2L)
            assertThat(queryLong(db, "SELECT COUNT(*) FROM word")).isEqualTo(2L)

            // The rebuilt view projects the five v3 columns.
            assertThat(
                queryString(
                    db,
                    "SELECT user_note FROM word_with_progress WHERE word_id = 1",
                ),
            ).isEqualTo("卡片笔记")
            assertThat(
                queryString(
                    db,
                    "SELECT en_definition FROM word_with_progress WHERE word_id = 1",
                ),
            ).isEqualTo("the fruit of the apple tree")
            assertThat(
                queryString(
                    db,
                    "SELECT lookup_state FROM word_with_progress WHERE word_id = 1",
                ),
            ).isEqualTo("OK")
            assertThat(
                queryString(
                    db,
                    "SELECT meaning_source FROM word_with_progress WHERE word_id = 1",
                ),
            ).isEqualTo("file")
            assertThat(
                queryString(
                    db,
                    "SELECT audio_url FROM word_with_progress WHERE word_id = 1",
                ),
            ).isEqualTo("https://cdn.example/apple.mp3")
            // A row with no learner edits reads the column defaults, and the join still works:
            // no progress → NULL status for word 2, REVIEW + favourite for word 1.
            assertThat(
                queryString(
                    db,
                    "SELECT user_note FROM word_with_progress WHERE word_id = 2",
                ),
            ).isEmpty()
            assertThat(
                queryString(
                    db,
                    "SELECT status FROM word_with_progress WHERE word_id = 1",
                ),
            ).isEqualTo("REVIEW")
            assertThat(
                queryString(
                    db,
                    "SELECT status FROM word_with_progress WHERE word_id = 2",
                ),
            ).isNull()
            assertThat(queryLong(db, "SELECT COUNT(*) FROM word_with_progress")).isEqualTo(2L)
        } finally {
            database.close()
        }
    }

    @Test
    fun `a note and a manual meaning written after the upgrade read back and stay manual`() {
        val database = migrateSeededV2()
        try {
            val db = database.openHelper.writableDatabase

            // The M6a write path (the same DAO methods the repository calls).
            runBlocking {
                database.wordDao().updateUserNote(1, "升级后写的笔记", 999L)
                database.wordDao().updateManualMeaning(1, """["手工释义一","手工释义二"]""", 999L)
            }

            assertThat(queryString(db, "SELECT user_note FROM word_with_progress WHERE word_id = 1"))
                .isEqualTo("升级后写的笔记")
            assertThat(queryString(db, "SELECT meanings FROM word_with_progress WHERE word_id = 1"))
                .isEqualTo("""["手工释义一","手工释义二"]""")
            assertThat(queryString(db, "SELECT meaning_source FROM word_with_progress WHERE word_id = 1"))
                .isEqualTo("manual")
            assertThat(queryString(db, "SELECT lookup_state FROM word_with_progress WHERE word_id = 1"))
                .isEqualTo("OK")

            // The gateway's automated guards skip a manual row from now on.
            runBlocking {
                database.wordDao().recordLookupFailure(1, "FAILED", 1_000L)
            }
            assertThat(queryString(db, "SELECT meaning_source FROM word WHERE id = 1")).isEqualTo("manual")
            assertThat(queryString(db, "SELECT lookup_state FROM word WHERE id = 1")).isEqualTo("OK")
            assertThat(queryString(db, "SELECT meanings FROM word WHERE id = 1"))
                .isEqualTo("""["手工释义一","手工释义二"]""")

            val queue = runBlocking {
                database.wordDao().idsNeedingLookup(limit = 10, maxAttempts = 3)
            }
            assertThat(queue).doesNotContain(1L)
            assertThat(queue).contains(2L) // the untouched PENDING word is still queued
        } finally {
            database.close()
        }
    }

    private companion object {
        const val DB_CLASS = "top.toneup.word.data.local.ToneUpWordDatabase"
    }
}
