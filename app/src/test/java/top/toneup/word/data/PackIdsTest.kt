package top.toneup.word.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import top.toneup.word.data.settings.PackIds
import top.toneup.word.domain.model.CetLevel

/**
 * Unit tests for the level ↔ pack-id mapping (plan D9/Q1).
 *
 * The mapping is the single place that decides which reserved pack a legacy `level` value
 * becomes, and which display level a pack id shows as — both directions must be total and
 * stable, because migration backfills and UI labels depend on them.
 */
class PackIdsTest {

    @Test
    fun `the reserved pack ids are stable identifiers`() {
        assertThat(PackIds.BUILTIN_CET4).isEqualTo("builtin-cet4")
        assertThat(PackIds.BUILTIN_CET6).isEqualTo("builtin-cet6")
        assertThat(PackIds.LEGACY_OTHER).isEqualTo("legacy-other")
    }

    @Test
    fun `defaultPackIdFor maps each level to its reserved pack`() {
        assertThat(PackIds.defaultPackIdFor(CetLevel.CET4)).isEqualTo(PackIds.BUILTIN_CET4)
        assertThat(PackIds.defaultPackIdFor(CetLevel.CET6)).isEqualTo(PackIds.BUILTIN_CET6)
        assertThat(PackIds.defaultPackIdFor(CetLevel.OTHER)).isEqualTo(PackIds.LEGACY_OTHER)
    }

    @Test
    fun `levelForPackId is the inverse for reserved ids`() {
        assertThat(PackIds.levelForPackId(PackIds.BUILTIN_CET4)).isEqualTo(CetLevel.CET4)
        assertThat(PackIds.levelForPackId(PackIds.BUILTIN_CET6)).isEqualTo(CetLevel.CET6)
        assertThat(PackIds.levelForPackId(PackIds.LEGACY_OTHER)).isEqualTo(CetLevel.OTHER)
    }

    @Test
    fun `unknown pack ids fall back to the custom level`() {
        assertThat(PackIds.levelForPackId("imported-my-list")).isEqualTo(CetLevel.OTHER)
        assertThat(PackIds.levelForPackId("")).isEqualTo(CetLevel.OTHER)
    }
}
