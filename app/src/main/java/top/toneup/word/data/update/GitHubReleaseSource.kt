package top.toneup.word.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.toneup.word.core.json.JsonCodec
import top.toneup.word.core.result.AppError
import top.toneup.word.core.result.Outcome

@Serializable
private data class GitHubAssetDto(
    val name: String = "",
    val size: Long = 0,
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
)

@Serializable
private data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<GitHubAssetDto> = emptyList(),
)

/**
 * Reads the newest release of this project from the GitHub Releases API.
 *
 * The endpoint is called unauthenticated (public repo, 60 req/h per IP — the repository
 * throttles checks to well below that). Any failure is a value: transport errors are
 * [retryable][AppError.retryable] so the worker backs off and the next app start resumes,
 * while "no apk asset" is a parse error that must not loop.
 */
interface GitHubReleaseSource {
    suspend fun fetchLatest(): Outcome<RemoteRelease>
}

class GitHubReleaseHttpSource(
    private val client: okhttp3.OkHttpClient,
    private val url: String = AppUpdateDefaults.RELEASES_LATEST_URL,
    private val json: Json = JsonCodec.json,
) : GitHubReleaseSource {

    override suspend fun fetchLatest(): Outcome<RemoteRelease> = withContext(Dispatchers.IO) {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", AppUpdateDefaults.USER_AGENT)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                when {
                    !response.isSuccessful -> Outcome.Failure(
                        if (response.code in 500..599 || response.code == 429) {
                            AppError.network("GitHub API HTTP ${response.code}")
                        } else {
                            AppError.notFound("GitHub API HTTP ${response.code}")
                        },
                    )

                    else -> parse(response.body.string())
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            Outcome.Failure(AppError.network(e.message ?: e::class.java.simpleName))
        } catch (e: Exception) {
            Outcome.Failure(AppError.parse(e.message ?: e::class.java.simpleName))
        }
    }

    /** Lenient decoding (GitHub adds fields over time) + the first `*.apk` asset. */
    internal fun parse(payload: String): Outcome<RemoteRelease> = try {
        val release = json.decodeFromString<GitHubReleaseDto>(payload)
        val asset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
        when {
            release.tagName.isBlank() -> Outcome.Failure(AppError.parse("release tag 缺失"))
            asset == null -> Outcome.Failure(AppError.parse("release 没有 apk 资产"))
            asset.browserDownloadUrl.isBlank() || asset.size <= 0 ->
                Outcome.Failure(AppError.parse("apk 资产信息不完整"))

            else -> Outcome.Success(
                RemoteRelease(
                    tagName = release.tagName,
                    apkUrl = asset.browserDownloadUrl,
                    apkSize = asset.size,
                    releasePageUrl = release.htmlUrl,
                ),
            )
        }
    } catch (e: kotlinx.serialization.SerializationException) {
        Outcome.Failure(AppError.parse(e.message ?: e::class.java.simpleName))
    } catch (e: IllegalArgumentException) {
        Outcome.Failure(AppError.parse(e.message ?: e::class.java.simpleName))
    }
}
