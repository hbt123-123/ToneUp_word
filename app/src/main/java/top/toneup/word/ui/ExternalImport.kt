package top.toneup.word.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import top.toneup.word.AppContainer
import top.toneup.word.core.result.Outcome
import top.toneup.word.data.local.entity.WordPackEntity
import top.toneup.word.data.repository.MeaningSyncRepository
import top.toneup.word.data.repository.PackAddResult
import top.toneup.word.data.settings.PackIds
import top.toneup.word.domain.importer.PlainTextImportPreview
import top.toneup.word.domain.importer.PlainTextWordParser
import top.toneup.word.ui.components.AccentPanel
import top.toneup.word.ui.components.FilterChipGroup
import top.toneup.word.ui.components.PrimaryButton
import top.toneup.word.ui.components.SecondaryButton
import top.toneup.word.ui.theme.AppTheme
import top.toneup.word.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * Clipboard auto-detection and Share-intake import (M5, plan §8).
 *
 * **Recorded deviation from the single-file ViewModel convention**: this ViewModel and its
 * sheet live here instead of in `ui/ViewModels.kt` because M5 landed in a parallel editing
 * lane with `ViewModels.kt` frozen to avoid merge conflicts — **merge it back into
 * `ViewModels.kt` (and its factory into `ViewModelFactories`) once the lanes have converged**.
 * The shape below deliberately mirrors the existing sections: one `@Immutable` state class,
 * a ViewModel over `AppContainer`, a `Factory` companion, callbacks by function reference.
 *
 * Privacy (hard rule, plan §1/§8): the clipboard / share text is parsed **locally only** and
 * never logged; after a confirmed import only the individual word tokens travel to the
 * gateway (through `meaningSync`). The dedupe marker persisted for the clipboard is a
 * SHA-256 **hash**, never the text itself.
 */

// ---------------------------------------------------------------------------------------------
// State model
// ---------------------------------------------------------------------------------------------

/** Where the detected text came from; drives the sheet title and the hash bookkeeping. */
enum class ExternalImportSource { CLIPBOARD, SHARE }

/**
 * Sheet lifecycle: `IDLE → DETECTED → IMPORTING → DONE | back to DETECTED (retry)`,
 * plus `ERROR` (nothing importable) and `DISMISSED` (user closed it; both hide the sheet).
 */
enum class ExternalImportPhase { IDLE, DETECTED, IMPORTING, DONE, ERROR, DISMISSED }

@Immutable
data class ExternalImportUiState(
    val phase: ExternalImportPhase = ExternalImportPhase.IDLE,
    val source: ExternalImportSource = ExternalImportSource.SHARE,
    /**
     * The raw detected text. Kept in memory only so the state machine can re-derive what was
     * shown; it is **never rendered, logged or uploaded** — the sheet shows counts, not content.
     */
    val text: String = "",
    /** Parse result shown as "可导入 N · 过滤 M · 重复 K" before anything is written. */
    val preview: PlainTextImportPreview? = null,
    /** Parse failure (share path) or repository failure (during import), surfaced in the sheet. */
    val error: String? = null,
    /** Inserted / skipped summary once the write committed. */
    val result: PackAddResult? = null,
) {
    /** Whether the sheet currently covers the screen (the clipboard probe must not pop under it). */
    val sheetVisible: Boolean
        get() = when (phase) {
            ExternalImportPhase.DETECTED,
            ExternalImportPhase.IMPORTING,
            ExternalImportPhase.DONE,
            ExternalImportPhase.ERROR,
            -> true

            else -> false
        }

    val importing: Boolean get() = phase == ExternalImportPhase.IMPORTING
}

/**
 * SHA-256 fingerprint of a clipboard payload (plan §8.1).
 *
 * "归一化" is a trim: the marker has to survive a stray trailing newline when the system
 * round-trips clipboard content, while still telling "apple" from "apples" apart. Only the
 * hex digest is ever stored (`UserSettings.lastClipboardHash`); the text stays in RAM.
 */
object ClipboardHash {

    fun of(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.trim().toByteArray(Charsets.UTF_8))
        // Hand-rolled hex: `String.format("%02x")` is locale-sensitive, this is not.
        return buildString(digest.size * 2) {
            for (byte in digest) {
                val value = byte.toInt() and 0xFF
                append(HEX[value shr 4])
                append(HEX[value and 0x0F])
            }
        }
    }

    private const val HEX = "0123456789abcdef"
}

// ---------------------------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------------------------

