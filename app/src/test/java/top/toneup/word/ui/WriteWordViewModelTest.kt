package top.toneup.word.ui

import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewModelScope
import org.robolectric.Shadows.shadowOf
import top.toneup.word.AppContainer
import top.toneup.word.core.result.AppError
import top.toneup.word.core.result.Outcome
import top.toneup.word.data.gateway.LookupResult
import top.toneup.word.data.local.ToneUpWordDatabase
import top.toneup.word.data.repository.BatchReport
import top.toneup.word.data.repository.MeaningSyncRepository
import top.toneup.word.data.settings.PlanIds
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The 「写词库」 ViewModel: the one-word-at-a-time write path into the studied pack, plus the
 * "本次已写入" list it keeps for the screen.
 *
 * Runs against a **real in-memory Room database and the real DataStore** through [AppContainer]
 * (the write semantics — PENDING rows, identity by `(pack, normalized word)` — are exactly what
 * the learner depends on, so faking the repository would test nothing). Only
 * [MeaningSyncRepository] is faked, because WorkManager has no test artifact in this project.
 *
 * The behaviour under real risk is the duplicate-key crash: writing the same word twice returns
 * the *same* row id, and a recent list that keeps both copies would hand the LazyColumn a
 * duplicated key (plan §D2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class WriteWordViewModelTest {

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
    private lateinit var viewModel: WriteWordViewModel

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
        viewModel = WriteWordViewModel(container, meaningSync)
        // `state` is a `WhileSubscribed` StateFlow, so a live subscriber is needed for its upstream
        // to run; the coroutine lives in [viewModelScope] and is cancelled by [tearDown].
        viewModel.viewModelScope.launch { viewModel.state.collect { } }
    }

    @After
    fun tearDown() {
        // Drain the paused main looper after cancelling, so DataStore's write actor is released
        // before the next test class in the same JVM touches it.
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
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Types [word] + [note], submits, and waits until the write has actually landed. */
    private fun writeAndAwait(word: String, note: String = "") {
        viewModel.onWordChanged(word)
        viewModel.onNoteChanged(note)
        viewModel.write()
        // Wait for *this* word's confirmation, not just "a message is showing": the previous
        // write's line is still up when this one starts and would be mistaken for completion by
        // a `!= null` check. (Callers writing the *same* word twice await a counter instead —
        // the message text would be identical.)
        awaitCondition("the write of $word to land") {
            viewModel.state.value.message == "已写入「$word」。"
        }
    }

    /** The plan's「手写词」pack: what the ViewModel creates on the first write (D6). */
    private val writePackId = PlanIds.writePackIdFor(PlanIds.DEFAULT_PLAN)

    private fun rowFor(word: String) = runBlocking(Dispatchers.IO) {
        database.wordDao().byNormalized(writePackId, word)
    }

    private fun countInPack() = runBlocking(Dispatchers.IO) {
        database.wordDao().countInPack(writePackId)
    }

    // --- writing a word --------------------------------------------------------------------

    @Test
    fun `writing a word stores it as PENDING with the note and enqueues the gateway`() {
        writeAndAwait("apple", "苹果公司")

        val state = viewModel.state.value
        assertThat(state.written).hasSize(1)
        assertThat(state.written.single().word).isEqualTo("apple")
        assertThat(state.written.single().note).isEqualTo("苹果公司")
        assertThat(state.planId).isEqualTo(PlanIds.DEFAULT_PLAN)
        assertThat(state.wordInput).isEmpty()
        assertThat(state.noteInput).isEmpty()
        assertThat(state.saving).isFalse()

        val row = rowFor("apple")!!
        assertThat(row.planId).isEqualTo(PlanIds.DEFAULT_PLAN)
        assertThat(row.lookupState).isEqualTo("PENDING")
        assertThat(row.meanings).isEqualTo("[]")
        assertThat(row.userNote).isEqualTo("苹果公司")
        assertThat(meaningSync.enqueues).isEqualTo(1)
    }

    @Test
    fun `writing a blank word is refused and writes nothing`() {
        viewModel.onWordChanged("   ")
        viewModel.write()
        awaitCondition("the empty-input message") { viewModel.state.value.message != null }

        assertThat(viewModel.state.value.message).isEqualTo("请输入要写入的单词。")
        assertThat(viewModel.state.value.written).isEmpty()
        assertThat(countInPack()).isEqualTo(0)
        assertThat(meaningSync.enqueues).isEqualTo(0)
    }

    /**
     * The regression this guards: a second write of the same word (case variant included) resolves
     * to the same row id, so keeping both copies on show would give the LazyColumn two identical
     * keys — which Compose rejects with an `IllegalArgumentException`.
     */
    @Test
    fun `writing the same word twice keeps a single recent row`() {
        writeAndAwait("apple", "第一版")
        writeAndAwait("Apple", "第二版")

        val written = viewModel.state.value.written
        assertThat(written).hasSize(1)
        assertThat(written.single().note).isEqualTo("第二版")
        assertThat(countInPack()).isEqualTo(1)
    }

    @Test
    fun `the recent list keeps only the newest MAX_RECENT words`() {
        val total = WriteWordViewModel.MAX_RECENT + 1
        repeat(total) { index -> writeAndAwait("word-${index + 1}") }

        val written = viewModel.state.value.written
        assertThat(written).hasSize(WriteWordViewModel.MAX_RECENT)
        assertThat(written.first().word).isEqualTo("word-$total")
        assertThat(written.last().word).isEqualTo("word-2") // word-1 fell off the end
    }

    // --- commit feedback (focus tick, session counter, inline message) ------------------------

    @Test
    fun `a successful write bumps the commit tick and the session count`() {
        writeAndAwait("apple", "苹果")

        // All three land in the same state emission as the confirmation message, so once
        // [writeAndAwait] returned they are guaranteed visible — the screen keys its
        // "cursor back to the word field" effect on `commitTick` and its header on
        // `sessionCount`.
        val state = viewModel.state.value
        assertThat(state.commitTick).isEqualTo(1)
        assertThat(state.sessionCount).isEqualTo(1)
        assertThat(state.messageIsError).isFalse()
    }

    @Test
    fun `writing the same word twice counts one distinct word but two commits`() {
        writeAndAwait("apple", "第一版")
        // Same word again: the confirmation text would be identical, so await the commit counter.
        viewModel.onWordChanged("apple")
        viewModel.onNoteChanged("第二版")
        viewModel.write()
        awaitCondition("the second write of apple to land") {
            viewModel.state.value.commitTick == 2
        }

        val state = viewModel.state.value
        assertThat(state.commitTick).isEqualTo(2)
        assertThat(state.sessionCount).isEqualTo(1) // distinct words, not commits
        assertThat(state.written).hasSize(1)
        assertThat(state.written.single().note).isEqualTo("第二版")
        assertThat(countInPack()).isEqualTo(1)
    }

    @Test
    fun `a blank word leaves the counters untouched and flags an error`() {
        viewModel.onWordChanged("   ")
        viewModel.write()
        awaitCondition("the empty-input message") { viewModel.state.value.message != null }

        val state = viewModel.state.value
        assertThat(state.commitTick).isEqualTo(0)
        assertThat(state.sessionCount).isEqualTo(0)
        assertThat(state.messageIsError).isTrue()
        assertThat(state.message).isEqualTo("请输入要写入的单词。")
        assertThat(countInPack()).isEqualTo(0)
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
