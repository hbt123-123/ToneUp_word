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

    // --- host ordering (shared by the manifest and the APK download) --------------------------

    @Test
    fun `the preferred host is tried first and dead hosts are skipped`() {
        val ordered = AppUpdateDefaults.orderedHosts(
            preferredHost = "gh-proxy.com",
            blocked = setOf(AppUpdateDefaults.DIRECT_HOST),
        )

        assertThat(ordered.first()).isEqualTo("gh-proxy.com")
        assertThat(ordered).doesNotContain(AppUpdateDefaults.DIRECT_HOST)
        assertThat(ordered).containsNoDuplicates()
        assertThat(ordered).hasSize(AppUpdateDefaults.MIRROR_HOSTS.size)
    }

    @Test
    fun `an unknown preferred host does not reorder anything`() {
        val ordered = AppUpdateDefaults.orderedHosts(
            preferredHost = "not-a-mirror.example.com",
            blocked = emptySet(),
        )

        assertThat(ordered.first()).isEqualTo(AppUpdateDefaults.DIRECT_HOST)
        assertThat(ordered).hasSize(AppUpdateDefaults.MIRROR_HOSTS.size + 1)
    }

    @Test
    fun `an all-cooled-down list still returns every host`() {
        val everyHost = (listOf(AppUpdateDefaults.DIRECT_HOST) + AppUpdateDefaults.MIRROR_HOSTS).toSet()

        // 冷却只是优化：同一次会话里连续手动重试可以把所有线路都拉黑，此时必须继续尝试，
        // 否则「检查更新」会直接报「没有可用的线路」而永远好不了。
        val ordered = AppUpdateDefaults.orderedHosts(
            preferredHost = "gh-proxy.com",
            blocked = everyHost,
        )

        assertThat(ordered).hasSize(everyHost.size)
        assertThat(ordered.first()).isEqualTo("gh-proxy.com")
        assertThat(ordered).containsNoDuplicates()
    }

    @Test
    fun `mirror urls are prefixed while the direct host is untouched`() {
        val target = "https://github.com/hbt123-123/ToneUp_word/releases/latest/download/version.json"

        assertThat(AppUpdateDefaults.url(AppUpdateDefaults.DIRECT_HOST, target)).isEqualTo(target)
        assertThat(AppUpdateDefaults.url("gh-proxy.com", target))
            .isEqualTo("https://gh-proxy.com/$target")
    }
}