/**
 * Parses external text and writes it into a pack on confirmation.
 *
 * One instance for the Activity's lifetime: `MainActivity` feeds it share intents and
 * clipboard reads, and the root composable renders [state] as an [ExternalImportSheet]. The
 * write path is exactly the paste-import one — `PlainTextWordParser` → `addWordsToPack`
 * (PENDING rows) → `meaningSync.enqueueAll()` — so an import never depends on the network.
 */
class ExternalImportViewModel(
    private val container: AppContainer,
    /**
     * Meaning-completion trigger; defaults to the container's instance. The parameter exists
     * so tests can assert "enqueue happened" without standing up WorkManager.
     */
    private val meaningSync: MeaningSyncRepository = container.meaningSync,
) : ViewModel() {

    private val _state = MutableStateFlow(ExternalImportUiState())

    val state: StateFlow<ExternalImportUiState> = _state.asStateFlow()

    /**
     * Selectable packs: built-in first, `legacy-other` filtered out (D9 — a bucket, not a pack).
     *
     * `WhileSubscribed` for the same reason as [activePackId]: the sheet is the only observer,
     * and an eager, never-cancelled collector keeps a Room subscription alive after the screen
     * is gone.
     */
    val packs: StateFlow<List<WordPackEntity>> = container.wordRepository.observePacks()
        .map { list ->
            list.filter { it.id != PackIds.LEGACY_OTHER }
                .sortedByDescending { it.isBuiltIn }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Default destination for a new import: the pack the learner is studying right now.
     *
     * `WhileSubscribed` rather than `Eagerly`: the sheet is the only observer, and an eager,
     * never-cancelled collector keeps a DataStore subscription alive after the screen is gone.
     * When its continuation is queued on a paused (Robolectric) main looper, the DataStore write
     * actor parks on that emission and the next write in the same JVM never completes.
     */
    val activePackId: StateFlow<String> = container.settingsRepository.settings
        .map { it.effectivePackId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /**
     * Share / process-text entry (plan §8.2): parse the whole payload at once — one shared
     * paragraph of words, comma/newline separated, imports in a single tap. Blank or fully
     * filtered text becomes the short [ExternalImportPhase.ERROR] state instead of a sheet
     * full of zeroes.
     */
    fun onSharedText(text: String) {
        if (_state.value.importing) return
        _state.value = when (val parsed = PlainTextWordParser.parse(text)) {
            is Outcome.Failure -> ExternalImportUiState(
                phase = ExternalImportPhase.ERROR,
                source = ExternalImportSource.SHARE,
                text = text,
                error = parsed.error.message,
            )

            is Outcome.Success -> ExternalImportUiState(
                phase = ExternalImportPhase.DETECTED,
                source = ExternalImportSource.SHARE,
                text = text,
                preview = parsed.value,
            )
        }
    }

    /**
     * Clipboard entry (plan §8.1): a clipboard holding URLs / Chinese / digits has nothing to
     * import, and **popping a sheet about it would be noise** — so unlike the share path this
     * one fails silently (MainActivity only calls it for non-blank, unseen content anyway).
     */
    fun onClipboardText(text: String) {
        if (_state.value.importing) return
        val parsed = PlainTextWordParser.parse(text).getOrNull() ?: return
        if (parsed.isEmpty) return
        // Persist the "seen" marker immediately: the sheet is on screen, so the VM is alive
        // and the write starts now — no dependence on dismiss/confirm being called before
        // the Activity is destroyed.
        val hash = ClipboardHash.of(text)
        viewModelScope.launch {
            runCatching { container.settingsRepository.setLastClipboardHash(hash) }
        }
        _state.value = ExternalImportUiState(
            phase = ExternalImportPhase.DETECTED,
            source = ExternalImportSource.CLIPBOARD,
            text = text,
            preview = parsed,
        )
    }

    /** Writes the previewed words into [packId], then queues meaning completion. */
    fun confirmImport(packId: String) {
        val current = _state.value
        // DETECTED is the only legal entry point: a second tap during IMPORTING or after
        // DONE must not start a duplicate write.
        if (current.phase != ExternalImportPhase.DETECTED) return
        val preview = current.preview ?: return
        _state.update { it.copy(phase = ExternalImportPhase.IMPORTING, error = null) }
        viewModelScope.launch { runImport(packId, preview) }
    }

    /**
     * "新建词库…" path: create the pack, make it the active study scope (so home/import all
     * point at the words just added), then import exactly like [confirmImport].
     */
    fun createPackAndImport(name: String) {
        val current = _state.value
        if (current.phase != ExternalImportPhase.DETECTED) return
        val preview = current.preview ?: return
        _state.update { it.copy(phase = ExternalImportPhase.IMPORTING, error = null) }
        viewModelScope.launch {
            when (
                val created = container.wordRepository.createPack(name, container.timeProvider.nowMillis())
            ) {
                is Outcome.Failure -> _state.update {
                    it.copy(phase = ExternalImportPhase.DETECTED, error = created.error.message)
                }

                is Outcome.Success -> {
                    container.settingsRepository.setActivePackId(created.value)
                    runImport(created.value, preview)
                }
            }
        }
    }

    /** Closes the sheet from any visible state; a write in flight owns the state and wins. */
    fun dismiss() {
        if (_state.value.importing) return
        _state.value = ExternalImportUiState(phase = ExternalImportPhase.DISMISSED)
    }

    /** True while the sheet covers the screen — MainActivity's clipboard probe checks this. */
    fun isSheetVisible(): Boolean = _state.value.sheetVisible

    private suspend fun runImport(packId: String, preview: PlainTextImportPreview) {
        when (
            val result = container.wordRepository.addWordsToPack(
                packId = packId,
                words = preview.words,
                now = container.timeProvider.nowMillis(),
            )
        ) {
            // A failed write goes back to DETECTED with the repository's message: the learner
            // can pick another pack or retry — the sheet never dies with the error.
            is Outcome.Failure -> _state.update {
                it.copy(phase = ExternalImportPhase.DETECTED, error = result.error.message)
            }

            is Outcome.Success -> {
                // Rows are already stored as PENDING (plan §6.2): a missing gateway only
                // delays completion, never data — so a sync failure must not fail the import.
                runCatching { meaningSync.enqueueAll() }
                _state.update {
                    it.copy(phase = ExternalImportPhase.DONE, result = result.value, error = null)
                }
            }
        }
    }

    companion object {
        /** Activity-scoped factory, same pattern as `ViewModelFactories` (see file header). */
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { ExternalImportViewModel(container) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sheet UI
// ---------------------------------------------------------------------------------------------

/** Host callbacks; `MainActivity` wires them to the [ExternalImportViewModel]. */
@Immutable
data class ExternalImportCallbacks(
    val onConfirmImport: (String) -> Unit = {},
    val onCreatePackAndImport: (String) -> Unit = {},
    val onDismiss: () -> Unit = {},
)

/** Pseudo-option rendered as the last chip; never selectable itself. */
private const val NEW_PACK_OPTION = "__new_pack__"

/** Mirrors the repository's name cap so the dialog validates before a round-trip (PackScreens). */
private const val MAX_PACK_NAME_FIELD_LENGTH = 40

/**
 * The import sheet shared by both intake paths (plan §8): counts → pack choice (or
 * "新建词库…") → "导入并补全", with a progress state that cannot be double-tapped and a
 * done summary of inserted / skipped rows. Style follows `PackScreens.kt` (same buttons,
 * chips, spacing and name dialog).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalImportSheet(
    state: ExternalImportUiState,
    packs: List<WordPackEntity>,
    activePackId: String,
    callbacks: ExternalImportCallbacks,
) {
    if (!state.sheetVisible) return

    // Re-initialised every time the sheet opens (the early return drops the slot), so the
    // default is always today's active pack rather than the one from a previous detection.
    var selectedPackId by remember { mutableStateOf(activePackId) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    // Keep the selection valid while the pack list / active pack are still loading.
    LaunchedEffect(packs, activePackId) {
        if (packs.isEmpty()) return@LaunchedEffect
        if (selectedPackId.isEmpty() || packs.none { it.id == selectedPackId }) {
            selectedPackId = activePackId.takeIf { id -> packs.any { it.id == id } }
                ?: packs.first().id
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!state.importing) callbacks.onDismiss() },
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            when (state.phase) {
                ExternalImportPhase.ERROR -> ErrorContent(state = state, callbacks = callbacks)
                ExternalImportPhase.DONE -> DoneContent(state = state, callbacks = callbacks)
                else -> DetectContent(
                    state = state,
                    packs = packs,
                    selectedPackId = selectedPackId,
                    onSelectPack = { id ->
                        if (!state.importing) {
                            if (id == NEW_PACK_OPTION) {
                                newName = ""
                                showCreateDialog = true
                            } else {
                                selectedPackId = id
                            }
                        }
                    },
                    onConfirm = {
                        if (selectedPackId.isNotEmpty()) callbacks.onConfirmImport(selectedPackId)
                    },
                    callbacks = callbacks,
                )
            }
        }
    }

    if (showCreateDialog) {
        CreatePackDialog(
            name = newName,
            onNameChange = { newName = it },
            onConfirm = {
                val trimmed = newName.trim()
                if (trimmed.isNotEmpty() && trimmed.length <= MAX_PACK_NAME_FIELD_LENGTH) {
                    showCreateDialog = false
                    callbacks.onCreatePackAndImport(trimmed)
                }
            },
            onDismiss = { showCreateDialog = false },
        )
    }
}

@Composable
private fun DetectContent(
    state: ExternalImportUiState,
    packs: List<WordPackEntity>,
    selectedPackId: String,
    onSelectPack: (String) -> Unit,
    onConfirm: () -> Unit,
    callbacks: ExternalImportCallbacks,
) {
    Text(
        text = when (state.source) {
            ExternalImportSource.CLIPBOARD -> "检测到剪贴板单词"
            ExternalImportSource.SHARE -> "分享的内容"
        },
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.testTag("ext_import_title"),
    )

    state.preview?.let { preview ->
        Text(
            text = "可导入 ${preview.importableCount} · 过滤 ${preview.dropped} · 重复 ${preview.duplicates}",
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.mutedText,
            modifier = Modifier.testTag("ext_import_stats"),
        )
    }

    AccentPanel {
        Text(
            text = "文本只在本机解析；确认导入后仅上传单词本身用于补全释义，不联网也能先入库。",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (packs.isNotEmpty()) {
        val names = packs.associate { it.id to it.name }
        FilterChipGroup(
            options = packs.map { it.id } + NEW_PACK_OPTION,
            selected = selectedPackId,
            onSelect = onSelectPack,
            label = { id ->
                if (id == NEW_PACK_OPTION) "新建词库…" else names[id].orEmpty()
            },
            modifier = Modifier.testTag("ext_import_packs"),
        )
    }

    state.error?.let { error ->
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("ext_import_error"),
        )
    }

    PrimaryButton(
        text = "导入并补全",
        onClick = onConfirm,
        // A spinner plus the disabled flag: importing twice would double-submit the batch.
        enabled = selectedPackId.isNotEmpty(),
        loading = state.importing,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ext_import_confirm"),
    )
    SecondaryButton(
        text = "忽略",
        onClick = { callbacks.onDismiss() },
        enabled = !state.importing,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ext_import_ignore"),
    )
}

@Composable
private fun DoneContent(
    state: ExternalImportUiState,
    callbacks: ExternalImportCallbacks,
) {
    Text(
        text = "导入完成",
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.testTag("ext_import_done_title"),
    )
    state.result?.let { result ->
        Text(
            text = "新增 ${result.inserted} · 跳过 ${result.skipped}（共提交 ${result.total} 个）",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("ext_import_done_summary"),
        )
    }
    Text(
        text = "单词已入库，释义与音标稍后自动补全。",
        style = MaterialTheme.typography.bodySmall,
        color = AppTheme.colors.mutedText,
    )
    PrimaryButton(
        text = "完成",
        onClick = { callbacks.onDismiss() },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ext_import_done"),
    )
}

@Composable
private fun ErrorContent(
    state: ExternalImportUiState,
    callbacks: ExternalImportCallbacks,
) {
    Text(
        text = "没有识别到单词",
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.testTag("ext_import_error_title"),
    )
    Text(
        text = state.error ?: "没有可导入的单词。",
        style = MaterialTheme.typography.bodyMedium,
        color = AppTheme.colors.mutedText,
        modifier = Modifier.testTag("ext_import_error_message"),
    )
    SecondaryButton(
        text = "知道了",
        onClick = { callbacks.onDismiss() },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ext_import_error_close"),
    )
}

/** Create-and-import dialog, validated inline like `PackScreens.PackNameDialog`. */
@Composable
private fun CreatePackDialog(
    name: String,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val trimmed = name.trim()
    val tooLong = trimmed.length > MAX_PACK_NAME_FIELD_LENGTH

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建词库") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
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
                keyboardActions = KeyboardActions(onDone = { onConfirm() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ext_import_pack_name_field"),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = trimmed.isNotEmpty() && !tooLong) {
                Text("创建并导入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
