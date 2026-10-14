package top.toneup.word.data.gateway

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import top.toneup.word.core.json.JsonCodec
import top.toneup.word.core.result.ErrorCode
import top.toneup.word.core.result.Outcome
import top.toneup.word.core.time.TimeProvider
import top.toneup.word.data.local.ToneUpWordDatabase
import top.toneup.word.data.local.entity.MeaningCacheEntity
import top.toneup.word.data.local.entity.WordEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Cascade, caching and error-mapping tests for [MeaningLookupService] against a local
 * MockWebServer (plan §11). Fixed timestamps throughout — the clock is injected, never read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class MeaningLookupServiceTest {

    private lateinit var database: ToneUpWordDatabase
    private lateinit var server: MockWebServer

    /** Fixed "now": 2026-01-15T03:00:00Z. Every cache window is computed against it. */
    private val now = 1_768_450_800_000L
    private val time = object : TimeProvider {
        override fun nowMillis(): Long = now
        override fun zone(): ZoneId = ZoneOffset.UTC
    }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ToneUpWordDatabase::class.java,
        )
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .build()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        database.close()
        server.shutdown()
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun config() = GatewayConfig(
        baseUrl = server.url("/").toString(),
        key = "test-key",
        dictPath = "/dict",
        enPath = "/en/{word}",
        mtPath = "/v1/chat/completions",
    )

    private fun service(
        readTimeoutSeconds: Long = 10L,
        config: GatewayConfig = config(),
        httpsOnly: Boolean = false,
    ): MeaningLookupService {
        val provider = GatewayClientProvider(
            configProvider = { config },
            json = JsonCodec.json,
            isDebugBuild = false,
            httpsOnly = httpsOnly,
            readTimeoutSeconds = readTimeoutSeconds,
        )
        return MeaningLookupService(
            clients = provider,
            cacheDao = database.meaningCacheDao(),
            wordDao = database.wordDao(),
            time = time,
            json = JsonCodec.json,
        )
    }

    private fun json(code: Int, body: String): MockResponse =
        MockResponse().setResponseCode(code).setBody(body)

    private fun success(outcome: Outcome<LookupResult>): LookupResult =
        (outcome as Outcome.Success).value

    private fun failureCode(outcome: Outcome<LookupResult>): ErrorCode =
        (outcome as Outcome.Failure).error.code

    private suspend fun seedCache(status: String, fetchedAt: Long, payload: String = "", source: String = "") {
        database.meaningCacheDao().upsert(
            MeaningCacheEntity(
                normalizedWord = "hello",
                payload = payload,
                source = source,
                status = status,
                fetchedAt = fetchedAt,
            ),
        )
    }

    // --- ① ECDICT hit stops the cascade ----------------------------------------------------

    @Test
    fun `ecdict hit stops after a single request`() = runTest {
        server.enqueue(
            json(
                200,
                """
                {"found":true,"entry":{"word":"hello","phonetic":"hә'lәu",
                 "definition":"n. an expression of greeting","translation":"interj. 喂, 嘿",
                 "pos":"interj","tag":"cet4"}}
                """.trimIndent(),
            ),
        )
        // Left over on purpose: if the cascade continued, requestCount would become 2.
        server.enqueue(json(200, """[{"word":"hello"}]"""))

        val result = success(service().lookupToken("hello"))

        assertThat(result.source).isEqualTo(LookupSource.ECDICT)
        assertThat(result.meanings).containsExactly("interj. 喂, 嘿")
        assertThat(result.phonetic).isEqualTo("hә'lәu")
        assertThat(result.partOfSpeech).isEqualTo("interj")
        assertThat(result.enDefinition).isEqualTo("n. an expression of greeting")
        assertThat(server.requestCount).isEqualTo(1)

        val request = server.takeRequest()
        assertThat(request.path).startsWith("/dict?word=hello")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-key")
        // Privacy: the key travels in the header only, never in the URL.
        assertThat(request.requestUrl?.queryParameter("key")).isNull()
    }

    // --- ② FreeDictionary (both response shapes) -------------------------------------------

    @Test
    fun `ecdict miss falls through to the dictionary array shape`() = runTest {
        server.enqueue(json(200, """{"found":false,"suggestions":[]}"""))
        server.enqueue(
            json(
                200,
                """
                [{"word":"serendipity",
                 "phonetics":[{"text":"/ˌserənˈdɪpəti/","audio":"https://cdn.example/serendipity.mp3"}],
                 "meanings":[{"partOfSpeech":"noun",
                   "definitions":[{"definition":"the occurrence of events by chance","example":"a fortunate accident","synonyms":["luck"]}],
                   "synonyms":["fortune"]}]}]
                """.trimIndent(),
            ),
        )

        val result = success(service().lookupToken("serendipity"))

        assertThat(result.source).isEqualTo(LookupSource.FREEDICT)
        assertThat(result.phonetic).isEqualTo("/ˌserənˈdɪpəti/")
        assertThat(result.partOfSpeech).isEqualTo("noun")
        assertThat(result.enDefinition).isEqualTo("the occurrence of events by chance")
        assertThat(result.example).isEqualTo("a fortunate accident")
        assertThat(result.synonyms).containsAtLeast("fortune", "luck")
        assertThat(result.audioUrl).isEqualTo("https://cdn.example/serendipity.mp3")
        assertThat(result.meanings).isEmpty() // aux-only source (plan Q2)
        assertThat(server.requestCount).isEqualTo(2)

        // The hit is cached for every pack that contains the word.
        val cached = database.meaningCacheDao().byNormalized("serendipity")
        assertThat(cached).isNotNull()
        assertThat(cached!!.status).isEqualTo(MeaningStates.CACHE_OK)
        assertThat(cached.source).isEqualTo(MeaningStates.SOURCE_FREEDICT)
        assertThat(cached.fetchedAt).isEqualTo(now)
    }

    @Test
    fun `ecdict 404 falls through to the ecdict-fallback object shape`() = runTest {
        server.enqueue(json(404, """{"error":"not found"}"""))
        server.enqueue(
            json(
                200,
                """
                {"source":"ecdict-fallback","word":"fallback","phonetic":"ˈfɔːlˌbæk",
                 "meanings":[{"definition":"a thing that takes over when another fails"}]}
                """.trimIndent(),
            ),
        )

        val result = success(service().lookupToken("fallback"))

        assertThat(result.source).isEqualTo(LookupSource.FREEDICT)
        assertThat(result.phonetic).isEqualTo("ˈfɔːlˌbæk")
        assertThat(result.enDefinition).isEqualTo("a thing that takes over when another fails")
        assertThat(server.requestCount).isEqualTo(2)
    }

    // --- ③ LLM last resort -----------------------------------------------------------------

    @Test
    fun `both dictionaries empty falls through to the chat endpoint`() = runTest {
        server.enqueue(json(200, """{"found":false}"""))
        server.enqueue(json(404, """{"error":"not found"}"""))
        server.enqueue(
            json(
                200,
                """{"id":"1","choices":[{"index":0,"message":{"role":"assistant","content":"n. 名词; 问候语"}}]}""",
            ),
        )

        val result = success(service().lookupToken("hello"))

        assertThat(result.source).isEqualTo(LookupSource.MT)
        assertThat(result.partOfSpeech).isEqualTo("n")
        assertThat(result.meanings).containsExactly("名词; 问候语").inOrder()
        assertThat(server.requestCount).isEqualTo(3)

        server.takeRequest() // dict
        server.takeRequest() // en
        val chat = server.takeRequest()
        assertThat(chat.method).isEqualTo("POST")
        assertThat(chat.path).isEqualTo("/v1/chat/completions")
        val body = chat.body.readUtf8()
        assertThat(body).contains("\"messages\"")
        assertThat(body).contains("hello")
        assertThat(body).contains("\"stream\":false")
        assertThat(chat.getHeader("Authorization")).isEqualTo("Bearer test-key")
    }

    // --- ④ 401 -----------------------------------------------------------------------------

    @Test
    fun `unauthorized gateway is a configuration error and aborts the cascade`() = runTest {
        server.enqueue(json(401, """{"error":"unauthorized"}"""))
        server.enqueue(json(200, """{"found":true,"entry":{"word":"hello","translation":"嗨"}}"""))

        val outcome = service().lookupToken("hello")

        assertThat(outcome.isSuccess).isFalse()
        assertThat(failureCode(outcome)).isEqualTo(ErrorCode.CONFIG)
        assertThat(server.requestCount).isEqualTo(1)
        // No negative cache for a configuration problem: the word must be retried once fixed.
        assertThat(database.meaningCacheDao().byNormalized("hello")).isNull()
    }

    // --- ⑤ timeout -------------------------------------------------------------------------

    @Test
    fun `timeout maps to a retryable network error`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val outcome = service(readTimeoutSeconds = 1L).lookupToken("hello")

        assertThat(outcome.isSuccess).isFalse()
        val error = (outcome as Outcome.Failure).error
        assertThat(error.code).isEqualTo(ErrorCode.NETWORK)
        assertThat(error.retryable).isTrue()
        // A transport failure must not poison the cache for 24h.
        assertThat(database.meaningCacheDao().byNormalized("hello")).isNull()
    }

    // --- ⑥ positive cache ------------------------------------------------------------------

    @Test
    fun `fresh cache entry is served without any network call`() = runTest {
        val payload = LookupPayloadCodec.encode(
            LookupResult(
                phonetic = "hә'lәu",
                partOfSpeech = "interj",
                meanings = listOf("interj. 喂, 嘿"),
                source = LookupSource.ECDICT,
            ),
            JsonCodec.json,
        )
        seedCache(MeaningStates.CACHE_OK, fetchedAt = now - 1_000L, payload = payload, source = "ecdict")

        val result = success(service().lookupToken("hello"))

        assertThat(result.meanings).containsExactly("interj. 喂, 嘿")
        assertThat(server.requestCount).isEqualTo(0)
    }

    // --- ⑦ negative cache ------------------------------------------------------------------

    @Test
    fun `failed cache blocks the gateway for 24h unless a manual retry forces it`() = runTest {
        seedCache(MeaningStates.CACHE_FAILED, fetchedAt = now - 23L * 60 * 60 * 1000)

        val blocked = service().lookupToken("hello")
        assertThat(blocked.isSuccess).isFalse()
        assertThat(failureCode(blocked)).isEqualTo(ErrorCode.NOT_FOUND)
        assertThat(server.requestCount).isEqualTo(0)

        // Manual retry ignores the negative cache and reaches the gateway again.
        server.enqueue(
            json(200, """{"found":true,"entry":{"word":"hello","translation":"interj. 喂"}}"""),
        )
        val forced = success(service().lookupToken("hello", ignoreNegativeCache = true))
        assertThat(forced.source).isEqualTo(LookupSource.ECDICT)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `exhausted cascade writes the negative cache`() = runTest {
        server.enqueue(json(200, """{"found":false}"""))
        server.enqueue(json(404, """{"error":"not found"}"""))
        server.enqueue(json(404, """{"error":"not found"}"""))

        val outcome = service().lookupToken("ghost")

        assertThat(outcome.isSuccess).isFalse()
        assertThat(failureCode(outcome)).isEqualTo(ErrorCode.NOT_FOUND)
        assertThat(server.requestCount).isEqualTo(3)
        val cached = database.meaningCacheDao().byNormalized("ghost")
        assertThat(cached!!.status).isEqualTo(MeaningStates.CACHE_FAILED)
        assertThat(cached.fetchedAt).isEqualTo(now)
    }

    // --- ⑧ lookup by word id ---------------------------------------------------------------

    @Test
    fun `lookup by word id resolves the row's normalized token`() = runTest {
        val wordId = database.wordDao().insert(
            WordEntity(
                packId = "builtin-cet4",
                word = "Hello",
                normalizedWord = "hello",
                level = "CET4",
                lookupState = "PENDING",
                meaningSource = "",
            ),
        )
        server.enqueue(
            json(200, """{"found":true,"entry":{"word":"hello","translation":"interj. 喂"}}"""),
        )

        val result = success(service().lookup(wordId))

        assertThat(result.source).isEqualTo(LookupSource.ECDICT)
        assertThat(server.takeRequest().path).startsWith("/dict?word=hello")
    }

    // --- ⑨ https enforcement ---------------------------------------------------------------

    @Test
    fun `http base url is rejected with a config error before any request`() = runTest {
        val insecure = GatewayConfig(
            baseUrl = "http://insecure.example",
            key = "test-key",
            dictPath = "/dict",
            enPath = "/en/{word}",
            mtPath = "/v1/chat/completions",
        )
        val outcome = service(config = insecure, httpsOnly = true).lookupToken("hello")

        assertThat(outcome.isSuccess).isFalse()
        assertThat(failureCode(outcome)).isEqualTo(ErrorCode.CONFIG)
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(database.meaningCacheDao().byNormalized("hello")).isNull()
    }

    // --- cache window ----------------------------------------------------------------------

    @Test
    fun `stale positive cache is refreshed from the gateway`() = runTest {
        val payload = LookupPayloadCodec.encode(
            LookupResult(meanings = listOf("旧释义"), source = LookupSource.ECDICT),
            JsonCodec.json,
        )
        seedCache(
            MeaningStates.CACHE_OK,
            fetchedAt = now - GatewayDefaults.CACHE_TTL_MILLIS - 1L,
            payload = payload,
            source = "ecdict",
        )
        server.enqueue(
            json(200, """{"found":true,"entry":{"word":"hello","translation":"interj. 新释义"}}"""),
        )

        val result = success(service().lookupToken("hello"))

        assertThat(result.meanings).containsExactly("interj. 新释义")
        assertThat(server.requestCount).isEqualTo(1)
        val cached = database.meaningCacheDao().byNormalized("hello")
        assertThat(cached!!.fetchedAt).isEqualTo(now)
    }
}
