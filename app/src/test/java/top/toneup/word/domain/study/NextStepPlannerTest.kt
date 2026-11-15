package top.toneup.word.domain.study

import top.toneup.word.domain.model.CetLevel
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for the "next step" decision.
 *
 * The product requirement is that the home screen always answers "what should I do next" with
 * something concrete, so these tests pin the three states a learner can actually be in, plus the
 * two edge cases (nothing loaded at all, and a plan that is already finished).
 */
class NextStepPlannerTest {

    private fun plan(
        dueCount: Int = 0,
        newAvailableCount: Int = 0,
        activeMistakeCount: Int = 0,
        studiedToday: Int = 0,
        answeredToday: Int = 0,
        dailyNewGoal: Int = 20,
        dailyReviewLimit: Int = 60,
    ) = TodayPlan(
        level = CetLevel.CET4,
        dueCount = dueCount,
        fragileCount = 0,
        newAvailableCount = newAvailableCount,
        activeMistakeCount = activeMistakeCount,
        studiedToday = studiedToday,
        answeredToday = answeredToday,
        correctToday = 0,
        dailyNewGoal = dailyNewGoal,
        dailyReviewLimit = dailyReviewLimit,
    )

    @Test
    fun `due reviews win over new words`() {
        val step = NextStepPlanner.plan(plan(dueCount = 12, newAvailableCount = 100, studiedToday = 0))

        assertThat(step).isInstanceOf(NextStep.Review::class.java)
        assertThat((step as NextStep.Review).dueCount).isEqualTo(12)
        assertThat(step.destination).isEqualTo(NextStepDestination.REVIEW)
        assertThat(step.actionLabel).isEqualTo("开始复习")
    }

    @Test
    fun `review estimate rounds up to whole minutes`() {
        // 12 cards * 20s = 240s = exactly 4 minutes.
        val exact = NextStepPlanner.plan(plan(dueCount = 12)) as NextStep.Review
        assertThat(exact.estimatedMinutes).isEqualTo(4)

        // 7 cards * 20s = 140s -> 3 minutes (never "2" and never "0").
        val rounded = NextStepPlanner.plan(plan(dueCount = 7)) as NextStep.Review
        assertThat(rounded.estimatedMinutes).isEqualTo(3)

        // A single card must not report "约 0 分钟".
        val single = NextStepPlanner.plan(plan(dueCount = 1)) as NextStep.Review
        assertThat(single.estimatedMinutes).isEqualTo(1)
    }

    @Test
    fun `new words are offered when nothing is due`() {
        val step = NextStepPlanner.plan(
            plan(dueCount = 0, newAvailableCount = 100, studiedToday = 5, dailyNewGoal = 20),
        )

        assertThat(step).isInstanceOf(NextStep.LearnNew::class.java)
        assertThat((step as NextStep.LearnNew).newCount).isEqualTo(15)
        assertThat(step.destination).isEqualTo(NextStepDestination.STUDY)
    }

    @Test
    fun `new words never exceed the daily goal or the available supply`() {
        val byGoal = NextStepPlanner.plan(plan(newAvailableCount = 500, studiedToday = 19, dailyNewGoal = 20))
        assertThat((byGoal as NextStep.LearnNew).newCount).isEqualTo(1)

        val bySupply = NextStepPlanner.plan(plan(newAvailableCount = 3, studiedToday = 0, dailyNewGoal = 20))
        assertThat((bySupply as NextStep.LearnNew).newCount).isEqualTo(3)
    }

    @Test
    fun `mistakes are suggested once the plan is finished`() {
        val step = NextStepPlanner.plan(
            plan(dueCount = 0, newAvailableCount = 0, studiedToday = 20, activeMistakeCount = 7),
        )

        assertThat(step).isInstanceOf(NextStep.PracticeMistakes::class.java)
        assertThat((step as NextStep.PracticeMistakes).mistakeCount).isEqualTo(7)
        assertThat(step.destination).isEqualTo(NextStepDestination.REVIEW)
    }

    @Test
    fun `a finished day reports completion when there is nothing left`() {
        val step = NextStepPlanner.plan(plan(studiedToday = 20, answeredToday = 20))

        assertThat(step).isEqualTo(NextStep.AllDone)
        assertThat(step.actionLabel).isNull()
        assertThat(step.destination).isEqualTo(NextStepDestination.NONE)
    }

