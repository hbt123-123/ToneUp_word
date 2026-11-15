package top.toneup.word.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import top.toneup.word.AppContainer
import top.toneup.word.domain.motivation.MotivationContext
import top.toneup.word.domain.motivation.MotivationEngine
import top.toneup.word.domain.motivation.MotivationMoment
import top.toneup.word.domain.study.SessionSummary
import top.toneup.word.domain.srs.ReviewGrade
import top.toneup.word.ui.StudyViewModel
import top.toneup.word.ui.ViewModelFactories
import top.toneup.word.ui.components.AnswerActionBar
import top.toneup.word.ui.components.AppCard
import top.toneup.word.ui.components.EmptyState
import top.toneup.word.ui.components.ErrorState
import top.toneup.word.ui.components.LoadingState
import top.toneup.word.ui.components.PrimaryButton
import top.toneup.word.ui.components.SecondaryButton
import top.toneup.word.ui.components.SectionHeader
import top.toneup.word.ui.components.StatCard
import top.toneup.word.ui.components.StudyProgressIndicator
import top.toneup.word.ui.components.WordCard
import top.toneup.word.ui.components.WordEditDialog
import top.toneup.word.ui.speech.rememberSpeechActions
import top.toneup.word.ui.theme.AppTheme
import top.toneup.word.ui.theme.ToneUpWordTheme
import top.toneup.word.ui.theme.Spacing

