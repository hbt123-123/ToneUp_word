package top.toneup.word

import android.content.Context
import android.content.pm.ApplicationInfo
import top.toneup.word.core.json.JsonCodec
import top.toneup.word.core.time.SystemTimeProvider
import top.toneup.word.core.time.TimeProvider
import top.toneup.word.data.gateway.GatewayClientProvider
import top.toneup.word.data.gateway.MeaningLookupService
import top.toneup.word.data.gateway.toGatewayConfig
import top.toneup.word.data.io.DocumentIo
import top.toneup.word.data.local.ToneUpWordDatabase
import top.toneup.word.data.motivation.AssetStartupTipSource
import top.toneup.word.data.motivation.StartupTipSource
import top.toneup.word.data.repository.DefaultInsightRepository
import top.toneup.word.data.repository.DefaultMeaningSyncRepository
import top.toneup.word.data.repository.InsightRepository
import top.toneup.word.data.repository.MeaningSyncRepository
import top.toneup.word.data.speech.SpeechDiagnostic
import top.toneup.word.data.speech.SpeechDiagnosticsStore
import top.toneup.word.data.speech.SpeechService
import top.toneup.word.data.speech.SystemTtsSpeechService
import top.toneup.word.data.repository.AppUpdateFactory
import top.toneup.word.data.repository.AppUpdateRepository
import top.toneup.word.data.repository.BackupRepository
import top.toneup.word.data.repository.DefaultBackupRepository
import top.toneup.word.data.repository.DefaultExamRepository
import top.toneup.word.data.repository.DefaultMistakeRepository
import top.toneup.word.data.repository.DefaultStatsRepository
import top.toneup.word.data.repository.DefaultStudyRepository
import top.toneup.word.data.repository.DefaultWordRepository
import top.toneup.word.data.repository.ExamRepository
import top.toneup.word.data.repository.MistakeRepository
import top.toneup.word.data.repository.ProgressRecorder
import top.toneup.word.data.repository.SeedProgress
import top.toneup.word.data.repository.StatsRepository
import top.toneup.word.data.repository.StudyRepository
import top.toneup.word.data.repository.WordRepository
import top.toneup.word.data.seed.BuiltInPackLoader
import top.toneup.word.data.settings.DataStoreSettingsRepository
import top.toneup.word.data.settings.SettingsRepository
import top.toneup.word.work.ReminderScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The application's object graph.
 *
 * Constructor injection by hand instead of a DI framework: the graph is small and explicit,
 * it keeps the build fast (one annotation processor instead of three), and every ViewModel
 * receives exactly the interfaces it needs through a factory, so nothing in the UI can reach
 * a DAO or DataStore directly.
 */
