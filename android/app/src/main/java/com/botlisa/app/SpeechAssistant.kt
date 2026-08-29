package com.botlisa.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Hands-free "Lisa Assistant" listening loop -- the production version of
 * speech_lab/trigger_flow.py's state machine, built on Android's built-in
 * SpeechRecognizer instead of the external Whisper/SpeechBrain models the
 * Python prototype used. See TriggerPhraseDetector.kt / trigger_phrase.py's
 * docstring for why a fixed trigger phrase replaces statistical language
 * ID.
 *
 * States:
 *   DEFAULT      -- listen in [getDefaultLanguageCode] (the target
 *                   language, Hindi by default). Every transcript is
 *                   checked against two independently
 *                   configured trigger phrases (see TriggerPhraseConfig.kt)
 *                   before being treated as an ordinary utterance:
 *                     - matches the *translate* trigger -> switch to
 *                       LISTENING_FOR_WORD instead of treating this as a
 *                       normal utterance.
 *                     - matches the *next-suggestion* trigger -> fire
 *                       [onNextSuggestionRequested] (read the next
 *                       suggested phrase aloud) and keep listening in
 *                       DEFAULT -- this doesn't change state, it's a
 *                       one-shot command.
 *                     - matches neither -> hand the transcript to
 *                       [onUtterance] as a normal default-language
 *                       utterance (expand-mode territory).
 *   LISTENING_FOR_WORD -- listen in [translateLanguageCode] (English) for
 *                   the *next* utterance -- the word/phrase to translate --
 *                   report it via [onUtterance], then switch back to
 *                   DEFAULT.
 *
 * Utterances handed to [onUtterance] are passed exactly as heard, in
 * whichever language that state was listening in -- the caller just feeds
 * them into the existing /assist pipeline (MainActivity's onSend()), which
 * already auto-detects translate-vs-expand mode from the text itself. This
 * class's job is only getting the STT locale right *before* transcription
 * (the original bug this whole feature line started from) and recognizing
 * the two fixed command phrases -- it doesn't know or care what "reading
 * the next suggestion" or "translating a word" actually involves.
 *
 * Must be constructed, started, and stopped from the main thread --
 * SpeechRecognizer requires it. Re-arms itself (calls startListening again)
 * after every result or recoverable error, so it keeps listening
 * continuously until [stop] is called; a fatal error (e.g. no recognizer on
 * this device, or a permission problem the caller didn't head off) reports
 * itself once via [onError] and leaves the assistant idle rather than
 * looping forever on a state it can't recover from.
 */
class SpeechAssistant(
    private val context: Context,
    /**
     * BCP-47 locale to listen in for ordinary utterances -- read fresh each
     * time so it tracks the selected target language (Hindi by default).
     */
    private val getDefaultLanguageCode: () -> String,
    private val translateLanguageCode: String,
    private val getTranslateTriggerPhrase: () -> String,
    private val getNextSuggestionTriggerPhrase: () -> String,
    private val onUtterance: (text: String, state: State) -> Unit,
    private val onNextSuggestionRequested: () -> Unit,
    /**
     * Every transcript this recogniser produces -- partials while the
     * caregiver is still speaking, and finals -- including the command
     * phrases that [onUtterance] never sees. Purely for showing what's being
     * heard in the UI; does not affect the state machine.
     */
    private val onTranscript: (String) -> Unit = {},
    /**
     * While this returns true (the app's own TextToSpeech is playing a
     * translation or a suggestion), recognised audio is discarded -- the mic
     * would otherwise transcribe the assistant's own voice and treat a
     * spoken suggestion as a new user utterance.
     */
    private val isMuted: () -> Boolean = { false },
    private val onStateChanged: (State) -> Unit,
    private val onError: (String) -> Unit,
) {
    enum class State { IDLE, LISTENING_DEFAULT, LISTENING_FOR_WORD }

    private var recognizer: SpeechRecognizer? = null
    private var stoppedByUser = true

    var state: State = State.IDLE
        private set(value) {
            field = value
            onStateChanged(value)
        }

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition isn't available on this device.")
            return
        }
        stoppedByUser = false
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
        }
        state = State.LISTENING_DEFAULT
        listenOnce(getDefaultLanguageCode())
    }

    fun stop() {
        stoppedByUser = true
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
        state = State.IDLE
    }

    private fun listenOnce(languageCode: String) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
            // Partials feed onTranscript (live "what's being heard" UI). The
            // state machine still only acts on the final result.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        runCatching { recognizer?.startListening(intent) }
            .onFailure { onError("Couldn't start listening: ${it.message}") }
    }

    private fun rearm() {
        if (stoppedByUser) return
        val languageCode = if (state == State.LISTENING_FOR_WORD) translateLanguageCode else getDefaultLanguageCode()
        listenOnce(languageCode)
    }

    private fun handleTranscript(transcript: String) {
        if (stoppedByUser) return
        when (state) {
            State.LISTENING_DEFAULT -> {
                when {
                    transcript.isNotBlank() && TriggerPhraseDetector.matches(transcript, getTranslateTriggerPhrase()) -> {
                        state = State.LISTENING_FOR_WORD
                        listenOnce(translateLanguageCode)
                    }
                    transcript.isNotBlank() && TriggerPhraseDetector.matches(transcript, getNextSuggestionTriggerPhrase()) -> {
                        onNextSuggestionRequested()
                        rearm()
                    }
                    else -> {
                        if (transcript.isNotBlank()) onUtterance(transcript, State.LISTENING_DEFAULT)
                        rearm()
                    }
                }
            }
            State.LISTENING_FOR_WORD -> {
                if (transcript.isNotBlank()) onUtterance(transcript, State.LISTENING_FOR_WORD)
                state = State.LISTENING_DEFAULT
                rearm()
            }
            State.IDLE -> Unit
        }
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle) {
            // Drop anything captured while our own TTS is playing -- otherwise
            // a spoken suggestion gets transcribed and re-sent as a query.
            if (isMuted()) {
                rearm()
                return
            }
            val transcript = results
                .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (transcript.isNotBlank()) onTranscript(transcript)
            handleTranscript(transcript)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (isMuted()) return
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (partial.isNotBlank()) onTranscript(partial)
        }

        override fun onError(error: Int) {
            if (stoppedByUser) return
            when (error) {
                // No speech heard, or nothing recognizable -- the built-in
                // recognizer's equivalent of speech_lab's silence gate.
                // Don't change state, just keep listening.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> rearm()
                else -> {
                    onError("Lisa Assistant stopped listening (error code $error). Tap to restart.")
                    stoppedByUser = true
                    state = State.IDLE
                }
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
