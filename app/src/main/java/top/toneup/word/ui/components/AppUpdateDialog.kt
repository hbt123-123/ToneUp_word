package top.toneup.word.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

/**
 * "新版本已就绪" install prompt — the third host-level overlay (next to the start-up line and
 * the import sheet). It is raised by [top.toneup.word.ui.MainViewModel.pendingUpdate] the
 * moment the silent background download of a newer release finishes; "下次再说" suppresses
 * that exact version, "立即安装" hands the APK to the system installer.
 */
@Composable
fun AppUpdateDialog(
    versionName: String,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "新版本已就绪") },
        text = {
            Text(
                text = "版本 $versionName 已在后台下载完成，现在安装即可更新。" +
                    "安装过程中应用会短暂关闭，学习进度不会丢失。",
            )
        },
        confirmButton = {
            TextButton(onClick = onInstall) { Text(text = "立即安装") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "下次再说") }
        },
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    )
}
