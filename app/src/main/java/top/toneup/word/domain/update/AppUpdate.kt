package top.toneup.word.domain.update

/**
 * Pure version-string handling for the in-app update check (GitHub Releases).
 *
 * Releases are tagged `v<major>.<minor>.<patch>` (e.g. `v1.0.4`); the installed version comes
 * from `versionName`. Both go through [parse] so the comparison is numeric, not lexicographic —
 * `"1.0.10"` must be newer than `"1.0.9"`, which a string compare gets wrong.
 */
object AppVersion {

    /**
     * `"v1.0.4"` / `"1.0.4"` / `"1.0.4-beta"` → `[1, 0, 4]`.
     *
     * Every digit group in the string becomes one segment; all other characters are ignored.
     * Returns `null` when the string contains no digits at all (unparseable tag — treated as
     * "no update" by [AppUpdatePolicy] so a bad release tag can never trigger a bogus prompt).
     */
    fun parse(raw: String?): List<Int>? {
        if (raw.isNullOrBlank()) return null
        val segments = Regex("""\d+""").findAll(raw)
            .mapNotNull { it.value.toIntOrNull() }
            .toList()
        return segments.ifEmpty { null }
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

    /** `[1, 0, 4]` → `"1.0.4"`; used as the display version in the install prompt. */
    fun format(parts: List<Int>): String = parts.joinToString(".")
}

/** What the update orchestration should do after comparing the remote release with local state. */
sealed interface UpdateDecision {
    /** Same release is already downloaded and intact — just (re)show the install prompt. */
    data object Ready : UpdateDecision

    /** A newer, not-yet-dismissed release exists — start (or resume) the download. */
    data object Download : UpdateDecision

    /** Newer than the installed app, but the user already declined this exact version. */
    data object Dismissed : UpdateDecision

    /** Remote is not newer than what is installed. */
    data object UpToDate : UpdateDecision

    /** Remote tag carries no digits — never prompt on garbage. */
    data object Unknown : UpdateDecision
}

/**
 * The whole update decision as one pure function so it can be tested without network or files.
 *
 * Order matters: the installed check wins first (an old "already-up-to-date" release must not
 * resurrect a prompt), then the user's dismissal, then the downloaded-but-uninstalled cache.
 */
object AppUpdatePolicy {

    fun decide(
        remoteTag: String,
        installedVersion: String,
        downloadedTag: String?,
        dismissedTag: String?,
    ): UpdateDecision {
        val remote = AppVersion.parse(remoteTag) ?: return UpdateDecision.Unknown
        val installed = AppVersion.parse(installedVersion)
        if (installed != null && !AppVersion.isNewer(remote, installed)) return UpdateDecision.UpToDate
        val dismissed = AppVersion.parse(dismissedTag)
        if (dismissed != null && !AppVersion.isNewer(remote, dismissed)) return UpdateDecision.Dismissed
        if (downloadedTag == remoteTag) return UpdateDecision.Ready
        return UpdateDecision.Download
    }

    /**
     * Rate limit for the releases API (unauthenticated quota is 60/h per IP): check at most
     * once per [minIntervalMillis]. A failed check does not update the timestamp, so the next
     * app start retries instead of waiting out the interval.
     */
    fun shouldCheckNow(lastCheckedMillis: Long?, nowMillis: Long, minIntervalMillis: Long): Boolean {
        val last = lastCheckedMillis ?: return true
        return nowMillis - last >= minIntervalMillis
    }
}
