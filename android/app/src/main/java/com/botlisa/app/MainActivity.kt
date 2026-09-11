package com.botlisa.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Single-screen caregiver-assist front end for bot-lisa.
 *
 * One text box, one behavior, chosen by the script of what you type:
 * - Type/dictate an English (Latin-script) word or phrase -> on-device
 *   ML Kit translates it into the selected target language (Russian by
 *   default). No backend call -- faster and works offline. The server is
 *   only used as a backup if the on-device model isn't downloaded yet.
 * - Type/dictate a Russian (Cyrillic) phrase -> the server's "expand" mode
 *   returns related phrases from the curated library. Russian-only for now
 *   (curated content + Cyrillic-based detection); for any other target
 *   language the next-suggestion command and its trigger are hidden.
 *
 * Networking config lives in ApiClient.kt / ServerConfig.kt. Server URL
 * defaults to http://10.0.2.2:8002 (orchestration-service, where /assist
 * lives), editable in-app via "Server settings" -- no rebuild needed to
 * switch between emulator, a real device on your LAN, or the deployed
 * cluster. The API key field is only required once pointed at a backend
 * that sets ORCHESTRATION_API_KEY (the deployed cluster does; local dev by
 * default does not).
 *
 * "Lisa Assistant" (below) is the hands-free mode: tap to start continuous
 * listening in the target language (Russian by default). Two independently
 * editable, per-language trigger phrases (Settings -> "Trigger phrase
 * (translate)" / "Trigger phrase (next suggestion)") drive it:
 *   - say the translate trigger (Russian "как сказать?" by default), pause,
 *     then an English word -- that word is sent to /assist as translate-mode.
 *   - say the next-suggestion trigger (Russian "что ещё?"; Russian target
 *     only) to have the app read the next related/suggested phrase from the
 *     most recent lookup aloud; say it again to hear the next in that list.
 *   - anything else defaults to a normal target-language utterance, sent to
 *     /assist as expand-mode, same as typing it in.
 * See SpeechAssistant.kt for the listening state machine and
 * TriggerPhraseDetector.kt for the fuzzy phrase match -- both are ports of
 * the speech_lab/ Python prototype, using Android's built-in
 * SpeechRecognizer instead of an external model.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = androidx.compose.ui.platform.LocalContext.current
            // null override = follow the system setting; the Settings toggle
            // flips it to an explicit true/false (persisted in ThemeConfig).
            var darkOverride by remember { mutableStateOf(ThemeConfig.getDarkOverride(context)) }
            val useDark = darkOverride ?: isSystemInDarkTheme()
            BotLisaTheme(useDarkTheme = useDark) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LisaScreen(
                        isDark = useDark,
                        onToggleDark = {
                            val next = !useDark
                            darkOverride = next
                            ThemeConfig.setDarkOverride(context, next)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Coarse UI phase driving the header subtitle (and, from a later step, the
 * central mic/speaker button's colour). SPEAKING_TRANSLATION /
 * READING_RECOMMENDATION are not reachable yet -- they arrive once
 * TranslationSpeaker reports playback state -- but are listed here so the
 * consumers don't need reshaping when that lands.
 */
enum class UiPhase { IDLE, LISTENING_RU, LISTENING_EN, SPEAKING_TRANSLATION, READING_RECOMMENDATION }

private fun uiPhaseOf(assistantState: SpeechAssistant.State): UiPhase = when (assistantState) {
    SpeechAssistant.State.IDLE -> UiPhase.IDLE
    SpeechAssistant.State.LISTENING_DEFAULT -> UiPhase.LISTENING_RU
    SpeechAssistant.State.LISTENING_FOR_WORD -> UiPhase.LISTENING_EN
}

// Kept short -- these render in the handwritten hint beside the mic.
private fun UiPhase.subtitle(): String = when (this) {
    UiPhase.IDLE -> "Tap for hands-free mode"
    UiPhase.LISTENING_RU -> "Listening…"
    UiPhase.LISTENING_EN -> "Now say the English word"
    UiPhase.SPEAKING_TRANSLATION -> "Playing the translation…"
    UiPhase.READING_RECOMMENDATION -> "Playing the suggestion…"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LisaScreen(
    isDark: Boolean = false,
    onToggleDark: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()

    // rememberSaveable (not plain remember) for anything the user would be upset to lose on
    // an Activity recreation -- most commonly a screen rotation. Plain `remember` state is
    // wiped when the Activity is destroyed and recreated, which is what was happening here:
    // rotating the phone reset the whole screen back to its empty starting state. isLoading
    // is deliberately left as plain `remember`: if a request was in flight during rotation,
    // its coroutine (scoped to this composition) is gone either way, so persisting `true`
    // would leave the button stuck disabled forever with nothing left to ever set it false.
    var input by rememberSaveable { mutableStateOf("") }
    // True while the caregiver has the shared field focused for typing -- the
    // live transcript must not overwrite text they're mid-edit on. See
    // handleTranscript below.
    var inputFocused by remember { mutableStateOf(false) }
    // Small-print English translation of the current transcript, shown under
    // the field. Null unless hands-free is actively listening (see the
    // LaunchedEffect further down).
    var transcriptGloss by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by rememberSaveable { mutableStateOf<String?>(null) }
    var result by rememberSaveable { mutableStateOf<AssistResult?>(null) }

    // Which related/suggested phrase the next-suggestion trigger should
    // speak. Reset to 0 every time a fresh /assist result comes back, so
    // cycling always starts from the top of the newest suggestion list;
    // wraps around (see speakNextSuggestion()) once it runs past the end.
    var suggestionIndex by rememberSaveable { mutableStateOf(0) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var serverUrl by remember { mutableStateOf(ServerConfig.getBaseUrl(context)) }
    var apiKey by remember { mutableStateOf(ServerConfig.getApiKey(context)) }
    var showServerSettings by rememberSaveable { mutableStateOf(false) }
    var showInstructions by rememberSaveable { mutableStateOf(false) }

    // Command-chip reminders (§3.6): hidden in hands-free once a command is
    // used (spoken trigger), back on the next new input.
    var commandsDismissed by remember { mutableStateOf(false) }

    // Target language: drives translation, the spoken voice, the hands-free
    // STT locale, and the trigger phrases. Everything downstream reads from
    // this so switching language updates the whole screen.
    var targetLanguage by remember { mutableStateOf(LanguageConfig.getTargetLanguage(context)) }
    var languageMenuExpanded by remember { mutableStateOf(false) }

    // The curated phrase library + the backend's "expand" mode are
    // Russian-only: that's what powers a typed Russian phrase -> related
    // phrases, and the "how to answer?" lookup.
    val curatedRelatedSupported = targetLanguage.code == SupportedLanguages.RUSSIAN.code

    // "What else?" now works for any target language: PromptComposer carries
    // a per-language few-shot seed, so on-device generation produces
    // suggestions in whatever language is selected. Gated only on the device
    // being able to run the model at all and the user not having forced
    // library-only mode (same "hide, don't disable" rule the Settings block
    // below uses). Russian still gets it via curatedRelatedSupported even
    // when the model isn't available, falling back to the library.
    val aiWhatElseSupported = OnDeviceLlm.isDeviceCapable(context) &&
        OnDeviceLlmConfig.getWhatElseSource(context) != OnDeviceLlmConfig.WhatElseSource.LIBRARY_ONLY
    val nextSuggestionSupported = curatedRelatedSupported || aiWhatElseSupported

    // Lisa Assistant's voice-command trigger phrases -- editable in Settings,
    // persisted per target-language via TriggerPhraseConfig.kt. Keyed on
    // `targetLanguage` so switching language swaps in that language's phrases.
    var translateTriggerPhrase by remember(targetLanguage) {
        mutableStateOf(TriggerPhraseConfig.getTranslateTriggerPhrase(context, targetLanguage.code))
    }
    fun onTranslateTriggerPhraseChange(newPhrase: String) {
        translateTriggerPhrase = newPhrase
        TriggerPhraseConfig.setTranslateTriggerPhrase(context, targetLanguage.code, newPhrase)
    }
    var meaningTriggerPhrase by remember(targetLanguage) {
        mutableStateOf(TriggerPhraseConfig.getMeaningTriggerPhrase(context, targetLanguage.code))
    }
    fun onMeaningTriggerPhraseChange(newPhrase: String) {
        meaningTriggerPhrase = newPhrase
        TriggerPhraseConfig.setMeaningTriggerPhrase(context, targetLanguage.code, newPhrase)
    }
    var nextSuggestionTriggerPhrase by remember(targetLanguage) {
        mutableStateOf(TriggerPhraseConfig.getNextSuggestionTriggerPhrase(context, targetLanguage.code))
    }
    fun onNextSuggestionTriggerPhraseChange(newPhrase: String) {
        nextSuggestionTriggerPhrase = newPhrase
        TriggerPhraseConfig.setNextSuggestionTriggerPhrase(context, targetLanguage.code, newPhrase)
    }
    var answerTriggerPhrase by remember(targetLanguage) {
        mutableStateOf(TriggerPhraseConfig.getAnswerTriggerPhrase(context, targetLanguage.code))
    }
    fun onAnswerTriggerPhraseChange(newPhrase: String) {
        answerTriggerPhrase = newPhrase
        TriggerPhraseConfig.setAnswerTriggerPhrase(context, targetLanguage.code, newPhrase)
    }

    // The last ordinary target-language utterance heard hands-free -- what
    // the "what does that mean?" / "how to answer?" commands act on.
    var lastUtterance by remember { mutableStateOf("") }

    // On-device "что ещё" suggestions for lastUtterance, prefetched as soon
    // as it's heard (see the LaunchedEffect below) so there's no dead air
    // when the next-suggestion trigger actually fires. Null means "not
    // fetched / not available this time" (device ineligible, model not
    // ready, generation failed, or LIBRARY_ONLY mode skipped it entirely) --
    // distinct from an empty list, which would mean the model genuinely
    // produced zero phrases. See ON_DEVICE_LLM_PLAN.md Phase 7.
    var onDeviceRelated by remember { mutableStateOf<List<Phrase>?>(null) }

    // TTS playback state. Drives the speaker-icon pulse in the result card,
    // and -- crucially -- mutes the mic (isMuted below) so the assistant
    // reading a suggestion aloud isn't transcribed back as a user utterance.
    var translationSpeaking by remember { mutableStateOf(false) }
    var relatedSpeaking by remember { mutableStateOf(false) }
    var englishSpeaking by remember { mutableStateOf(false) }
    // Which related-phrase row is currently being read (null = none). Cleared
    // by phraseSpeaker's onSpeakingChanged when playback ends.
    var speakingIndex by remember { mutableStateOf<Int?>(null) }

    // Speaks translation results aloud -- see TranslationSpeaker.kt. Rebuilt
    // whenever the target language changes (or on rotation, like any other
    // `remember`); the previous instance is shut down first so the TTS
    // engine doesn't leak.
    var speaker by remember { mutableStateOf<TranslationSpeaker?>(null) }
    DisposableEffect(targetLanguage) {
        val current = TranslationSpeaker(
            context,
            targetLanguage.ttsLocale,
            onSpeakingChanged = { translationSpeaking = it },
        )
        speaker = current
        onDispose { current.shutdown() }
    }

    // Speaker for reading related/suggested phrases aloud. Follows the
    // selected target language: the Russian curated library speaks Russian
    // (target == Russian in that case anyway), and on-device "what else?"
    // suggestions are in whatever language is selected. Rebuilt on language
    // change like `speaker` above.
    var phraseSpeaker by remember { mutableStateOf<TranslationSpeaker?>(null) }
    DisposableEffect(targetLanguage) {
        val current = TranslationSpeaker(
            context,
            targetLanguage.ttsLocale,
            onSpeakingChanged = { speaking ->
                relatedSpeaking = speaking
                if (!speaking) speakingIndex = null
            },
        )
        phraseSpeaker = current
        onDispose { current.shutdown() }
    }

    // English-locale speaker for the "what does that mean?" command, which
    // reads an English translation of the previous utterance aloud.
    var englishSpeaker by remember { mutableStateOf<TranslationSpeaker?>(null) }
    DisposableEffect(Unit) {
        val current = TranslationSpeaker(
            context,
            java.util.Locale.US,
            onSpeakingChanged = { englishSpeaking = it },
        )
        englishSpeaker = current
        onDispose { current.shutdown() }
    }

    fun onServerUrlChange(newUrl: String) {
        serverUrl = newUrl
        ServerConfig.setBaseUrl(context, newUrl)
    }

    fun onApiKeyChange(newKey: String) {
        apiKey = newKey
        ServerConfig.setApiKey(context, newKey)
    }

    fun onSend() {
        if (input.isBlank()) return
        errorText = null
        // Drop the previous card straight away so a new lookup (typed or
        // spoken) doesn't sit under a stale result until the response lands.
        result = null
        isLoading = true
        scope.launch {
            try {
                // Translate mode -- English (Latin-script) word/phrase in,
                // selected target language out -- needs no backend, and
                // on-device ML Kit is faster and works offline, so do it
                // straight away and skip the server round-trip entirely.
                // Only non-Latin input (Cyrillic = Russian "expand" mode,
                // which genuinely needs the curated library) falls through
                // to the server below. See ON_DEVICE_LLM_PLAN / roadmap:
                // on-device translation is textbook phrasing, not the
                // curated baby-register tone.
                val looksLikeExpand = input.any { it.isLetter() && it.code > 0x024F }
                if (!looksLikeExpand) {
                    val translated = try {
                        OnDeviceTranslator.translate(input, targetLanguage)
                    } catch (_: Exception) {
                        // Model not downloaded yet (first use needs wifi), or
                        // an ML Kit failure -- fall through to the server as a
                        // backup rather than dead-ending. If that's also
                        // unreachable the catch blocks below report it.
                        null
                    }
                    if (translated != null) {
                        val res = AssistResult(
                            mode = "translate",
                            source = "on_device",
                            input = input,
                            translation = Phrase(ru = translated, glossEn = input),
                            related = emptyList(),
                            latencyMs = 0.0,
                        )
                        res.translation?.let { speaker?.speak(it.ru) }
                        result = res
                        suggestionIndex = 0
                        isLoading = false
                        return@launch
                    }
                }

                var assist = ApiClient.sendAssist(baseUrl = serverUrl, apiKey = apiKey, text = input)
                if (assist.mode == "translate") {
                    // Backend translate result (the on-device path above was
                    // skipped for non-Latin input, or its model wasn't
                    // ready). Its curated-phrase match only checks the
                    // Russian library, so for any other target language fall
                    // back to on-device; likewise when the backend had no
                    // match at all (source == "mock").
                    val needsOnDevice = assist.source == "mock" ||
                        targetLanguage.code != SupportedLanguages.RUSSIAN.code
                    if (needsOnDevice) {
                        try {
                            val translated = OnDeviceTranslator.translate(input, targetLanguage)
                            assist = assist.copy(
                                source = "on_device",
                                translation = Phrase(ru = translated, glossEn = input),
                            )
                        } catch (e: Exception) {
                            errorText = "On-device translation failed (${e.message}) -- showing the placeholder instead. First use needs wifi to download the translation model."
                        }
                    }
                    // Read the translation back aloud -- the "one earbud in, talking
                    // to the kid" use case. Whatever won above gets spoken.
                    assist.translation?.let { speaker?.speak(it.ru) }
                }
                result = assist
                // A fresh lookup means a fresh suggestion list -- next-suggestion
                // cycling (see speakNextSuggestion()) should start from the top.
                suggestionIndex = 0
            } catch (e: IOException) {
                // Server unreachable (wrong URL, backend down, phone off the
                // dev LAN). The translate path -- English in -> selected
                // language out -- needs no backend at all, so fall back to
                // on-device ML Kit rather than blocking translation outright.
                // Only attempt it when the input is Latin-script: non-Latin
                // input means the caregiver is typing in the target language
                // ("expand" mode), which genuinely needs the server.
                val hasNonLatinLetters = input.any { it.isLetter() && it.code > 0x024F }
                val offline = if (!hasNonLatinLetters) {
                    try {
                        val translated = OnDeviceTranslator.translate(input, targetLanguage)
                        AssistResult(
                            mode = "translate",
                            source = "on_device",
                            input = input,
                            translation = Phrase(ru = translated, glossEn = input),
                            related = emptyList(),
                            latencyMs = 0.0,
                        )
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
                // 10.0.2.2 is the *emulator's* alias for the host machine; on
                // a physical device it routes nowhere, so call that out
                // explicitly -- it's the most common misconfiguration here.
                val emulatorUrlHint = if ("10.0.2.2" in serverUrl) {
                    " Server URL is set to the emulator address 10.0.2.2 -- on a physical device set it to http://<mac-lan-ip>:8002 in Settings."
                } else {
                    ""
                }
                if (offline != null) {
                    offline.translation?.let { speaker?.speak(it.ru) }
                    result = offline
                    suggestionIndex = 0
                    errorText = "Server unreachable -- translated on-device instead.$emulatorUrlHint"
                } else {
                    errorText = "Couldn't reach the server: ${e.message}.$emulatorUrlHint"
                }
            } catch (e: Exception) {
                errorText = "Something went wrong: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    // The single source of truth for which related-phrase list is currently
    // "showing" -- read by speakNextSuggestion(), speakRelated(), and the
    // result-card rendering below, so what's spoken, what's labeled "AI" vs
    // "Library", and what's displayed can never drift out of sync with each
    // other. AI_ONLY never falls back to the library (an honest "nothing"
    // beats silently swapping in the library after the caregiver explicitly
    // chose AI-only); LIBRARY_ONLY never reads onDeviceRelated even if it
    // happens to be populated. See ON_DEVICE_LLM_PLAN.md Phase 7.
    //
    // BOTH treats an empty (but non-null) onDeviceRelated the same as "not
    // ready" and falls back to the library, not just a null one -- found via
    // a real device test where the on-device model's still-unresolved
    // thinking-mode issue (ON_DEVICE_LLM_PLAN.md Phase 4) produced zero
    // parsed phrases for a real utterance. Without this, a plain `?:` only
    // catches "AI hasn't answered yet", so a *bad* AI answer would silently
    // blank out an already-good library result the card was already
    // showing, rather than keeping it.
    // onDeviceRelated is only ever populated by the AI path, so a non-empty
    // one always means "showing AI".
    val usingAiSuggestions = !onDeviceRelated.isNullOrEmpty()
    // The phrase library (result.related) is Russian-only: for any other
    // target language there is nothing to fall back to, so the AI result --
    // or nothing -- is all there is, whatever the source setting says. Only
    // when the target actually is Russian does the BOTH / LIBRARY_ONLY
    // fallback to the library apply.
    val relatedForDisplay: List<Phrase> = when {
        !curatedRelatedSupported -> onDeviceRelated.orEmpty()
        OnDeviceLlmConfig.getWhatElseSource(context) == OnDeviceLlmConfig.WhatElseSource.AI_ONLY ->
            onDeviceRelated.orEmpty()
        OnDeviceLlmConfig.getWhatElseSource(context) == OnDeviceLlmConfig.WhatElseSource.LIBRARY_ONLY ->
            result?.related.orEmpty()
        else -> // BOTH
            if (usingAiSuggestions) onDeviceRelated.orEmpty() else result?.related.orEmpty()
    }

    // Speaks the next related/suggested phrase from relatedForDisplay aloud,
    // cycling through the list and wrapping back to the start once it runs
    // out. Triggered by the next-suggestion voice command (see
    // SpeechAssistant.kt) -- doesn't touch `input`/onSend() at all, since
    // this reads existing suggestions rather than making a new request.
    fun speakNextSuggestion() {
        val related = relatedForDisplay
        if (related.isEmpty()) return
        val index = suggestionIndex % related.size
        // Only claim the row / advance if TTS actually started, so a
        // not-ready engine doesn't leave the mic muted or a row stuck lit.
        if (phraseSpeaker?.speak(related[index].ru) == true) {
            speakingIndex = index
            suggestionIndex = index + 1
        }
    }

    // Reads one specific related phrase aloud -- the trailing speaker button
    // on a result row. Sets suggestionIndex so a following next-suggestion
    // trigger continues from the next one. Reads from relatedForDisplay (not
    // result.related directly) so the index lines up with whichever list is
    // actually rendered on screen right now.
    fun speakRelated(index: Int) {
        val phrase = relatedForDisplay.getOrNull(index) ?: return
        if (phraseSpeaker?.speak(phrase.ru) == true) {
            speakingIndex = index
            suggestionIndex = index + 1
        }
    }

    // Reads a trigger phrase aloud in the target-language voice -- tapping
    // a command chip or an instruction step. Drop a trailing "?" so TTS
    // doesn't over-emphasise it.
    fun speakTriggerPhrase(phrase: String) {
        speaker?.speak(phrase.trimEnd('?', ' '))
    }

    // "what does that mean?" -- translate the previous target-language
    // utterance into English and read it aloud (English voice).
    fun speakMeaningOfLast() {
        val text = lastUtterance.ifBlank { input }
        if (text.isBlank()) return
        scope.launch {
            val english = runCatching {
                OnDeviceTranslator.translateToEnglish(text, targetLanguage)
            }.getOrNull()
            if (!english.isNullOrBlank()) englishSpeaker?.speak(english)
        }
    }

    // "how to answer?" -- a fresh related-phrases lookup on the previous
    // utterance, so the result card shows things you could say back. Russian
    // only (same backend limitation as the next-suggestion command).
    fun requestAnswerSuggestions() {
        val text = lastUtterance.ifBlank { input }
        if (text.isBlank() || !curatedRelatedSupported) return
        input = text
        commandsDismissed = false
        onSend()
    }

    // Always-current handles onto onSend()/speakNextSuggestion() for Lisa
    // Assistant's callbacks below. SpeechAssistant is constructed once (via
    // `remember`) and holds onto whatever lambdas it's given at that first
    // composition; routing through rememberUpdatedState means those lambdas
    // always forward to the *latest* onSend()/speakNextSuggestion(), even
    // though both are fresh closures every recomposition. (input, result,
    // etc. are all backed by stable `remember`ed state objects too, so this
    // is belt-and-suspenders -- but it's the correct pattern for a
    // long-lived object holding a composable's callbacks, so we use it
    // here.)
    val handleAssistantUtterance =
        rememberUpdatedState<(String, SpeechAssistant.State) -> Unit> { text, state ->
            // Remember the last plain utterance for the meaning / answer
            // commands to act on.
            if (state == SpeechAssistant.State.LISTENING_DEFAULT && text.isNotBlank()) {
                lastUtterance = text
            }
            // The English word after the translate trigger always runs a
            // lookup. A plain default-mode utterance only does for Russian --
            // the backend "expand" mode has nothing to return for other
            // languages, and auto-translating a foreign phrase (+ speaking
            // it) just feeds the mic its own output in a loop. On-device
            // "what else?" for other languages runs off lastUtterance (set
            // above) via the prefetch effect below, not this lookup.
            if (state == SpeechAssistant.State.LISTENING_FOR_WORD || curatedRelatedSupported) {
                input = text
                commandsDismissed = false // fresh utterance -> chips come back
                onSend()
            }
        }
    val handleMeaningRequest = rememberUpdatedState { speakMeaningOfLast() }
    val handleAnswerRequest = rememberUpdatedState { requestAnswerSuggestions() }
    val handleNextSuggestionRequest = rememberUpdatedState {
        speakNextSuggestion()
        commandsDismissed = true // spoken "что ещё?" -> hide the chips
    }

    var assistantState by remember { mutableStateOf(SpeechAssistant.State.IDLE) }
    var assistantError by remember { mutableStateOf<String?>(null) }

    // Header subtitle + (later) central-button colour. derivedStateOf so more
    // inputs (speaking state, result mode) can fold in without changing callers.
    val uiPhase by remember { derivedStateOf { uiPhaseOf(assistantState) } }

    // The chips are hidden only while mid translate-command
    // (LISTENING_FOR_WORD, reached by the spoken "как сказать"). Every other
    // state -- IDLE, or LISTENING_DEFAULT -- clears the flag, so starting or
    // restarting hands-free always brings the reminders back even if a stale
    // word is sitting in the field from a previous session.
    LaunchedEffect(assistantState) {
        commandsDismissed = assistantState == SpeechAssistant.State.LISTENING_FOR_WORD
    }

    // SpeechAssistant can go IDLE on its own -- not just via stopHandsFree()
    // -- e.g. its ten-minute silence watchdog (SpeechAssistant.kt). Whatever
    // the cause, the foreground service (and the wake lock/notification that
    // come with it) has no reason to keep running once hands-free is IDLE.
    LaunchedEffect(assistantState) {
        if (assistantState == SpeechAssistant.State.IDLE) {
            ListeningForegroundService.stop(context)
        }
    }

    // Small-print English gloss of the transcript. Only while hands-free is
    // listening in the target language (not IDLE, not the English-word
    // phase), and debounced so partials don't hammer the translator. Fails
    // silently -- no gloss -- if the target->English model isn't available.
    LaunchedEffect(input, assistantState, targetLanguage) {
        val show = assistantState == SpeechAssistant.State.LISTENING_DEFAULT && input.isNotBlank()
        if (!show) {
            transcriptGloss = null
            return@LaunchedEffect
        }
        delay(350)
        transcriptGloss = runCatching {
            OnDeviceTranslator.translateToEnglish(input, targetLanguage)
        }.getOrNull()
    }

    // Prefetch on-device "what else?" suggestions as soon as a new utterance
    // is heard, rather than waiting for the next-suggestion trigger itself --
    // generation takes real time, and starting it only once the caregiver
    // has already asked would mean several seconds of dead air in a
    // hands-free flow nobody's looking at the screen for. Compose's
    // cancel-and-relaunch-on-key-change handles abandoning a stale
    // in-flight generation when a newer utterance arrives, same as the
    // transcriptGloss effect above. Runs for any target language (the
    // few-shot seed is per-language now); LIBRARY_ONLY mode and a
    // not-ready model skip the call -- no point spending battery/time on a
    // generation nothing will display. See ON_DEVICE_LLM_PLAN.md Phase 7.
    LaunchedEffect(lastUtterance, targetLanguage) {
        onDeviceRelated = null
        val source = OnDeviceLlmConfig.getWhatElseSource(context)
        if (lastUtterance.isNotBlank() &&
            source != OnDeviceLlmConfig.WhatElseSource.LIBRARY_ONLY &&
            OnDeviceLlm.canGenerate(context)
        ) {
            onDeviceRelated = runCatching {
                OnDeviceLlm.generateWhatElse(context, lastUtterance, targetLanguage.code)
            }.getOrNull()
        }
    }


    // Live transcript from Lisa Assistant -> the shared input/search field.
    // Only while hands-free is running, so it never clobbers something the
    // caregiver is typing. Covers partials and the command phrases too
    // (those never reach onUtterance).
    val handleTranscript = rememberUpdatedState<(String) -> Unit> { text ->
        if (assistantState != SpeechAssistant.State.IDLE && !inputFocused && text.isNotBlank()) {
            input = text
        }
    }

    val assistant = remember {
        SpeechAssistant(
            context = context,
            getDefaultLanguageCode = { targetLanguage.code },
            translateLanguageCode = "en-US",
            getTranslateTriggerPhrase = {
                TriggerPhraseConfig.getTranslateTriggerPhrase(context, targetLanguage.code)
            },
            getMeaningTriggerPhrase = {
                TriggerPhraseConfig.getMeaningTriggerPhrase(context, targetLanguage.code)
            },
            getNextSuggestionTriggerPhrase = {
                TriggerPhraseConfig.getNextSuggestionTriggerPhrase(context, targetLanguage.code)
            },
            getAnswerTriggerPhrase = {
                TriggerPhraseConfig.getAnswerTriggerPhrase(context, targetLanguage.code)
            },
            onUtterance = { text, state -> handleAssistantUtterance.value(text, state) },
            onMeaningRequested = { handleMeaningRequest.value() },
            onNextSuggestionRequested = { handleNextSuggestionRequest.value() },
            onAnswerRequested = { handleAnswerRequest.value() },
            onTranscript = { handleTranscript.value(it) },
            isMuted = { translationSpeaking || relatedSpeaking || englishSpeaking },
            onStateChanged = { assistantState = it },
            onError = { assistantError = it },
        )
    }
    // Hands-free needs a foreground service running alongside SpeechAssistant
    // -- since Android 9, a backgrounded process can't touch the microphone
    // at all without one. See ListeningForegroundService.kt.
    fun startHandsFree() {
        assistantError = null
        assistant.start()
        ListeningForegroundService.start(context)
    }
    fun stopHandsFree() {
        assistant.stop()
        ListeningForegroundService.stop(context)
    }

    DisposableEffect(Unit) {
        onDispose { stopHandsFree() }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* best-effort: hands-free works either way, this only affects whether
          the background-listening notification is visible */ }

    // Android 13+ requires this to show the foreground service's notification
    // -- requested best-effort alongside mic permission; denying it doesn't
    // block hands-free mode, the service and mic access still work.
    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val assistantMicPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            requestNotificationPermissionIfNeeded()
            startHandsFree()
        } else {
            assistantError = "Microphone permission is required for Lisa Assistant."
        }
    }

    fun onToggleAssistant() {
        if (assistantState != SpeechAssistant.State.IDLE) {
            stopHandsFree()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            requestNotificationPermissionIfNeeded()
            startHandsFree()
        } else {
            assistantMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Tapping the fox logo returns the screen to its opening state -- stops
    // hands-free, clears the field/result/errors and any expanded panels.
    // Persistent settings (server, language, trigger phrases) are left alone.
    fun resetToStart() {
        stopHandsFree()
        // Drop focus so the field isn't left selected -- a focused field
        // hides the "start typing" helper under the buttons.
        focusManager.clearFocus()
        input = ""
        result = null
        onDeviceRelated = null
        errorText = null
        assistantError = null
        suggestionIndex = 0
        commandsDismissed = false
        showInstructions = false
        showServerSettings = false
        languageMenuExpanded = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Image(
                    painter = painterResource(R.drawable.lisa_fox),
                    contentDescription = "Start over",
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable { resetToStart() },
                )
                IconButton(onClick = { showServerSettings = !showServerSettings }) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = if (showServerSettings) "Hide settings" else "Settings",
                    )
                }
            }
            Text(
                "Assistant Lisa",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Your foreign language companion",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        AssistantButton(
            phase = uiPhase,
            onClick = { onToggleAssistant() },
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(vertical = 8.dp),
        )
        Text(
            uiPhase.subtitle(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        assistantError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }

        if (showServerSettings) {
            // Material3's ExposedDropdownMenu flips above the field whenever
            // it doesn't measure enough room below (a 48dp margin plus the
            // menu's own height) -- capping the height alone doesn't stop
            // that. Anchor a plain Popup to the field's bottom edge instead,
            // which always opens downward regardless of available space.
            var languageFieldHeightPx by remember { mutableStateOf(0) }
            var languageFieldWidthPx by remember { mutableStateOf(0) }
            val density = LocalDensity.current
            Box {
                // supportingText deliberately NOT used here (even though
                // OutlinedTextField has that slot) -- it's measured as part
                // of the field's own onGloballyPositioned height below,
                // which would push the popup's anchor point below the
                // explanation text instead of right under the bordered box.
                // Confirmed live: this exact gap showed the explanation
                // text sitting between the field and the dropdown list. The
                // explanation renders as a plain Text after this Box instead.
                OutlinedTextField(
                    value = targetLanguage.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Target language") },
                    trailingIcon = {
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = if (languageMenuExpanded) "Collapse" else "Expand",
                            modifier = Modifier.rotate(if (languageMenuExpanded) 180f else 0f),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned {
                            languageFieldHeightPx = it.size.height
                            languageFieldWidthPx = it.size.width
                        },
                )
                // A readOnly OutlinedTextField still consumes taps for its own
                // focus-request behavior, so a .clickable{} modifier on the
                // field itself never fires -- confirmed live on a physical
                // device: tapping focused the field (label/border turned
                // purple) but the dropdown never opened. This transparent
                // overlay sits on top and gets the tap first instead.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { languageMenuExpanded = !languageMenuExpanded },
                )
                if (languageMenuExpanded) {
                    Popup(
                        alignment = Alignment.TopStart,
                        offset = IntOffset(0, languageFieldHeightPx),
                        onDismissRequest = { languageMenuExpanded = false },
                        properties = PopupProperties(focusable = true),
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            // tonalElevation deliberately 0 -- Material3
                            // tints a Surface toward the primary color at
                            // higher elevations, which on this app's purple
                            // theme showed up as an unwanted lavender wash
                            // behind the list. shadowElevation alone still
                            // gives it a floating drop shadow.
                            tonalElevation = 0.dp,
                            shadowElevation = 3.dp,
                            modifier = Modifier.width(with(density) { languageFieldWidthPx.toDp() }),
                        ) {
                            Column(modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                                SupportedLanguages.ALL.forEach { language ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                language.displayName,
                                                style = MaterialTheme.typography.bodyLarge,
                                            )
                                        },
                                        onClick = {
                                            targetLanguage = language
                                            LanguageConfig.setTargetLanguage(context, language)
                                            languageMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // Same text Material3's supportingText slot would have shown --
            // moved out here (see the comment above the OutlinedTextField)
            // so it doesn't get counted in the height the popup anchors to.
            // Matches OutlinedTextField's own default supportingText padding
            // (16dp start/end, 4dp top) and style so it looks unchanged.
            Text(
                "What English translates into, the voice that reads it back, and what Lisa Assistant listens for. Related-phrase suggestions still come from the Russian library for now.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Dark mode", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Overrides the system setting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = isDark, onCheckedChange = { onToggleDark() })
            }
            OutlinedTextField(
                value = translateTriggerPhrase,
                onValueChange = { onTranslateTriggerPhraseChange(it) },
                label = { Text("Voice command — translate") },
                supportingText = { Text("Say this, pause, then an English word, to have Lisa Assistant translate it instead of treating it as ${targetLanguage.displayName}.") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = meaningTriggerPhrase,
                onValueChange = { onMeaningTriggerPhraseChange(it) },
                label = { Text("Voice command — what does that mean?") },
                supportingText = { Text("Say this to hear an English translation of the last thing you said, spoken aloud.") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            if (nextSuggestionSupported) {
                OutlinedTextField(
                    value = nextSuggestionTriggerPhrase,
                    onValueChange = { onNextSuggestionTriggerPhraseChange(it) },
                    label = { Text("Voice command — next suggestion") },
                    supportingText = { Text("Say this to have Lisa Assistant read the next suggested phrase aloud. Say it again for the next one in the list.") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            if (curatedRelatedSupported) {
                OutlinedTextField(
                    value = answerTriggerPhrase,
                    onValueChange = { onAnswerTriggerPhraseChange(it) },
                    label = { Text("Voice command — how to answer?") },
                    supportingText = { Text("Say this to look up phrases you could say back to what you just heard.") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            // Visible only on capable hardware (API 33+, enough RAM) --
            // hides rather than disables controls on ineligible
            // configurations. See ON_DEVICE_LLM_PLAN.md Phase 5.
            if (OnDeviceLlm.isDeviceCapable(context)) {
                var whatElseSource by remember {
                    mutableStateOf(OnDeviceLlmConfig.getWhatElseSource(context))
                }
                val modelState = OnDeviceLlmConfig.getModelState(context)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("\"What else?\" suggestions", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Your device can generate these on-device, in the selected target language, instead of (or alongside) the Russian phrase library. Model: ${modelState.name}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    listOf(
                        OnDeviceLlmConfig.WhatElseSource.BOTH to "Both — AI, falling back to the library if it's not ready",
                        OnDeviceLlmConfig.WhatElseSource.AI_ONLY to "AI only",
                        OnDeviceLlmConfig.WhatElseSource.LIBRARY_ONLY to "Library only",
                    ).forEach { (source, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    whatElseSource = source
                                    OnDeviceLlmConfig.setWhatElseSource(context, source)
                                },
                        ) {
                            RadioButton(
                                selected = whatElseSource == source,
                                onClick = {
                                    whatElseSource = source
                                    OnDeviceLlmConfig.setWhatElseSource(context, source)
                                },
                            )
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    // Live download progress, if a download is currently
                    // running or queued -- WorkManager persists this across
                    // process death, so this reflects reality even right
                    // after the app relaunches mid-download. See
                    // ON_DEVICE_LLM_PLAN.md Phase 6.
                    val workInfos by remember(context) {
                        WorkManager.getInstance(context)
                            .getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.UNIQUE_WORK_NAME)
                    }.collectAsState(initial = emptyList())
                    val activeWork = workInfos.firstOrNull { !it.state.isFinished }
                    if (activeWork != null) {
                        val progress = activeWork.progress.getInt(ModelDownloadWorker.KEY_PROGRESS, 0)
                        Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text("Downloading model... $progress%", style = MaterialTheme.typography.bodyMedium)
                            LinearProgressIndicator(
                                progress = { progress / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else if (modelState != OnDeviceLlmConfig.ModelState.READY) {
                        Button(
                            onClick = { ModelDownloadWorker.enqueue(context) },
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text("Download model (2.7GB, WiFi only)")
                        }
                    }
                }
            }
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { onServerUrlChange(it) },
                label = { Text("Server URL") },
                supportingText = { Text("Emulator: http://10.0.2.2:8002 · Real device: http://<mac-lan-ip>:8002 · Deployed: http://<load-balancer-ip>:8002") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { onApiKeyChange(it) },
                label = { Text("API key (optional)") },
                supportingText = { Text("Only required when the server enforces ORCHESTRATION_API_KEY, e.g. the deployed cluster.") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
        }

        // Shared field: the caregiver types here, AND Lisa Assistant's live
        // transcript lands here while hands-free is running (see
        // handleTranscript). Submits on the keyboard's Search action -- no
        // separate "Look up" button. While the assistant "owns" it -- hands-
        // free running, field not focused, transcript present -- the text is
        // italic + muted so it reads as "being heard", not "typed".
        val assistantOwnsField = assistantState != SpeechAssistant.State.IDLE &&
            !inputFocused && input.isNotBlank()
        OutlinedTextField(
            value = input,
            onValueChange = {
                input = it
                commandsDismissed = false // caregiver typing -> chips come back
            },
            placeholder = { Text("Enter English or ${targetLanguage.displayName} Text") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                // Reads aloud: the translation if there is one, otherwise
                // whatever's in the field, in the target-language voice.
                IconButton(onClick = {
                    val r = result
                    val text = if (r?.mode == "translate" && r.translation != null) {
                        r.translation.ru
                    } else {
                        input
                    }
                    speaker?.speak(text)
                }) {
                    Icon(
                        Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Read aloud",
                        tint = if (translationSpeaking) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            },
            // Not singleLine: a long spoken transcript should push the field
            // taller so the caregiver can read all of it, up to a few lines
            // before it starts to scroll internally.
            singleLine = false,
            minLines = 1,
            maxLines = 6,
            shape = RoundedCornerShape(28.dp),
            textStyle = if (assistantOwnsField) {
                LocalTextStyle.current.copy(
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LocalTextStyle.current
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { inputFocused = it.isFocused },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSend() }),
        )

        transcriptGloss?.let { gloss ->
            // Purple box directly beneath the search bar. Grows with the text
            // so a long gloss stays fully visible.
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                Text(
                    gloss,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        InstructionsPanel(
            expanded = showInstructions,
            onToggle = { showInstructions = !showInstructions },
            spokenLanguage = targetLanguage.displayName,
            translateTriggerPhrase = translateTriggerPhrase,
            meaningTriggerPhrase = meaningTriggerPhrase,
            nextSuggestionTriggerPhrase = nextSuggestionTriggerPhrase,
            answerTriggerPhrase = answerTriggerPhrase,
            onSpeakTranslate = { speakTriggerPhrase(translateTriggerPhrase) },
            onSpeakMeaning = { speakTriggerPhrase(meaningTriggerPhrase) },
            onSpeakNext = { speakTriggerPhrase(nextSuggestionTriggerPhrase) },
            onSpeakAnswer = { speakTriggerPhrase(answerTriggerPhrase) },
            showNextSuggestionStep = nextSuggestionSupported,
            showAnswerStep = curatedRelatedSupported,
        )

        CommandChips(
            // Hands-free: shown until a command is used (commandsDismissed).
            // Text mode (IDLE): shown only while the field is empty -- once
            // you type, the keyboard's Search key does the same job.
            visible = !commandsDismissed &&
                (assistantState != SpeechAssistant.State.IDLE || input.isBlank()),
            items = buildList {
                add(
                    CommandChipSpec(
                        CommandKind.TRANSLATE, translateTriggerPhrase,
                        TriggerPhraseConfig.TRANSLATE_TRIGGER_EN,
                    ) { speakTriggerPhrase(translateTriggerPhrase) },
                )
                add(
                    CommandChipSpec(
                        CommandKind.MEANING, meaningTriggerPhrase,
                        TriggerPhraseConfig.MEANING_TRIGGER_EN,
                    ) { speakTriggerPhrase(meaningTriggerPhrase) },
                )
                if (nextSuggestionSupported) {
                    add(
                        CommandChipSpec(
                            CommandKind.NEXT_SUGGESTION, nextSuggestionTriggerPhrase,
                            TriggerPhraseConfig.NEXT_SUGGESTION_TRIGGER_EN,
                        ) { speakTriggerPhrase(nextSuggestionTriggerPhrase) },
                    )
                }
                if (curatedRelatedSupported) {
                    add(
                        CommandChipSpec(
                            CommandKind.ANSWER, answerTriggerPhrase,
                            TriggerPhraseConfig.ANSWER_TRIGGER_EN,
                        ) { speakTriggerPhrase(answerTriggerPhrase) },
                    )
                }
            },
        )

        errorText?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        result?.let { r ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // The related-phrases list always comes from the Russian
                    // curated library. In expand mode it's the whole point; in
                    // translate mode it's only relevant when the target
                    // language actually is Russian.
                    val relatedRelevant =
                        r.mode == "expand" || targetLanguage.code == SupportedLanguages.RUSSIAN.code

                    if (r.mode == "translate" && r.translation != null) {
                        Text("Translation:", style = MaterialTheme.typography.labelLarge)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { speaker?.speak(r.translation.ru) }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = "Play translation",
                                    tint = if (translationSpeaking) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.pulse(translationSpeaking),
                                )
                            }
                            Column {
                                Text(r.translation.ru, style = MaterialTheme.typography.headlineSmall)
                                Text(
                                    "\"${r.input}\"",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontStyle = FontStyle.Italic,
                                )
                            }
                        }
                        if (relatedRelevant && r.related.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "More related phrases (Russian curated library):",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            RelatedPhraseList(r.related, speakingIndex, ::speakRelated)
                        }
                    } else if (relatedForDisplay.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Related phrases:", style = MaterialTheme.typography.labelLarge)
                            // Deliberately plain-text, not a subtler icon --
                            // the user explicitly wants it obvious which
                            // source answered, not a detail you have to
                            // notice. See ON_DEVICE_LLM_PLAN.md Phase 7.
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Text(
                                    if (usingAiSuggestions) "AI" else "Library",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                        RelatedPhraseList(relatedForDisplay, speakingIndex, ::speakRelated)
                    } else {
                        Text(
                            "No related phrases for \"${r.input}\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Non-Russian targets have no backend `result`, so the on-device
        // "what else?" suggestions (heard aloud on the trigger) get their own
        // card -- otherwise there's nothing on screen to show they worked,
        // or that they're still generating.
        if (result == null && !curatedRelatedSupported && lastUtterance.isNotBlank() &&
            (relatedForDisplay.isNotEmpty() || OnDeviceLlm.canGenerate(context))
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Related phrases:", style = MaterialTheme.typography.labelLarge)
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                "AI",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    when {
                        relatedForDisplay.isNotEmpty() ->
                            RelatedPhraseList(relatedForDisplay, speakingIndex, ::speakRelated)
                        onDeviceRelated == null ->
                            Text(
                                "Generating suggestions for “$lastUtterance”…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        else ->
                            Text(
                                "No suggestions for “$lastUtterance”.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                    }
                }
            }
        }
    }
}

@Composable
private fun RelatedPhraseList(
    phrases: List<Phrase>,
    speakingIndex: Int?,
    onSpeak: (Int) -> Unit,
) {
    phrases.forEachIndexed { index, phrase ->
        val active = index == speakingIndex
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (active) {
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
            } else {
                androidx.compose.ui.graphics.Color.Transparent
            },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(phrase.ru, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        phrase.glossEn,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onSpeak(index) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Play phrase",
                        tint = if (active) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.pulse(active),
                    )
                }
            }
        }
    }
}
