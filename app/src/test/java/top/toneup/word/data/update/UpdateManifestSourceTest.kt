package top.toneup.word.data.update

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.junit.Test

/**
 * Parsing of the release `version.json`: the shared field contract, lenient decoding, and the
 * validation that keeps a malformed/hostile manifest from ever reaching the downloader.
 */
class UpdateManifestSourceTest {

    private val source = OkHttpUpdateManifestSource(OkHttpClient())

    private val validJson = """
        {
          "version": "1.0.3",
          "versionCode": 103,
          "url": "https://github.com/hbt123-123/ToneUp_word/releases/latest/download/ToneUpWord.apk",
          "sha256": "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789",
          "size": 3277452,
          "notes": "修复若干问题",
          "someFutureField": { "nested": true }
        }
    """.trimIndent()

    @Test
    fun `parses the shared manifest contract and normalises the digest`() {
        val update = source.parse(validJson)

        assertThat(update).isNotNull()
        assertThat(update!!.version).isEqualTo("1.0.3")
        assertThat(update.versionCode).isEqualTo(103)
        assertThat(update.sha256)
            .isEqualTo("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789")
        assertThat(update.size).isEqualTo(3277452L)
        assertThat(update.notes).isEqualTo("修复若干问题")
    }

    @Test
    fun `rejects a manifest without a digest`() {
        assertThat(source.parse(validJson.replace("\"sha256\"", "\"sha-256\""))).isNull()
    }

    @Test
    fun `rejects a manifest without a versionCode`() {
        assertThat(source.parse(validJson.replace("\"versionCode\": 103,", ""))).isNull()
    }

    @Test
    fun `rejects a non-https apk url`() {
        assertThat(
            source.parse(validJson.replace("https://github.com", "http://github.com")),
        ).isNull()
    }

    @Test
    fun `rejects an apk host outside the allow-list`() {
        assertThat(
            source.parse(validJson.replace("https://github.com", "https://evil.example.com")),
        ).isNull()
    }

    @Test
    fun `accepts a mirror host in the manifest url`() {
        assertThat(
            source.parse(validJson.replace("https://github.com", "https://gh-proxy.com/https://github.com")),
        ).isNotNull()
    }

    @Test
    fun `returns null for malformed json`() {
        assertThat(source.parse("not json")).isNull()
    }
}
