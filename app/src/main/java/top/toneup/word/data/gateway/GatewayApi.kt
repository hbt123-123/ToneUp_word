package top.toneup.word.data.gateway

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * Retrofit view of the gateway (plan D6).
 *
 * All three endpoints take a **full URL built from settings** (`@Url`) instead of a fixed
 * `@Path`: `dictPath`/`mtPath` are free-form and `enPath` contains a literal `{word}` template
 * that is substituted before the call. Retrofit would percent-encode `{`/`}` inside a path
 * segment, so the template can only be expanded on our side — and building the URL ourselves
 * also means a user who changes a path never needs a new client.
 *
 * Authentication is a header interceptor, never a `?key=` query (隐私红线：key 不进 URL/日志).
 */
interface GatewayApi {

    /** §1 ECDICT. `found=false` (or HTTP 404) means "go to the next source". */
    @GET
    suspend fun dict(@Url url: String, @Query("word") word: String): DictResponse

    /** §2 FreeDictionary / ecdict-fallback. Empty answer or 404 = miss. */
    @GET
    suspend fun en(@Url url: String): EnResponse

    /** §3 OpenAI-compatible chat completion, non-streaming. */
    @POST
    suspend fun chat(@Url url: String, @Body body: ChatRequest): ChatResponse
}
