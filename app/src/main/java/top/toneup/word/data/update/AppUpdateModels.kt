package top.toneup.word.data.update

import android.content.Intent
import top.toneup.word.core.result.AppError
import java.io.File

/**
 * Models shared across the app-update pipeline (check → download → install).
 *
 * Kept free of Android framework types except [androidx]‑free `java.io.File` so the repository
 * and its tests stay light: the UI only renders [PendingAppUpdate.versionName] and hands the
 * file back through [AppInstallRequest].
 */

/** The `latest` GitHub release, reduced to what the update flow needs. */
data class RemoteRelease(
    /** Raw tag, e.g. `v1.0.4`. */
    val tagName: String,
    /** `browser_download_url` of the first `*.apk` asset. */
    val apkUrl: String,
    /** Declared byte size of that asset — the resume/verify reference. */
    val apkSize: Long,
    /** Human-readable release page, kept for future "查看更新说明" links. */
    val releasePageUrl: String,
)

/** A fully downloaded APK that waits for the learner's go-ahead to install. */
data class PendingAppUpdate(
    /** Display version, e.g. `1.0.4` (tag without the leading `v`). */
    val versionName: String,
    /** Raw release tag this file was downloaded for. */
    val tagName: String,
    /** The local APK file (inside the app's cache dir). */
    val apk: File,
)

/** Result of one check+download run — what [top.toneup.word.work.AppUpdateWorker] maps to retry/failure. */
sealed interface AppUpdateCheckResult {
    /** A newer release was fetched and downloaded; the prompt flow will emit. */
    data object Downloaded : AppUpdateCheckResult

    /** The release was already on disk; the prompt flow will emit. */
    data object AlreadyReady : AppUpdateCheckResult

    /** Remote is older/equal, dismissed, or unparseable — nothing to do this run. */
    data object NothingToDo : AppUpdateCheckResult

    /** Network/parse/IO failure; [AppError.retryable] decides between retry and give-up. */
    data class Failed(val error: AppError) : AppUpdateCheckResult
}

/** What the UI should launch to fulfil an install tap. */
sealed interface AppInstallRequest {
    /** Permission already granted — start this `ACTION_VIEW` install intent. */
    data class Ready(val intent: Intent) : AppInstallRequest

    /** Needs the "install unknown apps" grant first — open the system settings screen. */
    data class NeedPermission(val intent: Intent) : AppInstallRequest

    /** No intact downloaded APK (cache evicted) — the check flow will re-download later. */
    data object Unavailable : AppInstallRequest
}
