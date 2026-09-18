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

    // Tracks what's currently playing so a second speak() call for the exact
    // same text can be treated as "stop" instead of restarting it -- see
    // speak() below. Both read from the main thread (speak()) and written
    // from whatever thread the TTS engine calls the listener back on; a
    // stray race just means one tap reads a stale value, which at worst
    // restarts instead of stopping (or vice versa) -- not worth locking for.
    private var isSpeaking = false
    private var currentText: String? = null

    private val progressListener = object : UtteranceProgressListener() {
        private fun stopped() { isSpeaking = false; mainHandler.post { onSpeakingChanged(false) } }
        override fun onStart(utteranceId: String?) { isSpeaking = true; mainHandler.post { onSpeakingChanged(true) } }
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

    /**
     * Speaks [text] immediately, interrupting anything already being spoken
     * -- unless [text] is the exact thing already playing, in which case
     * this is a stop instead (tap-to-speak, tap-again-to-stop). Returns true
     * if playback was actually (re)started, false if nothing is playing now
     * (not ready, blank text, or this call just stopped it) -- callers use
     * this to avoid leaving UI stuck "playing".
     */
    fun speak(text: String): Boolean {
        if (!isReady || text.isBlank()) return false
        if (isSpeaking && text == currentText) {
            stop()
            return false
        }
        currentText = text
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bot_lisa_${utteranceCount++}")
        return true
    }

    /** Stops whatever's currently playing, if anything. */
    fun stop() {
        tts.stop()
    }

    /** Call when the owning screen (or language setting) goes away to free the TTS engine. */
    fun shutdown() {
        onSpeakingChanged(false)
        tts.stop()
        tts.shutdown()
    }
}
