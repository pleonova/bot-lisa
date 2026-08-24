package com.botlisa.app

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Speaks a translated phrase aloud via Android's built-in TextToSpeech engine
 * -- "read the translation back to me": look up a word with one hand while
 * holding the baby with the other, and hear it spoken instead of having to
 * read the screen. TTS output follows whatever audio route is currently
 * active on the phone (a single earbud, a Bluetooth earpiece, wired
 * headphones, or the speaker if nothing's connected) -- no special routing
 * code needed for the one-earbud use case, that's just how Android audio
 * output already works.
 *
 * [locale] is a required constructor parameter, driven by the target
 * language picked in Settings (LanguageConfig.kt) -- see MainActivity.kt,
 * which recreates this whenever that setting changes.
 *
 * Tied to the Activity/Composable lifecycle (unlike OnDeviceTranslator,
 * which is a process-wide singleton): a fresh instance on rotation, or when
 * the target language changes, is normal and cheap -- TextToSpeech
 * initialization takes well under what a user would notice.
 */
class TranslationSpeaker(context: Context, private val locale: Locale) {

    private var isReady = false

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            configureLanguage()
        }
        // No-op on failure -- isReady stays false, speak() below silently does
        // nothing rather than crashing. Good enough for now; worth surfacing
        // to the user (e.g. "no Hindi voice installed on this device") if
        // this turns out to matter in practice.
    }

    private fun configureLanguage() {
        val result = tts.setLanguage(locale)
        isReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
    }

    /** Speaks [text] immediately, interrupting anything already being spoken. */
    fun speak(text: String) {
        if (!isReady || text.isBlank()) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bot_lisa_translation")
    }

    /** Call when the owning screen (or language setting) goes away to free the TTS engine. */
    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
