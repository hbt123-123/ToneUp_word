package top.toneup.word.ui.screens

import androidx.compose.ui.platform.LocalContext
import top.toneup.word.data.speech.SpeechRateMode
import top.toneup.word.data.speech.SystemTtsSpeechService
import top.toneup.word.ui.components.SpeechMessage
import top.toneup.word.ui.components.SpeechStatusCard
import top.toneup.word.ui.speech.rememberSpeechActions

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import top.toneup.word.AppContainer
import top.toneup.word.data.gateway.GatewayDefaults
import top.toneup.word.data.settings.ThemeMode
import top.toneup.word.data.settings.UserSettings
import top.toneup.word.domain.model.CetLevel
import top.toneup.word.ui.GatewayUiState
import top.toneup.word.ui.ProfileViewModel
import top.toneup.word.ui.ViewModelFactories
import top.toneup.word.ui.gatewayUrlError
import top.toneup.word.ui.components.AppCard
import top.toneup.word.ui.components.ConfirmDialog
import top.toneup.word.ui.components.EmptyState
import top.toneup.word.ui.components.EqualHeightTileRow
import top.toneup.word.ui.components.FilterChipGroup
import top.toneup.word.ui.components.SecondaryButton
import top.toneup.word.ui.components.SectionHeader
import top.toneup.word.ui.components.SettingToggleRow
import top.toneup.word.ui.components.StatCard
import top.toneup.word.ui.theme.AppTheme
import top.toneup.word.ui.theme.ToneUpWordTheme
import top.toneup.word.ui.theme.Spacing

