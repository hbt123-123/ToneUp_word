package top.toneup.word.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.times
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import top.toneup.word.AppContainer
import top.toneup.word.data.local.entity.PlanEntity
import top.toneup.word.data.local.entity.WordPackEntity
import top.toneup.word.domain.model.CetLevel
import top.toneup.word.ui.ImportWordsViewModel
import top.toneup.word.ui.PlanDetailViewModel
import top.toneup.word.ui.PlanListViewModel
import top.toneup.word.ui.PlanRowUi
import top.toneup.word.ui.ViewModelFactories
import top.toneup.word.ui.WriteWordViewModel
import top.toneup.word.ui.WrittenWordEntry
import top.toneup.word.ui.components.AccentPanel
import top.toneup.word.ui.components.AppCard
import top.toneup.word.ui.components.ConfirmDialog
import top.toneup.word.ui.components.EmptyState
import top.toneup.word.ui.components.PrimaryButton
import top.toneup.word.ui.components.ReviewBadge
import top.toneup.word.ui.components.SecondaryButton
import top.toneup.word.ui.components.SectionHeader
import top.toneup.word.ui.theme.AppTheme
import top.toneup.word.ui.theme.Spacing

// ---------------------------------------------------------------------------------------------
// Plans and their packs (schema v4)
// ---------------------------------------------------------------------------------------------

/**
 * Plan management: list, create, rename, delete, switch (schema v4, D5).
 *
 * A plan is the study scope; its packs are only content sources. The built-in default plan may be
 * renamed but not deleted — the repository guards that too, but the menu hides the action so the
 * learner never taps into a dead end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanListScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenPlan: (String) -> Unit,
) {
    val viewModel: PlanListViewModel = viewModel(factory = ViewModelFactories.planList(container))
    val state by viewModel.state.collectAsStateWithLifecycle()

    var nameDialog by remember { mutableStateOf<PlanNameDialogState?>(null) }
    var pendingDelete by remember { mutableStateOf<PlanEntity?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    // One-shot messages (rename ok, delete refused …) surface as snackbars; null clears.
    val message = state.message
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("计划管理") },
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.lg,
                end = Spacing.lg,
                top = Spacing.md,
                bottom = Spacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item(key = "plans_create_panel") {
                AccentPanel {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text("自建计划", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "把一个用途的词库放进同一个计划，例如「外国文献单词计划」下放《时代周刊》《经济学人》词库。学习、复习、考试与统计都按当前计划合并汇总。",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTheme.colors.mutedText,
                        )
                        PrimaryButton(
                            text = "新建计划",
                            onClick = {
                                nameDialog = PlanNameDialogState(
                                    mode = PlanNameMode.Create,
                                    planId = null,
                                    initial = "",
                                )
                            },
                            leadingIcon = Icons.Filled.Add,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("plans_create"),
                        )
                    }
                }
            }

            if (state.plans.isEmpty()) {
                item {
                    EmptyState(
                        title = "还没有计划",
                        description = "内置的「默认计划」会在首次启动时自动创建；也可以新建自己的计划。",
                        actionLabel = "新建计划",
                        onAction = {
                            nameDialog = PlanNameDialogState(
                                mode = PlanNameMode.Create,
                                planId = null,
                                initial = "",
                            )
                        },
                    )
                }
            }

            items(state.plans, key = { it.plan.id }) { row ->
                PlanRow(
                    row = row,
                    isActive = row.plan.id == state.activePlanId,
                    onOpen = { onOpenPlan(row.plan.id) },
                    onSetCurrent = { viewModel.activate(row.plan.id) },
                    onRename = {
                        nameDialog = PlanNameDialogState(
                            mode = PlanNameMode.Rename,
                            planId = row.plan.id,
                            initial = row.plan.name,
                        )
                    },
                    onDelete = { pendingDelete = row.plan },
                )
            }
        }
    }

    nameDialog?.let { dialog ->
        NameDialog(
            title = if (dialog.mode == PlanNameMode.Create) "新建计划" else "重命名计划",
            label = "计划名称",
            fieldTag = "plan_name_field",
            initialName = dialog.initial,
            onConfirm = { name ->
                when (dialog.mode) {
                    PlanNameMode.Create -> viewModel.createPlan(name, onCreated = onOpenPlan)
                    PlanNameMode.Rename -> dialog.planId?.let { viewModel.renamePlan(it, name) }
                }
                nameDialog = null
            },
            onDismiss = { nameDialog = null },
        )
    }

    pendingDelete?.let { plan ->
        ConfirmDialog(
            title = "删除计划「${plan.name}」？",
            message = "计划本身不含单词；如果计划下还有词库，需要先删除或移出这些词库。删除计划不影响其他计划里的单词与进度。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                viewModel.deletePlan(plan.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/** Dialog mode: a fresh plan or renaming an existing one. */
