package top.toneup.word.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import top.toneup.word.AppContainer
import top.toneup.word.data.local.entity.WordPackEntity
import top.toneup.word.domain.model.CetLevel
import top.toneup.word.ui.ImportWordsViewModel
import top.toneup.word.ui.PackListViewModel
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
// Pack list (M2)
// ---------------------------------------------------------------------------------------------

/**
 * Pack management: list, create, rename, delete, switch (plan §5).
 *
 * Built-in packs are listed first and refuse rename/delete — the repository guards that too,
 * but the menu hides the actions so the learner never taps into a dead end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackListScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenImport: (String) -> Unit,
) {
    val viewModel: PackListViewModel = viewModel(factory = ViewModelFactories.packList(container))
    val state by viewModel.state.collectAsStateWithLifecycle()

    var nameDialog by remember { mutableStateOf<PackNameDialogState?>(null) }
    var pendingDelete by remember { mutableStateOf<WordPackEntity?>(null) }

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
                title = { Text("词库管理") },
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
            // Self-built packs are the point of this screen now: the create entry is a full-width
            // highlighted panel with its own explanation, so it reads as "make your own pack"
            // rather than a secondary button next to the built-in list (plan §B).
            item(key = "packs_create_panel") {
                AccentPanel {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text("自建词库", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "把你阅读中积累的单词整理成自己的词库：到「写词库」页逐个写入，可附上自己的中文释义。",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTheme.colors.mutedText,
                        )
                        PrimaryButton(
                            text = "新建自建词库",
                            onClick = { nameDialog = PackNameDialogState(mode = PackNameMode.Create, packId = null, initial = "") },
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
                        title = "还没有词库",
                        description = "内置演示词库会在首次启动时自动创建；也可以新建自己的词库。",
                        actionLabel = "新建词库",
                        onAction = { nameDialog = PackNameDialogState(mode = PackNameMode.Create, packId = null, initial = "") },
                    )
                }
            }

            items(state.packs, key = { it.id }) { pack ->
                PackRow(
                    pack = pack,
                    isActive = pack.id == state.activePackId,
                    onImport = { onOpenImport(pack.id) },
                    onSetCurrent = { viewModel.activate(pack.id) },
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
        PackNameDialog(
            mode = dialog.mode,
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
    isActive: Boolean,
    onImport: () -> Unit,
    onSetCurrent: () -> Unit,
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = pack.name,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (isActive) {
                        ReviewBadge(text = "当前词库")
                    }
                }
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
                if (!pack.isBuiltIn) {
                    DropdownMenuItem(
                        text = { Text("设为当前词库") },
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

/** Create / rename dialog with inline blank-name validation. */
@Composable
private fun PackNameDialog(
    mode: PackNameMode,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    val trimmed = name.trim()
    val tooLong = trimmed.length > MAX_PACK_NAME_FIELD_LENGTH

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (mode == PackNameMode.Create) "新建词库" else "重命名词库") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("词库名称") },
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
                    .testTag("pack_name_field"),
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
                title = { Text("写词库") },
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
            item(key = "write_pack_hint") {
                AccentPanel {
                    Text(
                        text = "写入到当前词库：${
                            state.packName.ifBlank { state.packId }.ifBlank { "（未选择）" }
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
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("write_word_field"),
                        )
                        OutlinedTextField(
                            value = state.noteInput,
                            onValueChange = viewModel::onNoteChanged,
                            label = { Text("我的释义 / 注释（可选，中文）") },
                            minLines = 2,
                            maxLines = 4,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
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
                    }
                }
            }

            item(key = "write_written_header") {
                SectionHeader(title = "本次已写入")
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