/** Profile: word list, quick links, tidy metrics and the entries to data management. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    container: AppContainer,
    onOpenSettings: () -> Unit,
    onOpenDataTransfer: () -> Unit,
    onOpenWordBrowser: () -> Unit,
    onOpenPacks: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    val viewModel: ProfileViewModel = viewModel(factory = ViewModelFactories.profile(container))
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("profile_content"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = Spacing.lg,
                end = Spacing.lg,
                bottom = Spacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                // EqualHeightTileRow (instead of a bare Row) because only the second tile has a
                // caption: without it the two 色块 come out at different heights and the row looks
                // misaligned. `fillMaxHeight()` makes whichever tile is shorter match the taller one.
                EqualHeightTileRow {
                    StatCard(
                        label = "连续学习",
                        value = "${state.stats?.streakDays ?: 0} 天",
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    StatCard(
                        label = "已学习",
                        value = "${state.stats?.totalLearnedWords ?: 0}",
                        caption = "共 ${state.wordCount} 个",
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }

            item { SettingsEntry(title = "学习设置", subtitle = "每日目标、提醒、发音、主题与动画", onClick = onOpenSettings) }
            item {
                SettingsEntry(
                    title = "浏览 / 搜索词库",
                    subtitle = "按单词或中文释义查找当前词库中的任意单词",
                    onClick = onOpenWordBrowser,
                    testTag = "profile_word_browser",
                )
            }
            item {
                SettingsEntry(
                    title = "词库管理",
                    subtitle = "新建、重命名、删除词库，切换当前学习的词库",
                    onClick = onOpenPacks,
                    testTag = "profile_packs",
                )
            }
            item { SettingsEntry(title = "词库导入与数据导出", subtitle = "导入 JSON / CSV 词库，导出学习记录或完整备份", onClick = onOpenDataTransfer) }
            item { SettingsEntry(title = "词库来源与许可", subtitle = "内置演示词库、用户导入内容的版权说明", onClick = onOpenLicenses) }
            item { SettingsEntry(title = "关于", subtitle = "应用版本 ${state.appVersion}　·　隐私说明", onClick = onOpenAbout) }

            item {
                AppCard {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        SectionHeader(title = "词库列表")
                        if (state.packs.isEmpty()) {
                            Text(
                                text = "还没有词库。内置演示词库会在首次启动时自动创建。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppTheme.colors.mutedText,
                            )
                        } else {
                            state.packs.forEach { pack ->
                                // No trailing spacer: the gap between rows comes from `spacedBy`, so
                                // the last row is not followed by a stray gap before the card padding.
                                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = pack.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            text = if (pack.isBuiltIn) "内置" else "已导入",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = AppTheme.colors.mutedText,
                                        )
                                    }
                                    Text(
                                        text = "${CetLevel.fromId(pack.level).label} · ${pack.wordCount} 词 · ${pack.license}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AppTheme.colors.mutedText,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                AppCard {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        SectionHeader(title = "最近导入记录")
                        if (state.importHistory.isEmpty()) {
                            Text(
                                text = "还没有导入或导出记录。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppTheme.colors.mutedText,
                            )
                        } else {
                            state.importHistory.take(5).forEach { record ->
                                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                    Text(
                                        text = "${record.fileName}（${record.format}）",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        text = if (record.status == "SUCCESS") {
                                            "新增 ${record.inserted} · 更新 ${record.updated} · 跳过 ${record.skipped} · 失败 ${record.failed}"
                                        } else {
                                            "导入失败，数据已回滚（${record.message}）"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (record.status == "SUCCESS") {
                                            AppTheme.colors.mutedText
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsEntry(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    testTag: String? = null,
) {
    AppCard(onClick = onClick, modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier) {
        // `spacedBy` instead of two flush Texts: the title/subtitle gap is then identical to every
        // other two-line block in the app (which all use Spacing.xs).
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.mutedText,
            )
        }
    }
}

/** All user settings, each one writing straight through to DataStore. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val viewModel: ProfileViewModel = viewModel(factory = ViewModelFactories.profile(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = state.settings
    val context = LocalContext.current
    // The sample-reading button speaks through the shared engine, using the live rate setting.
    val speech = rememberSpeechActions(container.speechService) { state.settings.speechRateMode }
    val speechState by speech.state.collectAsStateWithLifecycle()

    var pendingReset by remember { mutableStateOf(false) }
    var pendingClearProgress by remember { mutableStateOf(false) }
    var pendingClearAll by remember { mutableStateOf(false) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // The enqueue result is one-shot: show it, then clear it so a recomposition cannot
    // re-pop the snackbar.
    val enqueueMessage = state.gateway.enqueueMessage
    LaunchedEffect(enqueueMessage) {
        if (enqueueMessage != null) {
            snackbarHostState.showSnackbar(enqueueMessage)
            viewModel.dismissGatewayMessage()
        }
    }

    // Android 13+ needs an explicit runtime permission for notifications; Android 8-12 do not,
    // so the request is only launched on the newer versions and never affects older devices.
    val notificationPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        infoMessage = if (granted) "已开启通知权限。" else "未获得通知权限，提醒不会显示通知，可稍后在系统设置中开启。"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("学习设置") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "返回" },
                    ) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (infoMessage != null) {
                AppCard { Text(infoMessage!!, style = MaterialTheme.typography.bodyMedium) }
            }

            AppCard {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    SectionHeader(title = "每日目标")
                    StepperRow(
                        label = "每日新词",
                        value = "${settings.dailyNewWordGoal} 个",
                        onDecrease = { viewModel.setDailyNewWordGoal(settings.dailyNewWordGoal - 5) },
                        onIncrease = { viewModel.setDailyNewWordGoal(settings.dailyNewWordGoal + 5) },
                        testTagPrefix = "settings_new_words",
                    )
                    StepperRow(
                        label = "每日复习上限",
                        value = "${settings.dailyReviewLimit} 个",
                        onDecrease = { viewModel.setDailyReviewLimit(settings.dailyReviewLimit - 10) },
                        onIncrease = { viewModel.setDailyReviewLimit(settings.dailyReviewLimit + 10) },
                        testTagPrefix = "settings_review_limit",
                    )
                }
            }

            AppCard {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    SectionHeader(title = "学习顺序")
                    SettingToggleRow(
                        label = "乱序刷单词",
                        description = "开启后，每次开始学习时会把整个队列随机打乱（到期词、"
                            + "未巩固词与新词混在一起）；关闭时按「先复习到期词、再由难到易、最后新词」"
                            + "的固定顺序。进行中的学习不会被中途打乱。",
                        checked = settings.shuffleWords,
                        onCheckedChange = viewModel::setShuffleWords,
                        testTag = "settings_shuffle_switch",
                    )
                }
            }

            AppCard {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    SectionHeader(title = "学习提醒")
                    SettingToggleRow(
                        label = "开启每日提醒",
                        checked = settings.reminderEnabled,
                        onCheckedChange = { enabled ->
                            viewModel.setReminder(enabled, settings.reminderHour, settings.reminderMinute)
                            infoMessage = if (enabled) {
                                "已开启提醒。"
                            } else {
                                "已关闭提醒。"
                            }
                            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        testTag = "settings_reminder_switch",
                    )
                    if (settings.reminderEnabled) {
                        FilterChipGroup(
                            options = REMINDER_PRESETS,
                            selected = settings.reminderHour to settings.reminderMinute,
                            onSelect = { (hour, minute) -> viewModel.setReminder(true, hour, minute) },
                            label = { (hour, minute) -> "%02d:%02d".format(hour, minute) },
                            modifier = Modifier.testTag("settings_reminder_time"),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            SecondaryButton(
                                text = "提前 30 分钟",
                                onClick = {
                                    val total = (settings.reminderHour * 60 + settings.reminderMinute - 30 + 1440) % 1440
                                    viewModel.setReminder(true, total / 60, total % 60)
                                },
                                modifier = Modifier.weight(1f),
                            )
                            SecondaryButton(
                                text = "推后 30 分钟",
                                onClick = {
                                    val total = (settings.reminderHour * 60 + settings.reminderMinute + 30) % 1440
                                    viewModel.setReminder(true, total / 60, total % 60)
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            AppCard {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    SectionHeader(title = "朗读（系统语音）")
                    SpeechStatusCard(
                        status = state.speechStatus,
                        diagnostics = state.speechDiagnostics,
                        onTrySample = { speech.speakSample() },
                        onOpenSystemSettings = { SystemTtsSpeechService.openSystemSpeechSettings(context) },
                        onInstallVoiceData = { SystemTtsSpeechService.requestVoiceDataInstall(context) },
                        onResetCounters = viewModel::resetSpeechDiagnostics,
                    )
                    SpeechMessage(
                        state = speechState,
                        onRetry = speech::retry,
                        onOpenSettings = speech::openSystemSpeechSettings,
                        onInstallVoiceData = speech::installVoiceData,
                    )
                    Text(
                        text = "朗读速度",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    FilterChipGroup(
                        options = SpeechRateMode.entries.toList(),
                        selected = settings.speechRateMode,
                        onSelect = viewModel::setSpeechRateMode,
                        label = { it.label },
                        modifier = Modifier.testTag("settings_speech_rate"),
                    )
                    Text(
                        text = "选择「跟随系统」时，语速与音调由系统的文字转语音设置决定，" +
                            "应用不会覆盖它们。",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.mutedText,
                    )
                    SettingToggleRow(
                        label = "卡片出现时自动朗读",
                        checked = settings.autoPlayPronunciation,
                        onCheckedChange = viewModel::setAutoPlayPronunciation,
                        testTag = "settings_autoplay_switch",
                    )
                    SettingToggleRow(
                        label = "同时朗读例句",
                        checked = settings.readExampleSentences,
                        onCheckedChange = viewModel::setReadExampleSentences,
                        testTag = "settings_read_examples_switch",
                    )
                    Text(
                        text = "朗读使用系统自带的文字转语音服务：单词与例句用英语，" +
                            "「朗读释义」用中文。应用不联网、不下载音频，也不需要额外的权限。" +
                            "如果系统没有安装语音引擎或缺少英语语音包，朗读会给出提示并提供设置入口。",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.mutedText,
                    )
                }
            }

            AppCard {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    SectionHeader(title = "外观")
                    FilterChipGroup(
                        options = ThemeMode.entries.toList(),
                        selected = settings.themeMode,
                        onSelect = viewModel::setThemeMode,
                        label = { it.label },
                        modifier = Modifier.testTag("settings_theme"),
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        SettingToggleRow(
                            label = "动态颜色",
                            description = "使用系统壁纸配色（Android 12+），关闭后使用薄荷绿品牌色。",
                            checked = settings.dynamicColor,
                            onCheckedChange = viewModel::setDynamicColor,
                            testTag = "settings_dynamic_color_switch",
                        )
                    } else {
                        Text(
                            text = "动态颜色需要 Android 12 及以上，本机使用薄荷绿品牌配色。",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTheme.colors.mutedText,
                        )
                    }
                    SettingToggleRow(
                        label = "动画效果",
                        description = "关闭后所有过渡立即完成，也会尊重系统的「移除动画」设置。",
                        checked = settings.animationsEnabled,
                        onCheckedChange = viewModel::setAnimationsEnabled,
                        testTag = "settings_animations_switch",
                    )
                }
            }

            GatewaySettingsSection(
                settings = settings,
                gateway = state.gateway,
                onSetBaseUrl = viewModel::setGatewayBaseUrl,
                onSetKey = viewModel::setGatewayKey,
                onSetDictPath = viewModel::setDictPath,
                onSetEnPath = viewModel::setEnPath,
                onSetMtPath = viewModel::setMtPath,
                onSetSyncOnlyUnmetered = viewModel::setSyncOnlyUnmetered,
                onSetClipboardDetect = viewModel::setClipboardDetectEnabled,
                onRestoreDefaults = viewModel::restoreGatewayDefaults,
                onEnqueueAll = viewModel::enqueueAllMeaningCompletion,
                onTestConnection = viewModel::testGatewayConnection,
            )

            AppCard {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    SectionHeader(title = "数据管理")
                    SecondaryButton(
                        text = "恢复默认设置",
                        onClick = { pendingReset = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_reset"),
                    )
                    SecondaryButton(
                        text = "清空学习进度",
                        onClick = { pendingClearProgress = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_clear_progress"),
                    )
                    SecondaryButton(
                        text = "重置全部数据（含词库）",
                        onClick = { pendingClearAll = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_clear_all"),
                    )
                    Text(
                        text = "「清空学习进度」会删除掌握状态、错题、答题日志与统计；" +
                            "「重置全部数据」还会删除所有已导入词库（内置演示词库会恢复）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.mutedText,
                    )
                }
            }
            Spacer(Modifier.height(Spacing.lg))
        }
    }

    if (pendingReset) {
        ConfirmDialog(
            title = "恢复默认设置",
            message = "每日目标、提醒、发音与外观设置都会恢复为默认值。学习数据不会受影响。",
            confirmLabel = "恢复默认",
            destructive = true,
            onConfirm = {
                viewModel.resetSettings()
                pendingReset = false
                infoMessage = "已恢复默认设置。"
            },
            onDismiss = { pendingReset = false },
        )
    }

    if (pendingClearProgress) {
        ConfirmDialog(
            title = "清空学习进度",
            message = "将永久删除全部掌握状态、错题记录、答题日志与学习统计。词库本身会保留，此操作无法撤销。",
            confirmLabel = "确认清空",
            destructive = true,
            onConfirm = {
                viewModel.clearStudyProgress()
                pendingClearProgress = false
                infoMessage = "学习进度已清空。"
            },
            onDismiss = { pendingClearProgress = false },
        )
    }

    if (pendingClearAll) {
        ConfirmDialog(
            title = "重置全部数据",
            message = "将删除所有词库（包括导入的词库）与全部学习数据，并恢复内置演示词库。此操作无法撤销。",
            confirmLabel = "确认重置",
            destructive = true,
            onConfirm = {
                viewModel.clearAllData()
                pendingClearAll = false
                infoMessage = "全部数据已重置。"
            },
            onDismiss = { pendingClearAll = false },
        )
    }
}

/**
 * "词库与联网" settings group (plan §10): gateway address/key, the advanced path fields,
 * the two completion toggles, the pending count with a manual trigger and the
 * connectivity test.
 *
 * Extracted from [SettingsScreen] (the hottest function in the codebase) so the gateway
 * rules — https validation, the key's visibility toggle, the one-in-flight test — live in
 * one reviewable place. Text fields hold *local* state while typing and only write through
 * to DataStore on an explicit save, so a half-typed URL is never persisted.
 */
