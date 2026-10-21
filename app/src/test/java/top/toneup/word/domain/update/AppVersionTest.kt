package top.toneup.word.domain.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Version parsing/comparison and the whole update decision ([AppUpdatePolicy]). */
class AppVersionTest {

    private fun remote(version: String = "1.0.4", code: Int = 104) = RemoteUpdate(
        version = version,
        versionCode = code,
        apkUrl = "https://github.com/hbt123-123/ToneUp_word/releases/latest/download/ToneUpWord.apk",
        sha256 = "a".repeat(64),
        size = 10L,
        notes = "",
    )

    // --- parse ------------------------------------------------------------------------------

    @Test
    fun `parses a v-prefixed tag into three numeric segments`() {
        assertThat(AppVersion.parse("v1.0.4")).containsExactly(1, 0, 4).inOrder()
    }

    @Test
    fun `parses a bare version name`() {
        assertThat(AppVersion.parse("1.0.4")).containsExactly(1, 0, 4).inOrder()
    }

    @Test
    fun `ignores pre-release suffixes`() {
        assertThat(AppVersion.parse("1.0.4-beta")).containsExactly(1, 0, 4).inOrder()
        assertThat(AppVersion.parse("1.0.4+build.7")).containsExactly(1, 0, 4).inOrder()
    }

    @Test
    fun `pads a shorter version with zeros`() {
        assertThat(AppVersion.parse("v2.10")).containsExactly(2, 10, 0).inOrder()
    }

    @Test
    fun `returns null for strings without digits or with four segments`() {
        assertThat(AppVersion.parse("release")).isNull()
        assertThat(AppVersion.parse("")).isNull()
        assertThat(AppVersion.parse(null)).isNull()
        assertThat(AppVersion.parse("1.2.3.4")).isNull()
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
    fun `major segment dominates`() {
        assertThat(AppVersion.isNewer(listOf(2), listOf(1, 9, 9))).isTrue()
        assertThat(AppVersion.isNewer(listOf(1, 9, 9), listOf(2))).isFalse()
    }

    // --- decide -----------------------------------------------------------------------------

    @Test
    fun `a newer versionCode downloads`() {
        assertThat(
            AppUpdatePolicy.decide(
                remote = remote(),
                installedVersion = "1.0.3",
                installedVersionCode = 103,
                downloadedVersionCode = 0,
                dismissedVersionCode = 0,
            ),
        ).isEqualTo(UpdateDecision.Download)
    }

    @Test
    fun `same versionCode is up to date`() {
        assertThat(
            AppUpdatePolicy.decide(remote("1.0.3", 103), "1.0.3", 103, 0, 0),
        ).isEqualTo(UpdateDecision.UpToDate)
    }

    @Test
    fun `older release is up to date`() {
        assertThat(
            AppUpdatePolicy.decide(remote("0.2", 2), "1.0.3", 103, 0, 0),
        ).isEqualTo(UpdateDecision.UpToDate)
    }

    @Test
    fun `semantic version is used when a versionCode is missing`() {
        assertThat(
            AppUpdatePolicy.decide(remote("1.0.4", 0), "1.0.3", 0, 0, 0),
        ).isEqualTo(UpdateDecision.Download)
        assertThat(
            AppUpdatePolicy.decide(remote("release", 0), "1.0.3", 0, 0, 0),
        ).isEqualTo(UpdateDecision.Unknown)
    }

    @Test
    fun `a dismissed versionCode is suppressed`() {
        assertThat(
            AppUpdatePolicy.decide(remote("1.0.4", 104), "1.0.3", 103, 0, 104),
        ).isEqualTo(UpdateDecision.Dismissed)
    }

    @Test
    fun `a release newer than the dismissed one downloads again`() {
        assertThat(
            AppUpdatePolicy.decide(remote("1.0.5", 105), "1.0.3", 103, 0, 104),
        ).isEqualTo(UpdateDecision.Download)
    }

    @Test
    fun `an already downloaded versionCode is ready`() {
        assertThat(
            AppUpdatePolicy.decide(remote("1.0.4", 104), "1.0.3", 103, 104, 0),
        ).isEqualTo(UpdateDecision.Ready)
    }

    @Test
    fun `installed check wins over dismissal and download state`() {
        assertThat(
            AppUpdatePolicy.decide(remote("1.0.3", 103), "1.0.3", 103, 103, 103),
        ).isEqualTo(UpdateDecision.UpToDate)
    }

    // --- shouldCheckNow ---------------------------------------------------------------------

    private val interval = 24L * 60 * 60 * 1000

    @Test
    fun `first ever check runs immediately`() {
        assertThat(AppUpdatePolicy.shouldCheckNow(null, 100L, interval)).isTrue()
    }

    @Test
    fun `a check inside the interval window is throttled`() {
        assertThat(AppUpdatePolicy.shouldCheckNow(100L, 100L + interval - 1, interval)).isFalse()
    }

    @Test
    fun `a check at the interval boundary runs`() {
        assertThat(AppUpdatePolicy.shouldCheckNow(100L, 100L + interval, interval)).isTrue()
    }
}
