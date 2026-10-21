package top.toneup.word.data.update

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.toneup.word.core.result.AppError
import top.toneup.word.core.result.ErrorCode
import top.toneup.word.core.result.Outcome
import java.io.File

/**
 * The resumable download semantics of [OkHttpApkDownloader]: fresh transfer, `Range` resume
 * from a partial file, fallback when the server ignores the range, and size verification.
 */
class ApkUpdateDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var target: File
    private val downloader = OkHttpApkDownloader(okhttp3.OkHttpClient())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        target = File(tmp.root, "toneup-update.apk")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun url(): String = server.url("/release.apk").toString()

    @Test
    fun `fresh download writes the whole file`() = runTest {
        val payload = "ABCDEFGHIJ"
        server.enqueue(MockResponse().setResponseCode(200).setBody(payload))

        val outcome = downloader.download(url(), target, expectedSize = 10L)

        assertThat(outcome.isSuccess).isTrue()
        assertThat(target.readText()).isEqualTo(payload)
        val request = server.takeRequest()
        assertThat(request.getHeader("Range")).isNull()
    }

    @Test
    fun `partial file resumes with a range request and appends the tail`() = runTest {
        target.writeText("ABCD")
        val tail = "EFGHIJ"
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 4-9/10")
                .setBody(tail),
        )

        val outcome = downloader.download(url(), target, expectedSize = 10L)

        assertThat(outcome.isSuccess).isTrue()
        assertThat(target.readText()).isEqualTo("ABCDEFGHIJ")
        assertThat(server.takeRequest().getHeader("Range")).isEqualTo("bytes=4-")
    }

    @Test
    fun `a server ignoring the range rewrites the file from scratch`() = runTest {
        target.writeText("XXXX")
        server.enqueue(MockResponse().setResponseCode(200).setBody("ABCDEFGHIJ"))

        val outcome = downloader.download(url(), target, expectedSize = 10L)

        assertThat(outcome.isSuccess).isTrue()
        assertThat(target.readText()).isEqualTo("ABCDEFGHIJ")
    }

    @Test
    fun `an incomplete transfer fails and removes the broken file`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ABC"))

        val outcome = downloader.download(url(), target, expectedSize = 10L)

        assertThat(outcome.isSuccess).isFalse()
        assertThat((outcome as Outcome.Failure).error.code).isEqualTo(ErrorCode.IO)
        assertThat(target.exists()).isFalse()
    }

    @Test
    fun `an already complete file needs no request at all`() = runTest {
        target.writeText("ABCDEFGHIJ")

        val outcome = downloader.download(url(), target, expectedSize = 10L)

        assertThat(outcome.isSuccess).isTrue()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `oversized leftovers are discarded before the resume`() = runTest {
        target.writeText("ABCDEFGHIJKL")
        server.enqueue(MockResponse().setResponseCode(200).setBody("ABCDEFGHIJ"))

        val outcome = downloader.download(url(), target, expectedSize = 10L)

        assertThat(outcome.isSuccess).isTrue()
        assertThat(target.readText()).isEqualTo("ABCDEFGHIJ")
    }

    @Test
    fun `an http 500 is a retryable network error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val outcome = downloader.download(url(), target, expectedSize = 10L)

        assertThat(outcome.isSuccess).isFalse()
        val error = (outcome as Outcome.Failure).error
        assertThat(error.code).isEqualTo(ErrorCode.NETWORK)
        assertThat(error.retryable).isTrue()
    }

    @Test
    fun `a content range mismatch aborts instead of appending junk`() = runTest {
        target.writeText("ABCD")
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 4-9/11")
                .setBody("EFGHIJ"),
        )

        val outcome = downloader.download(url(), target, expectedSize = 10L)

        assertThat(outcome.isSuccess).isFalse()
    }
}
