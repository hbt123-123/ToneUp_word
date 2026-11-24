package top.toneup.word.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.toneup.word.domain.stats.StageGoal
import top.toneup.word.domain.study.NextStep
import top.toneup.word.domain.study.NextStepPlanner
import top.toneup.word.domain.study.TodayPlan
import top.toneup.word.domain.srs.MasteryStatus
import top.toneup.word.ui.theme.AppTheme
import top.toneup.word.ui.theme.Spacing

/**
 * The dashboard cards of the home screen.
 *
 * They are separate from `HomeScreen` so each one can be reviewed (and screenshot-tested) in
 * isolation, and so the home screen itself stays readable: its job is ordering, not layout detail.
 *
 * The hierarchy follows the product decision "the home screen is an entry point, not a
 * dashboard": today's progress and the next action come first, the word distribution is
 * secondary context, and nothing else competes with them above the fold.
 *
 * Every number can be explained through 「统计口径」; the definition text lives in
 * `StatDefinition` so the docs and the UI cannot drift apart.
 */

/** ① Today's progress: one big "done / planned" number, one bar, one honest remainder. */
@Composable
fun TodayProgressCard(
    plan: TodayPlan,
    onShowDefinitions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppCard(modifier = modifier.testTag("home_today_progress")) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "今日学习进度", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onShowDefinitions, modifier = Modifier.testTag("home_definitions")) {
                    Text("统计口径")
                }
            }

            if (plan.plannedCards <= 0) {
                // The vocabulary exists but nothing is due and no new words are planned: a full
                // bar plus a plain sentence, never a "0 / 0" that reads like a broken counter.
                LinearProgressIndicator(
                    progress = { 1f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 10.dp),
                    strokeCap = StrokeCap.Round,
                    drawStopIndicator = {},
                )
                Text(
                    text = "今日没有待办，可以休息一下。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val remaining = plan.plannedCards - plan.doneCards
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Text(
                        text = "${plan.doneCards}",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = "/ ${plan.plannedCards}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LinearProgressIndicator(
                    progress = { plan.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 10.dp)
                        .clearAndSetSemantics {
                            progressBarRangeInfo = ProgressBarRangeInfo(plan.progressFraction, 0f..1f)
                            contentDescription =
                                "今日进度：已完成 ${plan.doneCards} 个，计划 ${plan.plannedCards} 个"
                        },
                    strokeCap = StrokeCap.Round,
                    drawStopIndicator = {},
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "已学 ${plan.doneCards} 词",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        // doneCards is capped at plannedCards, so this is never negative.
                        text = "剩余 $remaining 词",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** ② The one thing to do next: a headline, one supporting line, and a concrete button. */
@Composable
fun NextStepCard(
    step: NextStep,
    onStudy: () -> Unit,
    onReview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppCard(modifier = modifier.testTag("home_next_step")) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "今天该做什么", style = MaterialTheme.typography.titleMedium)
                NextStepPlanner.tag(step)?.let { tag ->
                    // A quiet badge: it names the priority without competing with the headline.
                    Surface(
                        shape = RoundedCornerShape(percent = 50),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp),
                        )
                    }
                }
            }
            Text(
                text = NextStepPlanner.headline(step),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag("home_next_step_text"),
            )
            NextStepPlanner.subline(step)?.let { subline ->
                Text(
                    text = subline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (step) {
                is NextStep.Review -> PrimaryButton(
                    // These three subtypes always define an actionLabel; the fallback only
                    // satisfies the nullable interface type.
                    text = step.actionLabel ?: "开始复习",
                    onClick = onReview,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("home_next_step_action"),
                )

                is NextStep.LearnNew -> PrimaryButton(
                    text = step.actionLabel ?: "开始背新词",
                    onClick = onStudy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("home_next_step_action"),
                )

                is NextStep.PracticeMistakes -> PrimaryButton(
                    text = step.actionLabel ?: "巩固错题",
                    onClick = onReview,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("home_next_step_action"),
                )

                // Finished and "nothing loaded" have no primary action: offering one would imply
                // there is work left when there is none.
                NextStep.AllDone, is NextStep.NoVocabulary -> Unit
            }
        }
    }
}

/**
 * ③ Word-status distribution with the next milestone folded in.
 *
 * One bar, three numbers, no chart to interpret; the stage goal loses its own card and becomes
 * one quiet line here, because "where the vocabulary stands" and "what the next milestone is"
 * are the same story told twice.
 */
@Composable
fun WordStatusCard(
    statusCounts: Map<MasteryStatus, Int>,
    totalWords: Int,
    modifier: Modifier = Modifier,
    stageGoal: StageGoal? = null,
) {
    val mastered = statusCounts[MasteryStatus.MASTERED] ?: 0
    val learning = statusCounts[MasteryStatus.LEARNING] ?: 0
    val untouched = (totalWords - mastered - learning).coerceAtLeast(0)

    AppCard(modifier = modifier.testTag("home_word_status")) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(text = "词汇分布", style = MaterialTheme.typography.titleMedium)
            if (totalWords <= 0) {
                Text(
                    text = "词库还没有加载完成。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .semantics {
                        contentDescription = "已掌握 $mastered 个，学习中 $learning 个，待学习 $untouched 个"
                    },
            ) {
                StatusSegment(weight = mastered, color = AppTheme.colors.successGreen)
                StatusSegment(weight = learning, color = MaterialTheme.colorScheme.primary)
                StatusSegment(weight = untouched, color = MaterialTheme.colorScheme.surfaceContainerHigh)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                LegendItem(color = AppTheme.colors.successGreen, label = "已掌握", count = mastered)
                LegendItem(color = MaterialTheme.colorScheme.primary, label = "学习中", count = learning)
                LegendItem(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    label = "待学习",
                    count = untouched,
                )
            }
            // The former StageGoalCard, reduced to one line: a milestone is context here, not a task.
            stageGoal?.let { goal ->
                Text(
                    text = if (goal.isReached) {
                        "已达成 ${goal.target} 词，继续下一个目标。"
                    } else {
                        "下一个目标：${goal.label} · ${goal.current} / ${goal.target} 词"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusSegment(weight: Int, color: androidx.compose.ui.graphics.Color) {
    if (weight <= 0) return
    Box(
        modifier = Modifier
            .fillMaxWidth(weight.toFloat())
            .height(12.dp)
            .background(color),
    )
}

@Composable
private fun LegendItem(color: androidx.compose.ui.graphics.Color, label: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = "$label $count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
