package com.botlisa.app

import android.content.Context

/**
 * The trigger phrase that starts a "how do you say ___" hand-off during
 * Lisa Assistant's hands-free listening mode (see SpeechAssistant.kt): say
 * this phrase, pause, then say the word/phrase to translate.
 *
 * Editable in Settings rather than hardcoded -- the fuzzy match in
 * TriggerPhraseDetector.kt is tolerant but not perfect, and a caregiver
 * whose accent or phrasing doesn't land well on "как сказать" should be
 * able to pick something that works better for them without a rebuild.
 * Mirrors LanguageConfig.kt's SharedPreferences pattern exactly.
 *
 * Keyed by language code (the same `code` SupportedLanguages uses, e.g.
 * "ru-RU"), so a per-language trigger phrase can be added alongside a new
 * SupportedLanguages entry later. Only Russian is wired into Lisa Assistant
 * today -- see SpeechAssistant.kt -- so that's the only default that
 * matters right now.
 */
object TriggerPhraseConfig {
    private const val PREFS_NAME = "bot_lisa_prefs"
    private const val KEY_PREFIX = "trigger_phrase_"

    // Default trigger phrase per default-listening-language code. Add an
    // entry here when a second default-language listening mode exists --
    // not needed yet.
    val DEFAULT_TRIGGER_PHRASES = mapOf(
        SupportedLanguages.RUSSIAN.code to "как сказать",
    )

    fun getTriggerPhrase(context: Context, languageCode: String): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val default = DEFAULT_TRIGGER_PHRASES[languageCode] ?: ""
        return prefs.getString(KEY_PREFIX + languageCode, default) ?: default
    }

    fun setTriggerPhrase(context: Context, languageCode: String, phrase: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PREFIX + languageCode, phrase).apply()
    }
}
