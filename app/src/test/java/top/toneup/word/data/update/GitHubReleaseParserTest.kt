package top.toneup.word.data.update

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test
import top.toneup.word.core.result.Outcome

/**
 * Parsing of the GitHub `releases/latest` payload: apk asset selection, tolerant decoding,
 * and the failure values a broken payload must produce.
 */
class GitHubReleaseParserTest {

    private val source = GitHubReleaseHttpSource(OkHttpClient())

    @Test
    fun `parses a release with an apk asset`() = runTest {
        val payload = """
            {
              "tag_name": "v1.0.4",
              "html_url": "https://github.com/hbt123-123/ToneUp_word/releases/tag/v1.0.4",
              "assets": [
                {
                  "name": "ToneUp-Word-v1.0.4.apk",
                  "size": 12345678,
                  "browser_download_url": "https://github.com/hbt123-123/ToneUp_word/releases/download/v1.0.4/ToneUp-Word-v1.0.4.apk"
                }
              ],
              "some_future_field": { "nested": true }
            }
        """.trimIndent()

        val outcome = source.parse(payload)

        assertThat(outcome.isSuccess).isTrue()
        val release = (outcome as Outcome.Success).value
        assertThat(release.tagName).isEqualTo("v1.0.4")
        assertThat(release.apkSize).isEqualTo(12345678L)
        assertThat(release.apkUrl)
            .isEqualTo("https://github.com/hbt123-123/ToneUp_word/releases/download/v1.0.4/ToneUp-Word-v1.0.4.apk")
        assertThat(release.releasePageUrl).endsWith("tag/v1.0.4")
    }

    @Test
    fun `picks the apk asset among unrelated files`() = runTest {
        val payload = """
            {
              "tag_name": "v1.0.4",
              "html_url": "https://example.com",
              "assets": [
                { "name": "checksums.txt", "size": 12, "browser_download_url": "https://example.com/checksums.txt" },
                { "name": "release.apk", "size": 42, "browser_download_url": "https://example.com/release.apk" }
              ]
            }
        """.trimIndent()

        val release = (source.parse(payload) as Outcome.Success).value
        assertThat(release.apkUrl).isEqualTo("https://example.com/release.apk")
        assertThat(release.apkSize).isEqualTo(42L)
    }

    @Test
    fun `a release without apk asset is a parse failure`() = runTest {
        val payload = """
            {
              "tag_name": "v1.0.4",
              "html_url": "https://example.com",
              "assets": [ { "name": "notes.md", "size": 1, "browser_download_url": "https://example.com/notes.md" } ]
            }
        """.trimIndent()

        val outcome = source.parse(payload)
        assertThat(outcome.isSuccess).isFalse()
        assertThat((outcome as Outcome.Failure).error.debugDetail).contains("apk")
    }

    @Test
    fun `a zero size asset is rejected`() = runTest {
        val payload = """
            {
              "tag_name": "v1.0.4",
              "html_url": "https://example.com",
              "assets": [ { "name": "app.apk", "size": 0, "browser_download_url": "https://example.com/app.apk" } ]
            }
        """.trimIndent()

        assertThat(source.parse(payload).isSuccess).isFalse()
    }

    @Test
    fun `a missing tag is rejected`() = runTest {
        val payload = """
            {
              "html_url": "https://example.com",
              "assets": [ { "name": "app.apk", "size": 1, "browser_download_url": "https://example.com/app.apk" } ]
            }
        """.trimIndent()

        assertThat(source.parse(payload).isSuccess).isFalse()
    }

    @Test
    fun `malformed json is a parse failure not a crash`() = runTest {
        assertThat(source.parse("not json at all").isSuccess).isFalse()
    }
}
