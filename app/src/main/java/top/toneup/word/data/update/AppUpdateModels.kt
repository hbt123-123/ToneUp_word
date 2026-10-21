package top.toneup.word.data.update

import android.content.Intent

/**
 * Models shared across the app-update pipeline (check → download → verify → install).
 *
 * [UpdatePhase] mirrors the one in `ToneUp_read/lib/services/update_controller.dart` so both
 * apps explain the same states to the learner. The UI only renders [AppUpdateUiState]; the
 * repository owns the transitions.
 */

enum class UpdatePhase {
    Idle,
    Checking,
    Latest,
    Available,
    Downloading,
    Verifying,
    Ready,
    Failed,
}

/** Everything the update dialog and the profile row need, in one immutable snapshot. */
data class AppUpdateUiState(
    val phase: UpdatePhase = UpdatePhase.Idle,
    /** Installed versionName, shown as "当前 vX". */
    val installedVersion: String = "",
    /** Remote (or ready) versionName. */
    val version: String = "",
    /** Remote (or ready) versionCode. */
    val versionCode: Int = 0,
    val notes: String = "",
    /** Declared APK size in bytes; 0 when unknown. */
    val size: Long = 0,
    val received: Long = 0,
    val total: Long = 0,
    val error: String = "",
    /**
     * One-shot install request; MainActivity launches it and calls `consumeInstallRequest()`.
     * Kept in state (instead of starting an Activity from the repository) so the UI layer owns
     * every Android Intent.
     */
    val installRequest: AppInstallRequest? = null,
) {
    val progress: Float
        get() = if (total > 0) (received.toFloat() / total).coerceIn(0f, 1f) else 0f
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