@Composable
private fun GatewaySettingsSection(
    settings: UserSettings,
    gateway: GatewayUiState,
    onSetBaseUrl: (String) -> Unit,
    onSetKey: (String) -> Unit,
    onSetDictPath: (String) -> Unit,
    onSetEnPath: (String) -> Unit,
    onSetMtPath: (String) -> Unit,
    onSetSyncOnlyUnmetered: (Boolean) -> Unit,
    onSetClipboardDetect: (Boolean) -> Unit,
    onRestoreDefaults: () -> Unit,
    onEnqueueAll: () -> Unit,
    onTestConnection: () -> Unit,
) {
    var baseUrlText by remember(settings.gatewayBaseUrl) { mutableStateOf(settings.gatewayBaseUrl) }
    var keyText by remember(settings.gatewayKey) { mutableStateOf(settings.gatewayKey) }
    var keyVisible by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var dictPathText by remember(settings.dictPath) { mutableStateOf(settings.dictPath) }
    var enPathText by remember(settings.enPath) { mutableStateOf(settings.enPath) }
    var mtPathText by remember(settings.mtPath) { mutableStateOf(settings.mtPath) }

    // Inline validation: the learner sees the reason immediately and the save button
    // stays disabled, so an invalid URL can never reach the repository (plan D7).
    val baseUrlError = gatewayUrlError(baseUrlText)

    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            SectionHeader(title = "词库与联网")

            Text(
                text = "导入单词后，应用可通过自建网关补全释义、音标与词性；" +
                    "联网只用于释义补全，上传内容仅为待查询的单词本身。",
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.mutedText,
            )

            OutlinedTextField(
                value = baseUrlText,
                onValueChange = { baseUrlText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_gateway_url"),
                singleLine = true,
                label = { Text("网关地址") },
                placeholder = { Text(GatewayDefaults.BASE_URL) },
                isError = baseUrlError != null,
                supportingText = if (baseUrlError != null) {
                    { Text(baseUrlError) }
                } else {
                    null
                },
            )
            SecondaryButton(
                text = "保存地址",
                onClick = { onSetBaseUrl(baseUrlText) },
                enabled = baseUrlError == null && baseUrlText.trim() != settings.gatewayBaseUrl,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_gateway_url_save"),
            )

            OutlinedTextField(
                value = keyText,
                onValueChange = { keyText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_gateway_key"),
                singleLine = true,
                label = { Text("网关密钥") },
                // The learner sees and edits the real value (it may be the shipped default);
                // the toggle only hides it from onlookers, it is never masked for the owner.
                visualTransformation = if (keyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    TextButton(
                        onClick = { keyVisible = !keyVisible },
                        modifier = Modifier.testTag("settings_gateway_key_visibility"),
                    ) {
                        Text(if (keyVisible) "隐藏" else "显示")
                    }
                },
            )
            SecondaryButton(
                text = "保存密钥",
                onClick = { onSetKey(keyText) },
                enabled = keyText.trim() != settings.gatewayKey,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_gateway_key_save"),
            )

            TextButton(
                onClick = { showAdvanced = !showAdvanced },
                modifier = Modifier.testTag("settings_gateway_advanced_toggle"),
            ) {
                Text(if (showAdvanced) "收起高级设置" else "高级设置（路径）")
            }
            if (showAdvanced) {
                OutlinedTextField(
                    value = dictPathText,
                    onValueChange = { dictPathText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_gateway_dict_path"),
                    singleLine = true,
                    label = { Text("ECDICT 路径") },
                    placeholder = { Text(GatewayDefaults.DICT_PATH) },
                )
                OutlinedTextField(
                    value = enPathText,
                    onValueChange = { enPathText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_gateway_en_path"),
                    singleLine = true,
                    label = { Text("英文释义路径") },
                    placeholder = { Text(GatewayDefaults.EN_PATH) },
                )
                OutlinedTextField(
                    value = mtPathText,
                    onValueChange = { mtPathText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_gateway_mt_path"),
                    singleLine = true,
                    label = { Text("翻译模型路径") },
                    placeholder = { Text(GatewayDefaults.MT_PATH) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SecondaryButton(
                        text = "保存路径",
                        onClick = {
                            onSetDictPath(dictPathText)
                            onSetEnPath(enPathText)
                            onSetMtPath(mtPathText)
                        },
                        enabled = dictPathText.trim() != settings.dictPath ||
                            enPathText.trim() != settings.enPath ||
                            mtPathText.trim() != settings.mtPath,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_gateway_paths_save"),
                    )
                    SecondaryButton(
                        text = "恢复默认",
                        onClick = {
                            onRestoreDefaults()
                            dictPathText = GatewayDefaults.DICT_PATH
                            enPathText = GatewayDefaults.EN_PATH
                            mtPathText = GatewayDefaults.MT_PATH
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_gateway_paths_reset"),
                    )
                }
            }

            SettingToggleRow(
                label = "仅 Wi-Fi 下补全",
                description = "开启后，释义补全只在 Wi-Fi 网络下进行，移动数据下不会发起请求。",
                checked = settings.syncOnlyUnmetered,
                onCheckedChange = onSetSyncOnlyUnmetered,
                testTag = "settings_sync_unmetered_switch",
            )
            SettingToggleRow(
                label = "剪贴板识别",
                description = "复制单词后回到应用时，提示快速导入到词库。",
                checked = settings.clipboardDetectEnabled,
                onCheckedChange = onSetClipboardDetect,
                testTag = "settings_clipboard_switch",
            )

            // Pending count: null (no emission yet) reads differently from 0 — the screen
            // must not claim "全部已补全" before the query has run.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Text("待补充释义", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = when (val count = gateway.pendingCount) {
                            null -> "正在统计…"
                            0 -> "全部已补全"
                            else -> "$count 个单词待补充"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.mutedText,
                        modifier = Modifier.testTag("settings_gateway_pending_count"),
                    )
                }
                SecondaryButton(
                    text = "立即补全",
                    onClick = onEnqueueAll,
                    modifier = Modifier.testTag("settings_gateway_enqueue"),
                )
            }
            Text(
                text = "需联网；仅 Wi-Fi 可在开关中设置。",
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.mutedText,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SecondaryButton(
                    text = "测试连接",
                    onClick = onTestConnection,
                    // Disabled while in flight so a double tap cannot start a second request.
                    enabled = !gateway.testing,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("settings_gateway_test"),
                )
                if (gateway.testing) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .testTag("settings_gateway_test_progress"),
                        strokeWidth = 2.dp,
                    )
                }
            }
            gateway.testMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (message == "密钥错误") {
                        MaterialTheme.colorScheme.error
                    } else {
                        AppTheme.colors.mutedText
                    },
                    modifier = Modifier.testTag("settings_gateway_test_result"),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsEntryPreview() {
    ToneUpWordTheme {
        SettingsEntry(title = "学习设置", subtitle = "每日目标、提醒、发音、主题与动画", onClick = {})
    }
}

/** Preview-only empty state usage keeps the import of EmptyState meaningful in this file. */
@Preview(showBackground = true)
@Composable
private fun ProfileEmptyPreview() {
    ToneUpWordTheme {
        EmptyState(title = "还没有词库", description = "内置演示词库会在首次启动时自动创建。")
    }
}
