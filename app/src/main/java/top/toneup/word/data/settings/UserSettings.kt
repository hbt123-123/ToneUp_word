package top.toneup.word.data.settings

import top.toneup.word.data.gateway.GatewayDefaults
import top.toneup.word.data.speech.SpeechRateMode
import top.toneup.word.domain.model.CetLevel

/** Light / dark / follow system. */
enum class ThemeMode(val id: String, val label: String) {
    SYSTEM("SYSTEM", "跟随系统"),
    LIGHT("LIGHT", "浅色"),
    DARK("DARK", "深色");

    companion object {
        fun fromId(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/**
 * Every user-facing setting. Stored in Preferences DataStore (small, non-relational),
 * while structured study data lives in Room.
 *
 * Note on speech: there is deliberately **no accent option**. Reading is done by the system
 * text-to-speech engine, so the language, voice, rate and pitch come from the operating system's own
 * settings; [speechRateMode] only exists so a learner can slow down a single hard word without
 * changing a device-wide preference.
 */
data class UserSettings(
    val onboardingCompleted: Boolean = false,
    val level: CetLevel = CetLevel.CET4,
    /**
     * Pack the learner is studying, `""` until a pack is chosen explicitly (schema v2, D1).
     *
     * Empty is a legitimate state: it means "derive from [level]" so a pre-upgrade install
     * keeps studying exactly what it studied before without any migration of its own.
     */
    val activePackId: String = "",
    val dailyNewWordGoal: Int = DEFAULT_NEW_WORDS,
    val dailyReviewLimit: Int = DEFAULT_REVIEW_LIMIT,
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val animationsEnabled: Boolean = true,
    /** Auto-play the word when a new card appears. */
    val autoPlayPronunciation: Boolean = false,
    /**
     * Shuffle the study queue ("乱序").
     *
     * Applied **once to the whole queue** when a session starts — never per card: a per-card
     * random could show the same word twice and never reach the end of the list. The session
     * persists the resulting order, so resuming continues in the same order instead of
     * re-shuffling on every resume.
     */
    val shuffleWords: Boolean = false,
    /** Read the example sentence too when the card is revealed. */
    val readExampleSentences: Boolean = false,
    /** How fast to read; SYSTEM means "use the operating system's setting". */
    val speechRateMode: SpeechRateMode = SpeechRateMode.SYSTEM,
    val examQuestionCount: Int = 20,
    val examDurationMinutes: Int = 15,
    /** Day key (`yyyy-MM-dd`) of the last motivational line shown on the home screen. */
    val lastMotivationDayKey: String? = null,
    /**
     * Id of the start-up line shown on the previous cold start.
     *
     * Only used to avoid showing the same line twice in a row; it is never shown to the learner and
     * carries no study data.
     */
    val lastStartupTipId: String? = null,
    /** Highest streak value already celebrated, so a new record is announced exactly once. */
    val celebratedStreak: Int = 0,
    /** Highest vocabulary milestone already celebrated. */
    val celebratedMilestone: Int = 0,
    // --- 释义补全网关 (M4, plan §10) — appended; defaults live in data/gateway/GatewayDefaults ---
    /** Gateway base URL; the client rejects `http://` with `AppError.config` (plan D7). */
    val gatewayBaseUrl: String = GatewayDefaults.BASE_URL,
    /** Gateway key; the hard-coded default can be overridden here (plan Q3). Never logged. */
    val gatewayKey: String = GatewayDefaults.KEY,
    /** ECDICT path, query parameter `word`. */
    val dictPath: String = GatewayDefaults.DICT_PATH,
    /** FreeDictionary path; `{word}` is the token placeholder. */
    val enPath: String = GatewayDefaults.EN_PATH,
    /** LLM fallback path (OpenAI compatible). */
    val mtPath: String = GatewayDefaults.MT_PATH,
    /** 「仅 Wi-Fi 补全」— constrains the `meaning-sync` worker (plan D5). */
    val syncOnlyUnmetered: Boolean = false,
    /** 「剪贴板识别」— consumed by M5; the key lands in M4 so both lanes agree on its name. */
    val clipboardDetectEnabled: Boolean = true,
    /**
     * SHA-256 hex of the last clipboard text the M5 detector showed (plan §8.1); `""` until
     * the first detection. Stores the **hash only** — the clipboard text itself is never
     * persisted, and a matching hash means "already seen, do not pop the sheet again".
     */
    val lastClipboardHash: String = "",
) {
    companion object {
        const val DEFAULT_NEW_WORDS = 20
        const val DEFAULT_REVIEW_LIMIT = 60

        /** Allowed ranges; the UI clamps to these and the repository validates on write. */
        val NEW_WORD_RANGE = 5..100
        val REVIEW_LIMIT_RANGE = 10..300
        val EXAM_QUESTION_RANGE = 5..50
        val EXAM_DURATION_RANGE = 5..60

        fun clampNewWords(value: Int) = value.coerceIn(NEW_WORD_RANGE)
        fun clampReviewLimit(value: Int) = value.coerceIn(REVIEW_LIMIT_RANGE)
        fun clampExamQuestions(value: Int) = value.coerceIn(EXAM_QUESTION_RANGE)
        fun clampExamDuration(value: Int) = value.coerceIn(EXAM_DURATION_RANGE)
    }

    /** Reminder time formatted as `HH:mm` for display. */
    val reminderLabel: String
        get() = "%02d:%02d".format(reminderHour, reminderMinute)

    /**
     * The pack every query must scope by: the explicit choice, or the builtin pack matching
     * [level] when none was made yet. Never `""` — callers can pass it straight to a DAO.
     */
    val effectivePackId: String
        get() = activePackId.ifEmpty { PackIds.defaultPackIdFor(level) }

    /**
     * Display level of [effectivePackId]. UI labels (四级/六级/自定义) read this, never the raw
     * [level], so an explicit pack choice always wins over the onboarding default.
     */
    val effectiveLevel: CetLevel
        get() = PackIds.levelForPackId(effectivePackId)
}
