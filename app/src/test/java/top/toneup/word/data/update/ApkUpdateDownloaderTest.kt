package top.toneup.word.data.update

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.toneup.word.core.result.Outcome
import top.toneup.word.domain.update.RemoteUpdate
import java.io.File
import java.security.MessageDigest

/**
 * Resumable + verified download semantics: fresh transfer, `Range` resume, a host that ignores
 * the range, SHA-256 rejection, and the guarantee that only `<versionCode>.apk` is installable.
 */
class ApkUpdateDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val downloader = OkHttpApkDownloader(OkHttpClient())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dir = File(tmp.root, "updates")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun url(): String = server.url("/ToneUpWord.apk").toString()

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun update(
        payload: ByteArray,
        digest: String = sha256(payload),
    ) = RemoteUpdate(
        version = "1.0.3",
        versionCode = 103,
        apkUrl = url(),
        sha256 = digest,
        size = payload.size.toLong(),
        notes = "",
    )

    private fun part() = File(dir, "103.apk.part")
    private fun ready() = File(dir, "103.apk")

    @Test
    fun `fresh download verifies and renames to versionCode apk`() = runTest {
        val payload = "ABCDEFGHIJ".toByteArray()
        server.enqueue(MockResponse().setResponseCode(200).setBody("ABCDEFGHIJ"))

        val result = downloader.download(update(payload), listOf(""), dir) { _, _ -> }

        assertThat(result.isSuccess).isTrue()
        assertThat(ready().readText()).isEqualTo("ABCDEFGHIJ")
        assertThat(part().exists()).isFalse()
        assertThat(server.takeRequest().getHeader("Range")).isNull()
    }

    @Test
    fun `partial file resumes with a range request and appends the tail`() = runTest {
        dir.mkdirs()
        part().writeText("ABCD")
        val payload = "ABCDEFGHIJ".toByteArray()
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 4-9/10")
                .setBody("EFGHIJ"),
        )

        val result = downloader.download(update(payload), listOf(""), dir) { _, _ -> }

        assertThat(result.isSuccess).isTrue()
        assertThat(ready().readText()).isEqualTo("ABCDEFGHIJ")
        assertThat(server.takeRequest().getHeader("Range")).isEqualTo("bytes=4-")
    }

    @Test
    fun `a host ignoring the range rewrites the file from scratch`() = runTest {
        dir.mkdirs()
        part().writeText("XXXX")
        val payload = "ABCDEFGHIJ".toByteArray()
        server.enqueue(MockResponse().setResponseCode(200).setBody("ABCDEFGHIJ"))

        val result = downloader.download(update(payload), listOf(""), dir) { _, _ -> }

        assertThat(result.isSuccess).isTrue()
        assertThat(ready().readText()).isEqualTo("ABCDEFGHIJ")
    }

    @Test
    fun `an incomplete transfer fails and keeps the part file for resume`() = runTest {
        val payload = "ABCDEFGHIJ".toByteArray()
        server.enqueue(MockResponse().setResponseCode(200).setBody("ABC"))

        val result = downloader.download(update(payload), listOf(""), dir) { _, _ -> }

        assertThat(result.isSuccess).isFalse()
        assertThat(part().readText()).isEqualTo("ABC")
        assertThat(ready().exists()).isFalse()
    }

    @Test
    fun `a digest mismatch deletes the part and never creates an installable file`() = runTest {
        val payload = "ABCDEFGHIJ".toByteArray()
        server.enqueue(MockResponse().setResponseCode(200).setBody("ABCDEFGHIJ"))

        val result = downloader.download(
            update(payload, digest = sha256("XXXXXXXXXX".toByteArray())),
            listOf(""),
            dir,
        ) { _, _ -> }

        assertThat(result.isSuccess).isFalse()
        assertThat((result as Outcome.Failure).error.message).contains("校验")
        assertThat(part().exists()).isFalse()
        assertThat(ready().exists()).isFalse()
    }

    @Test
    fun `an already complete file needs no request at all`() = runTest {
        dir.mkdirs()
        ready().writeText("ABCDEFGHIJ")

        val result = downloader.download(update("ABCDEFGHIJ".toByteArray()), listOf(""), dir) { _, _ -> }

        assertThat(result.isSuccess).isTrue()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `oversized leftovers are discarded before the resume`() = runTest {
        dir.mkdirs()
        part().writeText("ABCDEFGHIJKL")
        server.enqueue(MockResponse().setResponseCode(200).setBody("ABCDEFGHIJ"))

        val result = downloader.download(update("ABCDEFGHIJ".toByteArray()), listOf(""), dir) { _, _ -> }

        assertThat(result.isSuccess).isTrue()
        assertThat(ready().readText()).isEqualTo("ABCDEFGHIJ")
    }

    @Test
    fun `an http 500 is a retryable network error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = downloader.download(update("ABCDEFGHIJ".toByteArray()), listOf(""), dir) { _, _ -> }

        assertThat(result.isSuccess).isFalse()
        val error = (result as Outcome.Failure).error
        assertThat(error.retryable).isTrue()
    }

    @Test
    fun `a content range mismatch aborts instead of appending junk`() = runTest {
        dir.mkdirs()
        part().writeText("ABCD")
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 4-9/11")
                .setBody("EFGHIJ"),
        )

        val result = downloader.download(update("ABCDEFGHIJ".toByteArray()), listOf(""), dir) { _, _ -> }

        assertThat(result.isSuccess).isFalse()
        assertThat(ready().exists()).isFalse()
    }

    @Test
    fun `no hosts fails without touching the network`() = runTest {
        val result = downloader.download(update("ABCDEFGHIJ".toByteArray()), emptyList(), dir) { _, _ -> }

        assertThat(result.isSuccess).isFalse()
        assertThat(server.requestCount).isEqualTo(0)
    }
}