private enum class PlanNameMode { Create, Rename }

private data class PlanNameDialogState(
    val mode: PlanNameMode,
    val planId: String?,
    val initial: String,
)

@Composable
private fun PlanRow(
    row: PlanRowUi,
    isActive: Boolean,
    onOpen: () -> Unit,
    onSetCurrent: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val plan = row.plan

    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("plan_row_${plan.id}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = plan.name,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (isActive) {
                        ReviewBadge(text = "当前计划")
                    }
                }
                Text(
                    text = buildString {
                        append(if (plan.isBuiltIn) "内置 · " else "自建 · ")
                        append("${row.packCount} 个词库")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.mutedText,
                )
            }

            TextButton(onClick = onOpen) {
                Text("词库", style = MaterialTheme.typography.labelLarge)
            }

            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "更多操作：${plan.name}",
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("查看词库") },
                    onClick = {
                        menuOpen = false
                        onOpen()
                    },
                )
                DropdownMenuItem(
                    text = { Text("设为当前计划") },
                    onClick = {
                        menuOpen = false
                        onSetCurrent()
                    },
                    enabled = !isActive,
                )
                DropdownMenuItem(
                    text = { Text("重命名") },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                if (!plan.isBuiltIn) {
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/**
 * One plan's packs: create, rename, delete, move between plans, import into a pack.
 *
 * The「当前计划」badge lives on the header, so a pack row carries no "active" state — switching
 * is a plan-level action (schema v4, D1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanDetailScreen(
    container: AppContainer,
    planId: String,
    onBack: () -> Unit,
    onOpenImport: (String) -> Unit,
) {
    val viewModel: PlanDetailViewModel =
        viewModel(factory = ViewModelFactories.planDetail(container, planId))
    val state by viewModel.state.collectAsStateWithLifecycle()

    var nameDialog by remember { mutableStateOf<PackNameDialogState?>(null) }
    var pendingDelete by remember { mutableStateOf<WordPackEntity?>(null) }
    var pendingMove by remember { mutableStateOf<WordPackEntity?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val message = state.message
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.planName.isBlank()) "计划" else state.planName) },
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.lg,
                end = Spacing.lg,
                top = Spacing.md,
                bottom = Spacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item(key = "plan_header") {
                AccentPanel {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (state.planName.isBlank()) "计划" else state.planName,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            if (state.planId == state.activePlanId) {
                                ReviewBadge(text = "当前计划")
                            }
                        }
                        Text(
                            text = "本计划共 ${state.packs.size} 个词库。学习、复习、考试与统计按整个计划汇总，计划内重复的单词只算一次、共用一份进度。",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTheme.colors.mutedText,
                        )
                        if (state.planId != state.activePlanId) {
                            SecondaryButton(
                                text = "设为当前计划",
                                onClick = viewModel::activate,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("plan_set_current"),
                            )
                        }
                    }
                }
            }

            item(key = "pack_create_panel") {
                AccentPanel {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text("新建词库", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "在这个计划下新建一个词库，再粘贴或导入单词；词库只是内容来源，学习范围始终是整个计划。",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTheme.colors.mutedText,
                        )
                        PrimaryButton(
                            text = "新建词库",
                            onClick = {
                                nameDialog = PackNameDialogState(
                                    mode = PackNameMode.Create,
                                    packId = null,
                                    initial = "",
                                )
                            },
                            leadingIcon = Icons.Filled.Add,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("packs_create"),
                        )
                    }
                }
            }

            if (state.packs.isEmpty()) {
                item {
                    EmptyState(
                        title = "这个计划里还没有词库",
                        description = "新建词库并导入单词后，这个计划就有内容可以学习了。",
                        actionLabel = "新建词库",
                        onAction = {
                            nameDialog = PackNameDialogState(
                                mode = PackNameMode.Create,
                                packId = null,
                                initial = "",
                            )
                        },
                    )
                }
            }

            items(state.packs, key = { it.id }) { pack ->
                PackRow(
                    pack = pack,
                    onImport = { onOpenImport(pack.id) },
                    onMove = { pendingMove = pack },
                    onRename = {
                        nameDialog = PackNameDialogState(
                            mode = PackNameMode.Rename,
                            packId = pack.id,
                            initial = pack.name,
                        )
                    },
                    onDelete = { pendingDelete = pack },
                )
            }
        }
    }

    nameDialog?.let { dialog ->
        NameDialog(
            title = if (dialog.mode == PackNameMode.Create) "新建词库" else "重命名词库",
            label = "词库名称",
            fieldTag = "pack_name_field",
            initialName = dialog.initial,
            onConfirm = { name ->
                when (dialog.mode) {
                    PackNameMode.Create -> viewModel.createPack(name, onCreated = onOpenImport)
                    PackNameMode.Rename -> dialog.packId?.let { viewModel.renamePack(it, name) }
                }
                nameDialog = null
            },
            onDismiss = { nameDialog = null },
        )
    }

    pendingDelete?.let { pack ->
        ConfirmDialog(
            title = "删除词库「${pack.name}」？",
            message = "词库中的 ${pack.wordCount} 个单词以及它们的学习进度、错题记录、考试记录会一并删除，无法恢复。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                viewModel.deletePack(pack.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    pendingMove?.let { pack ->
        MovePackDialog(
            packName = pack.name,
            targets = state.otherPlans,
            onMove = { targetPlanId ->
                viewModel.movePackTo(pack.id, targetPlanId)
                pendingMove = null
            },
            onDismiss = { pendingMove = null },
        )
    }
}

/** Dialog mode: a fresh pack or renaming an existing one. */
private enum class PackNameMode { Create, Rename }

private data class PackNameDialogState(
    val mode: PackNameMode,
    val packId: String?,
    val initial: String,
)

@Composable
private fun PackRow(
    pack: WordPackEntity,
    onImport: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("pack_row_${pack.id}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    text = pack.name,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = buildString {
                        append(if (pack.isBuiltIn) "内置 · " else "自定义 · ")
                        append(CetLevel.fromId(pack.level).shortLabel)
                        append(" · ${pack.wordCount} 词")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.mutedText,
                )
            }

            TextButton(onClick = onImport) {
                Text("导入", style = MaterialTheme.typography.labelLarge)
            }

            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "更多操作：${pack.name}",
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("导入单词") },
                    onClick = {
                        menuOpen = false
                        onImport()
                    },
                )
                DropdownMenuItem(
                    text = { Text("移动到其他计划") },
                    onClick = {
                        menuOpen = false
                        onMove()
                    },
                )
                if (!pack.isBuiltIn) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/** Target picker of「移动到其他计划」; the destination list never includes the current plan. */
