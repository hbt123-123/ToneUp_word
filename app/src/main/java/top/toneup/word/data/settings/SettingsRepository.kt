package top.toneup.word.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import top.toneup.word.data.speech.SpeechRateMode
import top.toneup.word.domain.model.CetLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/** DataStore handle for the single preferences file used by the app. */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "toneup_settings",
)

/**
 * Settings boundary. The UI only ever sees [UserSettings] and suspending setters, so the
 * storage technology could be swapped without touching a single screen.
 */
interface SettingsRepository {
    val settings: Flow<UserSettings>

    suspend fun current(): UserSettings

    suspend fun setOnboardingCompleted(completed: Boolean)
    suspend fun setLevel(level: CetLevel)

    /**
     * Chooses the pack to study (schema v2, D1). `""` clears the explicit choice and falls
     * back to `defaultPackIdFor(level)` — used when the learner switches back to the
     * onboarding level default.
     */
    suspend fun setActivePackId(packId: String)
    suspend fun setDailyNewWordGoal(count: Int)
    suspend fun setDailyReviewLimit(limit: Int)
    suspend fun setReminder(enabled: Boolean, hour: Int, minute: Int)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setAnimationsEnabled(enabled: Boolean)
    suspend fun setAutoPlayPronunciation(enabled: Boolean)

    /** Whether the study queue is shuffled ("乱序") when a session starts. */
    suspend fun setShuffleWords(enabled: Boolean)

    /** Whether the example sentence is read aloud as well as the word. */
    suspend fun setReadExampleSentences(enabled: Boolean)

    /** How fast to read; [SpeechRateMode.SYSTEM] defers to the operating-system setting. */
    suspend fun setSpeechRateMode(mode: SpeechRateMode)

    /** Records that today's motivational line has been shown, so it appears once per day. */
    suspend fun setLastMotivationDayKey(dayKey: String)

    /**
     * Records which start-up line was shown last, so the next cold start can avoid repeating it.
     *
     * It lives in preferences rather than in memory precisely because the process is gone by then.
     */
    suspend fun setLastStartupTipId(id: String)

    /** Records a celebrated streak / milestone so the same achievement is not announced twice. */
    suspend fun setCelebratedStreak(value: Int)
    suspend fun setCelebratedMilestone(value: Int)

    suspend fun setExamPreferences(questionCount: Int, durationMinutes: Int)

    // --- 释义补全网关 (M4, plan §10) — appended setters, same pattern as above ----------------
    /** Gateway base URL; only `https` will be accepted by the client (plan D7). */
    suspend fun setGatewayBaseUrl(baseUrl: String)

    /** Gateway key; empty means "use [GatewayDefaults.KEY]" again. */
    suspend fun setGatewayKey(key: String)

    suspend fun setDictPath(path: String)

    /** FreeDictionary path template, must keep the `{word}` placeholder to be useful. */
    suspend fun setEnPath(path: String)

    suspend fun setMtPath(path: String)

    /** 「仅 Wi-Fi 补全」 */
    suspend fun setSyncOnlyUnmetered(enabled: Boolean)

    /** 「剪贴板识别」(M5 开关，M4 先落键) */
    suspend fun setClipboardDetectEnabled(enabled: Boolean)

    /** Restores every preference to its factory value (study data is untouched). */
    suspend fun resetToDefaults()
}

/** Preferences DataStore implementation. */
class DataStoreSettingsRepository(private val context: Context) : SettingsRepository {

    override val settings: Flow<UserSettings> = context.settingsDataStore.data
        .catch { throwable ->
            // A corrupted preferences file must never crash the app: fall back to defaults.
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { prefs -> prefs.toUserSettings() }

    override suspend fun current(): UserSettings = settings.first()

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        context.settingsDataStore.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
    }

