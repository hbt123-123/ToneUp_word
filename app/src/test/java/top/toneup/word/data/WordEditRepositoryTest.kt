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
import top.toneup.word.core.json.JsonCodec
import top.toneup.word.core.result.AppError
import top.toneup.word.core.result.ErrorCode
import top.toneup.word.core.result.Outcome
import top.toneup.word.core.time.TimeProvider
import top.toneup.word.data.gateway.GatewayClientProvider
import top.toneup.word.data.gateway.GatewayConfig
import top.toneup.word.data.gateway.LookupResult
import top.toneup.word.data.gateway.LookupSource
import top.toneup.word.data.gateway.MeaningLookupService
import top.toneup.word.data.local.ToneUpWordDatabase
import top.toneup.word.data.local.entity.UserWordProgressEntity
import top.toneup.word.data.repository.DefaultMeaningSyncRepository
import top.toneup.word.data.repository.DefaultWordRepository
import top.toneup.word.data.repository.MeaningSyncRepository
import top.toneup.word.data.settings.DataStoreSettingsRepository
import top.toneup.word.data.seed.BuiltInPackDefinition
import top.toneup.word.data.seed.PackSource
import top.toneup.word.domain.importer.ImportPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.ZoneId

/**
 * Repository tests for the M6a card editor (plan §9): the learner's note, and a hand-edited
 * meaning set that must become the terminal `manual` state.
 *
 * The behaviour under real risk is the promise the dialog shows the user — "手动编辑后不再被
 * 自动补全覆盖" — so the interesting case is not the write itself but what the *gateway* does
 * afterwards: [DefaultMeaningSyncRepository.applyLookupToWord] and the worker's queue query
 * both have to skip a row the learner froze.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26])
class WordEditRepositoryTest {

    private class FixedTime(private var now: Long = 1_768_450_800_000L) : TimeProvider {
        override fun nowMillis(): Long = now
        override fun zone(): ZoneId = ZoneId.of("Asia/Shanghai")
    }

    /** No assets: the tests create their own packs and never rely on seeding. */
    private object EmptyPackSource : PackSource {
        override suspend fun definitions(): List<BuiltInPackDefinition> = emptyList()
        override suspend fun load(definition: BuiltInPackDefinition): Outcome<ImportPreviewHolder> =
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

    private suspend fun wordId(token: String = "apple"): Long {
        val packId = requireNotNull(
            repository.createPack("测试词库", now).getOrNull(),
        )
        val added = repository.addWordsToPack(packId, listOf(token), now)
        assertThat(added.isSuccess).isTrue()
        return requireNotNull(database.wordDao().byNormalized(packId, token)).id
    }

    /** The gateway write-back, wired with a client that never leaves the process. */
    private fun meaningSync(): MeaningSyncRepository = DefaultMeaningSyncRepository(
        context = ApplicationProvider.getApplicationContext(),
        db = database,
        time = time,
        lookupService = MeaningLookupService(
            clients = GatewayClientProvider(
                configProvider = {
                    GatewayConfig(
                        baseUrl = "https://gateway.invalid/",
                        key = "test-key",
                        dictPath = "/dict",
                        enPath = "/en/{word}",
                        mtPath = "/v1/chat/completions",
                    )
                },
                json = JsonCodec.json,
                isDebugBuild = false,
                httpsOnly = true,
            ),
            cacheDao = database.meaningCacheDao(),
            wordDao = database.wordDao(),
            time = time,
            json = JsonCodec.json,
        ),
        settingsRepository = DataStoreSettingsRepository(ApplicationProvider.getApplicationContext()),
        json = JsonCodec.json,
    )

    private fun favoriteProgress(wordId: Long) = UserWordProgressEntity(
        wordId = wordId,
        status = "REVIEW",
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

    // --- saveUserNote ------------------------------------------------------------------------

    @Test
    fun `saveUserNote stores the note and touches nothing else`() = runTest {
        val id = wordId()
        database.progressDao().upsert(favoriteProgress(id))

        val outcome = repository.saveUserNote(id, "a 与 an 的区别记这里")

        assertThat(outcome.isSuccess).isTrue()
        val row = database.wordDao().byId(id)!!
        assertThat(row.userNote).isEqualTo("a 与 an 的区别记这里")
        // The rest of the row — including the pending lookup — is left exactly as it was.
        assertThat(row.meanings).isEqualTo("[]")
        assertThat(row.lookupState).isEqualTo("PENDING")
        assertThat(row.meaningSource).isEmpty()
        assertThat(row.updatedAt).isEqualTo(now)
        assertThat(database.progressDao().byWordId(id)).isNotNull()
    }

    @Test
    fun `saveUserNote with an empty string clears the note`() = runTest {
        val id = wordId()
        repository.saveUserNote(id, "先写的笔记")
        assertThat(database.wordDao().byId(id)!!.userNote).isEqualTo("先写的笔记")

        val outcome = repository.saveUserNote(id, "")

        assertThat(outcome.isSuccess).isTrue()
        assertThat(database.wordDao().byId(id)!!.userNote).isEmpty()
    }

    @Test
    fun `saveUserNote on a missing word fails without writing`() = runTest {
        val outcome = repository.saveUserNote(4242L, "笔记")

        assertThat(outcome.isSuccess).isFalse()
        assertThat((outcome as Outcome.Failure).error.code).isEqualTo(ErrorCode.DATABASE)
    }

    @Test
    fun `the edited note is visible through the word_with_progress view`() = runTest {
        val id = wordId()
        repository.saveUserNote(id, "视图里的笔记")

        val detail = repository.wordDetail(id)

        assertThat(detail).isNotNull()
        assertThat(detail!!.word.userNote).isEqualTo("视图里的笔记")
        assertThat(detail.word.lookupState).isEqualTo("PENDING")
    }

    // --- saveManualMeaning --------------------------------------------------------------------

    @Test
    fun `saveManualMeaning stores the meanings and freezes the row as manual`() = runTest {
        val id = wordId()

        val outcome = repository.saveManualMeaning(id, listOf("放弃；抛弃", "遗弃"))

        assertThat(outcome.isSuccess).isTrue()
        val row = database.wordDao().byId(id)!!
        assertThat(row.meanings).isEqualTo("""["放弃；抛弃","遗弃"]""")
        assertThat(row.meaningSource).isEqualTo("manual")
        assertThat(row.lookupState).isEqualTo("OK")
        assertThat(row.updatedAt).isEqualTo(now)
    }

    @Test
    fun `saveManualMeaning trims entries and drops blank ones`() = runTest {
        val id = wordId()

        val outcome = repository.saveManualMeaning(id, listOf("  苹果  ", "", "   ", "苹果树"))

        assertThat(outcome.isSuccess).isTrue()
        assertThat(database.wordDao().byId(id)!!.meanings).isEqualTo("""["苹果","苹果树"]""")
    }

    @Test
    fun `saveManualMeaning refuses an empty or all-blank list and keeps the old meanings`() = runTest {
        val id = wordId()
        repository.saveManualMeaning(id, listOf("手工释义"))

        for (blank in listOf(emptyList(), listOf("", "   "))) {
            val outcome = repository.saveManualMeaning(id, blank)
            assertThat(outcome.isSuccess).isFalse()
            assertThat((outcome as Outcome.Failure).error.code).isEqualTo(ErrorCode.EMPTY)
        }

        val row = database.wordDao().byId(id)!!
        assertThat(row.meanings).isEqualTo("""["手工释义"]""")
        assertThat(row.meaningSource).isEqualTo("manual")
    }

    @Test
    fun `saveManualMeaning on a missing word fails without writing`() = runTest {
        val outcome = repository.saveManualMeaning(4242L, listOf("释义"))

        assertThat(outcome.isSuccess).isFalse()
        assertThat((outcome as Outcome.Failure).error.code).isEqualTo(ErrorCode.DATABASE)
    }

    // --- the promise: manual is terminal -------------------------------------------------------

    @Test
    fun `gateway write-back never overwrites a meaning the learner edited`() = runTest {
        val id = wordId()
        repository.saveManualMeaning(id, listOf("我的释义"))
        val gatewayResult = LookupResult(
            phonetic = "/ˈɡwɒst/",
            meanings = listOf("网关返回的释义"),
            enDefinition = "a returned definition",
            source = LookupSource.ECDICT,
        )

        meaningSync().applyLookupToWord(id, gatewayResult, now + 1)

        val row = database.wordDao().byId(id)!!
        assertThat(row.meanings).isEqualTo("""["我的释义"]""")
        assertThat(row.meaningSource).isEqualTo("manual")
        assertThat(row.lookupState).isEqualTo("OK")
        assertThat(row.phonetic).isEmpty() // no partial merge either: the guard runs first
    }

    @Test
    fun `an edited row leaves the worker queue while untouched PENDING words stay`() = runTest {
        val edited = wordId("apple")
        val waiting = wordId("banana")
        repository.saveManualMeaning(edited, listOf("手工释义"))

        val queue = database.wordDao().idsNeedingLookup(limit = 10, maxAttempts = 3)

        assertThat(queue).doesNotContain(edited)
        assertThat(queue).contains(waiting)
        assertThat(database.wordDao().observeNeedingLookupCount(3).valueForTest())
            .isEqualTo(1)
    }

    @Test
    fun `a failed lookup attempt cannot flip a manual row out of OK`() = runTest {
        val id = wordId()
        repository.saveManualMeaning(id, listOf("手工释义"))

        database.wordDao().recordLookupFailure(id, "FAILED", now + 5)
        // A stale work item that races the edit is dropped by the guarded UPDATE as well.
        database.wordDao().updateManualMeaning(id, """["仍是手工"]""", now + 6)

        val row = database.wordDao().byId(id)!!
        assertThat(row.lookupState).isEqualTo("OK")
        assertThat(row.meaningSource).isEqualTo("manual")
        assertThat(row.meanings).isEqualTo("""["仍是手工"]""")
    }

    private suspend fun Flow<Int>.valueForTest(): Int = first()
}

/** Import preview type alias kept local so [EmptyPackSource] matches the [PackSource] seam. */
private typealias ImportPreviewHolder = top.toneup.word.domain.importer.ImportPreview
