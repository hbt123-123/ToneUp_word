package top.toneup.word.domain.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Version parsing/comparison and the whole update decision ([AppUpdatePolicy]). */
class AppVersionTest {

    // --- parse ------------------------------------------------------------------------------

    @Test
    fun `parses a v-prefixed tag into numeric segments`() {
        assertThat(AppVersion.parse("v1.0.4")).containsExactly(1, 0, 4).inOrder()
    }

    @Test
    fun `parses a bare version name`() {
        assertThat(AppVersion.parse("1.0.4")).containsExactly(1, 0, 4).inOrder()
    }

    @Test
    fun `ignores non numeric suffixes`() {
        assertThat(AppVersion.parse("1.0.4-beta")).containsExactly(1, 0, 4).inOrder()
    }

    @Test
    fun `handles multi digit segments`() {
        assertThat(AppVersion.parse("v2.10")).containsExactly(2, 10).inOrder()
    }

    @Test
    fun `returns null for strings without digits`() {
        assertThat(AppVersion.parse("release")).isNull()
        assertThat(AppVersion.parse("")).isNull()
        assertThat(AppVersion.parse(null)).isNull()
    }

    // --- format -----------------------------------------------------------------------------

    @Test
    fun `formats segments back to a display version`() {
        assertThat(AppVersion.format(listOf(1, 0, 4))).isEqualTo("1.0.4")
    }

    // --- isNewer ----------------------------------------------------------------------------

    @Test
    fun `numeric compare is not lexicographic`() {
        assertThat(AppVersion.isNewer(listOf(1, 0, 10), listOf(1, 0, 9))).isTrue()
        assertThat(AppVersion.isNewer(listOf(1, 0, 9), listOf(1, 0, 10))).isFalse()
    }

    @Test
    fun `equal versions are not newer`() {
        assertThat(AppVersion.isNewer(listOf(1, 0, 3), listOf(1, 0, 3))).isFalse()
    }

    @Test
    fun `shorter prefix loses to an extra non zero segment`() {
        assertThat(AppVersion.isNewer(listOf(1, 0, 1), listOf(1, 0))).isTrue()
        assertThat(AppVersion.isNewer(listOf(1, 0), listOf(1, 0, 1))).isFalse()
    }

    @Test
    fun `major segment dominates`() {
        assertThat(AppVersion.isNewer(listOf(2), listOf(1, 9, 9))).isTrue()
        assertThat(AppVersion.isNewer(listOf(1, 9, 9), listOf(2))).isFalse()
    }

    // --- decide -----------------------------------------------------------------------------

    private val installed = "1.0.3"

    @Test
    fun `a newer release downloads`() {
        assertThat(
            AppUpdatePolicy.decide("v1.0.4", installed, downloadedTag = null, dismissedTag = null),
        ).isEqualTo(UpdateDecision.Download)
    }

    @Test
    fun `same version as installed is up to date`() {
        assertThat(
            AppUpdatePolicy.decide("v1.0.3", installed, downloadedTag = null, dismissedTag = null),
        ).isEqualTo(UpdateDecision.UpToDate)
    }

    @Test
    fun `older release is up to date`() {
        assertThat(
            AppUpdatePolicy.decide("v0.9.0", installed, downloadedTag = null, dismissedTag = null),
        ).isEqualTo(UpdateDecision.UpToDate)
    }

    @Test
    fun `unparseable remote tag is unknown`() {
        assertThat(
            AppUpdatePolicy.decide("hotfix", installed, downloadedTag = null, dismissedTag = null),
        ).isEqualTo(UpdateDecision.Unknown)
    }

    @Test
    fun `a dismissed release is suppressed`() {
        assertThat(
            AppUpdatePolicy.decide("v1.0.4", installed, downloadedTag = null, dismissedTag = "v1.0.4"),
        ).isEqualTo(UpdateDecision.Dismissed)
    }

    @Test
    fun `a release newer than the dismissed one downloads again`() {
        assertThat(
            AppUpdatePolicy.decide("v1.0.5", installed, downloadedTag = null, dismissedTag = "v1.0.4"),
        ).isEqualTo(UpdateDecision.Download)
    }

    @Test
    fun `an already downloaded tag is ready`() {
        assertThat(
            AppUpdatePolicy.decide("v1.0.4", installed, downloadedTag = "v1.0.4", dismissedTag = null),
        ).isEqualTo(UpdateDecision.Ready)
    }

    @Test
    fun `installed check wins over dismissal and download state`() {
        // v1.0.3 was dismissed *and* downloaded once — but it is not newer than installed,
        // so it must stay quiet.
        assertThat(
            AppUpdatePolicy.decide("v1.0.3", installed, downloadedTag = "v1.0.3", dismissedTag = "v1.0.3"),
        ).isEqualTo(UpdateDecision.UpToDate)
    }

    // --- shouldCheckNow ---------------------------------------------------------------------

    private val interval = AppUpdateDefaultsForTest.INTERVAL

    @Test
    fun `first ever check runs immediately`() {
        assertThat(AppUpdatePolicy.shouldCheckNow(lastCheckedMillis = null, nowMillis = 100L, minIntervalMillis = interval))
            .isTrue()
    }

    @Test
    fun `a check inside the interval window is throttled`() {
        assertThat(
            AppUpdatePolicy.shouldCheckNow(
                lastCheckedMillis = 100L,
                nowMillis = 100L + interval - 1,
                minIntervalMillis = interval,
            ),
        ).isFalse()
    }

    @Test
    fun `a check at or past the interval window runs`() {
        assertThat(
            AppUpdatePolicy.shouldCheckNow(
                lastCheckedMillis = 100L,
                nowMillis = 100L + interval,
                minIntervalMillis = interval,
            ),
        ).isTrue()
    }

    private object AppUpdateDefaultsForTest {
        const val INTERVAL = 12L * 60 * 60 * 1000
    }
}
