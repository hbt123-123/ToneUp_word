package top.toneup.word.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import top.toneup.word.core.json.JsonCodec
import top.toneup.word.core.result.AppError
import top.toneup.word.domain.update.RemoteUpdate
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Reads `releases/latest/download/version.json` through the direct GitHub link and the mirror
 * list, never through `api.github.com` (unauthenticated GitHub API calls are unreliable from
 * mainland China and rate-limited to 60/h per IP).
 *
 * Contract (see docs/UPDATE_PROTOCOL.md): `version`, `versionCode` (required), `url`, `sha256`,
 * `size`, `notes`. Unknown keys are ignored, so the manifest may grow without breaking old apps.
 */
interface UpdateManifestSource {
    suspend fun fetch(
        preferredHost: String,
        deadHosts: Map<String, Long>,
        nowMillis: Long,
    ): ManifestResult
}

sealed interface ManifestResult {
    data class Success(val update: RemoteUpdate, val host: String) : ManifestResult

    data class Failure(
        val error: AppError,
        /** Every host that answered in any way — a 404 still proves the channel works. */
        val aliveHosts: Set<String>,
        /** Hosts whose connection failed and should enter the cooldown. */
        val deadHosts: Set<String>,
    ) : ManifestResult
}

class OkHttpUpdateManifestSource(
    private val client: OkHttpClient,
    private val json: Json = JsonCodec.json,
) : UpdateManifestSource {

    override suspend fun fetch(
        preferredHost: String,
        deadHosts: Map<String, Long>,
        nowMillis: Long,
    ): ManifestResult = withContext(Dispatchers.IO) {
        val hosts = orderedHosts(preferredHost, deadHosts, nowMillis)
            .take(AppUpdateDefaults.PROBE_BUDGET)
        if (hosts.isEmpty()) {
            return@withContext ManifestResult.Failure(
                error = AppError.network("没有可用的更新线路"),
                aliveHosts = emptySet(),
                deadHosts = emptySet(),
            )
        }

        val alive = mutableSetOf<String>()
        val dead = mutableSetOf<String>()
        var lastError: AppError = AppError.network("检查更新失败")

        for (host in hosts) {
            val request = Request.Builder()
                .url(via(host, AppUpdateDefaults.MANIFEST_URL))
                .header("Accept", "application/json")
                .header("User-Agent", AppUpdateDefaults.USER_AGENT)
                .build()
            try {
                val call = client.newCall(request)
                // Same 5 s metadata budget as ToneUp_read; a mirror that cannot answer this
                // quickly is not worth waiting for when the next one may be healthy.
                call.timeout().timeout(AppUpdateDefaults.ENDPOINT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                call.execute().use { response ->
                    // Any HTTP response means the channel itself is reachable; a 404 just means
                    // the release has not been published yet and must not trigger a cooldown.
                    alive += host
                    if (response.code != 200) {
                        lastError = AppError.network("检查更新 HTTP ${response.code}")
                        return@use
                    }
                    val parsed = parse(response.body.string())
                    if (parsed != null) {
                        return@withContext ManifestResult.Success(parsed, host)
                    }
                    lastError = AppError.updateParse("version.json 内容不完整")
                }
            } catch (e: IOException) {
                dead += host
                lastError = AppError.network(e.message ?: e::class.java.simpleName)
            } catch (e: Exception) {
                dead += host
                lastError = AppError.network(e.message ?: e::class.java.simpleName)
            }
        }

        ManifestResult.Failure(lastError, alive, dead)
    }

    /** Lenient decoding + strict field/URL validation. `null` means "ignore this manifest". */
    internal fun parse(payload: String): RemoteUpdate? = try {
        val dto = json.decodeFromString<VersionJsonDto>(payload)
        val version = dto.version.trim()
        val url = dto.url.trim()
        val sha = dto.sha256.trim().lowercase()
        when {
            version.isBlank() -> null
            dto.versionCode <= 0 -> null
            url.isBlank() || !isAllowedUrl(url) -> null
            !SHA256_PATTERN.matches(sha) -> null
            else -> RemoteUpdate(
                version = version,
                versionCode = dto.versionCode,
                apkUrl = url,
                sha256 = sha,
                size = dto.size,
                notes = dto.notes,
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun orderedHosts(
        preferredHost: String,
        deadHosts: Map<String, Long>,
        nowMillis: Long,
    ): List<String> {
        val blocked = deadHosts.filterValues { it > nowMillis }.keys
        val all = listOf(AppUpdateDefaults.DIRECT_HOST) + AppUpdateDefaults.MIRROR_HOSTS
        val ordered = if (preferredHost in all) {
            listOf(preferredHost) + all.filter { it != preferredHost }
        } else {
            all
        }
        return ordered.filterNot { it in blocked }
    }

    private fun via(host: String, url: String): String =
        if (host == AppUpdateDefaults.DIRECT_HOST) url else "https://$host/$url"

    private fun isAllowedUrl(raw: String): Boolean {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return host == "github.com" ||
            host == "objects.githubusercontent.com" ||
            host in AppUpdateDefaults.MIRROR_HOSTS
    }

    @Serializable
    private data class VersionJsonDto(
        val version: String = "",
        val versionCode: Int = 0,
        val url: String = "",
        val sha256: String = "",
        val size: Long = 0,
        val notes: String = "",
    )

    private companion object {
        val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}

/** One client for both the manifest and the APK; short connect timeout, generous read timeout. */
internal fun buildUpdateHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()
