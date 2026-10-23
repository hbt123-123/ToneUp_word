package top.toneup.word.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
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
import top.toneup.word.data.repository.AnswerSource
import top.toneup.word.data.repository.DefaultWordRepository
import top.toneup.word.data.repository.ProgressRecorder
import top.toneup.word.data.seed.BuiltInPackDefinition
import top.toneup.word.data.seed.PackSource
import top.toneup.word.data.settings.PlanIds
import top.toneup.word.domain.importer.ImportPreview
import top.toneup.word.domain.srs.ReviewGrade
import java.time.ZoneId

/**
 * Plan-scoped repository behaviour (schema v4): plan CRUD, moving a pack between plans, the
 * de-duplicated study pool (D2) and the progress hand-over when a pack is deleted (D10).
 *
 * The interesting cases are the ones where a plan spans several packs: a word present in two
 * packs must be studied once, and deleting one of those packs must never turn the word back
 * into an unstudied one.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26])
class PlanRepositoryTest {

    private class FixedTime(private var now: Long = 1_768_450_800_000L) : TimeProvider {
        override fun nowMillis(): Long = now
        override fun zone(): ZoneId = ZoneId.of("Asia/Shanghai")
    }

    /** No assets: the tests create their own plans and packs. */
    private object EmptyPackSource : PackSource {
        override suspend fun definitions(): List<BuiltInPackDefinition> = emptyList()
        override suspend fun load(definition: BuiltInPackDefinition): Outcome<ImportPreview> =
            Outcome.Failure(AppError.io("no assets in test"))
    }

    private lateinit var database: ToneUpWordDatabase
    private lateinit var repository: DefaultWordRepository
    private lateinit var recorder: ProgressRecorder

    private val now = 1_768_450_800_000L
    private val time = FixedTime(now)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ToneUpWordDatabase::class.java,
        ).allowMainThreadQueries().build()
        database.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys=ON")
        repository = DefaultWordRepository(db = database, time = time, packLoader = EmptyPackSource)
        recorder = ProgressRecorder(database, time)
    }

    @After
    fun tearDown() = database.close()

    // --- helpers ---------------------------------------------------------------------------

    private suspend fun newPlan(name: String): String {
        repository.ensureDefaultPlan()
        return requireNotNull(repository.createPlan(name, now).getOrNull())
    }

    private suspend fun newPack(name: String, planId: String): String =
        requireNotNull(repository.createPack(name, planId, now).getOrNull())

    private suspend fun wordIdIn(packId: String, word: String): Long {
        assertThat(repository.addWordsToPack(packId, listOf(word), now).isSuccess).isTrue()
        return requireNotNull(database.wordDao().byNormalized(packId, word)).id
    }

    // --- plan CRUD -------------------------------------------------------------------------

    @Test
    fun `createPlan trims the name and refuses blank or over-long names`() = runTest {
        val id = newPlan("  外国文献单词计划  ")

        assertThat(database.planDao().planById(id)!!.name).isEqualTo("外国文献单词计划")
        assertThat(database.planDao().planById(id)!!.isBuiltIn).isFalse()

        val blank = repository.createPlan("   ", now)
        assertThat(blank.isSuccess).isFalse()

        val tooLong = repository.createPlan("长".repeat(41), now)
        assertThat(tooLong.isSuccess).isFalse()
    }

    @Test
    fun `the built-in plan lists first and can be renamed but never deleted`() = runTest {
        repository.ensureDefaultPlan()
        val second = requireNotNull(repository.createPlan("时代周刊", now).getOrNull())

        assertThat(repository.observePlans().first().map { it.id })
            .containsExactly(PlanIds.DEFAULT_PLAN, second).inOrder()

        // D7: the default plan keeps its id but not necessarily its name.
        assertThat(repository.renamePlan(PlanIds.DEFAULT_PLAN, "我的默认计划").isSuccess).isTrue()
        assertThat(database.planDao().planById(PlanIds.DEFAULT_PLAN)!!.name).isEqualTo("我的默认计划")

        assertThat(repository.deletePlan(PlanIds.DEFAULT_PLAN).isSuccess).isFalse()
    }

    @Test
    fun `deletePlan refuses a plan that still owns packs and accepts an empty one`() = runTest {
        val planId = newPlan("经济学人")
        newPack("经济学人单词", planId)

        val refused = repository.deletePlan(planId)
        assertThat(refused.isSuccess).isFalse()
        assertThat(database.planDao().planById(planId)).isNotNull()

        val empty = newPlan("空计划")
        assertThat(repository.deletePlan(empty).isSuccess).isTrue()
        assertThat(database.planDao().planById(empty)).isNull()
    }

    // --- moving packs ----------------------------------------------------------------------

    @Test
    fun `movePackToPlan moves the pack and its words into the other plan`() = runTest {
        val target = newPlan("外国文献单词计划")
        val packId = newPack("时代周刊单词", PlanIds.DEFAULT_PLAN)
        wordIdIn(packId, "apple")

        val moved = repository.movePackToPlan(packId, target)

        assertThat(moved.isSuccess).isTrue()
        assertThat(database.wordDao().packById(packId)!!.planId).isEqualTo(target)
        assertThat(database.wordDao().byNormalized(packId, "apple")!!.planId).isEqualTo(target)
        // The study pool follows the move: the word left the default plan and joined the target.
        assertThat(database.wordDao().planWordCount(PlanIds.DEFAULT_PLAN)).isEqualTo(0)
        assertThat(database.wordDao().planWordCount(target)).isEqualTo(1)
    }

    @Test
    fun `movePackToPlan refuses a missing pack or plan`() = runTest {
        val planId = newPlan("目标计划")
        val packId = newPack("词库", PlanIds.DEFAULT_PLAN)

        assertThat(repository.movePackToPlan("no-such-pack", planId).isSuccess).isFalse()
        assertThat(repository.movePackToPlan(packId, "no-such-plan").isSuccess).isFalse()
        assertThat(database.wordDao().packById(packId)!!.planId).isEqualTo(PlanIds.DEFAULT_PLAN)
    }

    // --- one study pool per plan -----------------------------------------------------------

    @Test
    fun `the same word in two packs of one plan is studied once and shares one progress row`() =
        runTest {
            val first = newPack("时代周刊单词", PlanIds.DEFAULT_PLAN)
            val second = newPack("经济学人单词", PlanIds.DEFAULT_PLAN)
            val firstApple = wordIdIn(first, "apple")
            val secondApple = wordIdIn(second, "apple")

            assertThat(database.wordDao().countInPack(first)).isEqualTo(1)
            assertThat(database.wordDao().countInPack(second)).isEqualTo(1)
            // D2: the pool counts the word once, whichever pack it came from.
            assertThat(database.wordDao().planWordCount(PlanIds.DEFAULT_PLAN)).isEqualTo(1)
            assertThat(database.wordDao().liteForPlan(PlanIds.DEFAULT_PLAN).map { it.word })
                .containsExactly("apple")

            // A second plan keeps its own copy — de-duplication never crosses plans.
            val other = newPlan("另一个计划")
            val otherPack = newPack("另一个词库", other)
            wordIdIn(otherPack, "apple")
            assertThat(database.wordDao().planWordCount(other)).isEqualTo(1)

            // Studying the copy the view picked makes that copy the canonical one.
            val visible = database.wordDao().canonicalInPlan(PlanIds.DEFAULT_PLAN, "apple")!!
            recorder.record(visible.id, ReviewGrade.MASTERED, AnswerSource.STUDY)
            assertThat(database.wordDao().canonicalInPlan(PlanIds.DEFAULT_PLAN, "apple")!!.id)
                .isEqualTo(visible.id)
            assertThat(
                database.progressDao().byWordId(
                    database.wordDao().canonicalInPlan(PlanIds.DEFAULT_PLAN, "apple")!!.id,
                ),
            ).isNotNull()
            assertThat(listOf(firstApple, secondApple)).contains(visible.id)
        }

    @Test
    fun `deleting a pack hands its progress to the surviving copy in the same plan`() = runTest {
        val kept = newPack("经济学人单词", PlanIds.DEFAULT_PLAN)
        val removed = newPack("时代周刊单词", PlanIds.DEFAULT_PLAN)
        wordIdIn(kept, "apple")
        val removedApple = wordIdIn(removed, "apple")

        // The learner studies the copy that is about to be deleted.
        recorder.record(removedApple, ReviewGrade.MASTERED, AnswerSource.STUDY)

        assertThat(repository.deletePack(removed).isSuccess).isTrue()

        val survivor = database.wordDao().byNormalized(kept, "apple")!!
        val progress = database.progressDao().byWordId(survivor.id)
        assertThat("deleting a pack must not un-learn a word").isNotNull()
        assertThat(progress!!.consecutiveCorrectCount).isEqualTo(1)
        assertThat(database.progressDao().byWordId(removedApple)).isNull()
        assertThat(database.wordDao().planWordCount(PlanIds.DEFAULT_PLAN)).isEqualTo(1)
    }

    @Test
    fun `each plan owns exactly one hand-written pack`() = runTest {
        val planId = newPlan("手写计划")

        val first = requireNotNull(repository.ensureWritePack(planId, now).getOrNull())
        val second = requireNotNull(repository.ensureWritePack(planId, now + 1).getOrNull())

        assertThat(first).isEqualTo(PlanIds.writePackIdFor(planId))
        assertThat(second).isEqualTo(first)
        assertThat(database.wordDao().observePacks(planId).first().map { it.id })
            .containsExactly(first)
    }

    @Test
    fun `a hand-written pack moved to another plan refuses further writes`() = runTest {
        val planId = newPlan("手写计划")
        val other = newPlan("另一个计划")
        val writePack = requireNotNull(repository.ensureWritePack(planId, now).getOrNull())

        assertThat(repository.movePackToPlan(writePack, other).isSuccess).isTrue()

        // The id embeds the plan, so writing through it would drop the word into the other
        // plan's study pool: the write flow must refuse instead.
        assertThat(repository.ensureWritePack(planId, now + 1).isSuccess).isFalse()

        // Moving it back — the fix the message points at — restores the flow.
        assertThat(repository.movePackToPlan(writePack, planId).isSuccess).isTrue()
        assertThat(repository.ensureWritePack(planId, now + 2).getOrNull()).isEqualTo(writePack)
    }
}