/**
 * The study session (单词记忆页面).
 *
 * Behaviour that the design brief calls out explicitly:
 *  * the meaning is hidden until the learner taps the card;
 *  * every answer is written to the database immediately, so leaving the screen keeps progress;
 *  * the session row is persisted after each card, so a process restart resumes the same queue;
 *  * an answer in flight disables the three buttons, which is what prevents double recording;
 *  * the grading area is pinned below the scrollable content, so a long revealed meaning never
 *    pushes the buttons out of reach;
 *  * the usage hints (swipe, save reassurance) show for the first two cards of a session only;
 *  * the system back gesture pauses (never loses) the session.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyScreen(
    container: AppContainer,
    drillIds: List<Long>,
    onExit: () -> Unit,
    onGoHome: () -> Unit,
) {
    val viewModel: StudyViewModel = viewModel(factory = ViewModelFactories.study(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val speech = rememberSpeechActions(
        service = container.speechService,
        rateModeProvider = { state.speechRateMode },
        engineModeProvider = { state.speechEngineMode },
        edgeTts = container.edgeTts,
    )
    val speechState by speech.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    // Note/meaning editor (plan §9): the dialog writes through the repository, and the card is
    // re-read from the database when it closes so the reveal shows the text just saved.
    var showEdit by remember { mutableStateOf(false) }

    LaunchedEffect(drillIds) { viewModel.start(drillIds) }

    // Optional auto-play: fires only when the card changes, so scrolling or a recomposition cannot
    // restart the reading.
    LaunchedEffect(state.card?.word?.id, state.autoPlayPronunciation) {
        val word = state.card?.word
        if (state.autoPlayPronunciation && word != null) {
            speech.speakWord(word)
        }
    }

    // One light haptic per graded answer: confirms "recorded" without becoming noise over a
    // long session (view-level haptic — no permission, no sound). The write-through semantics
    // of [StudyViewModel.answer] are untouched.
    val answerWith: (ReviewGrade) -> Unit = { grade ->
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        viewModel.answer(grade)
    }

    // Pausing persists the cursor; the session row stays ACTIVE so the home screen can resume it.
    val pauseAndLeave: () -> Unit = {
        viewModel.pauseAndExit()
        onExit()
    }
    BackHandler(onBack = pauseAndLeave)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (drillIds.isEmpty()) "背单词" else "巩固练习") },
                navigationIcon = {
                    IconButton(
                        onClick = pauseAndLeave,
                        modifier = Modifier.semantics { contentDescription = "返回" },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val session = state.session
        val card = state.card
        when {
            state.loading -> LoadingState(
                text = "正在准备学习队列…",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )

            state.errorMessage != null -> ErrorState(
                message = state.errorMessage!!,
                onRetry = viewModel::dismissError,
                retryLabel = "知道了",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )

            state.finished -> SummaryContent(
                summary = state.summary,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onPracticeMistakes = viewModel::retryFromSummary,
                onGoHome = onGoHome,
            )

            card == null -> EmptyState(
                title = "暂时没有需要学习的单词",
                description = state.emptyMessage ?: "今天的计划已经完成，可以到错题本继续巩固。",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                actionLabel = "返回首页",
                onAction = onGoHome,
            )

            else -> {
                // Reset the page position whenever the card changes: a long expanded meaning
                // could otherwise leave the next card scrolled past its prompt.
                val scrollState = rememberScrollState()
                LaunchedEffect(card.word.id) { scrollState.scrollTo(0) }
                val swipeThreshold = with(LocalDensity.current) { 64.dp.toPx() }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(scrollState)
                            .padding(horizontal = Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                    ) {
                        StudyProgressIndicator(
                            current = (session?.position ?: 0) + 1,
                            total = session?.totalCards ?: 0,
                            reasonLabel = state.reasonLabel,
                            modifier = Modifier.testTag("study_progress"),
                        )

                        // `key` scopes the card subtree to the word: a reveal animation from the
                        // previous word cannot leak into the next card, which always starts collapsed.
                        key(card.word.id) {
                            WordCard(
                                word = card.word,
                                progress = card.progress,
                                revealed = state.revealed,
                                isFavorite = card.progress.isFavorite,
                                onToggleReveal = viewModel::reveal,
                                onToggleFavorite = viewModel::toggleFavorite,
                                onSpeakWord = { speech.speakWord(card.word) },
                                speechState = speechState,
                                // Reading the example is offered only when the card actually has one.
                                onSpeakExample = if (state.readExampleSentences) {
                                    { speech.speakExample(card.word) }
                                } else {
                                    null
                                },
                                onRetrySpeech = speech::retry,
                                onOpenSpeechSettings = speech::openSystemSpeechSettings,
                                onInstallVoiceData = speech::installVoiceData,
                                onEdit = { showEdit = true },
                                modifier = Modifier
                                    // Swipe as page-turn: drag left pages to the meaning, drag right
                                    // pages back to the prompt. Tapping still works.
                                    .pointerInput(card.word.id) {
                                        var dragged = 0f
                                        detectHorizontalDragGestures(
                                            onDragStart = { dragged = 0f },
                                            onDragEnd = {
                                                when {
                                                    dragged <= -swipeThreshold -> viewModel.reveal()
                                                    dragged >= swipeThreshold -> viewModel.hideReveal()
                                                }
                                            },
                                            onHorizontalDrag = { change, amount ->
                                                dragged += amount
                                                change.consume()
                                            },
                                        )
                                    }
                                    .testTag("study_word_card"),
                            )
                        }

                        // Session rhythm: repeating the same instructions on every card turns them
                        // into noise after a few dozen words. They are shown for the first two cards
                        // only; a resumed session (whose position has already advanced) never sees
                        // them again.
                        if ((session?.position ?: 0) < 2) {
                            Text(
                                text = "左右滑动卡片可翻到释义或翻回正面；答题结果即时保存，中途退出不丢失进度",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("study_swipe_hint"),
                            )
                        }
                    }

                    // The grading area lives below the scrollable content, pinned to the bottom:
                    // a long revealed meaning must never push the buttons out of reach.
                    Column(
                        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    ) {
                        if (!state.revealed) {
                            PrimaryButton(
                                text = "显示释义",
                                onClick = viewModel::reveal,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("study_reveal"),
                            )
                        } else {
                            AnswerActionBar(
                                onUnknown = { answerWith(ReviewGrade.UNKNOWN) },
                                onFuzzy = { answerWith(ReviewGrade.FUZZY) },
                                onMastered = { answerWith(ReviewGrade.MASTERED) },
                                enabled = !state.saving,
                                modifier = Modifier.testTag("study_answer_bar"),
                            )
                        }
                    }
                }
            }
        }
    }

    val editingCard = state.card
    if (showEdit && editingCard != null) {
        WordEditDialog(
            word = editingCard.word,
            onSaveNote = { note ->
                viewModel.saveUserNote(editingCard.word.id, note)
            },
            onSaveMeaning = { meanings ->
                viewModel.saveManualMeaning(editingCard.word.id, meanings)
            },
            onDismiss = {
                showEdit = false
                // The queue is a snapshot: only the card on screen is re-read, so an edit can
                // never reshuffle the session the learner is in the middle of.
                viewModel.refreshCard()
            },
        )
    }
}

/** End-of-session summary ("学习总结"). */
@Composable
private fun SummaryContent(
    summary: SessionSummary?,
    modifier: Modifier = Modifier,
    onPracticeMistakes: () -> Unit,
    onGoHome: () -> Unit,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        AppCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("本次学习完成", style = MaterialTheme.typography.headlineSmall)
                Text(
                    // The closing line comes from the same engine the dashboard uses, so the tone is
                    // identical everywhere; `encouragement` (the accuracy-band phrasing) stays as the
                    // fallback for the rare summary without a percentage.
                    text = summary?.let { completionMessage(it) }
                        ?: "已完成本次学习",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("study_summary_motivation"),
                )
                summary?.let { finished ->
                    // A concrete next step, so the last screen of a session still answers "and now?".
                    Text(
                        text = if (finished.mistakeWordIds.size >= 5) {
                            "这 ${finished.mistakeWordIds.size} 个词已加入错题本，明天优先复习效果最好。"
                        } else if (finished.accuracyPercent != null && finished.accuracyPercent!! >= 90) {
                            "这一组掌握得很好，可以继续下一组，或明天再来。"
                        } else {
                            "错题已加入错题本，稍后复习一遍会更牢。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (summary != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                StatCard(
                    label = "作答",
                    value = "${summary.answeredCards}",
                    caption = "${summary.distinctWords} 个不同单词",
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    label = "正确率",
                    value = summary.accuracyPercent?.let { "$it%" } ?: "—",
                    caption = "已掌握 ${summary.masteredCards} 次",
                    accent = AppTheme.colors.successGreen,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                StatCard(
                    label = "模糊",
                    value = "${summary.fuzzyCards}",
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    label = "不认识",
                    value = "${summary.unknownCards}",
                    accent = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
            }

            AppCard {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SectionHeader(title = "本次详情")
                    KeyValueRow("新词卡片", "${summary.newCards}")
                    KeyValueRow("复习卡片", "${summary.reviewCards}")
                    KeyValueRow("用时", "${summary.durationMinutes} 分钟")
                    KeyValueRow("进入错题本", "${summary.mistakeWordIds.size} 个单词")
                }
            }
        }

        if (summary != null && summary.mistakeWordIds.isNotEmpty()) {
            PrimaryButton(
                text = "再练一次错词（${summary.mistakeWordIds.size}）",
                onClick = onPracticeMistakes,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("summary_practice_mistakes"),
            )
        }
        SecondaryButton(
            text = "返回首页",
            onClick = onGoHome,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("summary_go_home"),
        )
        Spacer(Modifier.height(Spacing.lg))
    }
}