    override suspend fun setLevel(level: CetLevel) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.LEVEL] = level.id
            // Choosing a level re-derives the default pack: a stale explicit pack id must not
            // silently override the learner's new choice (D1).
            prefs.remove(Keys.ACTIVE_PACK_ID)
        }
    }

    override suspend fun setActivePackId(packId: String) {
        context.settingsDataStore.edit { prefs ->
            if (packId.isEmpty()) prefs.remove(Keys.ACTIVE_PACK_ID)
            else prefs[Keys.ACTIVE_PACK_ID] = packId
        }
    }

    override suspend fun setDailyNewWordGoal(count: Int) {
        context.settingsDataStore.edit { it[Keys.DAILY_NEW_WORDS] = UserSettings.clampNewWords(count) }
    }

    override suspend fun setDailyReviewLimit(limit: Int) {
        context.settingsDataStore.edit { it[Keys.DAILY_REVIEW_LIMIT] = UserSettings.clampReviewLimit(limit) }
    }

    override suspend fun setReminder(enabled: Boolean, hour: Int, minute: Int) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.REMINDER_ENABLED] = enabled
            prefs[Keys.REMINDER_HOUR] = hour.coerceIn(0, 23)
            prefs[Keys.REMINDER_MINUTE] = minute.coerceIn(0, 59)
        }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.THEME_MODE] = mode.id }
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    override suspend fun setAnimationsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.ANIMATIONS] = enabled }
    }

    override suspend fun setAutoPlayPronunciation(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_PLAY] = enabled }
    }

    override suspend fun setShuffleWords(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.SHUFFLE_WORDS] = enabled }
    }

    override suspend fun setReadExampleSentences(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.READ_EXAMPLES] = enabled }
    }

    override suspend fun setSpeechRateMode(mode: SpeechRateMode) {
        context.settingsDataStore.edit { it[Keys.SPEECH_RATE_MODE] = mode.id }
    }

    override suspend fun setLastMotivationDayKey(dayKey: String) {
        context.settingsDataStore.edit { it[Keys.LAST_MOTIVATION_DAY] = dayKey }
    }

    override suspend fun setLastStartupTipId(id: String) {
        context.settingsDataStore.edit { it[Keys.LAST_STARTUP_TIP] = id }
    }

    override suspend fun setCelebratedStreak(value: Int) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[Keys.CELEBRATED_STREAK] ?: 0
            // Never move the marker backwards: a streak that was already celebrated must not be
            // re-announced because the streak later dropped and recovered.
            if (value > current) prefs[Keys.CELEBRATED_STREAK] = value
        }
    }

    override suspend fun setCelebratedMilestone(value: Int) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[Keys.CELEBRATED_MILESTONE] ?: 0
            if (value > current) prefs[Keys.CELEBRATED_MILESTONE] = value
        }
    }

    override suspend fun setExamPreferences(questionCount: Int, durationMinutes: Int) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.EXAM_QUESTIONS] = UserSettings.clampExamQuestions(questionCount)
            prefs[Keys.EXAM_DURATION] = UserSettings.clampExamDuration(durationMinutes)
        }
    }

    override suspend fun resetToDefaults() {
        context.settingsDataStore.edit { it.clear() }
    }

    // --- 释义补全网关 (M4) -------------------------------------------------------------------
    // Empty strings are stored as `remove`: "explicitly cleared" then falls back to the
    // GatewayDefaults constant, which is exactly what the UI shows as the placeholder.

    override suspend fun setGatewayBaseUrl(baseUrl: String) {
        context.settingsDataStore.edit { prefs ->
            val trimmed = baseUrl.trim()
            if (trimmed.isEmpty()) prefs.remove(Keys.GATEWAY_BASE_URL)
            else prefs[Keys.GATEWAY_BASE_URL] = trimmed
        }
    }

    override suspend fun setGatewayKey(key: String) {
        context.settingsDataStore.edit { prefs ->
            if (key.isEmpty()) prefs.remove(Keys.GATEWAY_KEY)
            else prefs[Keys.GATEWAY_KEY] = key
        }
    }

    override suspend fun setDictPath(path: String) {
        context.settingsDataStore.edit { prefs ->
            if (path.isEmpty()) prefs.remove(Keys.DICT_PATH)
            else prefs[Keys.DICT_PATH] = path
        }
    }

    override suspend fun setEnPath(path: String) {
        context.settingsDataStore.edit { prefs ->
            if (path.isEmpty()) prefs.remove(Keys.EN_PATH)
            else prefs[Keys.EN_PATH] = path
        }
    }

    override suspend fun setMtPath(path: String) {
        context.settingsDataStore.edit { prefs ->
            if (path.isEmpty()) prefs.remove(Keys.MT_PATH)
            else prefs[Keys.MT_PATH] = path
        }
    }

    override suspend fun setSyncOnlyUnmetered(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.SYNC_ONLY_UNMETERED] = enabled }
    }

    override suspend fun setClipboardDetectEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.CLIPBOARD_DETECT] = enabled }
    }

    private fun Preferences.toUserSettings(): UserSettings {
        val defaults = UserSettings()
        return UserSettings(
            onboardingCompleted = this[Keys.ONBOARDING_COMPLETED] ?: defaults.onboardingCompleted,
            level = CetLevel.fromId(this[Keys.LEVEL] ?: defaults.level.id),
            activePackId = this[Keys.ACTIVE_PACK_ID] ?: defaults.activePackId,
            dailyNewWordGoal = UserSettings.clampNewWords(
                this[Keys.DAILY_NEW_WORDS] ?: defaults.dailyNewWordGoal,
            ),
            dailyReviewLimit = UserSettings.clampReviewLimit(
                this[Keys.DAILY_REVIEW_LIMIT] ?: defaults.dailyReviewLimit,
            ),
            reminderEnabled = this[Keys.REMINDER_ENABLED] ?: defaults.reminderEnabled,
            reminderHour = (this[Keys.REMINDER_HOUR] ?: defaults.reminderHour).coerceIn(0, 23),
            reminderMinute = (this[Keys.REMINDER_MINUTE] ?: defaults.reminderMinute).coerceIn(0, 59),
            themeMode = ThemeMode.fromId(this[Keys.THEME_MODE] ?: defaults.themeMode.id),
            dynamicColor = this[Keys.DYNAMIC_COLOR] ?: defaults.dynamicColor,
            animationsEnabled = this[Keys.ANIMATIONS] ?: defaults.animationsEnabled,
            autoPlayPronunciation = this[Keys.AUTO_PLAY] ?: defaults.autoPlayPronunciation,
            shuffleWords = this[Keys.SHUFFLE_WORDS] ?: defaults.shuffleWords,
            readExampleSentences = this[Keys.READ_EXAMPLES] ?: defaults.readExampleSentences,
            speechRateMode = SpeechRateMode.fromId(
                this[Keys.SPEECH_RATE_MODE] ?: defaults.speechRateMode.id,
            ),
            lastMotivationDayKey = this[Keys.LAST_MOTIVATION_DAY],
            lastStartupTipId = this[Keys.LAST_STARTUP_TIP],
            celebratedStreak = this[Keys.CELEBRATED_STREAK] ?: defaults.celebratedStreak,
            celebratedMilestone = this[Keys.CELEBRATED_MILESTONE] ?: defaults.celebratedMilestone,
            examQuestionCount = UserSettings.clampExamQuestions(
                this[Keys.EXAM_QUESTIONS] ?: defaults.examQuestionCount,
            ),
            examDurationMinutes = UserSettings.clampExamDuration(
                this[Keys.EXAM_DURATION] ?: defaults.examDurationMinutes,
            ),
            // Gateway slice (M4): absent key → GatewayDefaults, so the UI placeholder is the truth.
            gatewayBaseUrl = this[Keys.GATEWAY_BASE_URL] ?: defaults.gatewayBaseUrl,
            gatewayKey = this[Keys.GATEWAY_KEY] ?: defaults.gatewayKey,
            dictPath = this[Keys.DICT_PATH] ?: defaults.dictPath,
            enPath = this[Keys.EN_PATH] ?: defaults.enPath,
            mtPath = this[Keys.MT_PATH] ?: defaults.mtPath,
            syncOnlyUnmetered = this[Keys.SYNC_ONLY_UNMETERED] ?: defaults.syncOnlyUnmetered,
            clipboardDetectEnabled = this[Keys.CLIPBOARD_DETECT] ?: defaults.clipboardDetectEnabled,
        )
    }

    private object Keys {
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val LEVEL = stringPreferencesKey("level")

        /** Explicit pack choice; absent → derive from [Keys.LEVEL] (see `UserSettings`). */
        val ACTIVE_PACK_ID = stringPreferencesKey("active_pack_id")
        val DAILY_NEW_WORDS = intPreferencesKey("daily_new_words")
        val DAILY_REVIEW_LIMIT = intPreferencesKey("daily_review_limit")
        val REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")
        val REMINDER_HOUR = intPreferencesKey("reminder_hour")
        val REMINDER_MINUTE = intPreferencesKey("reminder_minute")

        /**
         * The old `accent` key is intentionally left unmapped: the accent feature was removed, so a
         * value written by an older version is simply ignored (and dropped on the next reset).
         */
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val ANIMATIONS = booleanPreferencesKey("animations_enabled")
        val AUTO_PLAY = booleanPreferencesKey("auto_play_pronunciation")
        val SHUFFLE_WORDS = booleanPreferencesKey("shuffle_words")
        val READ_EXAMPLES = booleanPreferencesKey("read_example_sentences")
        val SPEECH_RATE_MODE = stringPreferencesKey("speech_rate_mode")
        val LAST_MOTIVATION_DAY = stringPreferencesKey("last_motivation_day")
        val LAST_STARTUP_TIP = stringPreferencesKey("last_startup_tip_id")
        val CELEBRATED_STREAK = intPreferencesKey("celebrated_streak")
        val CELEBRATED_MILESTONE = intPreferencesKey("celebrated_milestone")
        val EXAM_QUESTIONS = intPreferencesKey("exam_questions")
        val EXAM_DURATION = intPreferencesKey("exam_duration")

        // --- 释义补全网关 (M4, plan §10) -----------------------------------------------------
        val GATEWAY_BASE_URL = stringPreferencesKey("gateway_base_url")
        val GATEWAY_KEY = stringPreferencesKey("gateway_key")
        val DICT_PATH = stringPreferencesKey("dict_path")
        val EN_PATH = stringPreferencesKey("en_path")
        val MT_PATH = stringPreferencesKey("mt_path")
        val SYNC_ONLY_UNMETERED = booleanPreferencesKey("sync_only_unmetered")
        val CLIPBOARD_DETECT = booleanPreferencesKey("clipboard_detect_enabled")
    }
}
