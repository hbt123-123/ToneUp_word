package top.toneup.word.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import top.toneup.word.data.update.AppUpdateUiState
import top.toneup.word.data.update.UpdatePhase
import top.toneup.word.ui.theme.AppTheme
import top.toneup.word.ui.theme.Spacing

/**
 * Host-level update dialog — the same phases and buttons as `ToneUp_read`'s update sheet:
 *
 *  * Available: "立即更新" / "稍后" / "跳过此版本";
 *  * Downloading / Verifying: live progress, the dialog can be closed and the download continues;
 *  * Ready: "立即安装" / "稍后";
 *  * Failed: "重试" / "关闭".
 *
 * It is raised by the launch check only when a verified APK is ready, or manually by the profile
 * "检查更新" row. The repository owns every transition; this composable only renders state.
 */
@Composable
fun AppUpdateDialog(
    state: AppUpdateUiState,
    onInstall: () -> Unit,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    onClose: () -> Unit,
) {
    val phase = state.phase
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(titleFor(phase)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                when (phase) {
                    UpdatePhase.Checking -> Text(
                        text = "正在连接更新服务器…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.colors.mutedText,
                    )

                    UpdatePhase.Latest -> Text(
                        text = "当前版本 v${state.installedVersion} 已是最新。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.colors.mutedText,
                    )

                    UpdatePhase.Available -> AvailableBody(state)

                    UpdatePhase.Downloading,
                    UpdatePhase.Verifying,
                    -> DownloadBody(state)

                    UpdatePhase.Ready -> ReadyBody(state)

                    UpdatePhase.Failed -> Text(
                        text = state.error.ifBlank { "更新失败，请稍后重试。" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                    UpdatePhase.Idle -> Text(
                        text = "点击检查更新。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.colors.mutedText,
                    )
                }
            }
        },
        confirmButton = {
            when (phase) {
                UpdatePhase.Checking -> TextButton(onClick = onClose) { Text("取消") }
                UpdatePhase.Latest -> TextButton(onClick = onClose) { Text("关闭") }
                UpdatePhase.Available -> TextButton(onClick = onDownload) { Text("立即更新") }
                UpdatePhase.Downloading,
                UpdatePhase.Verifying,
                -> TextButton(onClick = onClose) { Text("后台下载") }

                UpdatePhase.Ready -> TextButton(onClick = onInstall) { Text("立即安装") }
                UpdatePhase.Failed -> TextButton(onClick = onRetry) { Text("重试") }
                UpdatePhase.Idle -> TextButton(onClick = onClose) { Text("关闭") }
            }
        },
        dismissButton = {
            when (phase) {
                UpdatePhase.Available -> Row {
                    TextButton(onClick = onClose) { Text("稍后") }
                    TextButton(onClick = onSkip) { Text("跳过此版本") }
                }

                UpdatePhase.Ready -> TextButton(onClick = onClose) { Text("稍后") }
                UpdatePhase.Failed -> TextButton(onClick = onClose) { Text("关闭") }
                else -> Unit
            }
        },
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    )
}

@Composable
private fun AvailableBody(state: AppUpdateUiState) {
    Text(
        text = "v${state.installedVersion} → v${state.version}",
        style = MaterialTheme.typography.titleSmall,
    )
    if (state.notes.isNotBlank()) {
        Text(
            text = state.notes,
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.mutedText,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (state.size > 0) {
        Text(
            text = "安装包 ${formatMb(state.size)} MB",
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.mutedText,
        )
    }
}

@Composable
private fun DownloadBody(state: AppUpdateUiState) {
    val verifying = state.phase == UpdatePhase.Verifying
    LinearProgressIndicator(
        progress = { if (verifying) 1f else state.progress },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = if (verifying) {
            "下载完成，正在校验安装包…"
        } else {
            "${(state.progress * 100).toInt()}%　" +
                "${formatMb(state.received)} / ${formatMb(state.total)} MB"
        },
        style = MaterialTheme.typography.bodySmall,
        color = AppTheme.colors.mutedText,
    )
    Text(
        text = "可以关闭此窗口继续使用，下载会在后台完成。",
        style = MaterialTheme.typography.bodySmall,
        color = AppTheme.colors.mutedText,
    )
}

@Composable
private fun ReadyBody(state: AppUpdateUiState) {
    Text(
        text = "版本 ${state.version} 已下载并通过 SHA-256 校验。",
        style = MaterialTheme.typography.bodyMedium,
    )
    if (state.notes.isNotBlank()) {
        Text(
            text = state.notes,
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.mutedText,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Text(
        text = "安装时应用会短暂关闭，学习进度不会丢失。",
        style = MaterialTheme.typography.bodySmall,
        color = AppTheme.colors.mutedText,
    )
}

private fun titleFor(phase: UpdatePhase): String = when (phase) {
    UpdatePhase.Checking -> "正在检查更新"
    UpdatePhase.Latest -> "已是最新版本"
    UpdatePhase.Available -> "发现新版本"
    UpdatePhase.Downloading,
    UpdatePhase.Verifying,
    -> "正在下载更新"

    UpdatePhase.Ready -> "新版本已就绪"
    UpdatePhase.Failed -> "更新失败"
    UpdatePhase.Idle -> "检查更新"
}

private fun formatMb(bytes: Long): String =
    if (bytes <= 0) "0.0" else "%.1f".format(bytes / 1024.0 / 1024.0)