@Preview(showBackground = true)
@Composable
private fun SummaryPreview() {
    ToneUpWordTheme {
        SummaryContent(
            summary = SessionSummary(
                sessionId = 1,
                level = top.toneup.word.domain.model.CetLevel.CET4,
                answeredCards = 30,
                newCards = 12,
                reviewCards = 18,
                masteredCards = 21,
                fuzzyCards = 6,
                unknownCards = 3,
                distinctWords = 26,
                durationMillis = 9 * 60_000L,
                mistakeWordIds = listOf(1, 2, 3),
            ),
            onPracticeMistakes = {},
            onGoHome = {},
        )
    }
}


/**
 * The closing line of a session.
 *
 * Routed through [MotivationEngine] rather than a local `when` on the accuracy band, so a learner who
 * just finished a session sees the same voice as on the dashboard. The moment is
 * [MotivationMoment.SESSION_COMPLETED] with the session's own numbers, and "struggling" is chosen
 * when the session went badly so the wording becomes supportive instead of celebratory.
 */
private fun completionMessage(summary: SessionSummary): String {
    val struggling = summary.accuracyPercent?.let { it < 50 } ?: false
    return MotivationEngine.message(
        MotivationContext(
            moment = if (struggling) MotivationMoment.STRUGGLING else MotivationMoment.SESSION_COMPLETED,
            accuracyPercent = summary.accuracyPercent,
            answeredToday = summary.answeredCards,
        ),
    ).text
}
