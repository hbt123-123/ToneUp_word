package top.toneup.word.ui

import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Shadows.shadowOf
import top.toneup.word.AppContainer
import top.toneup.word.core.result.AppError
import top.toneup.word.core.result.Outcome
import top.toneup.word.data.gateway.LookupResult
import top.toneup.word.data.local.ToneUpWordDatabase
import top.toneup.word.data.local.entity.WordPackEntity
import top.toneup.word.data.repository.BatchReport
import top.toneup.word.data.repository.MeaningSyncRepository
import top.toneup.word.data.repository.PackAddResult
import top.toneup.word.data.settings.PackIds
import top.toneup.word.data.settings.UserSettings
import top.toneup.word.domain.model.CetLevel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import androidx.lifecycle.viewModelScope
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The M5 intake ViewModel: parse routing (share vs clipboard), the write path into a pack,
 * meaning-sync triggering and the clipboard hash bookkeeping (plan §8).
 *
 * Runs against a **real in-memory Room database and the real DataStore** through
 * [AppContainer] — the paste-import write semantics are exactly what the learner depends on
 * (PENDING rows, no REPLACE), so faking the repository would test nothing. Only
 * [MeaningSyncRepository] is faked: WorkManager has no test artifact in this project, and
 * "the worker was enqueued" is asserted through the seam instead.
 *
 * Timing model: the ViewModel runs on `Dispatchers.Main.immediate`, which on a Robolectric
 * test thread executes inline until a real Room / DataStore suspension; the continuation is
 * posted back to the (paused) main looper and drained by [awaitCondition], whose condition
 * polls real state. Fixed epoch constants keep the suite flake-free.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ExternalImportViewModelTest {

    /** Records `enqueueAll` calls; everything else is unused by this ViewModel. */
    private class RecordingMeaningSync : MeaningSyncRepository {
        var enqueues: Int = 0

        override fun observePendingCount(): Flow<Int> = flowOf(0)

        override suspend fun enqueueAll(): Outcome<Unit> {
            enqueues++
            return Outcome.Success(Unit)
        }

        override suspend fun lookupWordNow(wordId: Long): Outcome<LookupResult> =
            Outcome.Failure(AppError.unknown("not under test"))

        override suspend fun runBatch(batchLimit: Int): BatchReport =
            BatchReport(processed = 0, abortedByConfig = false)

        override suspend fun applyLookupToWord(wordId: Long, result: LookupResult, now: Long) = Unit
    }

    private lateinit var database: ToneUpWordDatabase
    private lateinit var container: AppContainer
    private lateinit var meaningSync: RecordingMeaningSync
    private lateinit var viewModel: ExternalImportViewModel

    private val now = 1_768_450_800_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ToneUpWordDatabase::class.java,
        ).allowMainThreadQueries().build()
        database.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys=ON")
        container = AppContainer(context, database)
        // DataStore is shared between tests in the same process; start from a known state.
        runBlocking(Dispatchers.IO) { container.settingsRepository.resetToDefaults() }
        meaningSync = RecordingMeaningSync()
        viewModel = ExternalImportViewModel(container, meaningSync)
        // The pack / active-pack flows are `WhileSubscribed` (they must not stay subscribed after
        // the sheet is gone). These warm collectors give the assertions a live subscriber for the
        // whole test; the coroutines run inside [viewModelScope], so [tearDown] cancels them too.
        viewModel.viewModelScope.launch { viewModel.packs.collect { } }
        viewModel.viewModelScope.launch { viewModel.activePackId.collect { } }
    }

    @After
    fun tearDown() {
        // Cancel the ViewModel's Room/DataStore subscriptions, then keep draining the paused main
        // looper until they finish unwinding. DataStore's write actor stays reserved for the
        // previous reader until that reader's cancellation has actually run; the cancellation
        // continuation lands on this (soon to be abandoned) paused looper, so without the drain
        // the next test class in the same JVM blocks forever on its first DataStore write.
        viewModel.viewModelScope.cancel()
        repeat(DRAIN_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        database.close()
    }

    // --- helpers ---------------------------------------------------------------------------

    /**
     * Drains Robolectric's paused main looper (where ViewModel continuations land) and polls
     * [condition] until it holds, or fails loudly after a bounded real-time wait.
     */
    private fun awaitCondition(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timed out waiting for: $description")
            }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun storedSettings(): UserSettings =
        runBlocking(Dispatchers.IO) { container.settingsRepository.current() }

    private fun wordIn(packId: String, word: String) =
        runBlocking(Dispatchers.IO) { database.wordDao().byNormalized(packId, word) }

    private fun countIn(packId: String) =
        runBlocking(Dispatchers.IO) { database.wordDao().count(packId) }

    private fun awaitPhase(phase: ExternalImportPhase) =
        awaitCondition("phase == $phase") { viewModel.state.value.phase == phase }

    private fun awaitStoredHash(hash: String) =
        awaitCondition("lastClipboardHash == $hash") { storedSettings().lastClipboardHash == hash }

    private fun createTestPack(name: String = "测试词库"): String {
        val outcome = runBlocking(Dispatchers.IO) {
            container.wordRepository.createPack(name, now)
        }
        return requireNotNull(outcome.getOrNull())
    }

    // --- parse routing ---------------------------------------------------------------------

    @Test
    fun `shared text becomes a detected preview with honest counts`() {
        viewModel.onSharedText("apple, banana 中文123")

        val state = viewModel.state.value
        assertThat(state.phase).isEqualTo(ExternalImportPhase.DETECTED)
        assertThat(state.source).isEqualTo(ExternalImportSource.SHARE)
        val preview = requireNotNull(state.preview)
        assertThat(preview.words).containsExactly("apple", "banana").inOrder()
        assertThat(preview.importableCount).isEqualTo(2)
        assertThat(preview.dropped).isEqualTo(1)
        assertThat(preview.duplicates).isEqualTo(0)
    }

    @Test
    fun `blank share text becomes the short error state`() {
        viewModel.onSharedText("   ")

        val state = viewModel.state.value
        assertThat(state.phase).isEqualTo(ExternalImportPhase.ERROR)
        assertThat(state.preview).isNull()
        assertThat(state.error).isNotNull()
    }

    @Test
    fun `fully filtered text becomes the short error state`() {
        viewModel.onSharedText("中文？？ 123")

        val state = viewModel.state.value
        assertThat(state.phase).isEqualTo(ExternalImportPhase.ERROR)
        assertThat(state.preview).isNull()
        assertThat(state.error).isNotNull()
    }

    @Test
    fun `clipboard content without importable words stays silent`() {
        viewModel.onClipboardText("12345 中文 https://example.com")

        // A junk clipboard must never open the sheet (plan §8.1: 非空才展示).
        assertThat(viewModel.state.value.phase).isEqualTo(ExternalImportPhase.IDLE)
        assertThat(viewModel.isSheetVisible()).isFalse()
    }

    @Test
    fun `importable clipboard content enters the detected state`() {
        viewModel.onClipboardText("apple banana")

        val state = viewModel.state.value
        assertThat(state.phase).isEqualTo(ExternalImportPhase.DETECTED)
        assertThat(state.source).isEqualTo(ExternalImportSource.CLIPBOARD)
        assertThat(state.preview?.importableCount).isEqualTo(2)
        assertThat(viewModel.isSheetVisible()).isTrue()
    }

    // --- write path ------------------------------------------------------------------------

    @Test
    fun `confirmImport stores the words as PENDING and triggers meaning completion`() {
        val packId = createTestPack()
        viewModel.onSharedText("apple, banana")

        viewModel.confirmImport(packId)
        awaitPhase(ExternalImportPhase.DONE)

        assertThat(viewModel.state.value.result)
            .isEqualTo(PackAddResult(inserted = 2, skipped = 0, total = 2))
        val word = wordIn(packId, "apple")
        assertThat(word).isNotNull()
        assertThat(word!!.lookupState).isEqualTo("PENDING")
        assertThat(word.meaningSource).isEmpty()
        assertThat(countIn(packId)).isEqualTo(2)
        assertThat(meaningSync.enqueues).isEqualTo(1)
    }

    @Test
    fun `a second confirm tap while importing does not double import`() {
        val packId = createTestPack()
        viewModel.onSharedText("apple, banana")

        viewModel.confirmImport(packId)
        // The write suspends on Room, so the state is still IMPORTING here — a double tap.
        assertThat(viewModel.state.value.phase).isEqualTo(ExternalImportPhase.IMPORTING)
        viewModel.confirmImport(packId)
        awaitPhase(ExternalImportPhase.DONE)

        assertThat(viewModel.state.value.result)
            .isEqualTo(PackAddResult(inserted = 2, skipped = 0, total = 2))
        assertThat(countIn(packId)).isEqualTo(2)
        assertThat(meaningSync.enqueues).isEqualTo(1)
    }

    @Test
    fun `dismiss during an in-flight import is ignored`() {
        val packId = createTestPack()
        viewModel.onSharedText("apple")
        viewModel.confirmImport(packId)

        viewModel.dismiss()

        // The write owns the state until it finishes; dismissing mid-flight must not strand it.
        awaitPhase(ExternalImportPhase.DONE)
        assertThat(countIn(packId)).isEqualTo(1)
    }

    @Test
    fun `a failed write returns to the sheet with the repository error`() {
        viewModel.onSharedText("apple")

        viewModel.confirmImport("no-such-pack")

        awaitCondition("back on DETECTED with the repository error") {
            val state = viewModel.state.value
            state.phase == ExternalImportPhase.DETECTED && state.error != null
        }
        assertThat(countIn("no-such-pack")).isEqualTo(0)
        assertThat(meaningSync.enqueues).isEqualTo(0)
    }

    @Test
    fun `createPackAndImport creates activates and imports in one flow`() {
        viewModel.onSharedText("apple, banana")

        viewModel.createPackAndImport("新词库")
        awaitPhase(ExternalImportPhase.DONE)

        awaitCondition("the new pack became the active study scope") {
            storedSettings().activePackId.startsWith("user-")
        }
        val packId = storedSettings().activePackId
        assertThat(
            runBlocking(Dispatchers.IO) { database.wordDao().packById(packId) }!!.name,
        ).isEqualTo("新词库")
        assertThat(wordIn(packId, "banana")!!.lookupState).isEqualTo("PENDING")
        assertThat(viewModel.state.value.result?.inserted).isEqualTo(2)
        assertThat(meaningSync.enqueues).isEqualTo(1)
    }

    // --- clipboard hash bookkeeping ---------------------------------------------------------

    @Test
    fun `dismissing a clipboard detection records its hash`() {
        viewModel.onClipboardText("apple")
        assertThat(storedSettings().lastClipboardHash).isEmpty()

        viewModel.dismiss()
        awaitPhase(ExternalImportPhase.DISMISSED)

        // "本次已看过" — the same content must never pop again (MainActivity compares).
        awaitStoredHash(ClipboardHash.of("apple"))
        assertThat(viewModel.isSheetVisible()).isFalse()
    }

    @Test
    fun `dismissing a share never writes a clipboard hash`() {
        viewModel.onSharedText("apple")

        viewModel.dismiss()
        awaitPhase(ExternalImportPhase.DISMISSED)

        assertThat(storedSettings().lastClipboardHash).isEmpty()
    }

    @Test
    fun `a completed clipboard import records its hash too`() {
        val packId = createTestPack()
        viewModel.onClipboardText("apple")

        viewModel.confirmImport(packId)
        awaitPhase(ExternalImportPhase.DONE)
        awaitStoredHash(ClipboardHash.of("apple"))
    }

    // --- pack list --------------------------------------------------------------------------

    @Test
    fun `the selectable packs exclude the legacy bucket`() {
        val packId = createTestPack("自定义库")
        runBlocking(Dispatchers.IO) {
            database.wordDao().upsertPack(
                WordPackEntity(
                    id = PackIds.LEGACY_OTHER,
                    name = "历史归档",
                    level = CetLevel.OTHER.id,
                    isBuiltIn = false,
                    wordCount = 0,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }

        awaitCondition("the custom pack is visible") {
            viewModel.packs.value.any { it.id == packId }
        }
        assertThat(viewModel.packs.value.map { it.id }).doesNotContain(PackIds.LEGACY_OTHER)
    }

    @Test
    fun `the default pack follows the active pack setting`() {
        awaitCondition("the builtin default is exposed before any choice") {
            viewModel.activePackId.value == PackIds.BUILTIN_CET4
        }

        val packId = createTestPack("新库")
        runBlocking(Dispatchers.IO) { container.settingsRepository.setActivePackId(packId) }

        awaitCondition("the sheet default follows the learner's choice") {
            viewModel.activePackId.value == packId
        }
    }

    // --- hash function -----------------------------------------------------------------------

    @Test
    fun `ClipboardHash is stable trimmed and content sensitive`() {
        // Well-known SHA-256 vector for "abc".
        assertThat(ClipboardHash.of("abc"))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        // Normalisation is a trim: a round-tripped clipboard with a stray newline matches.
        assertThat(ClipboardHash.of(" apple ")).isEqualTo(ClipboardHash.of("apple"))
        assertThat(ClipboardHash.of("apple")).isNotEqualTo(ClipboardHash.of("apples"))
        assertThat(ClipboardHash.of("apple")).isNotEqualTo(ClipboardHash.of("Apple"))
    }

    private companion object {
        /** Real-time bound for Room / DataStore round-trips on a busy machine. */
        const val TIMEOUT_MILLIS = 10_000L

        /** Granularity at which the paused main looper is pumped while polling. */
        const val POLL_INTERVAL_MILLIS = 10L

        /** Teardown drain rounds: enough for a DataStore reader's cancellation to unwind. */
        const val DRAIN_ROUNDS = 50
    }
}
