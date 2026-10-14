package top.toneup.word.data.gateway

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import top.toneup.word.core.json.JsonCodec
import top.toneup.word.core.result.Outcome
import top.toneup.word.core.time.TimeProvider
import top.toneup.word.data.local.ToneUpWordDatabase
import top.toneup.word.data.local.entity.WordEntity
import top.toneup.word.data.repository.DefaultMeaningSyncRepository
import top.toneup.word.data.repository.MeaningSyncRepository
import top.toneup.word.data.settings.DataStoreSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
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
 * Write-back and batch semantics of [DefaultMeaningSyncRepository] (plan §7.2/§7.3):
 * field merge, the `manual` guard, state transitions and the 401 abort.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class MeaningSyncRepositoryTest {

    private lateinit var database: ToneUpWordDatabase
    private lateinit var server: MockWebServer

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

    private fun service(readTimeoutSeconds: Long = 10L): MeaningLookupService {
        val config = GatewayConfig(
            baseUrl = server.url("/").toString(),
            key = "test-key",
            dictPath = "/dict",
            enPath = "/en/{word}",
            mtPath = "/v1/chat/completions",
        )
        val provider = GatewayClientProvider(
            configProvider = { config },
            json = JsonCodec.json,
            isDebugBuild = false,
            httpsOnly = false,
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

    private fun repository(svc: MeaningLookupService = service()): MeaningSyncRepository =
        DefaultMeaningSyncRepository(
            context = ApplicationProvider.getApplicationContext(),
            db = database,
            time = time,
            lookupService = svc,
            settingsRepository = DataStoreSettingsRepository(ApplicationProvider.getApplicationContext()),
            json = JsonCodec.json,
        )

    private suspend fun word(
        token: String,
        state: String = "PENDING",
        source: String = "",
        meanings: String = "[]",
        attempts: Int = 0,
    ): Long = database.wordDao().insert(
        WordEntity(
            packId = "builtin-cet4",
            word = token,
            normalizedWord = token,
            level = "CET4",
            meanings = meanings,
            lookupState = state,
            meaningSource = source,
            lookupAttempts = attempts,
        ),
    )

    private fun json(code: Int, body: String): MockResponse =
        MockResponse().setResponseCode(code).setBody(body)

    private fun ecdictHit(word: String, translation: String): MockResponse = json(
        200,
        """{"found":true,"entry":{"word":"$word","phonetic":"/$word/","pos":"n.","translation":"$translation"}}""",
    )

    // --- write-back ------------------------------------------------------------------------

    @Test
    fun `apply writes every field and the shared cache row in one transaction`() = runTest {
        val id = word("hello")
        val result = LookupResult(
            phonetic = "/həˈləʊ/",
            partOfSpeech = "interj.",
            meanings = listOf("interj. 喂", "int. 嗨"),
            enDefinition = "used as a greeting",
            example = "Hello there!",
            synonyms = listOf("hi"),
            audioUrl = "https://cdn.example/hello.mp3",
            source = LookupSource.ECDICT,
        )

        repository().applyLookupToWord(id, result, now)

        val row = database.wordDao().byId(id)!!
        assertThat(row.lookupState).isEqualTo("OK")
        assertThat(row.meaningSource).isEqualTo("ecdict")
        assertThat(row.meanings).isEqualTo("""["interj. 喂","int. 嗨"]""")
        assertThat(row.phonetic).isEqualTo("/həˈləʊ/")
        assertThat(row.partOfSpeech).isEqualTo("interj.")
        assertThat(row.enDefinition).isEqualTo("used as a greeting")
        assertThat(row.exampleSentence).isEqualTo("Hello there!")
        assertThat(row.audioUrl).isEqualTo("https://cdn.example/hello.mp3")
        assertThat(row.lookupAttempts).isEqualTo(0)
        assertThat(row.lastLookupAt).isEqualTo(now)

        val cached = database.meaningCacheDao().byNormalized("hello")!!
        assertThat(cached.status).isEqualTo("OK")
        assertThat(cached.source).isEqualTo("ecdict")
        assertThat(cached.fetchedAt).isEqualTo(now)
        val payload = LookupPayloadCodec.decode(cached.payload, JsonCodec.json)
        assertThat(payload!!.meanings).containsExactly("interj. 喂", "int. 嗨")
    }

    @Test
    fun `manual source is never overwritten`() = runTest {
        val id = word("curated", state = "OK", source = "manual", meanings = """["手工释义"]""")

        repository().applyLookupToWord(
            id,
            LookupResult(meanings = listOf("网关释义"), source = LookupSource.ECDICT),
            now,
        )

        val row = database.wordDao().byId(id)!!
        assertThat(row.meanings).isEqualTo("""["手工释义"]""")
        assertThat(row.meaningSource).isEqualTo("manual")
        assertThat(row.lookupState).isEqualTo("OK")
        // Nothing at all is written for a guarded row — not even the cache entry.
        assertThat(database.meaningCacheDao().byNormalized("curated")).isNull()
    }

    @Test
    fun `a completed file sourced word is left alone`() = runTest {
        val id = word("builtin", state = "OK", source = "file", meanings = """["内置释义"]""")

        repository().applyLookupToWord(
            id,
            LookupResult(meanings = listOf("覆盖"), source = LookupSource.ECDICT),
            now,
        )

        val row = database.wordDao().byId(id)!!
        assertThat(row.meanings).isEqualTo("""["内置释义"]""")
        assertThat(row.meaningSource).isEqualTo("file")
    }

    @Test
    fun `partial results merge instead of blanking existing content`() = runTest {
        val id = word("merged", state = "PENDING", meanings = """["原有释义"]""")

        // FreeDictionary hit: auxiliary fields only, no Chinese meanings, no phonetic.
        repository().applyLookupToWord(
            id,
            LookupResult(enDefinition = "an English definition", source = LookupSource.FREEDICT),
            now,
        )

        val row = database.wordDao().byId(id)!!
        assertThat(row.meanings).isEqualTo("""["原有释义"]""")
        assertThat(row.enDefinition).isEqualTo("an English definition")
        assertThat(row.meaningSource).isEqualTo("freecdict")
        assertThat(row.lookupState).isEqualTo("OK")
    }

    // --- pending counter -------------------------------------------------------------------

    @Test
    fun `observePendingCount counts only rows the gateway should still touch`() = runTest {
        word("one")
        word("two")
        word("edited", state = "FAILED", source = "manual", attempts = 1)
        word("exhausted", state = "FAILED", attempts = 3)
        word("done", state = "OK", source = "ecdict", meanings = """["释义"]""")

        val pending = repository().observePendingCount().first()

        assertThat(pending).isEqualTo(2)
    }

    // --- batch runs ------------------------------------------------------------------------

    @Test
    fun `runBatch completes every queued word and empties the pending count`() = runTest {
        word("alpha")
        word("beta")
        server.enqueue(ecdictHit("alpha", "n. 阿尔法"))
        server.enqueue(ecdictHit("beta", "n. 贝塔"))

        val report = repository().runBatch(50)

        assertThat(report.processed).isEqualTo(2)
        assertThat(report.abortedByConfig).isFalse()
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(repository().observePendingCount().first()).isEqualTo(0)
        assertThat(database.wordDao().byNormalized("builtin-cet4", "alpha")!!.lookupState).isEqualTo("OK")
        assertThat(database.wordDao().byNormalized("builtin-cet4", "beta")!!.meanings)
            .isEqualTo("""["n. 贝塔"]""")
    }

    @Test
    fun `runBatch stops the whole queue on 401`() = runTest {
        word("alpha")
        word("beta")
        server.enqueue(json(401, """{"error":"unauthorized"}"""))

        val report = repository().runBatch(50)

        assertThat(report.abortedByConfig).isTrue()
        assertThat(report.processed).isEqualTo(1)
        assertThat(server.requestCount).isEqualTo(1)
        // The aborted row keeps its state untouched so nothing is burned by a bad key.
        val row = database.wordDao().byNormalized("builtin-cet4", "alpha")!!
        assertThat(row.lookupState).isEqualTo("PENDING")
        assertThat(row.lookupAttempts).isEqualTo(0)
        assertThat(database.wordDao().byNormalized("builtin-cet4", "beta")!!.lookupState)
            .isEqualTo("PENDING")
    }

    @Test
    fun `runBatch keeps a word pending on a transport error but counts the attempt`() = runTest {
        val id = word("flaky")
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val report = repository(svc = service(readTimeoutSeconds = 1L)).runBatch(50)

        assertThat(report.processed).isEqualTo(1)
        assertThat(report.abortedByConfig).isFalse()
        val row = database.wordDao().byId(id)!!
        assertThat(row.lookupState).isEqualTo("PENDING")
        assertThat(row.lookupAttempts).isEqualTo(1)
        assertThat(row.lastLookupAt).isEqualTo(now)
    }

    @Test
    fun `runBatch marks a completed miss as failed and writes the negative cache`() = runTest {
        val id = word("ghost")
        server.enqueue(json(200, """{"found":false}"""))
        server.enqueue(json(404, """{"error":"not found"}"""))
        server.enqueue(json(404, """{"error":"not found"}"""))

        val report = repository().runBatch(50)

        assertThat(report.processed).isEqualTo(1)
        assertThat(report.abortedByConfig).isFalse()
        val row = database.wordDao().byId(id)!!
        assertThat(row.lookupState).isEqualTo("FAILED")
        assertThat(row.lookupAttempts).isEqualTo(1)
        val cached = database.meaningCacheDao().byNormalized("ghost")!!
        assertThat(cached.status).isEqualTo("FAILED")
        // A FAILED word with attempts left still counts as pending work for the next run.
        assertThat(repository().observePendingCount().first()).isEqualTo(1)
    }

    @Test
    fun `lookupWordNow writes back immediately and clears the queue entry`() = runTest {
        val id = word("gamma")
        server.enqueue(ecdictHit("gamma", "n. 伽马"))

        val outcome = repository().lookupWordNow(id)

        assertThat((outcome as Outcome.Success).value.source).isEqualTo(LookupSource.ECDICT)
        assertThat(database.wordDao().byId(id)!!.lookupState).isEqualTo("OK")
        assertThat(repository().observePendingCount().first()).isEqualTo(0)
        // Second call is served from cache: no extra gateway traffic for the same token.
        repository().lookupWordNow(id)
        assertThat(server.requestCount).isEqualTo(1)
    }
}
