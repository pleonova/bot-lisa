package com.botlisa.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Speaks a phrase aloud via Android's built-in TextToSpeech engine -- "read
 * it back to me" for the one-earbud-in use case. Output follows whatever
 * audio route is currently active.
 *
 * [locale] is required and comes from the target-language setting
 * (LanguageConfig.kt); MainActivity recreates this whenever that changes.
 *
 * [onSpeakingChanged] reports playback start/stop on the main thread, so the
 * UI can pulse the speaker icon while audio is actually playing.
 *
 * Tied to the Activity/Composable lifecycle; a fresh instance on rotation or
 * a language change is normal and cheap.
 */
class TranslationSpeaker(
    context: Context,
    private val locale: Locale,
    private val onSpeakingChanged: (Boolean) -> Unit = {},
) {

    private var isReady = false
    private var utteranceCount = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    private val progressListener = object : UtteranceProgressListener() {
        private fun stopped() { mainHandler.post { onSpeakingChanged(false) } }
        override fun onStart(utteranceId: String?) { mainHandler.post { onSpeakingChanged(true) } }
        override fun onDone(utteranceId: String?) = stopped()

        @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId, errorCode)"))
        override fun onError(utteranceId: String?) = stopped()
        override fun onStop(utteranceId: String?, interrupted: Boolean) = stopped()
    }

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) configureOnReady()
        // No-op on failure -- isReady stays false, speak() silently does nothing.
    }

    private fun configureOnReady() {
        val result = tts.setLanguage(locale)
        isReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        tts.setOnUtteranceProgressListener(progressListener)
    }

    /** Speaks [text] immediately, interrupting anything already being spoken. */
    fun speak(text: String) {
        if (!isReady || text.isBlank()) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bot_lisa_${utteranceCount++}")
    }

    /** Call when the owning screen (or language setting) goes away to free the TTS engine. */
    fun shutdown() {
        onSpeakingChanged(false)
        tts.stop()
        tts.shutdown()
    }
}
