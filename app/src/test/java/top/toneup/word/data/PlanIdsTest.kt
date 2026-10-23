package top.toneup.word.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import top.toneup.word.data.settings.PlanIds
import top.toneup.word.domain.model.CetLevel

/**
 * Unit tests for the plan ids and the plan ↔ level mapping (plan hierarchy, D5/D6).
 *
 * Since schema v4 a plan is the study scope; the mapping here is the single place that decides
 * which display level a plan shows and which「手写词」pack belongs to it, so both must be total
 * and stable — the UI labels and the write path depend on them.
 */
class PlanIdsTest {

    @Test
    fun `the default plan keeps the identifiers old installs were migrated to`() {
        assertThat(PlanIds.DEFAULT_PLAN).isEqualTo("plan-default")
        assertThat(PlanIds.DEFAULT_PLAN_NAME).isEqualTo("默认计划")
    }

    @Test
    fun `the default plan still shows as CET-6 and user plans as custom`() {
        // Plans carry no level column (D5): the default plan keeps the label an upgraded install
        // showed before, and every user-created plan is the neutral level.
        assertThat(PlanIds.levelForPlanId(PlanIds.DEFAULT_PLAN)).isEqualTo(CetLevel.CET6)
        assertThat(PlanIds.levelForPlanId("plan-1-abc")).isEqualTo(CetLevel.OTHER)
        assertThat(PlanIds.levelForPlanId("")).isEqualTo(CetLevel.OTHER)
    }

    @Test
    fun `each plan owns one hand-written pack`() {
        assertThat(PlanIds.writePackIdFor(PlanIds.DEFAULT_PLAN)).isEqualTo("write-plan-default")
        assertThat(PlanIds.writePackIdFor("plan-1-abc")).isEqualTo("write-plan-1-abc")
        assertThat(PlanIds.WRITE_PACK_NAME).isEqualTo("手写词")
    }
}
