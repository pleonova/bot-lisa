package com.botlisa.app

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * looping forever on a state it can't recover from. It also stops itself
 * (via [onError]) after three minutes with no speech detected at all -- see
 * the silence watchdog below -- so hands-free mode doesn't listen to an
 * empty room indefinitely.
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
    private val getMeaningTriggerPhrase: () -> String,
    private val getNextSuggestionTriggerPhrase: () -> String,
    private val getAnswerTriggerPhrase: () -> String,
    private val onUtterance: (text: String, state: State) -> Unit,
    private val onMeaningRequested: () -> Unit,
    private val onNextSuggestionRequested: () -> Unit,
    private val onAnswerRequested: () -> Unit,
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

    companion object {
        // Hands-free is meant to run indefinitely, but there's no point
        // keeping the mic (and, with it, the foreground service + wake lock
        // in ListeningForegroundService.kt) alive if nobody's said anything
        // at all in a long while -- auto-stop rather than drain battery
        // listening to an empty room.
        private const val SILENCE_TIMEOUT_MS = 3 * 60 * 1000L
    }

    private var recognizer: SpeechRecognizer? = null
    private var stoppedByUser = true
    // True from the moment a *partial* matches the translate trigger until
    // that recognition session ends (naturally or nudged) -- at which point
    // onResults/onError re-arm the mic in English. No recogniser surgery in
    // this window; we just wait for the current session to finish cleanly.
    private var switchingToWord = false
    // Recoverable errors in a row with no successful recognition between --
    // bail out (visible "tap to restart") rather than retry-loop forever.
    private var consecutiveErrors = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null

    // Fires if resetSilenceTimeout() isn't called again within
    // SILENCE_TIMEOUT_MS -- i.e. ten minutes pass with no speech (not even
    // an unrecognised partial) detected at all.
    private val silenceWatchdog = Runnable {
        if (!stoppedByUser) {
            stop()
            onError("Lisa Assistant stopped listening after 10 minutes of silence.")
        }
    }

    // Called on start() and every time actual speech is heard (a non-blank
    // partial or final transcript) -- NOT on NO_MATCH/SPEECH_TIMEOUT, which
    // is what "silence" means here.
    private fun resetSilenceTimeout() {
        mainHandler.removeCallbacks(silenceWatchdog)
        mainHandler.postDelayed(silenceWatchdog, SILENCE_TIMEOUT_MS)
    }

    private fun newRecognizer(): SpeechRecognizer =
        SpeechRecognizer.createSpeechRecognizer(context).apply { setRecognitionListener(listener) }

    private fun beep() {
        runCatching {
            val t = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 80).also { tone = it }
            t.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        }
    }

    // Called once the trigger session has ended: open the English mic after a
    // short gap so the beep isn't transcribed.
    private fun listenForWord() {
        mainHandler.postDelayed({
            if (!stoppedByUser && state == State.LISTENING_FOR_WORD) listenOnce(translateLanguageCode)
        }, 150)
    }

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
        switchingToWord = false
        consecutiveErrors = 0
        recognizer?.destroy()
        recognizer = newRecognizer()
        state = State.LISTENING_DEFAULT
        resetSilenceTimeout()
        listenOnce(getDefaultLanguageCode())
    }

    fun stop() {
        stoppedByUser = true
        switchingToWord = false
        mainHandler.removeCallbacksAndMessages(null)
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
        tone?.release()
        tone = null
        state = State.IDLE
    }

    private fun listenOnce(languageCode: String) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
            // Partials feed onTranscript, and the translate trigger is acted
            // on straight from a partial (onPartialResults).
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Finalise soon after the speaker stops so the whole loop stays
            // snappy. (Hints -- honoured by the modern Google recogniser,
            // harmless where ignored.)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 450L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 250L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 200L)
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
                        // Trigger caught on the final result (partials didn't
                        // fire). Session already ended -- beep, then open the
                        // English mic.
                        switchingToWord = false
                        state = State.LISTENING_FOR_WORD
                        beep()
                        listenForWord()
                    }
                    transcript.isNotBlank() && TriggerPhraseDetector.matches(transcript, getMeaningTriggerPhrase()) -> {
                        onMeaningRequested()
                        rearm()
                    }
                    transcript.isNotBlank() && TriggerPhraseDetector.matches(transcript, getNextSuggestionTriggerPhrase()) -> {
                        onNextSuggestionRequested()
                        rearm()
                    }
                    transcript.isNotBlank() && TriggerPhraseDetector.matches(transcript, getAnswerTriggerPhrase()) -> {
                        onAnswerRequested()
                        rearm()
                    }
                    else -> {
                        if (transcript.isNotBlank()) onUtterance(transcript, State.LISTENING_DEFAULT)
                        rearm()
                    }
                }
            }
            State.LISTENING_FOR_WORD -> {
                // A late final of the trigger phrase itself -- we already
                // fast-switched on a partial and the English mic is opening
                // via beepThenListenForWord(). Ignore it.
                if (TriggerPhraseDetector.matches(transcript, getTranslateTriggerPhrase())) return
                if (transcript.isNotBlank()) onUtterance(transcript, State.LISTENING_FOR_WORD)
                state = State.LISTENING_DEFAULT
                rearm()
            }
            State.IDLE -> Unit
        }
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle) {
            val transcript = results
                .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (transcript.isNotBlank()) resetSilenceTimeout()
            // A partial already matched the translate trigger; this is that
            // session ending. Show it, then open the English mic.
            if (switchingToWord) {
                switchingToWord = false
                if (transcript.isNotBlank()) onTranscript(transcript)
                listenForWord()
                return
            }
            // Drop anything captured while our own TTS is playing -- otherwise
            // a spoken suggestion gets transcribed and re-sent as a query.
            if (isMuted()) {
                rearm()
                return
            }
            consecutiveErrors = 0
            if (transcript.isNotBlank()) onTranscript(transcript)
            handleTranscript(transcript)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (isMuted()) return
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (partial.isBlank()) return
            resetSilenceTimeout()
            onTranscript(partial)
            // Fast path: beep + switch to English-word mode the moment a
            // partial already contains the translate trigger. We do NOT touch
            // the recogniser -- the session ends on its own (short silence
            // timeout) or we nudge it with stopListening() after a beat;
            // either way onResults/onError then re-arms in English.
            // Partials are rougher than final transcripts, so the default
            // 0.75 fuzzy threshold often isn't cleared until the recognizer
            // finalizes -- which means waiting on a silence timeout + full
            // round trip before the beep fires. Loosen the bar here so the
            // beep fires as early as possible; a stray false-positive just
            // means an unwanted (recoverable) switch into LISTENING_FOR_WORD,
            // which is an acceptable trade for a snappy trigger.
            if (!stoppedByUser && !switchingToWord && state == State.LISTENING_DEFAULT &&
                TriggerPhraseDetector.matches(partial, getTranslateTriggerPhrase(), threshold = 0.6)
            ) {
                switchingToWord = true
                state = State.LISTENING_FOR_WORD
                beep()
                mainHandler.postDelayed({
                    if (switchingToWord && !stoppedByUser) {
                        runCatching { recognizer?.stopListening() }
                    }
                }, 600)
            }
        }

        override fun onError(error: Int) {
            if (stoppedByUser) return
            // The trigger session ended with an error instead of a result --
            // still fine, just open the English mic.
            if (switchingToWord) {
                switchingToWord = false
                listenForWord()
                return
            }
            when (error) {
                // Just means nobody said anything recognisable this session --
                // the expected steady state while hands-free sits waiting for
                // the trigger phrase (each session only waits ~450ms of
                // silence before timing out, so this fires constantly during
                // normal idle listening). Never counts toward the give-up
                // threshold below -- keep listening indefinitely.
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                -> {
                    mainHandler.postDelayed({ if (!stoppedByUser) rearm() }, 200)
                }
                // Transient recogniser/network hiccups -- keep listening
                // rather than dying, unless it keeps happening with nothing
                // recognised in between, which (unlike the no-match case
                // above) suggests something's actually wrong with the
                // recogniser/service. Google's on-device recognizer briefly
                // drops its connection to the cloud backend fairly often
                // (SERVER_DISCONNECTED/NETWORK on a weak signal), so back off
                // exponentially instead of a flat 200ms -- a real blip needs
                // more than ~1s of total grace to clear.
                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                -> {
                    consecutiveErrors++
                    if (consecutiveErrors >= 6) {
                        consecutiveErrors = 0
                        onError("Lisa Assistant stopped listening (error code $error). Tap to restart.")
                        stoppedByUser = true
                        state = State.IDLE
                    } else {
                        val delayMs = (200L shl (consecutiveErrors - 1)).coerceAtMost(3000L)
                        mainHandler.postDelayed({ if (!stoppedByUser) rearm() }, delayMs)
                    }
                }
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
