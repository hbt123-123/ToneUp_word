package top.toneup.word.data.speech

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.toneup.word.core.time.SystemTimeProvider
import top.toneup.word.core.time.TimeProvider
import java.util.Locale

/**
 * The Android implementation of [SpeechService], built on the **system** text-to-speech engine.
 *
 * Design decisions, each of which fixes a concrete problem:
 *
 *  * **The default engine, not a hard-coded one.** The engine is whatever the user selected in the
 *    system settings, which is exactly what the product requirement asks for. Users who installed a
 *    specific engine (or disabled Google's) keep it.
 *  * **The platform lives behind a seam.** Every `android.speech.tts` call is in
 *    [AndroidSpeechEngine]; this class is the platform-free state machine, so its transitions can
 *    be unit-tested on the JVM (see `SystemTtsSpeechServiceTest`).
 *  * **Requests made before the engine is ready are queued, not dropped.** The first version of
 *    this app returned silently in that window, so the very first tap on the speaker did nothing.
 *  * **A failed binding is retried, once per request, and not more often than that.** A dead
 *    engine used to be latched for the life of the process: `initialize()` refused to run again and
 *    every later tap reported the same error until the app was killed. Now the first request after
 *    a failure rebinds the engine; a short cooldown keeps a burst of taps from hammering the
 *    binder, and the failure is still reported with the same recovery action.
 *  * **A language is negotiated, not assumed.** [speechLocaleCandidates] supplies the requested
 *    locale plus the regional fallbacks engines actually ship, and an offline voice is pinned when
 *    one exists. Only when the whole chain fails is a language error reported.
 *  * **System rate/pitch are honoured, and never guessed.** When [SpeechDefaultsReader] cannot read
 *    a value, `setSpeechRate`/`setPitch` are simply not called, so the engine default applies. This
 *    is asserted by a unit test.
 *  * **Nothing throws outward.** Every platform call is wrapped by the adapter; failures become
 *    [SpeechStatus] values that the UI can render with a recovery action.
 */
