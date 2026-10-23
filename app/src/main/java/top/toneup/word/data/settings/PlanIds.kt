package top.toneup.word.data.settings

import top.toneup.word.domain.model.CetLevel

/**
 * Canonical plan ids and the plan ↔ level mapping (schema v4, `plan-default`).
 *
 * Since v4 the scope of every study query is a **plan id**: a plan groups any number of
 * `word_pack` rows and the learner studies the union of their words (de-duplicated inside the
 * plan by `word_with_progress`). Packs stay the content/management unit.
 *
 * The built-in [DEFAULT_PLAN] is created by migration 3→4 and by `ensureDefaultPlan()`, so
 * every pre-upgrade install keeps studying exactly the packs it had before.
 */
object PlanIds {

    /** The built-in plan every pre-upgrade pack is migrated into (D3). */
    const val DEFAULT_PLAN: String = "plan-default"

    /** Name shown for [DEFAULT_PLAN]; renamable by the learner (D7). */
    const val DEFAULT_PLAN_NAME: String = "默认计划"

    /**
     * Display level of a plan.
     *
     * Plans carry no `level` column on purpose (D5): the default plan keeps the CET-6 label so
     * an upgraded install still renders 六级, and every user-created plan is `OTHER` — exactly
     * what `WordRepository.createPack` already recorded for user packs.
     */
    fun levelForPlanId(planId: String): CetLevel = when (planId) {
        DEFAULT_PLAN -> CetLevel.CET6
        else -> CetLevel.OTHER
    }

    /** Id of the per-plan "手写词" pack (D6): `write-<planId>`. */
    fun writePackIdFor(planId: String): String = "write-$planId"

    /** Name of the per-plan hand-written pack. */
    const val WRITE_PACK_NAME: String = "手写词"
}
