package com.botlisa.app

import android.content.Context

/**
 * Trigger phrases that drive Lisa Assistant's hands-free listening mode
 * (see SpeechAssistant.kt). Independent, separately-editable phrases, each
 * checked against every default-language utterance (the listening language
 * follows the selected target language -- Russian by default):
 *
 *   - **Translate trigger** (Russian "как сказать?") -- say this, pause,
 *     then an English word, and that next utterance is translated instead of
 *     treated as the target language. See
 *     SpeechAssistant.State.LISTENING_FOR_WORD.
 *   - **Meaning trigger** (Russian "что это значит?") -- say this to hear an
 *     English translation of the *previous* target-language utterance spoken
 *     aloud. Works for any target language.
 *   - **Next-suggestion trigger** (Russian "что ещё?") -- say this to have
 *     the app read the next related/suggested phrase from the most recent
 *     lookup aloud, cycling through the list. Works for any target language
 *     the device can run on-device generation for (Russian also falls back
 *     to the curated library).
 *   - **Answer trigger** (Russian "как ответить?") -- say this to run a
 *     fresh related-phrases lookup on the previous utterance, surfacing
 *     things you could say back. Russian only.
 *
 * All are editable in Settings rather than hardcoded, and all use
 * TriggerPhraseDetector's same fuzzy match -- kept phonetically distinct by
 * default so the matcher doesn't confuse them; keep that in mind if you edit
 * one. Every default now opens with "Lisa" (transliterated per language, see
 * LISA_NAME_BY_LANGUAGE) as a wake word, so they share a common prefix --
 * distinctness comes from what follows it, not the opening word anymore.
 * That also means the translate trigger's own partial fast-path
 * (SpeechAssistant.onPartialResults -> matchesPrefix) can now start
 * switching into LISTENING_FOR_WORD on the shared "Lisa" lead-in alone,
 * before the caregiver's said which command they actually mean -- an
 * acceptable trade for a snappy trigger (see that function's own comment),
 * not something this file works around.
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
    private const val MEANING_KEY_PREFIX = "meaning_trigger_phrase_"
    private const val ANSWER_KEY_PREFIX = "answer_trigger_phrase_"

    // "Lisa", transliterated so it reads naturally (and transcribes
    // reliably) in each target language's own script -- prefixed onto every
    // default trigger phrase below as a wake word. Falls back to the plain
    // English name for any language without a hand-authored entry.
    //
    // Russian is "лиса" ("fox"), not a transliteration of the name "Лиза" --
    // matches the app's own fox mascot/branding (see lisa_fox in
    // InstructionsPanel.kt's SpeakerIcon).
    private val LISA_NAME_BY_LANGUAGE = mapOf(
        SupportedLanguages.RUSSIAN.code to "лиса",
        SupportedLanguages.HINDI.code to "लिसा",
        SupportedLanguages.MARATHI.code to "लिसा",
        SupportedLanguages.SPANISH.code to "Lisa",
        SupportedLanguages.FRENCH.code to "Lisa",
        SupportedLanguages.GERMAN.code to "Lisa",
        SupportedLanguages.PORTUGUESE.code to "Lisa",
        SupportedLanguages.ROMANIAN.code to "Lisa",
        SupportedLanguages.UKRAINIAN.code to "Ліза",
        SupportedLanguages.MANDARIN.code to "丽莎",
        SupportedLanguages.KOREAN.code to "리사",
    )
    // "Lisa, " (or its per-language equivalent) prefixed onto a bare command
    // body -- so every default trigger phrase map below composes its wake
    // word from LISA_NAME_BY_LANGUAGE in one place instead of hand-repeating
    // it per language per command.
    private fun withLisa(languageCode: String, command: String): String = "${lisaName(languageCode)}, $command"

    private fun lisaName(languageCode: String): String = LISA_NAME_BY_LANGUAGE[languageCode] ?: "Lisa"

    // The command body alone, with the "<Lisa name>, " wake word lead-in
    // removed -- so a caregiver who jumps straight to the command (skipping
    // the wake word entirely, not just pausing after it) still triggers it.
    // Only strips the wake word actually configured for [languageCode]; a
    // phrase that doesn't start with it (a from-scratch custom phrase with
    // no wake word at all) is returned unchanged, which is harmless since
    // matching it against itself again is a no-op.
    fun stripWakeWord(languageCode: String, phrase: String): String {
        val prefix = Regex("^\\s*${Regex.escape(lisaName(languageCode))}\\s*,\\s*", RegexOption.IGNORE_CASE)
        return phrase.replaceFirst(prefix, "")
    }

    // Each command has a default per target language, meaning the same as
    // its Russian original. A language with no hand-authored entry falls
    // back to the English phrase itself (see getPhrase), so a new language
    // in SupportedLanguages.ALL always has *some* working default.
    //
    // The "?" is cosmetic -- TriggerPhraseDetector.normalize() strips
    // punctuation before matching -- but keeps the phrase consistent in
    // Settings / instructions / chips.
    const val TRANSLATE_TRIGGER_EN = "Lisa, how to say?"
    const val MEANING_TRIGGER_EN = "Lisa, what does that mean?"
    const val NEXT_SUGGESTION_TRIGGER_EN = "Lisa, what else?"
    const val ANSWER_TRIGGER_EN = "Lisa, how to answer?"

    val DEFAULT_TRANSLATE_TRIGGER_PHRASES = mapOf(
        SupportedLanguages.RUSSIAN.code to "как сказать?",
        SupportedLanguages.HINDI.code to "कैसे कहें?",
        SupportedLanguages.MARATHI.code to "कसं म्हणायचं?",
        SupportedLanguages.SPANISH.code to "¿cómo se dice?",
        SupportedLanguages.FRENCH.code to "comment dit-on ?",
        SupportedLanguages.GERMAN.code to "wie sagt man?",
        SupportedLanguages.PORTUGUESE.code to "como se diz?",
        SupportedLanguages.ROMANIAN.code to "cum se spune?",
        SupportedLanguages.UKRAINIAN.code to "як сказати?",
        SupportedLanguages.MANDARIN.code to "怎么说？",
        SupportedLanguages.KOREAN.code to "어떻게 말해요?",
    ).mapValues { (code, command) -> withLisa(code, command) }
    val DEFAULT_MEANING_TRIGGER_PHRASES = mapOf(
        SupportedLanguages.RUSSIAN.code to "что это значит?",
        SupportedLanguages.HINDI.code to "इसका क्या मतलब है?",
        SupportedLanguages.MARATHI.code to "याचा अर्थ काय?",
        SupportedLanguages.SPANISH.code to "¿qué significa eso?",
        SupportedLanguages.FRENCH.code to "qu'est-ce que ça veut dire ?",
        SupportedLanguages.GERMAN.code to "was bedeutet das?",
        SupportedLanguages.PORTUGUESE.code to "o que significa isso?",
        SupportedLanguages.ROMANIAN.code to "ce înseamnă asta?",
        SupportedLanguages.UKRAINIAN.code to "що це означає?",
        SupportedLanguages.MANDARIN.code to "这是什么意思？",
        SupportedLanguages.KOREAN.code to "그게 무슨 뜻이에요?",
    ).mapValues { (code, command) -> withLisa(code, command) }
    val DEFAULT_NEXT_SUGGESTION_TRIGGER_PHRASES = mapOf(
        SupportedLanguages.RUSSIAN.code to "что ещё?",
        SupportedLanguages.HINDI.code to "और क्या?",
        SupportedLanguages.MARATHI.code to "आणखी काय?",
        SupportedLanguages.SPANISH.code to "¿qué más?",
        SupportedLanguages.FRENCH.code to "quoi d'autre ?",
        SupportedLanguages.GERMAN.code to "was noch?",
        SupportedLanguages.PORTUGUESE.code to "que mais?",
        SupportedLanguages.ROMANIAN.code to "ce altceva?",
        SupportedLanguages.UKRAINIAN.code to "що ще?",
        SupportedLanguages.MANDARIN.code to "还有什么？",
        SupportedLanguages.KOREAN.code to "또 뭐가 있어요?",
    ).mapValues { (code, command) -> withLisa(code, command) }
    // Answer suggestions are Russian-only (curated library) for now.
    val DEFAULT_ANSWER_TRIGGER_PHRASES = mapOf(
        SupportedLanguages.RUSSIAN.code to "как ответить?",
    ).mapValues { (code, command) -> withLisa(code, command) }

    fun getTranslateTriggerPhrase(context: Context, languageCode: String): String =
        getPhrase(context, TRANSLATE_KEY_PREFIX, DEFAULT_TRANSLATE_TRIGGER_PHRASES, TRANSLATE_TRIGGER_EN, languageCode)

    fun setTranslateTriggerPhrase(context: Context, languageCode: String, phrase: String) =
        setPhrase(context, TRANSLATE_KEY_PREFIX, languageCode, phrase)

    fun getMeaningTriggerPhrase(context: Context, languageCode: String): String =
        getPhrase(context, MEANING_KEY_PREFIX, DEFAULT_MEANING_TRIGGER_PHRASES, MEANING_TRIGGER_EN, languageCode)

    fun setMeaningTriggerPhrase(context: Context, languageCode: String, phrase: String) =
        setPhrase(context, MEANING_KEY_PREFIX, languageCode, phrase)

    fun getNextSuggestionTriggerPhrase(context: Context, languageCode: String): String =
        getPhrase(context, NEXT_SUGGESTION_KEY_PREFIX, DEFAULT_NEXT_SUGGESTION_TRIGGER_PHRASES, NEXT_SUGGESTION_TRIGGER_EN, languageCode)

    fun setNextSuggestionTriggerPhrase(context: Context, languageCode: String, phrase: String) =
        setPhrase(context, NEXT_SUGGESTION_KEY_PREFIX, languageCode, phrase)

    fun getAnswerTriggerPhrase(context: Context, languageCode: String): String =
        getPhrase(context, ANSWER_KEY_PREFIX, DEFAULT_ANSWER_TRIGGER_PHRASES, ANSWER_TRIGGER_EN, languageCode)

    fun setAnswerTriggerPhrase(context: Context, languageCode: String, phrase: String) =
        setPhrase(context, ANSWER_KEY_PREFIX, languageCode, phrase)

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
