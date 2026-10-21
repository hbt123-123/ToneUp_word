package top.toneup.word.data.update

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import top.toneup.word.core.result.AppError
import top.toneup.word.core.result.Outcome
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Streams the release APK to disk with **resumable** semantics.
 *
 * The partial file is the resume state: it is written to the target path directly and kept on
 * any transport failure, so the next run (app start, WorkManager backoff) sends
 * `Range: bytes=<length>-` and only the missing tail is downloaded. A server that ignores the
 * range answers `200` and the file is simply rewritten from scratch.
 */
interface ApkDownloader {
    suspend fun download(url: String, target: File, expectedSize: Long): Outcome<Unit>
}

class OkHttpApkDownloader(
    private val client: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ApkDownloader {

    override suspend fun download(url: String, target: File, expectedSize: Long): Outcome<Unit> =
        withContext(ioDispatcher) {
            try {
                // A complete file from an earlier run needs no request at all.
                if (target.isFile && target.length() == expectedSize) {
                    return@withContext Outcome.Success(Unit)
                }
                // Stale leftovers (older release, truncated weirdly) would corrupt the resume math.
                if (target.isFile && target.length() > expectedSize) target.delete()

                val resumeFrom = if (target.isFile) target.length() else 0L
                val request = Request.Builder().url(url).apply {
                    if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-")
                }.build()

                client.newCall(request).execute().use { response ->
                    val appending = when (response.code) {
                        HTTP_PARTIAL -> true
                        HTTP_OK -> {
                            // Server ignored the range — start from an empty file.
                            target.delete()
                            false
                        }

                        else -> return@withContext Outcome.Failure(
                            if (response.code in 500..599 || response.code == 429) {
                                AppError.network("下载 HTTP ${response.code}")
                            } else {
                                AppError.unknown("下载 HTTP ${response.code}")
                            },
                        )
                    }
                    val body = response.body

                    if (appending) validateContentRange(response.header("Content-Range"), resumeFrom, expectedSize)
                        ?.let { return@withContext Outcome.Failure(it) }

                    FileOutputStream(target, /* append = */ appending).use { out ->
                        val input = body.byteStream()
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            // Cooperative cancellation: the partial file survives so the next
                            // run resumes exactly here.
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                        }
                    }
                }

                if (target.length() != expectedSize) {
                    // Incomplete (connection dropped silently, or the release changed under us).
                    // Delete so a stale partial can never be mistaken for a full APK.
                    target.delete()
                    Outcome.Failure(AppError.io("下载不完整：${target.length()}/${expectedSize} 字节"))
                } else {
                    Outcome.Success(Unit)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IOException) {
                // Keep the partial file — it is the resume point for the next attempt.
                Outcome.Failure(AppError.network(e.message ?: e::class.java.simpleName))
            } catch (e: Exception) {
                Outcome.Failure(AppError.unknown(e.message ?: e::class.java.simpleName))
            }
        }

    /** `bytes=100-599/640` → total must match [expectedSize] or the resume basis is wrong. */
    private fun validateContentRange(header: String?, resumeFrom: Long, expectedSize: Long): AppError? {
        if (header.isNullOrBlank()) return null
        val total = header.substringAfter('/', "").toLongOrNull() ?: return null
        if (total <= 0) return null
        return if (total != expectedSize) {
            AppError.io("文件大小变化（$total ≠ $expectedSize），需重新下载")
        } else {
            null
        }
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_PARTIAL = 206
        const val BUFFER_BYTES = 64 * 1024
    }
}
