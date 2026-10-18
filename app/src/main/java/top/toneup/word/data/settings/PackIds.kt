package top.toneup.word.data.settings

import top.toneup.word.domain.model.CetLevel

/**
 * Canonical pack ids and the level ↔ pack mapping (plan D1/D2, schema v2).
 *
 * A **pack id** is the scope of every study query since v2. The single remaining built-in pack
 * keeps a stable id so seeded content, migrations and backups all agree on it; historical rows
 * that cannot be attributed to a real pack live under [LEGACY_OTHER] — kept for data safety,
 * never shown in any pack's statistics (D9/Q1).
 *
 * The CET-4 built-in pack no longer ships, but rows that earlier migrations moved to
 * `builtin-cet4` (see `Migrations.kt`, which is immutable history) can still exist in an
 * upgraded install, so the id is handled gracefully: it simply degrades to `OTHER`.
 * `UserSettings.level` (onboarding default) feeds `defaultPackIdFor` until the learner
 * explicitly picks a pack — that is what keeps pre-upgrade installs behaving identically.
 */
object PackIds {

    const val BUILTIN_CET6: String = "builtin-cet6"

    /** Reserved bucket for rows migrated from the pre-pack era (D9/Q1). Not a selectable pack. */
    const val LEGACY_OTHER: String = "legacy-other"

    /**
     * Pack a fresh install studies when the learner's default level is [level].
     *
     * Only CET-6 still has a bundled pack; a pre-upgrade install whose default level is CET-4
     * degrades to the remaining built-in so it always has a valid study scope instead of a
     * dangling id.
     */
    fun defaultPackIdFor(level: CetLevel): String = when (level) {
        CetLevel.CET4 -> BUILTIN_CET6
        CetLevel.CET6 -> BUILTIN_CET6
        CetLevel.OTHER -> LEGACY_OTHER
    }

    /**
     * Display level of a pack. Imported packs carry `OTHER` metadata; unknown ids (a pack
     * deleted after its settings row was written, backups from the future, …) also fall back
     * to `OTHER` so nothing ever renders as an official CET list by accident.
     */
    fun levelForPackId(packId: String): CetLevel = when (packId) {
        BUILTIN_CET6 -> CetLevel.CET6
        else -> CetLevel.OTHER
    }
}
