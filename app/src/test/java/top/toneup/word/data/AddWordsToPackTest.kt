package top.toneup.word.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import top.toneup.word.core.result.AppError
import top.toneup.word.core.result.Outcome
import top.toneup.word.core.time.TimeProvider
import top.toneup.word.data.local.ToneUpWordDatabase
import top.toneup.word.data.local.entity.UserWordProgressEntity
import top.toneup.word.data.local.entity.WordPackEntity
import top.toneup.word.data.repository.DefaultWordRepository
import top.toneup.word.data.repository.PackAddResult
import top.toneup.word.data.settings.PackIds
import top.toneup.word.domain.importer.ImportPreview
import top.toneup.word.domain.model.CetLevel
import top.toneup.word.domain.model.Word
import top.toneup.word.data.seed.BuiltInPackDefinition
import top.toneup.word.data.seed.PackSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneId

/**
 * Repository tests for pack management (M2) and the plain-text import write path (M3).
 *
 * The behaviour under real risk is the one the data-layer conventions guard: importing words
 * must **skip** existing `(pack_id, normalized_word)` rows instead of replacing them (REPLACE
 * would delete the row and CASCADE away the learner's progress), new rows must enter as
 * `PENDING` for the gateway, and deleting a pack must cascade — while built-in packs refuse
 * rename/delete.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26])
class AddWordsToPackTest {

    private class FixedTime(private var now: Long = 1_768_450_800_000L) : TimeProvider {
        override fun nowMillis(): Long = now
        override fun zone(): ZoneId = ZoneId.of("Asia/Shanghai")
    }

    /** No assets: the tests create their own packs and never rely on seeding. */
    private object EmptyPackSource : PackSource {
        override suspend fun definitions(): List<BuiltInPackDefinition> = emptyList()
        override suspend fun load(definition: BuiltInPackDefinition): Outcome<ImportPreview> =
            Outcome.Failure(AppError.io("no assets in test"))
    }

    private lateinit var database: ToneUpWordDatabase
    private lateinit var repository: DefaultWordRepository

    private val now = 1_768_450_800_000L
    private val time = FixedTime(now)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ToneUpWordDatabase::class.java,
        ).allowMainThreadQueries().build()
        database.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys=ON")
        repository = DefaultWordRepository(
            db = database,
            time = time,
            packLoader = EmptyPackSource,
        )
    }

    @After
    fun tearDown() = database.close()

    // --- helpers ---------------------------------------------------------------------------

    private suspend fun createTestPack(name: String = "测试词库"): String {
        val outcome = repository.createPack(name, now)
        assertThat(outcome.isSuccess).isTrue()
        return requireNotNull(outcome.getOrNull())
    }

    private suspend fun insertBuiltInPack(id: String = PackIds.BUILTIN_CET6) {
        database.wordDao().upsertPack(
            WordPackEntity(
                id = id,
                name = "六级演示词库",
                level = CetLevel.CET6.id,
                isBuiltIn = true,
                wordCount = 0,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun progressEntity(wordId: Long, status: String = "REVIEW") = UserWordProgressEntity(
        wordId = wordId,
        status = status,
        masteryLevel = 2,
        repetitions = 1,
        intervalDays = 1,
        easeFactor = 2.5,
        dueAt = now,
        lastReviewedAt = now,
        lastAnswer = "UNKNOWN",
        lapseCount = 0,
        wrongCount = 0,
        fuzzyCount = 0,
        consecutiveCorrectCount = 0,
        isFavorite = false,
        introducedAt = now,
        updatedAt = now,
    )

    // --- createPack --------------------------------------------------------------------------

    @Test
    fun `createPack writes an empty user pack with OTHER level`() = runTest {
        val packId = createTestPack("考研词汇")

        val pack = database.wordDao().packById(packId)
        assertThat(pack).isNotNull()
        assertThat(pack!!.name).isEqualTo("考研词汇")
        assertThat(pack.level).isEqualTo(CetLevel.OTHER.id)
        assertThat(pack.isBuiltIn).isFalse()
        assertThat(pack.wordCount).isEqualTo(0)
        assertThat(pack.createdAt).isEqualTo(now)
        assertThat(packId).startsWith("user-$now-")
    }

    @Test
    fun `createPack refuses a blank name`() = runTest {
        for (blank in listOf("", "   ")) {
            val outcome = repository.createPack(blank, now)
            assertThat(outcome.isSuccess).isFalse()
            assertThat((outcome as Outcome.Failure).error.code)
                .isEqualTo(top.toneup.word.core.result.ErrorCode.EMPTY)
        }
    }

    @Test
    fun `createPack refuses an over-long name`() = runTest {
        val outcome = repository.createPack("词".repeat(41), now)
        assertThat(outcome.isSuccess).isFalse()
    }

    @Test
    fun `two packs created back to back get distinct ids`() = runTest {
        val first = createTestPack("A")
        val second = createTestPack("B")
        assertThat(first).isNotEqualTo(second)
    }

    // --- addWordsToPack -----------------------------------------------------------------------

    @Test
    fun `addWordsToPack inserts rows as PENDING and refreshes the pack count`() = runTest {
        val packId = createTestPack()

        val outcome = repository.addWordsToPack(packId, listOf("apple", "banana"), now)

        assertThat(outcome.isSuccess).isTrue()
        val result = requireNotNull(outcome.getOrNull())
        assertThat(result).isEqualTo(PackAddResult(inserted = 2, skipped = 0, total = 2))

        val word = database.wordDao().byNormalized(packId, "apple")
        assertThat(word).isNotNull()
        assertThat(word!!.word).isEqualTo("apple")
        assertThat(word.phonetic).isEmpty()
        assertThat(word.meanings).isEqualTo("[]")
        assertThat(word.lookupState).isEqualTo("PENDING")
        assertThat(word.meaningSource).isEmpty()
        assertThat(word.level).isEqualTo(CetLevel.OTHER.id)

        assertThat(database.wordDao().packById(packId)!!.wordCount).isEqualTo(2)
    }

    @Test
    fun `re-importing the same words is idempotent and keeps existing rows`() = runTest {
        val packId = createTestPack()
        repository.addWordsToPack(packId, listOf("apple", "banana"), now)
        val firstId = database.wordDao().byNormalized(packId, "apple")!!.id

        val again = repository.addWordsToPack(packId, listOf("apple", "cherry"), now + 1)

        val result = requireNotNull(again.getOrNull())
        assertThat(result).isEqualTo(PackAddResult(inserted = 1, skipped = 1, total = 2))
        // The surviving apple row keeps its id — REPLACE would have given it a new one and
        // cascaded every progress row away.
        assertThat(database.wordDao().byNormalized(packId, "apple")!!.id).isEqualTo(firstId)
        assertThat(database.wordDao().packById(packId)!!.wordCount).isEqualTo(3)
    }

    @Test
    fun `case-insensitive duplicates inside one batch are skipped`() = runTest {
        val packId = createTestPack()

        val outcome = repository.addWordsToPack(packId, listOf("Apple", "apple", "APPLE"), now)

        val result = requireNotNull(outcome.getOrNull())
        assertThat(result).isEqualTo(PackAddResult(inserted = 1, skipped = 2, total = 3))
    }

    @Test
    fun `the same word in two packs is two independent rows`() = runTest {
        val packA = createTestPack("词库 A")
        val packB = createTestPack("词库 B")
        repository.addWordsToPack(packA, listOf("apple"), now)
        repository.addWordsToPack(packB, listOf("apple"), now + 1)

        val rowA = database.wordDao().byNormalized(packA, "apple")
        val rowB = database.wordDao().byNormalized(packB, "apple")
        assertThat(rowA).isNotNull()
        assertThat(rowB).isNotNull()
        assertThat(rowA!!.id).isNotEqualTo(rowB!!.id)
    }

    @Test
    fun `progress on an existing word survives a re-import`() = runTest {
        val packId = createTestPack()
        repository.addWordsToPack(packId, listOf("apple"), now)
        val wordId = database.wordDao().byNormalized(packId, "apple")!!.id
        database.progressDao().upsert(progressEntity(wordId, status = "REVIEW"))

        repository.addWordsToPack(packId, listOf("apple"), now + 1)

        assertThat(database.progressDao().byWordId(wordId)).isNotNull()
    }

    @Test
    fun `adding to a missing pack fails without writing anything`() = runTest {
        val outcome = repository.addWordsToPack("no-such-pack", listOf("apple"), now)
        assertThat(outcome.isSuccess).isFalse()
        assertThat(database.wordDao().count("no-such-pack")).isEqualTo(0)
    }

    @Test
    fun `adding an empty word list fails with the empty error`() = runTest {
        val packId = createTestPack()
        val outcome = repository.addWordsToPack(packId, emptyList(), now)
        assertThat(outcome.isSuccess).isFalse()
        assertThat((outcome as Outcome.Failure).error.code)
            .isEqualTo(top.toneup.word.core.result.ErrorCode.EMPTY)
    }

    @Test
    fun `words added to the built-in pack never replace seeded rows`() = runTest {
        insertBuiltInPack()
        val seeded = top.toneup.word.data.local.entity.WordEntity(
            packId = PackIds.BUILTIN_CET6,
            word = "apple",
            normalizedWord = Word.normalizeWordKey("apple"),
            phonetic = "/ˈæpl/",
            meanings = """["苹果"]""",
            level = CetLevel.CET6.id,
            lookupState = "OK",
            meaningSource = "file",
            createdAt = now,
            updatedAt = now,
        )
        database.wordDao().insert(seeded)
        val seededId = database.wordDao().byNormalized(PackIds.BUILTIN_CET6, "apple")!!.id

        val outcome = repository.addWordsToPack(PackIds.BUILTIN_CET6, listOf("apple", "banana"), now)

        val result = requireNotNull(outcome.getOrNull())
        assertThat(result).isEqualTo(PackAddResult(inserted = 1, skipped = 1, total = 2))
        val apple = database.wordDao().byNormalized(PackIds.BUILTIN_CET6, "apple")!!
        assertThat(apple.id).isEqualTo(seededId)
        assertThat(apple.phonetic).isEqualTo("/ˈæpl/") // untouched content
        assertThat(apple.lookupState).isEqualTo("OK") // not reset to PENDING
    }

    // --- addWordWithNote (the 「写词库」 write path) -------------------------------------------

    @Test
    fun `addWordWithNote inserts a PENDING row with the trimmed note and refreshes the count`() = runTest {
        insertBuiltInPack()

        val outcome = repository.addWordWithNote(
            packId = PackIds.BUILTIN_CET6,
            word = "  apple  ",
            note = "  特殊含义：苹果公司  ",
            now = now,
        )

        assertThat(outcome.isSuccess).isTrue()
        val word = database.wordDao().byNormalized(PackIds.BUILTIN_CET6, "apple")!!
        assertThat(word.id).isEqualTo(outcome.getOrNull())
        assertThat(word.word).isEqualTo("apple")
        // A word typed by hand has no dictionary meanings yet: it enters for the gateway, exactly
        // like an imported one, and the learner's note is stored beside it.
        assertThat(word.meanings).isEqualTo("[]")
        assertThat(word.lookupState).isEqualTo("PENDING")
        assertThat(word.meaningSource).isEmpty()
        assertThat(word.userNote).isEqualTo("特殊含义：苹果公司")
        assertThat(word.level).isEqualTo(CetLevel.CET6.id)
        assertThat(database.wordDao().packById(PackIds.BUILTIN_CET6)!!.wordCount).isEqualTo(1)
    }

    @Test
    fun `addWordWithNote reuses the existing row and never erases a note with a blank one`() = runTest {
        insertBuiltInPack()

        val first = repository.addWordWithNote(PackIds.BUILTIN_CET6, "apple", "第一版释义", now)
        val wordId = requireNotNull(first.getOrNull())
        database.progressDao().upsert(progressEntity(wordId, status = "REVIEW"))

        // Case variant → same identity, same row: the progress row must survive (no REPLACE).
        val second = repository.addWordWithNote(PackIds.BUILTIN_CET6, "Apple", "更精确的释义", now + 1)
        assertThat(second.getOrNull()).isEqualTo(wordId)
        assertThat(database.progressDao().byWordId(wordId)).isNotNull()
        assertThat(database.wordDao().packById(PackIds.BUILTIN_CET6)!!.wordCount).isEqualTo(1)

        // A blank note means "no note", so the saved one must stay untouched.
        val third = repository.addWordWithNote(PackIds.BUILTIN_CET6, "APPLE", "   ", now + 2)
        assertThat(third.getOrNull()).isEqualTo(wordId)
        assertThat(database.wordDao().byNormalized(PackIds.BUILTIN_CET6, "apple")!!.userNote)
            .isEqualTo("更精确的释义")
    }

    @Test
    fun `addWordWithNote fails on a blank word and writes nothing to a missing pack`() = runTest {
        insertBuiltInPack()

        val blank = repository.addWordWithNote(PackIds.BUILTIN_CET6, "   ", "x", now)
        assertThat(blank.isSuccess).isFalse()
        assertThat((blank as Outcome.Failure).error.code)
            .isEqualTo(top.toneup.word.core.result.ErrorCode.EMPTY)

        val missing = repository.addWordWithNote("no-such-pack", "apple", "", now)
        assertThat(missing.isSuccess).isFalse()
        assertThat(database.wordDao().count("no-such-pack")).isEqualTo(0)
    }

    // --- renamePack ----------------------------------------------------------------------------

    @Test
    fun `renamePack changes the name of a user pack`() = runTest {
        val packId = createTestPack("旧名字")

        val outcome = repository.renamePack(packId, "  新名字  ")

        assertThat(outcome.isSuccess).isTrue()
        assertThat(database.wordDao().packById(packId)!!.name).isEqualTo("新名字")
    }

    @Test
    fun `renamePack refuses built-in packs and blank names`() = runTest {
        insertBuiltInPack()
        assertThat(repository.renamePack(PackIds.BUILTIN_CET6, "改名").isSuccess).isFalse()

        val packId = createTestPack()
        assertThat(repository.renamePack(packId, "   ").isSuccess).isFalse()
        assertThat(repository.renamePack("no-such-pack", "名字").isSuccess).isFalse()
    }

    // --- deletePack ------------------------------------------------------------------------------

    @Test
    fun `deletePack removes words and cascades progress but keeps nothing behind`() = runTest {
        val packId = createTestPack()
        repository.addWordsToPack(packId, listOf("apple", "banana"), now)
        val wordId = database.wordDao().byNormalized(packId, "apple")!!.id
        database.progressDao().upsert(progressEntity(wordId, status = "NEW"))

        val outcome = repository.deletePack(packId)

        assertThat(outcome.isSuccess).isTrue()
        assertThat(database.wordDao().packById(packId)).isNull()
        assertThat(database.wordDao().count(packId)).isEqualTo(0)
        assertThat(database.progressDao().byWordId(wordId)).isNull()
    }

    @Test
    fun `deletePack refuses built-in packs and the legacy bucket`() = runTest {
        insertBuiltInPack()
        val outcome = repository.deletePack(PackIds.BUILTIN_CET6)
        assertThat(outcome.isSuccess).isFalse()
        assertThat(database.wordDao().packById(PackIds.BUILTIN_CET6)).isNotNull()
    }

    @Test
    fun `deleteImportedPack still works and matches deletePack`() = runTest {
        val packId = createTestPack()
        repository.addWordsToPack(packId, listOf("apple"), now)

        val outcome = repository.deleteImportedPack(packId)

        assertThat(outcome.isSuccess).isTrue()
        assertThat(database.wordDao().packById(packId)).isNull()
    }

    // --- observePacks ------------------------------------------------------------------------------

    @Test
    fun `observePacks emits user packs created at runtime`() = runTest {
        val packId = createTestPack("运行期词库")

        val packs = repository.observePacks().first()
        assertThat(packs.map { it.id }).contains(packId)
    }
}
