package top.toneup.word.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Builds the system intents that turn a downloaded APK into an installed update.
 *
 * Side-loaded updates need the "install unknown apps" grant on API 26+; when it is missing we
 * hand back the settings screen instead of the installer, and the prompt simply re-appears
 * after the user returns (the APK stays on disk).
 */
object ApkInstaller {

    /** Authority must match the manifest provider; `${applicationId}` differs in debug builds. */
    fun authority(context: Context): String = "${context.packageName}.fileprovider"

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, authority(context), apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
