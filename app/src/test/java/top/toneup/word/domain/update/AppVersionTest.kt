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
        // `parse` pads short versions to three segments so that comparisons line up.
        assertThat(AppVersion.parse("v2.10")).containsExactly(2, 10, 0).inOrder()
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
    private val installedCode = 103

    /** A manifest entry; only [version]/[versionCode] matter to the policy. */
    private fun remote(version: String, versionCode: Int) = RemoteUpdate(
        version = version,
        versionCode = versionCode,
        apkUrl = "https://example.invalid/ToneUpWord.apk",
        sha256 = "0".repeat(64),
        size = 1_024L,
        notes = "",
    )

    private fun decide(
        version: String,
        versionCode: Int,
        downloadedVersionCode: Int = 0,
        dismissedVersionCode: Int = 0,
    ): UpdateDecision = AppUpdatePolicy.decide(
        remote = remote(version, versionCode),
        installedVersion = installed,
        installedVersionCode = installedCode,
        downloadedVersionCode = downloadedVersionCode,
        dismissedVersionCode = dismissedVersionCode,
    )

    @Test
    fun `a newer release downloads`() {
        assertThat(decide("1.0.4", 104)).isEqualTo(UpdateDecision.Download)
    }

    @Test
    fun `same version as installed is up to date`() {
        assertThat(decide("1.0.3", 103)).isEqualTo(UpdateDecision.UpToDate)
    }

    @Test
    fun `older release is up to date`() {
        assertThat(decide("0.9.0", 90)).isEqualTo(UpdateDecision.UpToDate)
    }

    @Test
    fun `unparseable remote tag is unknown`() {
        assertThat(decide("hotfix", 0)).isEqualTo(UpdateDecision.Unknown)
    }

    @Test
    fun `a blank remote version is unknown`() {
        assertThat(decide("", 0)).isEqualTo(UpdateDecision.Unknown)
    }

    @Test
    fun `a dismissed release is suppressed`() {
        assertThat(decide("1.0.4", 104, dismissedVersionCode = 104)).isEqualTo(UpdateDecision.Dismissed)
    }

    @Test
    fun `a release newer than the dismissed one downloads again`() {
        assertThat(decide("1.0.5", 105, dismissedVersionCode = 104)).isEqualTo(UpdateDecision.Download)
    }

    @Test
    fun `an already downloaded version is ready`() {
        assertThat(decide("1.0.4", 104, downloadedVersionCode = 104)).isEqualTo(UpdateDecision.Ready)
    }

    @Test
    fun `installed check wins over dismissal and download state`() {
        // v1.0.3 was dismissed *and* downloaded once — but it is not newer than installed,
        // so it must stay quiet.
        assertThat(
            decide("1.0.3", 103, downloadedVersionCode = 103, dismissedVersionCode = 103),
        ).isEqualTo(UpdateDecision.UpToDate)
    }

    @Test
    fun `falls back to semantic comparison when the manifest has no version code`() {
        assertThat(decide("1.1.0", 0)).isEqualTo(UpdateDecision.Download)
        assertThat(decide("1.0.2", 0)).isEqualTo(UpdateDecision.UpToDate)
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
