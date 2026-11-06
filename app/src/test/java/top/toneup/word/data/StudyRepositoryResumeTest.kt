package top.toneup.word.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import top.toneup.word.core.json.JsonCodec
import top.toneup.word.core.time.TimeProvider
import top.toneup.word.data.local.ToneUpWordDatabase
import top.toneup.word.data.local.entity.StudySessionEntity
import top.toneup.word.data.local.entity.WordEntity
import top.toneup.word.data.repository.DefaultStudyRepository
import top.toneup.word.data.repository.ProgressRecorder
import top.toneup.word.data.repository.SessionStatus
import top.toneup.word.data.settings.PackIds
import top.toneup.word.data.settings.PlanIds
import top.toneup.word.domain.model.CetLevel
import top.toneup.word.domain.model.Word
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

/**
 * Resume-path tests for [DefaultStudyRepository].
 *
 * The regression these guard: a session row left `ACTIVE` after its queue was exhausted (the
 * summary screen used to re-persist a closed session as ACTIVE), or after some of its words were
 * deleted with the pack. Resuming such a row opened the study screen on an instantly "finished"
 * session on every later day.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class StudyRepositoryResumeTest {

    private class FakeTimeProvider(private var nowMillis: Long) : TimeProvider {
        override fun nowMillis(): Long = nowMillis

        override fun zone(): ZoneId = ZoneId.of("Asia/Shanghai")
    }

    private lateinit var database: ToneUpWordDatabase
    private lateinit var time: FakeTimeProvider
    private lateinit var repository: DefaultStudyRepository

    private val start = 1_768_442_400_000L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ToneUpWordDatabase::class.java,
        )
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .build()
        database.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys=ON")
        time = FakeTimeProvider(start)
        repository = DefaultStudyRepository(database, time, ProgressRecorder(database, time))
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun insertWord(word: String): Long =
        database.wordDao().insert(
            WordEntity(
                packId = PackIds.BUILTIN_CET6,
                planId = PlanIds.DEFAULT_PLAN,
                word = word,
                normalizedWord = Word.normalizeWordKey(word),
                meanings = """["释义"]""",
                level = CetLevel.CET6.id,
                createdAt = start,
                updatedAt = start,
            ),
        )

    private suspend fun insertSession(
        queue: List<Long>,
        position: Int,
        status: String = SessionStatus.ACTIVE,
    ): Long = database.studySessionDao().insert(
        StudySessionEntity(
            level = CetLevel.CET6.id,
            packId = "",
            planId = PlanIds.DEFAULT_PLAN,
            status = status,
            startedAt = start,
            updatedAt = start,
            plannedNewCount = 0,
            plannedReviewCount = queue.size,
            queue = JsonCodec.encodeLongs(queue),
            queuePosition = position,
            immediateQueue = JsonCodec.encodeLongs(emptyList()),
            immediateRepeatCounts = JsonCodec.encodeIntCounts(emptyMap()),
        ),
    )

    // --- Exhausted cursor ----------------------------------------------------------------

    @Test
    fun `a session whose cursor is past the end is closed instead of resumed`() = runTest {
        val id = insertSession(queue = listOf(999L, 1_000L), position = 2)

        assertThat(repository.resumeActiveSession(PlanIds.DEFAULT_PLAN)).isNull()
        assertThat(database.studySessionDao().byId(id)?.status).isEqualTo(SessionStatus.COMPLETED)
    }

    // --- Deleted words ---------------------------------------------------------------

    @Test
    fun `missing words are dropped and the cursor shifts to the same current card`() = runTest {
        val apple = insertWord("apple")
        val banana = insertWord("banana")
        val id = insertSession(queue = listOf(apple, 777L, banana), position = 2)

        val state = checkNotNull(repository.resumeActiveSession(PlanIds.DEFAULT_PLAN))

        assertThat(state.sessionId).isEqualTo(id)
        assertThat(state.queue).containsExactly(apple, banana).inOrder()
        assertThat(state.position).isEqualTo(1)
        assertThat(state.currentWordId).isEqualTo(banana)
    }

    @Test
    fun `a session whose remaining words are all gone is abandoned`() = runTest {
        val id = insertSession(queue = listOf(888L, 999L), position = 1)

        assertThat(repository.resumeActiveSession(PlanIds.DEFAULT_PLAN)).isNull()
        assertThat(database.studySessionDao().byId(id)?.status).isEqualTo(SessionStatus.ABANDONED)
    }
}
