package top.toneup.word.data.update

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import top.toneup.word.core.result.AppError
import top.toneup.word.core.result.Outcome
import top.toneup.word.domain.update.RemoteUpdate
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Streams the release APK to disk with **resumable** semantics and SHA-256 verification.
 *
 * Files live under `cacheDir/updates/` and are named by versionCode:
 *  * `<versionCode>.apk.part` — incomplete bytes; its length is the resume point.
 *  * `<versionCode>.apk` — verified bytes; the only file the installer may ever see.
 *
 * A checksum mismatch deletes the part file and tries the next mirror (a proxy may have served
 * corrupted bytes), then fails without leaving anything installable. A transport failure keeps
 * the part file so the next run resumes exactly where it stopped.
 */
interface ApkDownloader {
    suspend fun download(
        update: RemoteUpdate,
        hosts: List<String>,
        targetDir: File,
        onProgress: (received: Long, total: Long) -> Unit,
    ): Outcome<File>
}

class OkHttpApkDownloader(
    private val client: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ApkDownloader {

    override suspend fun download(
        update: RemoteUpdate,
        hosts: List<String>,
        targetDir: File,
        onProgress: (received: Long, total: Long) -> Unit,
    ): Outcome<File> = withContext(ioDispatcher) {
        targetDir.mkdirs()
        val expected = update.size
        val ready = File(targetDir, "${update.versionCode}.apk")
        if (ready.isFile && (expected <= 0 || ready.length() == expected)) {
            onProgress(ready.length(), if (expected > 0) expected else ready.length())
            return@withContext Outcome.Success(ready)
        }
        if (ready.isFile) ready.delete() // truncated / wrong file: never trust it

        val part = File(targetDir, "${update.versionCode}.apk.part")
        if (expected > 0 && part.isFile && part.length() > expected) part.delete()

        if (hosts.isEmpty()) {
            return@withContext Outcome.Failure(AppError.network("没有可用的下载线路"))
        }

        var lastError: AppError = AppError.network("下载失败")
        var checksumFailed = false
        for (host in hosts.take(AppUpdateDefaults.DOWNLOAD_BUDGET)) {
            try {
                downloadFrom(host, update, part, onProgress)
            } catch (e: HttpStatusException) {
                lastError = if (e.code in 500..599 || e.code == 429 || e.code == 408) {
                    AppError.network("下载 HTTP ${e.code}")
                } else {
                    AppError.unknown("下载 HTTP ${e.code}")
                }
                continue
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IOException) {
                lastError = AppError.network(e.message ?: e::class.java.simpleName)
                continue
            } catch (e: Exception) {
                lastError = AppError.unknown(e.message ?: e::class.java.simpleName)
                continue
            }

            val length = part.length()
            if (expected > 0 && length < expected) {
                // A dropped stream: keep the partial file and let the next host resume it.
                lastError = AppError.network("下载不完整：$length/$expected 字节")
                continue
            }
            val digest = sha256(part)
            if (!digest.equals(update.sha256, ignoreCase = true)) {
                part.delete()
                checksumFailed = true
                lastError = AppError.checksum("sha256 不匹配")
                continue
            }
            if (ready.exists()) ready.delete()
            if (!part.renameTo(ready)) {
                return@withContext Outcome.Failure(AppError.io("无法保存安装包"))
            }
            onProgress(ready.length(), if (expected > 0) expected else ready.length())
            return@withContext Outcome.Success(ready)
        }

        val error = if (checksumFailed) AppError.checksum("所有线路都未能通过校验") else lastError
        Outcome.Failure(error)
    }

    private suspend fun downloadFrom(
        host: String,
        update: RemoteUpdate,
        part: File,
        onProgress: (received: Long, total: Long) -> Unit,
    ) {
        var offset = if (part.isFile) part.length() else 0L
        val request = Request.Builder()
            .url(via(host, update.apkUrl))
            .header("User-Agent", AppUpdateDefaults.USER_AGENT)
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }
            .build()

        client.newCall(request).execute().use { response ->
            val appending = when (response.code) {
                206 -> true
                200 -> {
                    // The host ignored Range: start from an empty file instead of appending junk.
                    if (offset > 0) {
                        part.delete()
                        offset = 0
                    }
                    false
                }
                else -> throw HttpStatusException(response.code)
            }

            if (appending) {
                val total = response.header("Content-Range")
                    ?.substringAfter('/', "")
                    ?.toLongOrNull()
                if (update.size > 0 && total != null && total > 0 && total != update.size) {
                    throw IOException("文件大小变化（$total ≠ ${update.size}），需重新下载")
                }
            }

            val total = when {
                update.size > 0 -> update.size
                response.body.contentLength() >= 0 -> offset + response.body.contentLength()
                else -> 0L
            }
            FileOutputStream(part, appending).use { out ->
                val input = response.body.byteStream()
                val buffer = ByteArray(BUFFER_BYTES)
                var got = offset
                var lastTick = 0L
                onProgress(got, total)
                while (true) {
                    // Cooperative cancellation: the partial file survives as the resume point.
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    got += read
                    val now = System.currentTimeMillis()
                    if (now - lastTick >= PROGRESS_INTERVAL_MILLIS || (total > 0 && got == total)) {
                        lastTick = now
                        onProgress(got, total)
                    }
                }
                onProgress(got, total)
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun via(host: String, url: String): String =
        if (host == AppUpdateDefaults.DIRECT_HOST) url else "https://$host/$url"

    private class HttpStatusException(val code: Int) : IOException("HTTP $code")

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val PROGRESS_INTERVAL_MILLIS = 250L
    }
}
