package top.toneup.word.data.speech

import java.util.Locale

/**
 * The ordered list of languages to try for one read-aloud request.
 *
 * Why a chain and not a single `setLanguage("en")` call: vendor engines frequently ship only a
 * named regional voice (`en-US`, `en-GB`) and reject the generic language tag with
 * `LANG_NOT_SUPPORTED`. Treating that as fatal made the speaker button useless on those devices
 * even though a perfectly good English voice was installed.
 *
 * Order of preference:
 *  1. the requested locale exactly (`en-GB` when the caller asked for `en-GB`);
 *  2. the regional variants that engines most often ship, for the same language: `en-US`, `en-GB`
 *     for English and `zh-CN` for Chinese;
 *  3. the bare language (`en`, `zh`), which a few engines advertise without a region;
 *  4. the engine's own default language, but **only when it is the same language** as the request.
 *
 * Rule 4 is deliberately narrow: falling back to a Chinese voice to read an English word would
 * swap a clear "install the English voice data" prompt for garbled audio, so a device with no
 * English data at all still reports [SpeechStatus.LanguageMissing] and the UI can offer the
 * install action. The engine default is useful for what it really fixes — an engine whose default
 * is a variant we did not list (`en-AU`, `en-IN`).
 *
 * Duplicates are removed while order is preserved, so a request that is already `en-US` produces
 * `[en-US, en-GB, en]` rather than repeating itself.
 */
internal fun speechLocaleCandidates(requested: Locale?, engineDefault: Locale?): List<Locale> {
    val request = requested?.takeIf { it.language.isNotBlank() }
    val chain = LinkedHashSet<Locale>(4)

    if (request != null) {
        chain += request
        when (request.language.lowercase(Locale.ROOT)) {
            "en" -> {
                chain += Locale.US
                chain += Locale.UK
            }
            "zh" -> chain += Locale.SIMPLIFIED_CHINESE
        }
        // The bare language last: a few engines advertise `en`/`zh` without a region.
        val bareLanguage = Locale.forLanguageTag(request.language)
        if (bareLanguage.language.isNotBlank()) chain += bareLanguage
    }

    val engine = engineDefault?.takeIf { it.language.isNotBlank() }
    if (engine != null && (request == null || engine.language.equals(request.language, ignoreCase = true))) {
        chain += engine
    }

    return chain.toList()
}