class AppContainer(
    private val context: Context,
    /**
     * Injection seam for tests: unit and Compose-UI tests pass an in-memory Room database so a
     * test run never touches the user's real data and can be asserted on directly.
     */
    databaseOverride: ToneUpWordDatabase? = null,
) {

    val timeProvider: TimeProvider = SystemTimeProvider()

    val database: ToneUpWordDatabase = databaseOverride ?: ToneUpWordDatabase.build(context)

    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(context)

    val progressRecorder: ProgressRecorder = ProgressRecorder(database, timeProvider)

    /**
     * Progress of the built-in word-pack seeding.
     *
     * The complete CET-4/CET-6 lists hold thousands of entries, so the cold-start screen shows
     * real numbers instead of an indefinite spinner.
     */
    private val _seedProgress = MutableStateFlow<SeedProgress?>(null)
    val seedProgress: StateFlow<SeedProgress?> = _seedProgress.asStateFlow()

    private val packLoader = BuiltInPackLoader(context)

    val wordRepository: WordRepository = DefaultWordRepository(
        db = database,
        time = timeProvider,
        packLoader = packLoader,
        onSeedProgress = { progress -> _seedProgress.value = progress },
    )

    val studyRepository: StudyRepository =
        DefaultStudyRepository(database, timeProvider, progressRecorder)

    val mistakeRepository: MistakeRepository = DefaultMistakeRepository(database, timeProvider)

    val examRepository: ExamRepository = DefaultExamRepository(database, timeProvider, progressRecorder)

    val statsRepository: StatsRepository = DefaultStatsRepository(database, timeProvider)

    val backupRepository: BackupRepository = DefaultBackupRepository(database, timeProvider)

    val documentIo: DocumentIo = DocumentIo(context)

    val reminderScheduler: ReminderScheduler = ReminderScheduler(context)

    /** Version name shown in the profile / about screen. */
    val appVersionName: String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /**
     * In-app update pipeline (GitHub Releases check → silent resumable download → install
     * prompt). The only other consumer of the INTERNET permission besides the gateway.
     */
    val appUpdateRepository: AppUpdateRepository = AppUpdateFactory.create(
        context,
        appVersionName,
        AppUpdateFactory.readInstalledVersion(context).second,
    )

    /**
     * The start-up encouragement lines (开屏提示语).
     *
     * Read from the bundled asset with no network access at all; a missing or corrupt asset
     * degrades to one built-in fallback line.
     */
    val startupTipSource: StartupTipSource = AssetStartupTipSource(context)

    /**
     * Reads the inputs the learning-analysis rules need.
     *
     * Kept as its own repository because it is a read-only projection over existing tables: the
     * analysis adds no schema, so an upgrade cannot invalidate a learner's data.
     */
    val insightRepository: InsightRepository = DefaultInsightRepository(database, timeProvider)

    // --- Speech ----------------------------------------------------------------------------

    /**
     * Local counters for the speech diagnostics panel.
     *
     * They live in memory only: the app ships no analytics SDK, and these numbers exist to let the
     * learner see whether reading works on their device. Nothing is uploaded, and there is no
     * per-word history — just two counters and a failure breakdown.
     */
    val speechDiagnostics = SpeechDiagnosticsStore()

    /**
     * The system text-to-speech engine.
     *
     * Created once for the whole app: binding a `TextToSpeech` service is expensive, and one shared
     * instance keeps the engine state (and therefore the UI state) consistent between screens.
     * Nothing here requires a network or a permission.
     */
    val speechService: SpeechService = SystemTtsSpeechService(
        context = context,
        onDiagnostic = speechDiagnostics::record,
    )

    // --- Gateway 释义补全 (M4) --------------------------------------------------------------

    /** Shared JSON: the Retrofit converter and the `meaning_cache.payload` codec must agree. */
    val gatewayJson: Json = JsonCodec.json

    /**
     * HTTP stack holder: rebuilt automatically whenever baseUrl / key / paths change, and the
     * only place where https is validated (http → `AppError.config`).
     * Debuggable builds get BODY logging with the `Authorization` header redacted.
     */
    val gatewayClientProvider: GatewayClientProvider = GatewayClientProvider(
        configProvider = { settingsRepository.current().toGatewayConfig() },
        json = gatewayJson,
        isDebugBuild = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
    )

    /** ECDICT → FreeDictionary → LLM cascade with the 24h shared cache. */
    val meaningLookupService: MeaningLookupService = MeaningLookupService(
        clients = gatewayClientProvider,
        cacheDao = database.meaningCacheDao(),
        wordDao = database.wordDao(),
        time = timeProvider,
        json = gatewayJson,
    )

    /**
     * Orchestration for meaning completion: pending counter, unique-work enqueue, immediate
     * per-word lookup and batch runs. `prepare()` deliberately does **not** trigger a run —
     * the first enqueue happens after an import or from the settings screen.
     */
    val meaningSync: MeaningSyncRepository = DefaultMeaningSyncRepository(
        context = context,
        db = database,
        time = timeProvider,
        lookupService = meaningLookupService,
        settingsRepository = settingsRepository,
        json = gatewayJson,
    )

    /**
     * One-time preparation performed on app start: make sure the built-in packs are present and up
     * to date, then re-apply the reminder schedule (WorkManager persists it, but a settings change
     * or a restore can make the stored schedule stale).
     */
    suspend fun prepare() {
        wordRepository.ensureBuiltInPacks()
        _seedProgress.value = null
        val settings = settingsRepository.current()
        reminderScheduler.ensureChannel()
        reminderScheduler.sync(settings.reminderEnabled, settings.reminderHour, settings.reminderMinute)
    }
}
