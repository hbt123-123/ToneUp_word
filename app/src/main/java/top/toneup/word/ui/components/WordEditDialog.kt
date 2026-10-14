package top.toneup.word.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.launch
import top.toneup.word.core.result.Outcome
import top.toneup.word.domain.model.CetLevel
import top.toneup.word.domain.model.Word
import top.toneup.word.ui.theme.AppTheme
import top.toneup.word.ui.theme.ToneUpWordTheme
import top.toneup.word.ui.theme.Spacing

/**
 * The card / word-detail editor for the two things a learner may hand-write (plan §9): their
 * own **note**, and a **meaning** set that replaces the automatic one.
 *
 * Why the dialog owns no repository: the callers (study screen, word detail) already hold the
 * screen's scope and refresh the card afterwards, so the dialog only collects text and hands
 * back `Outcome`s — which keeps it a pure, previewable component and follows the rule that a
 * Composable never touches the database.
 *
 * The meaning field carries the standing warning "手动编辑后不再被自动补全覆盖": saving it
 * flips the row to `meaning_source='manual'`, a terminal state the completion worker and the
 * write-back both skip forever (D4), so the promise is enforced in SQL, not in this dialog.
 */
@Composable
fun WordEditDialog(
    word: Word,
    onSaveNote: suspend (String) -> Outcome<Unit>,
    onSaveMeaning: suspend (List<String>) -> Outcome<Unit>,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var noteText by remember(word.id) { mutableStateOf(word.userNote) }
    var meaningText by remember(word.id) { mutableStateOf(meaningsToInput(word.meanings)) }
    var saving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun startSave() {
        if (saving) return
        val noteDirty = noteText != word.userNote
        val meaningDirty = meaningText != meaningsToInput(word.meanings)
        if (!noteDirty && !meaningDirty) {
            onDismiss()
            return
        }
        if (meaningDirty && parseMeaningInput(meaningText).isEmpty()) {
            errorMessage = "释义不能为空；多条释义之间用「；」或换行分隔。"
            return
        }
        saving = true
        errorMessage = null
        scope.launch {
            if (noteDirty) {
                when (val noteResult = onSaveNote(noteText)) {
                    is Outcome.Failure -> {
                        errorMessage = noteResult.error.message
                        saving = false
                        return@launch
                    }

                    is Outcome.Success -> Unit
                }
            }
            if (meaningDirty) {
                when (val meaningResult = onSaveMeaning(parseMeaningInput(meaningText))) {
                    is Outcome.Failure -> {
                        errorMessage = meaningResult.error.message
                        saving = false
                        return@launch
                    }

                    is Outcome.Success -> Unit
                }
            }
            saving = false
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = {
            Text(
                text = "编辑 ${word.word}",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(
                    text = "笔记",
                    style = MaterialTheme.typography.labelLarge,
                    color = AppTheme.colors.mutedText,
                )
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_note_field"),
                    label = { Text("自己的理解、联想或易混词") },
                    minLines = 3,
                    shape = MaterialTheme.shapes.medium,
                )

                HorizontalDivider(color = AppTheme.colors.hairline)

                Text(
                    text = "释义",
                    style = MaterialTheme.typography.labelLarge,
                    color = AppTheme.colors.mutedText,
                )
                OutlinedTextField(
                    value = meaningText,
                    onValueChange = { meaningText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_meaning_field"),
                    label = { Text("多条释义之间用「；」或换行分隔") },
                    minLines = 3,
                    shape = MaterialTheme.shapes.medium,
                )
                Text(
                    text = "手动编辑后不再被自动补全覆盖。",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.mutedText,
                    modifier = Modifier.testTag("edit_manual_hint"),
                )

                errorMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("edit_error"),
                    )
                }
                Spacer(Modifier.height(Spacing.xs))
            }
        },
        confirmButton = {
            TextButton(onClick = ::startSave, enabled = !saving) {
                Text(
                    text = if (saving) "保存中…" else "保存",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!saving) onDismiss() }) {
                Text(
                    text = "取消",
                    style = MaterialTheme.typography.labelLarge,
                    color = AppTheme.colors.mutedText,
                )
            }
        },
    )
}

/**
 * The editor's interchange format: the meaning field holds one entry per line break, `；`
 * or `;`, which is how the multi-meaning list is round-tripped through a single text box.
 *
 * Blank fragments are dropped, so a trailing newline or a doubled separator can never store an
 * empty meaning.
 */
internal fun parseMeaningInput(raw: String): List<String> =
    raw.split('\n', '\r', '；', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

/** Prefill for the meaning field: the stored list joined with the same separator. */
internal fun meaningsToInput(meanings: List<String>): String = meanings.joinToString("；")

@Preview(showBackground = true)
@Composable
private fun WordEditDialogPreview() {
    ToneUpWordTheme {
        WordEditDialog(
            word = Word(
                id = 1,
                word = "abandon",
                meanings = listOf("放弃；抛弃", "遗弃"),
                userNote = "联想：a + band + on → 把人从乐队里赶走 = 抛弃",
                enDefinition = "to leave a place, thing, or person, usually forever",
                level = CetLevel.CET4,
            ),
            onSaveNote = { Outcome.Success(Unit) },
            onSaveMeaning = { Outcome.Success(Unit) },
            onDismiss = {},
        )
    }
}