    @Test
    fun `an empty vocabulary is not reported as a finished day`() {
        val step = NextStepPlanner.plan(plan(newAvailableCount = 0, studiedToday = 0))

        assertThat(step).isInstanceOf(NextStep.NoVocabulary::class.java)
        // It must not congratulate the learner for completing a list that never loaded.
        assertThat(NextStepPlanner.describe(step)).doesNotContain("已完成")
    }

    @Test
    fun `describe always produces a sentence with the real number`() {
        assertThat(NextStepPlanner.describe(NextStep.Review(12, 4))).isEqualTo("还需复习 12 个单词，约 4 分钟")
        assertThat(NextStepPlanner.describe(NextStep.LearnNew(15, 12))).isEqualTo("今天还可以学 15 个新词，约 12 分钟")
        assertThat(NextStepPlanner.describe(NextStep.PracticeMistakes(7))).contains("7 个错题")
        assertThat(NextStepPlanner.describe(NextStep.AllDone)).contains("已完成")
    }

    @Test
    fun `tag names the priority for each actionable state and stays empty otherwise`() {
        assertThat(NextStepPlanner.tag(NextStep.Review(15, 5))).isEqualTo("优先复习")
        assertThat(NextStepPlanner.tag(NextStep.LearnNew(15, 12))).isEqualTo("学习新词")
        assertThat(NextStepPlanner.tag(NextStep.PracticeMistakes(7))).isEqualTo("巩固错题")
        // Finished and "nothing loaded" promise no priority: a badge there would imply work.
        assertThat(NextStepPlanner.tag(NextStep.AllDone)).isNull()
        assertThat(NextStepPlanner.tag(NextStep.NoVocabulary("请导入词库"))).isNull()
    }

    @Test
    fun `headline states the concrete count`() {
        // Chinese count nouns do not inflect, so one template covers singular and plural alike.
        assertThat(NextStepPlanner.headline(NextStep.Review(15, 5))).isEqualTo("15 个待复习")
        assertThat(NextStepPlanner.headline(NextStep.Review(1, 1))).isEqualTo("1 个待复习")
        assertThat(NextStepPlanner.headline(NextStep.LearnNew(15, 12))).isEqualTo("今天还可以学 15 个新词")
        assertThat(NextStepPlanner.headline(NextStep.LearnNew(1, 1))).isEqualTo("今天还可以学 1 个新词")
        assertThat(NextStepPlanner.headline(NextStep.PracticeMistakes(7))).isEqualTo("巩固 7 个错题")
        assertThat(NextStepPlanner.headline(NextStep.AllDone)).isEqualTo("今天的计划已完成")
        // A missing vocabulary passes the message through unchanged — it is the headline.
        assertThat(NextStepPlanner.headline(NextStep.NoVocabulary("请导入词库"))).isEqualTo("请导入词库")
    }

    @Test
    fun `subline explains time and intent, or is null when there is nothing to add`() {
        assertThat(NextStepPlanner.subline(NextStep.Review(15, 5)))
            .isEqualTo("预计 5 分钟 · 优先巩固即将遗忘的单词")
        assertThat(NextStepPlanner.subline(NextStep.LearnNew(15, 12)))
            .isEqualTo("预计 12 分钟 · 新词按每日目标安排")
        assertThat(NextStepPlanner.subline(NextStep.PracticeMistakes(7)))
            .isEqualTo("今天的计划已完成，巩固错题是最好的收尾")
        assertThat(NextStepPlanner.subline(NextStep.AllDone)).isEqualTo("明天继续保持")
        assertThat(NextStepPlanner.subline(NextStep.NoVocabulary("请导入词库"))).isNull()
    }

    @Test
    fun `negative counts never leak into the plan`() {
        // Defensive: TodayPlan is derived data and a repository bug must not produce "-3 个单词".
        val step = NextStepPlanner.plan(plan(dueCount = -5, newAvailableCount = -1, activeMistakeCount = -2))
        assertThat(step).isInstanceOf(NextStep.NoVocabulary::class.java)
    }
}