@Composable
private fun MovePackDialog(
    packName: String,
    targets: List<PlanEntity>,
    onMove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动词库「$packName」") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (targets.isEmpty()) {
                    Text(
                        text = "没有其他计划可以移动。先新建一个计划，再回来移动词库。",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.mutedText,
                    )
                } else {
                    Text(
                        text = "选择目标计划：单词与学习进度会一起移动。",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.mutedText,
                    )
                    targets.forEach { target ->
                        TextButton(onClick = { onMove(target.id) }) {
                            Text(target.name)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** Create / rename dialog with inline blank-name validation, shared by plans and packs. */
@Composable
private fun NameDialog(
    title: String,
    label: String,
    fieldTag: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    val trimmed = name.trim()
    val tooLong = trimmed.length > MAX_PACK_NAME_FIELD_LENGTH

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(label) },
                singleLine = true,
                isError = trimmed.isEmpty() || tooLong,
                supportingText = {
                    when {
                        tooLong -> Text("名称过长，最多 $MAX_PACK_NAME_FIELD_LENGTH 个字符")
                        trimmed.isEmpty() -> Text("名称不能为空")
                        else -> Text("${name.length} / $MAX_PACK_NAME_FIELD_LENGTH")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (trimmed.isNotEmpty() && !tooLong) onConfirm(trimmed)
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(fieldTag),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmed) },
                enabled = trimmed.isNotEmpty() && !tooLong,
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** Mirrors the repository's name cap so the dialog can validate before a round-trip. */
private const val MAX_PACK_NAME_FIELD_LENGTH = 40

// ---------------------------------------------------------------------------------------------
// Import words (M3)
// ---------------------------------------------------------------------------------------------

/**
 * Paste plain text → live preview → import (plan §6.2).
 *
 * The preview counts ("可导入 N · 过滤 M · 重复 K") are produced by the pure
 * `PlainTextWordParser` on every change, so nothing is written until the learner confirms;
 * the write itself needs no network — meanings are completed later by the gateway (M4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportWordsScreen(
    container: AppContainer,
    packId: String,
    onBack: () -> Unit,
) {
    val viewModel: ImportWordsViewModel =
        viewModel(factory = ViewModelFactories.importWords(container, packId))
    val state by viewModel.state.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.packName.isBlank()) "导入单词" else "导入单词 · ${state.packName}") },
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            val result = state.lastResult
            if (result != null) {
                AccentPanel {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text("导入完成", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "新增 ${result.inserted} · 跳过 ${result.skipped}（共提交 ${result.total} 个）",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "单词已入库，释义与音标稍后自动补全。",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTheme.colors.mutedText,
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.xs))
                PrimaryButton(
                    text = "继续导入",
                    onClick = {
                        text = ""
                        viewModel.resetForMore()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("import_continue"),
                )
                SecondaryButton(
                    text = "完成",
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                AccentPanel {
                    Text(
                        text = "粘贴任意包含英文单词的文本：逗号、分号、空格或换行分隔均可；中文、数字、网址等无效内容会被自动过滤。导入不需要联网。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        viewModel.onTextChanged(it)
                    },
                    label = { Text("在这里粘贴单词") },
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("import_text_field"),
                )

                val preview = state.preview
                val parseError = state.parseError
                when {
                    parseError != null -> Text(
                        text = parseError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("import_parse_error"),
                    )

                    preview != null -> Text(
                        text = "可导入 ${preview.importableCount} · 过滤 ${preview.dropped} · 重复 ${preview.duplicates}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.colors.mutedText,
                        modifier = Modifier.testTag("import_preview_counts"),
                    )

                    else -> Text(
                        text = "等待粘贴内容…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.colors.mutedText,
                    )
                }

                state.message?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                PrimaryButton(
                    text = "导入",
                    onClick = viewModel::importWords,
                    enabled = preview != null && !preview.isEmpty,
                    loading = state.importing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("import_confirm"),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Write word (手写录词) — the「写词库」tab (plan §B / §D)
// ---------------------------------------------------------------------------------------------

/**
 * The inline status slot under the「写入」button reserves this many `bodySmall` lines: one for
 * the usual message, a second so a longer error can wrap without moving anything around it.
 */
private const val InlineStatusLines = 2

/**
 * Hand-type the words met while reading, one at a time, with an optional Chinese note.
 *
 * The note is the answer to "the dictionary meaning is not what I need here": it is stored in
 * `user_note` and shown **before** the looked-up meanings on the study card (see
 * `WordCard.WordMeaningBlock`). A word left without a note is completed by the gateway exactly
 * like an imported word — this screen therefore stays fully offline until the gateway runs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WriteWordScreen(
    container: AppContainer,
) {
    val viewModel: WriteWordViewModel =
        viewModel(factory = ViewModelFactories.writeWord(container))
    val state by viewModel.state.collectAsStateWithLifecycle()

    // No focus request on entry: pulling focus (and the IME) up during the navigation transition
    // resizes the entering screen mid-animation — the very jump this screen is meant to fix.
    // The cursor is returned after each successful write instead.
    val wordFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Fixed status-slot height, derived from the typography line height: the line height is in
    // sp, and `toDp()` folds the system font scale in, so large-font users get a taller fixed
    // slot instead of clipped glyphs. The height still never depends on the message itself, so
    // the "appears without moving anything" guarantee is kept.
    val statusSlotHeight = with(LocalDensity.current) {
        (MaterialTheme.typography.bodySmall.lineHeight * InlineStatusLines.toFloat()).toDp()
    }
    val commitTick = state.commitTick
    LaunchedEffect(commitTick) {
        if (commitTick == 0) return@LaunchedEffect
        try {
            wordFocus.requestFocus()
        } catch (_: IllegalStateException) {
            // The input card was recycled by the LazyColumn between commit and now — the word
            // was saved, there is simply nothing to refocus. Don't break the flow over it.
        }
        // Unconditional on purpose: the learner has just committed and the next step is typing
        // the next word, so an IME that collapsed itself on IME_ACTION_DONE is brought back up
        // (`requestFocus()` alone cannot — the field never lost focus, so nothing changes).
        // This also re-shows a keyboard the learner dismissed by gesture; accepted on a tab
        // whose only job is typing. The app itself never hides the keyboard.
        keyboard?.show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("写词库") },
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
                .padding(padding),
            contentPadding = PaddingValues(
                start = Spacing.lg,
                end = Spacing.lg,
                top = Spacing.md,
                bottom = Spacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item(key = "write_pack_hint") {
                AccentPanel {
                    Text(
                        text = "写入到当前计划：${
                            state.planName.ifBlank { state.planId }.ifBlank { "（未选择）" }
                        }",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("write_pack_hint"),
                    )
                }
            }

            item(key = "write_input") {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        OutlinedTextField(
                            value = state.wordInput,
                            onValueChange = viewModel::onWordChanged,
                            label = { Text("单词") },
                            singleLine = true,
                            // Ascii maps to TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, which makes
                            // mainstream Chinese IMEs drop their pinyin composing state and show a
                            // plain English layout, so the letters are visible as they are typed.
                            // The input is deliberately NOT character-filtered: any filtering here
                            // would also rewrite the IME's composing string mid-word.
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Done,
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                            ),
                            // "Done" commits straight from the word field, so a word-only entry
                            // is one keystroke instead of Next → Done.
                            keyboardActions = KeyboardActions(onDone = { viewModel.write() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("write_word_field")
                                .focusRequester(wordFocus),
                        )
                        OutlinedTextField(
                            value = state.noteInput,
                            onValueChange = viewModel::onNoteChanged,
                            label = { Text("我的释义 / 注释（可选，中文）") },
                            minLines = 2,
                            maxLines = 4,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { viewModel.write() }),
                            supportingText = {
                                Text("填写后会优先展示，覆盖词典里不符合语境的意思。")
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("write_note_field"),
                        )
                        PrimaryButton(
                            text = "写入",
                            onClick = viewModel::write,
                            enabled = state.wordInput.isNotBlank() && !state.saving,
                            loading = state.saving,
                            leadingIcon = Icons.Filled.Add,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("write_submit"),
                        )
                        // Fixed-height status slot: the confirmation / error line appears and
                        // disappears inside a box that is always laid out at the same height, so
                        // nothing around it moves (no snackbar covering the form, no jump when
                        // the message changes).
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(statusSlotHeight),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            val message = state.message
                            if (message != null) {
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = InlineStatusLines,
                                    // Long messages truncate with dots instead of hard-clipping
                                    // half a glyph.
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (state.messageIsError) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        AppTheme.colors.mutedText
                                    },
                                )
                            }
                        }
                    }
                }
            }

            item(key = "write_written_header") {
                SectionHeader(
                    title = if (state.sessionCount > 0) {
                        "本次已写入 · ${state.sessionCount} 词"
                    } else {
                        "本次已写入"
                    },
                )
            }

            if (state.written.isEmpty()) {
                item(key = "write_written_empty") {
                    EmptyState(
                        title = "还没有写入单词",
                        description = "在上方输入一个单词，可附上自己的中文释义；写入后会立即出现在这里。",
                    )
                }
            } else {
                items(state.written, key = { it.wordId }) { entry ->
                    WrittenWordRow(
                        entry = entry,
                        modifier = Modifier.testTag("write_written_${entry.wordId}"),
                    )
                }
            }
        }
    }
}

/** One row of "本次已写入": the word, and the learner's own note when present. */
@Composable
private fun WrittenWordRow(
    entry: WrittenWordEntry,
    modifier: Modifier = Modifier,
) {
    AppCard(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(text = entry.word, style = MaterialTheme.typography.titleSmall)
            if (entry.note.isNotEmpty()) {
                Text(
                    text = entry.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.mutedText,
                )
            }
        }
    }
}
