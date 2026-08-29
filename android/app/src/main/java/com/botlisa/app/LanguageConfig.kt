package com.botlisa.app

import android.content.Context
import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

/**
 * The caregiver-facing TARGET language -- what English translates into, and
 * the voice that reads it back (OnDeviceTranslator.kt, TranslationSpeaker.kt,
 * and the settings picker in MainActivity.kt all read from this).
 *
 * NOT the related-phrases/"expand" mode (say a phrase in the target language,
 * get similar ones from the curated library) -- that stays tied to the
 * Russian phrase library regardless of this setting. Two things would need
 * to change together before that could generalize: the curated content
 * itself (Russian only today), and the backend's mode-detection heuristic
 * (`_has_cyrillic()` in services/orchestration_service/main.py), which
 * assumes Cyrillic script specifically and would need to become
 * script-aware per language -- Hindi's Devanagari script makes that
 * concrete. Logged in the project roadmap as a follow-up once there's
 * curated content for a second language to actually detect.
 *
 * [code] doubles as the persisted setting key and the locale tag Android's
 * speech recognizer expects (e.g. "ru-RU") -- note the mic's dictation
 * locale itself is NOT wired to this yet (see MainActivity.kt); it still
 * always listens for Russian, since that's what the expand-mode use case
 * the mic mainly serves actually needs today.
 */
data class TargetLanguage(
    val code: String,
    val displayName: String,
    val mlKitLanguage: String, // a com.google.mlkit.nl.translate.TranslateLanguage constant
    val ttsLocale: Locale,
)

object SupportedLanguages {
    val RUSSIAN = TargetLanguage("ru-RU", "Russian", TranslateLanguage.RUSSIAN, Locale("ru", "RU"))
    val HINDI = TargetLanguage("hi-IN", "Hindi", TranslateLanguage.HINDI, Locale("hi", "IN"))
    val MARATHI = TargetLanguage("mr-IN", "Marathi", TranslateLanguage.MARATHI, Locale("mr", "IN"))
    val SPANISH = TargetLanguage("es-ES", "Spanish", TranslateLanguage.SPANISH, Locale("es", "ES"))
    val FRENCH = TargetLanguage("fr-FR", "French", TranslateLanguage.FRENCH, Locale("fr", "FR"))
    val GERMAN = TargetLanguage("de-DE", "German", TranslateLanguage.GERMAN, Locale("de", "DE"))

    // Add a language here to make it selectable in Settings -- everything
    // downstream (on-device translation, the spoken voice) reads from this
    // list; nothing else needs to change for translation to support it.
    val ALL = listOf(RUSSIAN, HINDI, MARATHI, SPANISH, FRENCH, GERMAN)

    fun byCode(code: String): TargetLanguage = ALL.firstOrNull { it.code == code } ?: RUSSIAN
}

object LanguageConfig {
    private const val PREFS_NAME = "bot_lisa_prefs"
    private const val KEY_TARGET_LANGUAGE = "target_language_code"

    fun getTargetLanguage(context: Context): TargetLanguage {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val code = prefs.getString(KEY_TARGET_LANGUAGE, SupportedLanguages.RUSSIAN.code)
            ?: SupportedLanguages.RUSSIAN.code
        return SupportedLanguages.byCode(code)
    }

    fun setTargetLanguage(context: Context, language: TargetLanguage) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_TARGET_LANGUAGE, language.code).apply()
    }
}
