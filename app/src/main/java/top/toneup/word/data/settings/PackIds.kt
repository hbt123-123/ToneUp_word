package top.toneup.word.data.settings

import top.toneup.word.domain.model.CetLevel

/**
 * Canonical pack ids and the level ↔ pack mapping (plan D1/D2, schema v2).
 *
 * A **pack id** is the scope of every study query since v2. The two built-in packs keep
 * stable ids so seeded content, migrations and backups all agree on them; historical rows
 * that cannot be attributed to a real pack live under [LEGACY_OTHER] — kept for data safety,
 * never shown in any pack's statistics (D9/Q1).
 *
 * The mapping is total and bijective over the reserved ids: `levelForPackId` is the exact
 * inverse of `defaultPackIdFor` for them and degrades to `OTHER` for anything imported.
 * `UserSettings.level` (onboarding default) feeds `defaultPackIdFor` until the learner
 * explicitly picks a pack — that is what keeps pre-upgrade installs behaving identically.
 */
object PackIds {

    const val BUILTIN_CET4: String = "builtin-cet4"
    const val BUILTIN_CET6: String = "builtin-cet6"

    /** Reserved bucket for rows migrated from the pre-pack era (D9/Q1). Not a selectable pack. */
    const val LEGACY_OTHER: String = "legacy-other"

    /** Pack a fresh install studies when the learner's default level is [level]. */
    fun defaultPackIdFor(level: CetLevel): String = when (level) {
        CetLevel.CET4 -> BUILTIN_CET4
        CetLevel.CET6 -> BUILTIN_CET6
        CetLevel.OTHER -> LEGACY_OTHER
    }

    /**
     * Display level of a pack. Imported packs carry `OTHER` metadata; unknown ids (a pack
     * deleted after its settings row was written, backups from the future, …) also fall back
     * to `OTHER` so nothing ever renders as an official CET list by accident.
     */
    fun levelForPackId(packId: String): CetLevel = when (packId) {
        BUILTIN_CET4 -> CetLevel.CET4
        BUILTIN_CET6 -> CetLevel.CET6
        else -> CetLevel.OTHER
    }
}
