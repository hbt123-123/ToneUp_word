package top.toneup.word.domain.update

/**
 * Pure update models and policy for the in-app updater.
 *
 * The wire contract is shared with `ToneUp_read` (see docs/UPDATE_PROTOCOL.md): the newest
 * GitHub release publishes two fixed assets, `version.json` and `ToneUpWord.apk`. The app reads
 * the manifest, compares `versionCode`, downloads the APK with resume support, verifies its
 * SHA-256, and only then hands it to the system installer.
 *
 * Everything here is Android-free so the whole decision can be unit-tested without network,
 * files or a package manager.
 */

/** One release as described by `version.json`. */
data class RemoteUpdate(
    /** Human-readable version, e.g. `1.0.3`. */
    val version: String,
    /** Monotonic integer; the primary comparison key. */
    val versionCode: Int,
    /** Direct or mirrored download URL of the fixed APK asset. */
    val apkUrl: String,
    /** Lower-case SHA-256 of the APK. Never install a file whose digest differs. */
    val sha256: String,
    /** Declared byte size — the resume/verify reference. 0 means "unknown". */
    val size: Long,
    /** Release notes shown in the update dialog. */
    val notes: String,
)

/**
 * Numeric version parsing, deliberately identical to the Dart implementation:
 * `"v3.6.0"` / `"3.6"` / `"v1.0.4-beta"` → three segments, padded with zeros.
 * Anything without digits, or with more than three dot segments, is unparseable.
 */
object AppVersion {

    fun parse(raw: String?): List<Int>? {
        var text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text.startsWith("v") || text.startsWith("V")) text = text.substring(1)
        text = text.split('+').first().split('-').first()
        val parts = text.split('.')
        if (parts.isEmpty() || parts.size > 3) return null
        val out = parts.map { it.toIntOrNull() ?: return null }
        return out + List(3 - out.size) { 0 }
    }

    /** Numeric segment-wise comparison; equal versions are not newer. */
    fun isNewer(remote: List<Int>, local: List<Int>): Boolean {
        val longest = maxOf(remote.size, local.size)
        for (index in 0 until longest) {
            val r = remote.getOrElse(index) { 0 }
            val l = local.getOrElse(index) { 0 }
            if (r != l) return r > l
        }
        return false
    }

    /** `[1, 0, 3]` → `"1.0.3"`. */
    fun format(parts: List<Int>): String = parts.joinToString(".")
}

/** What the orchestration should do after comparing the manifest with local state. */
sealed interface UpdateDecision {
    /** Same version is already downloaded and intact — show the install prompt. */
    data object Ready : UpdateDecision

    /** A newer, not-yet-dismissed version exists — download it. */
    data object Download : UpdateDecision

    /** Newer than the installed app, but the learner already skipped this exact versionCode. */
    data object Dismissed : UpdateDecision

    /** Remote is not newer than what is installed. */
    data object UpToDate : UpdateDecision

    /** Manifest is internally inconsistent — never prompt on garbage. */
    data object Unknown : UpdateDecision
}

object AppUpdatePolicy {

    /**
     * Order matters: installed wins first (an old manifest must not resurrect a prompt), then the
     * learner's dismissal, then the downloaded-but-uninstalled file.
     */
    fun decide(
        remote: RemoteUpdate,
        installedVersion: String,
        installedVersionCode: Int,
        downloadedVersionCode: Int,
        dismissedVersionCode: Int,
    ): UpdateDecision {
        if (remote.version.isBlank()) return UpdateDecision.Unknown
        if (remote.versionCode <= 0 && AppVersion.parse(remote.version) == null) {
            return UpdateDecision.Unknown
        }
        val newer = isNewer(remote, installedVersion, installedVersionCode) ?: return UpdateDecision.UpToDate
        if (!newer) return UpdateDecision.UpToDate
        if (dismissedVersionCode > 0 && remote.versionCode <= dismissedVersionCode) {
            return UpdateDecision.Dismissed
        }
        if (downloadedVersionCode > 0 && downloadedVersionCode == remote.versionCode) {
            return UpdateDecision.Ready
        }
        return UpdateDecision.Download
    }

    /**
     * `versionCode` is authoritative when both sides have one. Otherwise fall back to the
     * semantic version. `null` = cannot tell, which callers treat as "do not prompt".
     */
    fun isNewer(
        remote: RemoteUpdate,
        installedVersion: String,
        installedVersionCode: Int,
    ): Boolean? {
        if (remote.versionCode > 0 && installedVersionCode > 0) {
            return remote.versionCode > installedVersionCode
        }
        val remoteSemver = AppVersion.parse(remote.version) ?: return null
        val installedSemver = AppVersion.parse(installedVersion) ?: return null
        return AppVersion.isNewer(remoteSemver, installedSemver)
    }

    /**
     * Automatic checks run at most once per [minIntervalMillis]. Only a *successful* fetch writes
     * the timestamp (see the repository), so a failed check is retried on the next app start
     * instead of waiting out the window.
     */
    fun shouldCheckNow(lastCheckedMillis: Long?, nowMillis: Long, minIntervalMillis: Long): Boolean {
        val last = lastCheckedMillis ?: return true
        return nowMillis - last >= minIntervalMillis
    }
}
