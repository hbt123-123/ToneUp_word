package top.toneup.word.data.gateway

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import top.toneup.word.core.json.JsonCodec
import top.toneup.word.core.result.AppError
import top.toneup.word.core.result.Outcome
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/** A ready-to-use gateway client together with the settings snapshot it was built from. */
data class GatewaySession(val api: GatewayApi, val config: GatewayConfig)

/**
 * Builds (and caches) the Retrofit/OkHttp stack for the gateway.
 *
 * Design notes:
 *  * **Rebuilt on settings change** — the cache key is the whole [GatewayConfig] snapshot, so
 *    editing the URL or the key takes effect on the next lookup without any manual
 *    invalidation hook (simple and impossible to forget).
 *  * **https is enforced here** (plan D7): an `http://` base URL never reaches the wire and
 *    comes back as [AppError.config], which the settings screen can show in red.
 *  * **The key never appears in logs** — it travels only in the `Authorization` header, which
 *    the debug-only logging interceptor redacts; URLs carry no `?key=`.
 *  * Timeouts are constructor parameters purely so a test can force a 1s read timeout.
 */
class GatewayClientProvider(
    private val configProvider: suspend () -> GatewayConfig,
    private val json: Json = JsonCodec.json,
    private val isDebugBuild: Boolean = false,
    /** Test seam: MockWebServer speaks plain http; production always keeps this `true`. */
    private val httpsOnly: Boolean = true,
    private val connectTimeoutSeconds: Long = GatewayDefaults.CONNECT_TIMEOUT_SECONDS,
    private val readTimeoutSeconds: Long = GatewayDefaults.READ_TIMEOUT_SECONDS,
) {

    private data class Cached(val config: GatewayConfig, val session: GatewaySession)

    @Volatile
    private var cached: Cached? = null

    /**
     * Returns a client for the *current* settings, or a configuration error.
     * Never throws: a bad URL or a failed build is a value, not an exception.
     */
    suspend fun session(): Outcome<GatewaySession> {
        val config = try {
            configProvider()
        } catch (t: Throwable) {
            return Outcome.Failure(AppError.config(t.message ?: t::class.java.simpleName))
        }
        validate(config)?.let { return Outcome.Failure(it) }

        cached?.let { if (it.config == config) return Outcome.Success(it.session) }

        return when (val built = build(config)) {
            is Outcome.Success -> {
                cached = Cached(config, built.value)
                built
            }
            is Outcome.Failure -> built
        }
    }

    private fun validate(config: GatewayConfig): AppError? {
        val url = config.baseUrl.trim().lowercase()
        if (url.isEmpty()) return AppError.config("网关地址为空")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return AppError.config("网关地址缺少协议前缀")
        }
        // Plan D7: no cleartext. http is rejected before any socket is opened.
        if (httpsOnly && !url.startsWith("https://")) return AppError.config("网关地址必须使用 https")
        if (config.key.isBlank()) return AppError.config("网关密钥为空")
        if (config.dictPath.isBlank() || config.enPath.isBlank() || config.mtPath.isBlank()) {
            return AppError.config("网关路径不能为空")
        }
        return null
    }

    private fun build(config: GatewayConfig): Outcome<GatewaySession> = try {
        val baseUrl = config.baseUrl.trim().trimEnd('/') + "/"
        val client = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .addInterceptor(BearerAuthInterceptor(config.key))
            .apply {
                if (isDebugBuild) addInterceptor(redactedLoggingInterceptor())
            }
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        Outcome.Success(GatewaySession(retrofit.create(GatewayApi::class.java), config))
    } catch (t: Throwable) {
        // Malformed base URL, unresolvable service method, … — all configuration problems.
        Outcome.Failure(AppError.config(t.message ?: t::class.java.simpleName))
    }

    /** BODY-level logging for debug builds only, with the credential redacted. */
    private fun redactedLoggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
            redactHeader("Authorization")
        }
}

/** Injects `Authorization: Bearer <key>`; the key never goes into the URL. */
private class BearerAuthInterceptor(private val key: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("Authorization", "Bearer $key")
            .build()
        return chain.proceed(request)
    }
}
