package com.botlisa.app

import android.content.Context

/**
 * Trigger phrases that drive Lisa Assistant's hands-free listening mode
 * (see SpeechAssistant.kt). Two independent, separately-editable phrases,
 * both checked against every default-language (Russian) utterance:
 *
 *   - **Translate trigger** (default "как сказать?") -- say this, pause,
 *     then an English word, and that next utterance is translated instead
 *     of treated as Russian. See SpeechAssistant.State.LISTENING_FOR_WORD.
 *   - **Next-suggestion trigger** (default "что ещё?") -- say this to have
 *     the app read the next related/suggested phrase from the most recent
 *     lookup aloud. Saying it again reads the *next* one in that list,
 *     cycling back to the start once it runs out. Doesn't change listening
 *     state -- it's handled and then Lisa Assistant keeps listening in the
 *     default state, same as an ordinary Russian utterance that didn't
 *     match anything.
 *
 * Both are editable in Settings rather than hardcoded, and both use
 * TriggerPhraseDetector's same fuzzy match -- deliberately kept phonetically
 * distinct from each other by default so the fuzzy matcher doesn't confuse
 * one for the other; if you change either in Settings, keep that in mind.
 *
 * Storage mirrors LanguageConfig.kt's SharedPreferences pattern. The
 * translate-trigger key name (`trigger_phrase_<code>`) predates this file
 * having two phrases -- kept as-is rather than renamed, so an
 * already-customized translate trigger on your phone isn't silently reset
 * by this update.
 */
object TriggerPhraseConfig {
    private const val PREFS_NAME = "bot_lisa_prefs"
    private const val TRANSLATE_KEY_PREFIX = "trigger_phrase_"
    private const val NEXT_SUGGESTION_KEY_PREFIX = "next_suggestion_trigger_phrase_"

    // One entry per default-listening-language code. Add an entry to both
    // maps here when a second default-language listening mode exists --
    // not needed yet, only Russian is wired into Lisa Assistant today.
    // The "?" is cosmetic -- TriggerPhraseDetector.normalize() strips
    // punctuation before matching -- but it keeps the phrase shown in
    // Settings / the instructions / the reminder chips consistent.
    val DEFAULT_TRANSLATE_TRIGGER_PHRASES = mapOf(
        SupportedLanguages.RUSSIAN.code to "как сказать?",
    )
    val DEFAULT_NEXT_SUGGESTION_TRIGGER_PHRASES = mapOf(
        SupportedLanguages.RUSSIAN.code to "что ещё?",
    )

    fun getTranslateTriggerPhrase(context: Context, languageCode: String): String =
        getPhrase(context, TRANSLATE_KEY_PREFIX, DEFAULT_TRANSLATE_TRIGGER_PHRASES, languageCode)

    fun setTranslateTriggerPhrase(context: Context, languageCode: String, phrase: String) =
        setPhrase(context, TRANSLATE_KEY_PREFIX, languageCode, phrase)

    fun getNextSuggestionTriggerPhrase(context: Context, languageCode: String): String =
        getPhrase(context, NEXT_SUGGESTION_KEY_PREFIX, DEFAULT_NEXT_SUGGESTION_TRIGGER_PHRASES, languageCode)

    fun setNextSuggestionTriggerPhrase(context: Context, languageCode: String, phrase: String) =
        setPhrase(context, NEXT_SUGGESTION_KEY_PREFIX, languageCode, phrase)

    private fun getPhrase(
        context: Context,
        keyPrefix: String,
        defaults: Map<String, String>,
        languageCode: String,
    ): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val default = defaults[languageCode] ?: ""
        return prefs.getString(keyPrefix + languageCode, default) ?: default
    }

    private fun setPhrase(context: Context, keyPrefix: String, languageCode: String, phrase: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(keyPrefix + languageCode, phrase).apply()
    }
}
