package top.toneup.word.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import top.toneup.word.core.result.AppError
import top.toneup.word.core.result.Outcome
import top.toneup.word.core.time.TimeProvider
import top.toneup.word.data.local.ToneUpWordDatabase
import top.toneup.word.data.local.entity.ReviewEventEntity
import top.toneup.word.data.repository.DefaultBackupRepository
import top.toneup.word.data.repository.DefaultWordRepository
import top.toneup.word.data.repository.ExportScope
import top.toneup.word.data.seed.BuiltInPackDefinition
import top.toneup.word.data.seed.PackSource
import top.toneup.word.data.settings.PlanIds
import top.toneup.word.domain.importer.ImportPreview
import java.time.ZoneId

/**
 * Full-backup scope (schema v4): the export walks pack scopes, not just `word_pack` rows.
 *
 * A historical or restored database can hold words under a pack id whose `word_pack` row is gone
 * (`legacy-other`, `restored-*`); those words must still reach the backup file.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26])
class BackupPackScopeTest {

    private class FixedTime(private val now: Long = 1_768_450_800_000L) : TimeProvider {
        override fun nowMillis(): Long = now
        override fun zone(): ZoneId = ZoneId.of("Asia/Shanghai")
    }

    /** No assets: the test creates its own pack. */
    private object EmptyPackSource : PackSource {
        override suspend fun definitions(): List<BuiltInPackDefinition> = emptyList()
        override suspend fun load(definition: BuiltInPackDefinition): Outcome<ImportPreview> =
            Outcome.Failure(AppError.io("no assets in test"))
    }

    private lateinit var database: ToneUpWordDatabase
    private lateinit var words: DefaultWordRepository
    private lateinit var backup: DefaultBackupRepository

    private val now = 1_768_450_800_000L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ToneUpWordDatabase::class.java,
        ).allowMainThreadQueries().build()
        database.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys=ON")
        val time = FixedTime(now)
        words = DefaultWordRepository(db = database, time = time, packLoader = EmptyPackSource)
        backup = DefaultBackupRepository(db = database, time = time)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `a full backup keeps the words of a pack whose pack row is gone`() = runTest {
        words.ensureDefaultPlan()
        val packId = requireNotNull(
            words.createPack("时代周刊单词", PlanIds.DEFAULT_PLAN, now).getOrNull(),
        )
        assertThat(words.addWordsToPack(packId, listOf("orphanword"), now).isSuccess).isTrue()

        // The historical state: the word rows exist, their `word_pack` row does not.
        database.wordDao().deletePackRow(packId)

        val json = requireNotNull(
            backup.export(ExportScope.FULL_BACKUP, PlanIds.DEFAULT_PLAN).getOrNull(),
        )

        assertThat(json).contains("orphanword")
        assertThat(json).contains(packId)
    }

    @Test
    fun `a full backup round-trips session id and response time`() = runTest {
        words.ensureDefaultPlan()
        val packId = requireNotNull(
            words.createPack("会话包", PlanIds.DEFAULT_PLAN, now).getOrNull(),
        )
        assertThat(words.addWordsToPack(packId, listOf("sessionword"), now).isSuccess).isTrue()
        val wordId = requireNotNull(
            database.wordDao().idByNormalized(packId)
                .firstOrNull { it.normalizedWord == "sessionword" }?.id,
        )

        database.reviewEventDao().insert(
            ReviewEventEntity(
                wordId = wordId,
                sessionId = 42L,
                grade = "FUZZY",
                source = "STUDY",
                answeredAt = now,
                dayKey = "2026-11-14",
                level = "CET4",
                previousIntervalDays = 3,
                newIntervalDays = 7,
                responseTimeMs = 3_500L,
                packId = packId,
                planId = PlanIds.DEFAULT_PLAN,
            ),
        )

        val exported = requireNotNull(
            backup.export(ExportScope.FULL_BACKUP, PlanIds.DEFAULT_PLAN).getOrNull(),
        )
        assertThat(exported).contains("\"sessionId\": 42")
        assertThat(exported).contains("\"responseTimeMillis\": 3500")

        // Restore into the same database (replace-on-restore is idempotent) and export again:
        // the fields must survive the round trip instead of being reset to null.
        assertThat(backup.restore(exported).getOrNull()).isNotNull()
        val roundTripped = requireNotNull(
            backup.export(ExportScope.FULL_BACKUP, PlanIds.DEFAULT_PLAN).getOrNull(),
        )
        assertThat(roundTripped).contains("\"sessionId\": 42")
        assertThat(roundTripped).contains("\"responseTimeMillis\": 3500")
    }
}