class SystemTtsSpeechService internal constructor(
    private val engineFactory: SpeechEngineFactory,
    private val defaultsReader: SpeechDefaultsReader,
    private val onDiagnostic: (SpeechDiagnostic) -> Unit,
    private val time: TimeProvider,
) : SpeechService {

    /**
     * The Android entry point.
     *
     * The primary constructor above is `internal` on purpose: it takes a [SpeechEngineFactory], so
     * a test can drive the whole state machine without a device and without a `Context`.
     */
    constructor(
        context: Context,
        defaultsReader: SpeechDefaultsReader = AndroidSpeechDefaultsReader(context),
        onDiagnostic: (SpeechDiagnostic) -> Unit = {},
    ) : this(
        engineFactory = AndroidSpeechEngineFactory(context),
        defaultsReader = defaultsReader,
        onDiagnostic = onDiagnostic,
        time = SystemTimeProvider(),
    )

    private val _status = MutableStateFlow<SpeechStatus>(SpeechStatus.Initialising)
    override val status: StateFlow<SpeechStatus> = _status.asStateFlow()

    @Volatile
    private var engine: SpeechEngine? = null

    @Volatile
    private var ready = false

    private var released = false

    /**
     * True from the moment an engine is created until its init callback runs.
     *
     * It exists purely as a loop guard: initialisation must never be triggered twice concurrently,
     * and [speak] must not try to recover while a binding is already in flight.
     */
    @Volatile
    private var initialising = false

    /**
     * Set when an engine reports its init result *before* the factory has returned it (some vendor
     * engines call back on a direct executor). Replayed right after the engine is assigned, so the
     * result is never lost and never attached to the previous engine.
     */
    @Volatile
    private var deferredInit: SpeechInitResult? = null

    /**
     * Counts engine instances, so a callback from an engine that has already been replaced (a
     * rebind shuts the old one down, but a vendor engine may still call back afterwards) is
     * ignored instead of being applied to the engine that replaced it.
     */
    @Volatile
    private var engineGeneration = 0

    /** The request that arrived before the engine finished initialising. */
    @Volatile
    private var pending: PendingRequest? = null

    /** When the last bind failed; used to keep a burst of taps from hammering the binder. */
    private var lastBindFailureAtMillis: Long? = null

    /** What the last successful negotiation settled on, for the diagnostics panel. */
    private var negotiatedLanguage: Locale? = null
    private var negotiatedVoiceName: String? = null

    /** The voice chosen per locale, so the voice list is not re-sorted on every read-aloud. */
    private val voiceCache = mutableMapOf<String, SpeechVoice>()

    /**
     * The engine's installed voices, cached briefly.
     *
     * Reading the list is a round-trip to the speech engine, and the list only changes when the
     * learner installs or removes voice data. It is therefore cached for a moment — and dropped
     * immediately when a negotiation failed, because that is exactly the moment the list may have
     * changed.
     */
    private var cachedVoices: List<SpeechVoice>? = null
    private var cachedVoicesAtMillis: Long = 0L

    /** The engine generation whose init result was already applied, so a repeat is ignored. */
    private var handledInitGeneration = -1

    private data class PendingRequest(
        val request: SpeechRequest,
        val onStart: () -> Unit,
        val onDone: () -> Unit,
        val onError: (SpeechStatus) -> Unit,
    )

    /** Outcome of walking [speechLocaleCandidates] against the engine. */
    private sealed interface LanguageChoice {
        data class Applied(val locale: Locale, val voice: SpeechVoice?) : LanguageChoice
        data object MissingData : LanguageChoice
        data object Unsupported : LanguageChoice
    }

    init {
        initialize()
    }

    /**
     * Creates (or, with [force], replaces) the engine binding.
     *
     * @return `true` when a binding is now in flight or the engine is already usable; `false` when
     *   nothing could be started (released, another binding is still starting, or no engine is
     *   installed on the device).
     */
    private fun initialize(force: Boolean = false): Boolean {
        if (released) return false
        if (initialising) return false
        if (!force && engine != null && ready) return true

        initialising = true
        ready = false
        engine?.let { previous ->
            // A dead instance (init error) still holds a bound service; drop it before rebinding so
            // recovery cannot leak binder connections on repeated failures.
            engine = null
            previous.shutdown()
        }
        voiceCache.clear()
        cachedVoices = null
        cachedVoicesAtMillis = 0L
        // A result reported by the engine we just replaced belongs to that engine, not to the one
        // about to be created, so it is dropped rather than replayed onto the new instance.
        deferredInit = null
        // Only the newest engine may report its result: a replaced engine can still call back.
        val generation = ++engineGeneration

        val created = runCatching {
            engineFactory.create { result ->
                if (generation == engineGeneration) {
                    if (engine == null) deferredInit = result else handleInitialized(result, generation)
                }
            }
        }.getOrNull()

        engine = created
        if (engine == null) {
            // No engine object at all: no TTS service installed on this device (or it refused to
            // bind). Recovery is impossible, but the status still carries a usable recovery action.
            initialising = false
            recordBindFailure()
            _status.value = SpeechStatus.EngineUnavailable("未找到系统语音引擎")
            onDiagnostic(SpeechDiagnostic.Failure(SpeechDiagnostic.FailureKind.ENGINE, null))
            return false
        }

        deferredInit?.let { result ->
            deferredInit = null
            handleInitialized(result, generation)
        }
        return true
    }

    /**
     * Runs once per created engine: flips readiness and serves the queued request.
     *
     * [generation] identifies the engine the result belongs to. A result that arrives twice for
     * the same engine (the deferred replay *and* the live callback) is applied only once.
     */
    private fun handleInitialized(result: SpeechInitResult, generation: Int) {
        if (handledInitGeneration == generation) return
        handledInitGeneration = generation
        initialising = false
        if (released) return
        ready = result.success
        if (ready) {
            _status.value = describeReadyState()
            pending?.let { queued ->
                pending = null
                performSpeak(queued)
            }
        } else {
            recordBindFailure()
            val failure = SpeechStatus.EngineUnavailable("初始化返回错误码 ${result.errorCode}")
            _status.value = failure
            onDiagnostic(SpeechDiagnostic.Failure(SpeechDiagnostic.FailureKind.ENGINE, null))
            pending?.let { queued ->
                pending = null
                queued.onError(failure)
            }
        }
    }

    /** Engine identity, the system defaults, and what the last read-aloud actually used. */
    private fun describeReadyState(defaults: SystemSpeechDefaults = readDefaults()): SpeechStatus = SpeechStatus.Ready(
        engine = defaults.engine ?: engine?.engineName,
        language = defaults.localeTag ?: engine?.defaultLanguage?.toLanguageTag(),
        voice = engine?.defaultVoiceName,
        rateFromSystem = defaults.rate != null,
        pitchFromSystem = defaults.pitch != null,
        negotiatedLanguage = negotiatedLanguage?.toLanguageTag(),
        negotiatedVoice = negotiatedVoiceName,
    )

    /** The system's speech preferences, read once per request. */
    private fun readDefaults(): SystemSpeechDefaults =
        runCatching { defaultsReader.read() }.getOrDefault(SystemSpeechDefaults())

    override fun speak(
        request: SpeechRequest,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: (SpeechStatus) -> Unit,
    ) {
        if (released) return
        if (request.text.isBlank()) return
        val queued = PendingRequest(request, onStart, onDone, onError)

        if (initialising) {
            // A binding is already in flight (including the very first one): replace the queued
            // request rather than dropping it, and never start a second engine behind its back.
            pending = queued
            return
        }

        val current = _status.value
        if (current is SpeechStatus.EngineUnavailable) {
            // Only a dead engine is rebound. `SpeechStatus.Failed` describes a single utterance
            // gone wrong and never reaches `_status` (playback failures are reported through the
            // callback), so including it here would have been dead code — and a trap: it would
            // turn every utterance error into a full engine rebind.
            if (!mayRebind()) {
                onDiagnostic(SpeechDiagnostic.Failure(SpeechDiagnostic.FailureKind.ENGINE, request.content))
                onError(current)
                return
            }
            // Bounded recovery: exactly one rebind attempt per request, never while another binding
            // is in flight (the guard above). The queue entry is planted *before* initialising so a
            // synchronous init callback still consumes it, and the status is flipped back to
            // Initialising so a second tap waits for this attempt instead of failing instantly.
            _status.value = SpeechStatus.Initialising
            pending = queued
            if (initialize(force = true)) return
            pending = null
            onDiagnostic(SpeechDiagnostic.Failure(SpeechDiagnostic.FailureKind.ENGINE, request.content))
            onError(_status.value)
            return
        }

        if (ready) {
            performSpeak(queued)
            return
        }

        // Status is still Initialising from the very first bind: queue instead of dropping.
        pending = queued
    }

    private fun performSpeak(queued: PendingRequest) {
        val engine = engine
        if (engine == null || !ready) {
            queued.onError(_status.value)
            return
        }

        val request = queued.request
        val defaults = readDefaults()
        when (val choice = applyBestLocale(engine, localeFromTag(request.localeTag))) {
            is LanguageChoice.MissingData -> {
                failForLanguage(queued, SpeechStatus.LanguageMissing, SpeechDiagnostic.FailureKind.LANGUAGE_MISSING)
                return
            }

            is LanguageChoice.Unsupported -> {
                failForLanguage(
                    queued,
                    SpeechStatus.LanguageUnsupported,
                    SpeechDiagnostic.FailureKind.LANGUAGE_UNSUPPORTED,
                )
                return
            }

            is LanguageChoice.Applied -> {
                negotiatedLanguage = choice.locale
                negotiatedVoiceName = choice.voice?.name
            }
        }

        // The negotiation settled, so the status is re-described: a latched language badge is
        // stale (the learner may have installed voice data or switched engines since then), and the
        // card now shows the locale and voice that were really used.
        val described = describeReadyState(defaults)
        if (_status.value != described) {
            _status.value = described
        }

        applySystemTuning(engine, request.rateMode, defaults)

        val started = engine.speak(
            request.text,
            request.utteranceId,
            object : SpeechUtteranceListener {
                override fun onStart() {
                    onDiagnostic(SpeechDiagnostic.Spoken(request.content))
                    queued.onStart()
                }

                override fun onDone() {
                    queued.onDone()
                }

                override fun onError(errorCode: Int?) {
                    reportPlaybackFailure(queued, errorCode)
                }
            },
        )

        // A queued engine reports an error until it has finished loading: treat it as "try again
        // soon" rather than as a hard failure, because the engine is otherwise usable.
        if (!started) {
            reportPlaybackFailure(queued)
        }
    }

    /**
     * Applies the first language the engine accepts, in [speechLocaleCandidates] order.
     *
     * On success an explicit [SpeechVoice] is pinned when the engine offers one for that language:
     * the offline (non-network) voice wins over a streaming one, then the regional match, then the
     * higher quality. Failing to set a voice is harmless — `setLanguage` already selected the
     * engine's default voice for the language — so it is swallowed rather than escalated.
     */
    private fun applyBestLocale(engine: SpeechEngine, requested: Locale): LanguageChoice {
        val engineDefault = engine.defaultLanguage
        var missingData = false

        for (candidate in speechLocaleCandidates(requested, engineDefault)) {
            val result = engine.setLanguage(candidate)
            when (result) {
                SpeechLanguageResult.MISSING_DATA -> {
                    // The voice pack is not downloaded for this candidate; another regional variant
                    // may still be installed, so keep walking and only report this if nothing works.
                    missingData = true
                }

                SpeechLanguageResult.NOT_SUPPORTED -> Unit

                else -> {
                    val voices = engineVoices(engine)
                    val voice = chooseVoice(voices, candidate)
                    if (voice == null && voices.isNotEmpty()) {
                        // The engine claims the language, but not one installed voice speaks it:
                        // speaking anyway would read the text with whatever voice the engine kept,
                        // possibly in another language. Report it as missing data instead, so the
                        // UI can offer the voice-data install.
                        missingData = true
                        continue
                    }
                    if (voice != null) engine.setVoice(voice)
                    return LanguageChoice.Applied(candidate, voice)
                }
            }
        }

        // Missing data is the more actionable failure (the UI offers the install action), so it wins
        // over "unsupported" when both were seen along the way.
        if (missingData) {
            // The learner may have just installed voice data: the cached list is dropped so the
            // next attempt reads the engine again.
            cachedVoices = null
            return LanguageChoice.MissingData
        }
        return LanguageChoice.Unsupported
    }

    /** The engine's installed voices, cached for [VOICE_CACHE_MILLIS]. */
    private fun engineVoices(engine: SpeechEngine): List<SpeechVoice> {
        val now = time.nowMillis()
        val cached = cachedVoices
        if (cached != null && now - cachedVoicesAtMillis < VOICE_CACHE_MILLIS) return cached
        val voices = engine.voices
        cachedVoices = voices
        cachedVoicesAtMillis = now
        return voices
    }

    /**
     * Best installed voice for [locale], or `null` to keep the engine's choice.
     *
     * The result is cached per locale: the installed voice set rarely changes, and re-filtering
     * and re-sorting it on every read-aloud is pure waste.
     */
    private fun chooseVoice(voices: List<SpeechVoice>, locale: Locale): SpeechVoice? {
        val key = locale.toLanguageTag()
        voiceCache[key]?.let { cached ->
            if (voices.any { it.name == cached.name }) return cached
        }
        val chosen = voices
            .filter { voice -> voice.locale.language.equals(locale.language, ignoreCase = true) }
            .sortedWith(
                compareBy<SpeechVoice> { voice -> if (voice.requiresNetwork) 1 else 0 }
                    .thenBy { voice -> if (voice.locale.country.equals(locale.country, ignoreCase = true)) 0 else 1 }
                    .thenByDescending { voice -> voice.quality }
                    .thenBy { voice -> voice.name },
            )
            .firstOrNull()
        if (chosen != null) voiceCache[key] = chosen
        return chosen
    }

    private fun localeFromTag(tag: String): Locale =
        runCatching { Locale.forLanguageTag(tag) }
            .getOrNull()
            ?.takeIf { it.language.isNotEmpty() }
            ?: Locale.ENGLISH

    private fun failForLanguage(
        queued: PendingRequest,
        failure: SpeechStatus,
        kind: SpeechDiagnostic.FailureKind,
    ) {
        _status.value = failure
        onDiagnostic(SpeechDiagnostic.Failure(kind, queued.request.content))
        queued.onError(failure)
    }

    private fun reportPlaybackFailure(queued: PendingRequest, errorCode: Int? = null) {
        val failure = SpeechStatus.Failed(errorCode ?: -1)
        onDiagnostic(SpeechDiagnostic.Failure(SpeechDiagnostic.FailureKind.PLAYBACK, queued.request.content))
        queued.onError(failure)
    }

    /**
     * Applies the learner's own preferences.
     *
     * Rate: [SpeechRateMode.SYSTEM] uses the operating-system value when readable, otherwise the
     * engine default is left untouched. Pitch is only ever taken from the system — the app has no
     * pitch UI, so overriding it would be a pure regression.
     */
    private fun applySystemTuning(
        engine: SpeechEngine,
        rateMode: SpeechRateMode,
        defaults: SystemSpeechDefaults,
    ) {
        defaults.pitch?.let { pitch -> engine.setPitch(pitch) }

        when (val explicit = rateMode.factor) {
            null -> defaults.rate?.let { rate -> engine.setSpeechRate(rate) }
            else -> engine.setSpeechRate(explicit)
        }
    }

    /** A failed bind is not retried more than once per cooldown window. */
    private fun mayRebind(): Boolean {
        val lastFailure = lastBindFailureAtMillis ?: return true
        return time.nowMillis() - lastFailure >= REBIND_COOLDOWN_MILLIS
    }

    private fun recordBindFailure() {
        lastBindFailureAtMillis = time.nowMillis()
    }

    override fun stop() {
        pending = null
        engine?.stop()
    }

    override fun release() {
        if (released) return
        released = true
        pending = null
        initialising = false
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
        voiceCache.clear()
        cachedVoices = null
    }

    companion object {

        /**
         * How long a failed binding keeps the engine from being rebound.
         *
         * Long enough that a burst of taps does not hammer the speech service with one bind per
         * tap, short enough that a learner who just installed a voice pack can simply tap again.
         */
        const val REBIND_COOLDOWN_MILLIS = 2_000L

        /**
         * How long the engine's voice list is reused before it is read again.
         *
         * Reading it is a round-trip to the speech engine; the list only changes when the learner
         * installs or removes voice data.
         */
        const val VOICE_CACHE_MILLIS = 10_000L

        /**
         * Opens the system screen where text-to-speech engines and voice data are configured.
         *
         * `ACTION_ACCESSIBILITY_SETTINGS` is the documented public entry point; the TTS output
         * settings live inside it. Returning false means "no activity could handle this", which the
         * UI turns into a plain message instead of a crash.
         */
        fun openSystemSpeechSettings(context: Context): Boolean = runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)

        /** Asks the platform to install the missing voice data for the current engine. */
        fun requestVoiceDataInstall(context: Context): Boolean = runCatching {
            context.startActivity(
                // The literal of `TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA`, spelled out so
                // this file keeps no `android.speech.tts` import at all.
                Intent(ACTION_INSTALL_TTS_DATA)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)

        /** `TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA`. */
        private const val ACTION_INSTALL_TTS_DATA = "android.intent.action.TTS_INSTALL_DATA"
    }
}

/**
 * Local counters behind the "发音服务状态" panel.
 *
 * They exist so the speech success rate is measurable **on the device**, without any analytics SDK
 * and without sending anything anywhere.
 */
sealed interface SpeechDiagnostic {
    data class Spoken(val content: SpeechContent) : SpeechDiagnostic
    data class Failure(val kind: FailureKind, val content: SpeechContent?) : SpeechDiagnostic

    enum class FailureKind { ENGINE, LANGUAGE_MISSING, LANGUAGE_UNSUPPORTED, PLAYBACK }
}
