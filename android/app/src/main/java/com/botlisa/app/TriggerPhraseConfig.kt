package com.botlisa.app

import android.content.Context

/**
 * Trigger phrases that drive Lisa Assistant's hands-free listening mode
 * (see SpeechAssistant.kt). Two independent, separately-editable phrases,
 * both checked against every default-language utterance (the listening
 * language follows the selected target language -- Russian by default):
 *
 *   - **Translate trigger** (Russian default "как сказать?", Hindi
 *     "कैसे कहें?") -- say this, pause, then an English word, and that next
 *     utterance is translated instead of treated as the target language.
 *     See SpeechAssistant.State.LISTENING_FOR_WORD.
 *   - **Next-suggestion trigger** (Russian default "что ещё?") -- say this to have
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

    // Every target language gets a default translate / next-suggestion
    // trigger meaning the same as the Russian originals ("how to say?" /
    // "what else?"). A language without a hand-authored entry falls back to
    // the English phrase itself (see getPhrase), so adding a new language to
    // SupportedLanguages.ALL always yields *some* working default.
    //
    // The "?" is cosmetic -- TriggerPhraseDetector.normalize() strips
    // punctuation before matching -- but keeps the phrase shown in Settings /
    // instructions / reminder chips consistent.
    const val TRANSLATE_TRIGGER_EN = "how to say?"
    const val NEXT_SUGGESTION_TRIGGER_EN = "what else?"

    val DEFAULT_TRANSLATE_TRIGGER_PHRASES = mapOf(
        SupportedLanguages.RUSSIAN.code to "как сказать?",
        SupportedLanguages.HINDI.code to "कैसे कहें?",
        SupportedLanguages.SPANISH.code to "¿cómo se dice?",
        SupportedLanguages.FRENCH.code to "comment dit-on ?",
        SupportedLanguages.GERMAN.code to "wie sagt man?",
    )
    val DEFAULT_NEXT_SUGGESTION_TRIGGER_PHRASES = mapOf(
        SupportedLanguages.RUSSIAN.code to "что ещё?",
        SupportedLanguages.HINDI.code to "और क्या?",
        SupportedLanguages.SPANISH.code to "¿qué más?",
        SupportedLanguages.FRENCH.code to "quoi d'autre ?",
        SupportedLanguages.GERMAN.code to "was noch?",
    )

    fun getTranslateTriggerPhrase(context: Context, languageCode: String): String =
        getPhrase(context, TRANSLATE_KEY_PREFIX, DEFAULT_TRANSLATE_TRIGGER_PHRASES, TRANSLATE_TRIGGER_EN, languageCode)

    fun setTranslateTriggerPhrase(context: Context, languageCode: String, phrase: String) =
        setPhrase(context, TRANSLATE_KEY_PREFIX, languageCode, phrase)

    fun getNextSuggestionTriggerPhrase(context: Context, languageCode: String): String =
        getPhrase(context, NEXT_SUGGESTION_KEY_PREFIX, DEFAULT_NEXT_SUGGESTION_TRIGGER_PHRASES, NEXT_SUGGESTION_TRIGGER_EN, languageCode)

    fun setNextSuggestionTriggerPhrase(context: Context, languageCode: String, phrase: String) =
        setPhrase(context, NEXT_SUGGESTION_KEY_PREFIX, languageCode, phrase)

    private fun getPhrase(
        context: Context,
        keyPrefix: String,
        defaults: Map<String, String>,
        englishFallback: String,
        languageCode: String,
    ): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val default = defaults[languageCode] ?: englishFallback
        return prefs.getString(keyPrefix + languageCode, default) ?: default
    }

    private fun setPhrase(context: Context, keyPrefix: String, languageCode: String, phrase: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(keyPrefix + languageCode, phrase).apply()
    }
}
