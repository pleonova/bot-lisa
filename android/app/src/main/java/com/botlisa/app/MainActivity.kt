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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.coroutines.coroutineContext

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
private fun UiPhase.subtitle(spokenLanguage: String): String = when (this) {
    UiPhase.IDLE -> "Tap for hands-free mode"
    UiPhase.LISTENING_RU -> "Listening for $spokenLanguage…"
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
    // True when errorText is a ModelDownloadRequiredException surfaced from
    // a wifi-only attempt -- offers the caregiver a retry that allows the
    // one-time model download over cellular data instead of waiting for wifi.
    var offerCellularDownloadRetry by rememberSaveable { mutableStateOf(false) }
    var result by rememberSaveable { mutableStateOf<AssistResult?>(null) }
    // True right after a "how to say?" voice capture has filled `input`
    // with the captured word and spoken its translation. Lets a second tap
    // of the chip (see onTranslateChipTap) tell "the field still holds
    // last round's captured word" apart from "the caregiver typed this
    // word to translate it" -- both leave `input` non-blank, but only the
    // former should be cleared and re-listened rather than re-spoken.
    // Cleared as soon as `input` changes for any other reason (typing, or
    // the live transcript picking up new speech).
    var wordFromTranslateCapture by rememberSaveable { mutableStateOf(false) }

    // Which related/suggested phrase the next-suggestion trigger should
    // speak. Reset to 0 every time a fresh /assist result comes back, so
    // cycling always starts from the top of the newest suggestion list;
    // wraps around (see speakNextSuggestion()) once it runs past the end.
    var suggestionIndex by rememberSaveable { mutableStateOf(0) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var serverUrl by remember { mutableStateOf(ServerConfig.getBaseUrl(context)) }
    var apiKey by remember { mutableStateOf(ServerConfig.getApiKey(context)) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    // Auto-shown on every fresh screen, and stays expanded through
    // resetToStart() (fox tap) too, matching showIntro below -- tapping the
    // header still collapses it for the rest of the session.
    var showInstructions by rememberSaveable { mutableStateOf(true) }

    // Full-screen intro (IntroScreen.kt): auto-shown once on first launch,
    // and again any time the fox logo is tapped (see resetToStart() below).
    var showIntro by rememberSaveable { mutableStateOf(!IntroConfig.hasSeenIntro(context)) }
    var audience by remember { mutableStateOf(AudienceConfig.getAudience(context)) }

    // True once we've tried to auto-start the "what else?" model download
    // (on intro dismiss, or from requestWhatElse() itself) and found no
    // WiFi -- ModelDownloadWorker.enqueue() is WiFi-only by default and
    // would otherwise just queue silently and wait, possibly forever, with
    // no sign to the caregiver that's what's happening. Drives the homepage
    // banner below offering to spend cellular data instead. Cleared once
    // they act (either button) or the model becomes ready.
    var modelDownloadNeedsWifi by rememberSaveable { mutableStateOf(false) }

    // Starts the on-device "what else?" model download if it isn't already
    // downloaded/downloading -- auto-starts immediately on WiFi, otherwise
    // raises modelDownloadNeedsWifi instead of enqueueing blind (see its
    // comment). Called from the intro's onDismiss and from requestWhatElse()
    // so both the proactive (first-launch) and reactive (command used, model
    // still missing) paths land in the same place.
    fun maybeStartModelDownload() {
        if (OnDeviceLlmConfig.getModelState(context) == OnDeviceLlmConfig.ModelState.READY) return
        if (ModelDownloadWorker.isOnWifi(context)) {
            ModelDownloadWorker.enqueue(context)
        } else {
            modelDownloadNeedsWifi = true
        }
    }

    // Command-chip reminders (§3.6): hidden in hands-free once a command is
    // used (spoken trigger), back on the next new input.
    var commandsDismissed by remember { mutableStateOf(false) }

    // Declared up here (rather than down by the rest of the hands-free
    // plumbing) so requestWhatElse()/speakMeaningOfLast()/
    // requestAnswerSuggestions() above can read it -- they need to know
    // whether they're being invoked from a tap while idle (where falling
    // back to `input` is correct) or from genuine hands-free listening
    // (where it isn't -- see requestWhatElse()'s utterance comment).
    var assistantState by remember { mutableStateOf(SpeechAssistant.State.IDLE) }

    // Target language: drives translation, the spoken voice, the hands-free
    // STT locale, and the trigger phrases. Everything downstream reads from
    // this so switching language updates the whole screen.
    var targetLanguage by remember { mutableStateOf(LanguageConfig.getTargetLanguage(context)) }

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

    // Worked examples shown in the "Use voice commands" panel -- see
    // InstructionsExamples.kt. Small bundled-asset reads, cheap enough to
    // redo inline whenever the target language changes.
    val wordExample = remember(targetLanguage.code) { InstructionsExamples.wordExample(context, targetLanguage.code) }
    val phraseExample = remember(targetLanguage.code) { InstructionsExamples.phraseExample(context, targetLanguage.code) }

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

    // True exactly while the prefetch below is actually running a
    // generateWhatElse call for lastUtterance -- drives the "still
    // generating" spinner. Deliberately a plain flag we set ourselves
    // rather than reading OnDeviceLlm.availability(): that reports LOADING
    // (not READY) for the same span, which would make a canGenerate()-based
    // check read as "not eligible" during the exact window it's eligible
    // and running. Found via a real device test where the spinner never
    // showed and "No related phrases" sat there for the whole generation.
    var onDeviceGenerating by remember { mutableStateOf(false) }

    // The coroutine currently running generateWhatElse() -- from either the
    // eager prefetch effect or requestWhatElse()'s on-demand call, whichever
    // is in flight -- so the "X" on the generating card can cancel it
    // outright instead of just hiding the spinner while the model keeps
    // grinding in the background.
    var activeWhatElseJob by remember { mutableStateOf<Job?>(null) }

    // True once the caregiver has explicitly asked "what else?" for
    // lastUtterance (spoken trigger, or tapping its chip/button) -- only
    // matters in ON_DEMAND mode, where nothing has been generated yet and
    // the result card must stay hidden until asked (see its visibility
    // check below: `eagerMode || whatElseRequested`). In EAGER mode the
    // card shows automatically once there's something to show regardless
    // of this flag -- that's the point of "eager". Reset to false whenever
    // lastUtterance changes (see the prefetch LaunchedEffect below); set
    // true at the top of requestWhatElse().
    var whatElseRequested by remember { mutableStateOf(false) }

    // True when the caregiver asked "what else?" while a generation was
    // already in flight (PrefetchMode.EAGER started it, or a prior
    // on-demand request did) -- speaks the result the moment that
    // in-flight call lands instead of starting a redundant second one.
    // Cleared once consumed.
    var speakWhenReady by remember { mutableStateOf(false) }

    // True when EAGER prefetch would have run for lastUtterance but backed
    // off because the device is thermally elevated (see
    // OnDeviceLlm.isThermallyElevated). Drives an explicit "device's too
    // warm" message in the result card instead of just silently producing
    // nothing -- found via a real device test where "eager" quietly did
    // nothing on a warm phone with no indication why.
    var eagerSkippedForHeat by remember { mutableStateOf(false) }

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

    // fun onSend(allowCellularDownload: Boolean = false) below: true only
    // when replaying a lookup after the caregiver tapped "Download over
    // cellular data" on a ModelDownloadRequiredException -- see
    // offerCellularDownloadRetry. Not persisted past this one call, so a
    // later lookup for a *different* not-yet-downloaded language still asks
    // first rather than silently spending cellular data every time.
    fun onSend(allowCellularDownload: Boolean = false) {
        if (input.isBlank()) return
        errorText = null
        offerCellularDownloadRetry = false
        // Drop the previous card straight away so a new lookup (typed or
        // spoken) doesn't sit under a stale result until the response lands.
        result = null
        isLoading = true
        scope.launch {
            // Set when an on-device translate attempt below fails specifically
            // because its model hasn't downloaded yet -- kept aside rather than
            // written to errorText immediately, since a later attempt (backend,
            // or the offline fallback) may still succeed and make it moot. Only
            // surfaced if nothing else in this lookup pans out, so the
            // caregiver gets the actionable "connect to wifi" reason instead of
            // a generic "couldn't reach the server" that hides the real cause.
            var modelDownloadError: String? = null
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
                        OnDeviceTranslator.translate(input, targetLanguage, allowCellularDownload)
                    } catch (e: ModelDownloadRequiredException) {
                        modelDownloadError = e.message
                        null
                    } catch (_: Exception) {
                        // Some other ML Kit failure -- fall through to the
                        // server as a backup rather than dead-ending. If
                        // that's also unreachable the catch blocks below
                        // report it.
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
                        // "Last utterance" for eager prefetch / what-else /
                        // meaning / answer must be the *target-language*
                        // phrase, not the English input -- generateWhatElse's
                        // few-shot prompt is entirely target-language
                        // examples (see PromptComposer.kt / what_else.json),
                        // so feeding it English text back produces garbage or
                        // empty suggestions. The translation is what's
                        // actually now "in play" for the caregiver to build
                        // on. See ON_DEVICE_LLM_PLAN.md Phase 7.
                        lastUtterance = translated
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
                            val translated = OnDeviceTranslator.translate(input, targetLanguage, allowCellularDownload)
                            assist = assist.copy(
                                source = "on_device",
                                translation = Phrase(ru = translated, glossEn = input),
                            )
                        } catch (e: ModelDownloadRequiredException) {
                            errorText = "${e.message} Showing the placeholder instead."
                            offerCellularDownloadRetry = !allowCellularDownload
                        } catch (e: Exception) {
                            errorText = "On-device translation failed (${e.message}) -- showing the placeholder instead."
                        }
                    }
                    // Read the translation back aloud -- the "one earbud in, talking
                    // to the kid" use case. Whatever won above gets spoken.
                    assist.translation?.let { speaker?.speak(it.ru) }
                }
                result = assist
                // Target-language text only -- see the on-device translate
                // branch above for why. Translate mode: the translation that
                // won, if any (leave lastUtterance alone on outright
                // failure). Expand mode: input was already target-language
                // text going in.
                if (assist.mode == "translate") {
                    assist.translation?.let { lastUtterance = it.ru }
                } else {
                    lastUtterance = input
                }
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
                        val translated = OnDeviceTranslator.translate(input, targetLanguage, allowCellularDownload)
                        AssistResult(
                            mode = "translate",
                            source = "on_device",
                            input = input,
                            translation = Phrase(ru = translated, glossEn = input),
                            related = emptyList(),
                            latencyMs = 0.0,
                        )
                    } catch (e: ModelDownloadRequiredException) {
                        modelDownloadError = e.message
                        null
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
                    offline.translation?.let { lastUtterance = it.ru }
                    result = offline
                    suggestionIndex = 0
                    errorText = "Server unreachable -- translated on-device instead.$emulatorUrlHint"
                } else if (modelDownloadError != null) {
                    // Neither the server nor on-device translation came
                    // through -- lead with the actionable wifi reason rather
                    // than the generic "server unreachable", since that's
                    // almost certainly the real blocker here (this is the
                    // first use of this language and there's no wifi).
                    errorText = "$modelDownloadError Also couldn't reach the server: ${e.message}.$emulatorUrlHint"
                    offerCellularDownloadRetry = !allowCellularDownload
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

    // True while the result card should show the "still generating" spinner
    // rather than a premature "no suggestions" -- see onDeviceGenerating's
    // own comment for why this is a plain flag rather than derived from
    // OnDeviceLlm.canGenerate().
    val aiPending = onDeviceGenerating
    // True once an on-device attempt has started or finished for
    // lastUtterance -- used to decide whether the standalone AI card (for
    // non-Russian targets, which have no backend `result`) should appear at
    // all. False when AI "what else" was never in play (LIBRARY_ONLY mode,
    // an incapable/not-ready device), so no empty placeholder card shows for
    // a mode that was never going to answer.
    val aiAttempted = onDeviceGenerating || onDeviceRelated != null

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

    // The eager prefetch effect and requestWhatElse()'s own on-demand
    // generation below both call back into speakNextSuggestion() once a
    // generation they kicked off finishes -- but that call happens from
    // inside a coroutine that can outlive many recompositions. Calling
    // speakNextSuggestion() directly there would invoke whichever closure
    // was captured when that coroutine started, which read relatedForDisplay
    // as it was *then* (still empty -- the generation it's waiting on hadn't
    // produced anything yet), so the TTS call silently saw an empty list and
    // no-op'd. Routing through rememberUpdatedState (same pattern already
    // used for SpeechAssistant's voice-trigger callbacks below) makes the
    // call land on the latest recomposition's speakNextSuggestion, which
    // sees the suggestions that generation just produced -- found as "what
    // else?" needing to be asked twice to actually hear anything.
    val speakNextSuggestionWhenReady by rememberUpdatedState { speakNextSuggestion() }

    // Handles the "what else?" command end to end, covering both
    // PrefetchMode settings:
    //   - EAGER, already prefetched -> speak immediately (fast path, same
    //     as before this existed).
    //   - EAGER, still generating (e.g. asked right after a new utterance)
    //     -> don't start a second generation; just remember to speak once
    //     the in-flight one lands (see the prefetch LaunchedEffect below).
    //   - ON_DEMAND (or EAGER skipped this utterance because the device was
    //     thermally elevated) -> nothing has been generated yet, so kick
    //     off a generation now and speak when it finishes. This is the
    //     "few seconds of wait" trade-off for not pre-generating on every
    //     phrase -- see OnDeviceLlmConfig.PrefetchMode's own doc comment.
    fun requestWhatElse() {
        // Marks that the trigger has actually been used for lastUtterance --
        // the AI result card (see below) stays hidden until this is true,
        // even if EAGER prefetch already has (or is still generating)
        // suggestions in the background.
        whatElseRequested = true
        if (relatedForDisplay.isNotEmpty()) {
            speakNextSuggestion()
            return
        }
        // Only dedup against a call *this screen* already tracks (the
        // prefetch effect, or a previous requestWhatElse()) -- not against
        // OnDeviceLlm.canGenerate()'s LOADING check, which also reports
        // "not eligible" while OnDeviceLlm.warmUp() (kicked off from
        // startHandsFree(), untracked by onDeviceGenerating) is mid-load.
        // That's not a reason to give up: generateWhatElse()'s own mutex
        // already serializes behind whatever's using the engine, so a
        // request placed while warmUp() is still loading just waits a
        // couple more seconds and then runs -- it doesn't collide with
        // anything. Using canGenerate() here made requestWhatElse() give up
        // silently in exactly that window, found as "what else?" going
        // silent right after starting hands-free instead of just slower.
        if (onDeviceGenerating) {
            speakWhenReady = true
            return
        }
        val source = OnDeviceLlmConfig.getWhatElseSource(context)
        // Only fall back to `input` while idle (a phrase typed but not yet
        // sent) -- while genuinely hands-free listening, lastUtterance alone
        // is authoritative (cleared fresh by startHandsFree(), then kept
        // current by every heard utterance); falling back to `input` there
        // too could reach back into stale leftover field text from well
        // before this listening session started.
        val utterance = if (assistantState == SpeechAssistant.State.IDLE) {
            lastUtterance.ifBlank { input }
        } else {
            lastUtterance
        }
        if (utterance.isBlank()) return
        // Which source(s) are actually worth trying -- independently, since
        // BOTH should still fall back to the library when AI isn't
        // available rather than doing nothing (see below; this used to
        // bail out entirely whenever the on-device model wasn't ready,
        // which is the common case on a device that hasn't downloaded the
        // ~2.7GB model -- found as "what else? does nothing" for every
        // source setting except AI_ONLY on a capable+ready device).
        val tryAi = source != OnDeviceLlmConfig.WhatElseSource.LIBRARY_ONLY &&
            OnDeviceLlm.isDeviceCapable(context) &&
            OnDeviceLlmConfig.getModelState(context) == OnDeviceLlmConfig.ModelState.READY
        // Russian-only curated library (see relatedForDisplay/curatedRelatedSupported).
        val tryLibrary = source != OnDeviceLlmConfig.WhatElseSource.AI_ONLY && curatedRelatedSupported
        if (!tryAi && !tryLibrary) {
            // Silently doing nothing here reads as "the button is broken" --
            // this is overwhelmingly the "AI model never downloaded" case
            // (it's a ~2.7GB opt-in download, so NOT_DOWNLOADED is the
            // default for every fresh install) combined with a non-Russian
            // target, which has no curated-library fallback to fall back
            // to -- so for most target languages, until the model is
            // downloaded, this command has genuinely nothing to work with.
            // Say so and point at the fix instead of staying silent.
            errorText = if (!OnDeviceLlm.isDeviceCapable(context)) {
                "\"What else?\" isn't available -- this device can't run the on-device AI model."
            } else if (OnDeviceLlmConfig.getModelState(context) == OnDeviceLlmConfig.ModelState.DOWNLOADING) {
                // The intro-dismiss auto-download (see maybeStartModelDownload())
                // is presumably what's running -- worth distinguishing from
                // "never started" so this doesn't read as needing a trip to
                // Settings when it's already underway.
                "\"What else?\" needs the on-device AI model for ${targetLanguage.displayName}, " +
                    "which is still downloading -- try again once it finishes."
            } else {
                // Covers both "never got a chance to auto-start" (e.g. the
                // intro was dismissed back before that existed) and "was
                // WiFi-blocked and the caregiver hasn't acted on the banner
                // yet" -- try again now either way, and let the message
                // reflect whichever of those this call just found.
                maybeStartModelDownload()
                if (modelDownloadNeedsWifi) {
                    "\"What else?\" needs the on-device AI model for ${targetLanguage.displayName} -- " +
                        "you're not on WiFi, so connect to WiFi or download over cellular data below."
                } else {
                    "\"What else?\" needs the on-device AI model for ${targetLanguage.displayName} -- " +
                        "starting the download now (WiFi required; see Settings for progress)."
                }
            }
            return
        }
        val language = targetLanguage
        activeWhatElseJob = scope.launch {
            eagerSkippedForHeat = false // an explicit ask always tries, heat or not
            if (tryAi) {
                onDeviceGenerating = true
                val generated = runCatching {
                    OnDeviceLlm.generateWhatElse(context, utterance, language.code)
                }
                activeWhatElseJob = null
                onDeviceRelated = generated.getOrNull()
                onDeviceGenerating = false
                // Model marked READY but generation itself blew up (corrupt/
                // incomplete download despite that, OOM, a native crash in
                // the inference engine, ...) used to vanish into
                // getOrNull() with zero trace -- "what else?" would just do
                // nothing and never even show the Generating spinner long
                // enough to notice, since the failure could happen before
                // any real suspension point. Surface it instead -- if the
                // library can still answer (tryLibrary below), don't let
                // this failure block that, but it's still worth knowing
                // about, so only skip showing it when the library already
                // did the job silently. A CancellationException here just
                // means this job got superseded or cancelled (a newer
                // utterance, the "X" button, leaving the screen) -- not a
                // real failure, so rethrow it instead of reporting it as one
                // (runCatching would otherwise swallow it like any other
                // exception and surface "The coroutine scope left the
                // composition" as if generation itself had broken).
                generated.exceptionOrNull()?.let { e ->
                    if (e is CancellationException) throw e
                    if (!tryLibrary) errorText = "\"What else?\" generation failed: ${e.message ?: e::class.simpleName}"
                }
            }
            // Only hit the library when it's the only option (LIBRARY_ONLY)
            // or AI came back empty (BOTH's documented fallback -- see
            // relatedForDisplay) -- not when AI already has something to say,
            // so BOTH doesn't pay for a network round trip it won't use.
            if (tryLibrary && onDeviceRelated.isNullOrEmpty()) {
                val fetched = runCatching {
                    ApiClient.sendAssist(baseUrl = serverUrl, apiKey = apiKey, text = utterance)
                }
                fetched.getOrNull()?.let { result = it }
                fetched.exceptionOrNull()?.let { e ->
                    errorText = "\"What else?\" library lookup failed: ${e.message ?: e::class.simpleName}"
                }
            }
            speakNextSuggestionWhenReady()
        }
    }

    // Lets the caregiver back out of a generation that's taking too long,
    // via the "X" on the generating card -- cancels whichever coroutine is
    // actually running it (eager prefetch or an on-demand requestWhatElse()
    // call) and resets state as if it had never started, so the card
    // disappears rather than sitting on a spinner for a request nobody's
    // waiting on anymore.
    fun cancelWhatElseGeneration() {
        activeWhatElseJob?.cancel()
        activeWhatElseJob = null
        onDeviceGenerating = false
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
    fun speakTriggerPhrase(phrase: String, onComplete: (() -> Unit)? = null) {
        if (speaker?.speak(phrase.trimEnd('?', ' '), onComplete) != true) onComplete?.invoke()
    }

    // "what does that mean?" -- translate the previous target-language
    // utterance into English and read it aloud (English voice).
    fun speakMeaningOfLast() {
        // See requestWhatElse()'s utterance comment for why the `input`
        // fallback is IDLE-only.
        val text = if (assistantState == SpeechAssistant.State.IDLE) {
            lastUtterance.ifBlank { input }
        } else {
            lastUtterance
        }
        if (text.isBlank()) return
        scope.launch {
            val english = runCatching {
                OnDeviceTranslator.translateToEnglish(text, targetLanguage)
            }.getOrNull()
            if (!english.isNullOrBlank()) englishSpeaker?.speak(english)
        }
    }

    // "how to answer?" -- meant to run a fresh related-phrases lookup on the
    // previous utterance, so the result card shows things you could say
    // back. The backend endpoint this needs isn't wired up yet -- calling
    // onSend() here used to just hang until the network call timed out, then
    // surface a generic "server unreachable" error, which reads as broken
    // rather than "not built yet". Say so plainly and immediately instead.
    fun requestAnswerSuggestions() {
        // See requestWhatElse()'s utterance comment for why the `input`
        // fallback is IDLE-only.
        val text = if (assistantState == SpeechAssistant.State.IDLE) {
            lastUtterance.ifBlank { input }
        } else {
            lastUtterance
        }
        if (text.isBlank() || !curatedRelatedSupported) return
        errorText = "\"How to answer?\" isn't wired up yet -- this feature is still a work in progress."
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
            // Remember the last plain utterance for the meaning / what-else /
            // answer commands to act on, and bring the chips back regardless
            // of whether this utterance runs a lookup below. Also drop any
            // stale `result` from a previous utterance's explicit "how to
            // answer?" (or typed search) -- onSend() itself used to do this
            // on *every* utterance back when it ran automatically; now that
            // it only runs on an explicit request, nothing else clears it,
            // so a leftover non-null `result` would keep the old answer's
            // card showing (with the wrong phrase) AND suppress the
            // standalone AI card below for every later utterance, since that
            // card only renders when `result == null`. Found as "eager mode
            // doesn't seem to work" after using "как ответить?" even once.
            if (state == SpeechAssistant.State.LISTENING_DEFAULT && text.isNotBlank()) {
                lastUtterance = text
                result = null
                commandsDismissed = false // fresh utterance -> chips come back
            }
            // The English word after the translate trigger always runs a
            // lookup immediately -- that word IS the command. A plain
            // default-mode utterance no longer auto-runs the Russian
            // backend "expand" lookup: it used to, on *every* utterance,
            // which meant a result card (or its "No related phrases" empty
            // state) popped up whether or not the caregiver actually wanted
            // it. That lookup, and on-device "what else?", now only run via
            // the explicit "what else?" / "how to answer?" triggers
            // (requestWhatElse() / requestAnswerSuggestions()) or their
            // on-screen buttons -- see CommandChips' onClick handlers below.
            if (state == SpeechAssistant.State.LISTENING_FOR_WORD) {
                input = text
                commandsDismissed = false
                wordFromTranslateCapture = true
                onSend()
            }
        }
    val handleMeaningRequest = rememberUpdatedState { speakMeaningOfLast() }
    val handleAnswerRequest = rememberUpdatedState { requestAnswerSuggestions() }
    val handleNextSuggestionRequest = rememberUpdatedState { requestWhatElse() }

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
    // transcriptGloss effect above. Only runs in PrefetchMode.EAGER --
    // ON_DEMAND generates only when requestWhatElse() is actually called.
    // LIBRARY_ONLY mode and a not-ready model skip the call either way --
    // no point spending battery/time on a generation nothing will display.
    // See ON_DEVICE_LLM_PLAN.md Phase 7.
    LaunchedEffect(lastUtterance, targetLanguage) {
        onDeviceRelated = null
        onDeviceGenerating = false
        whatElseRequested = false // fresh utterance -> hasn't been asked about yet
        eagerSkippedForHeat = false
        // A fresh utterance means a fresh suggestion list -- without this,
        // "what else?" on utterance #2 could pick up wherever cycling left
        // off on utterance #1's (now-replaced) list instead of starting at
        // the first phrase. Covers both eager and on-demand: this effect
        // runs on every lastUtterance change regardless of PrefetchMode.
        suggestionIndex = 0
        val source = OnDeviceLlmConfig.getWhatElseSource(context)
        val eagerMode = OnDeviceLlmConfig.getPrefetchMode(context) == OnDeviceLlmConfig.PrefetchMode.EAGER
        val eligible = lastUtterance.isNotBlank() &&
            source != OnDeviceLlmConfig.WhatElseSource.LIBRARY_ONLY &&
            OnDeviceLlm.canGenerate(context)
        if (eagerMode && eligible) {
            // Every phrase triggering a multi-second, multi-core inference
            // measurably heats the device (see OnDeviceLlmConfig.PrefetchMode's
            // doc comment and android/app/benchmarks/README.md) -- back off
            // once it's already warm, but say so rather than quietly doing
            // nothing (the result card surfaces eagerSkippedForHeat below).
            // requestWhatElse() doesn't check this, so explicitly asking
            // still works despite the heat -- this only affects the
            // automatic background attempt.
            if (OnDeviceLlm.isThermallyElevated(context)) {
                eagerSkippedForHeat = true
            } else {
                onDeviceGenerating = true
                activeWhatElseJob = coroutineContext[Job]
                val generated = runCatching {
                    OnDeviceLlm.generateWhatElse(context, lastUtterance, targetLanguage.code)
                }
                activeWhatElseJob = null
                onDeviceRelated = generated.getOrNull()
                onDeviceGenerating = false
                // Same reasoning as requestWhatElse()'s equivalent comment --
                // a failure here used to vanish silently, and since this is
                // the *background* prefetch, it could fail minutes before
                // the caregiver ever says "what else?", with nothing to show
                // for it when they do. But a CancellationException here is
                // this LaunchedEffect getting superseded by a newer
                // utterance (its key) or the composable leaving composition
                // -- routine, not a failure -- so rethrow rather than report.
                generated.exceptionOrNull()?.let { e ->
                    if (e is CancellationException) throw e
                    errorText = "\"What else?\" generation failed: ${e.message ?: e::class.simpleName}"
                }
                if (speakWhenReady) {
                    speakWhenReady = false
                    speakNextSuggestionWhenReady()
                }
            }
        }
    }


    // Live transcript from Lisa Assistant -> the shared input/search field.
    // Only while hands-free is running, so it never clobbers something the
    // caregiver is typing. Covers partials and the command phrases too
    // (those never reach onUtterance).
    val handleTranscript = rememberUpdatedState<(String) -> Unit> { text ->
        if (assistantState != SpeechAssistant.State.IDLE && !inputFocused && text.isNotBlank()) {
            input = text
            wordFromTranslateCapture = false
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
    fun startHandsFree(listenForWordFirst: Boolean = false) {
        assistantError = null
        // Drop focus from the input field if switching straight from typing
        // mode -- handleTranscript guards writes on !inputFocused (so live
        // transcript updates never clobbers active typing), and that focus
        // otherwise lingers across the switch, silently freezing the
        // transcript for the entire hands-free session.
        focusManager.clearFocus()
        // A fresh hands-free session has nothing said in it yet -- clear the
        // "last utterance" meaning/what-else/answer act on, so a caregiver
        // who hasn't said anything this round can't trigger those and have
        // them reach back into a much earlier (possibly from a prior
        // session) utterance without any indication that's what happened.
        // Only lastUtterance itself -- not `input`, the visible field text --
        // stopping hands-free deliberately leaves that alone (see
        // stopHandsFree() callers) so the caregiver can still read the last
        // thing heard; this only resets what counts as "in play" for the
        // follow-up voice commands.
        lastUtterance = ""
        result = null
        assistant.start(listenForWordFirst)
        ListeningForegroundService.start(context)
        // Pre-load the on-device model + system prompt now, while the
        // caregiver is still settling into hands-free mode, rather than
        // lazily on their first utterance -- model load + system-prompt
        // processing (~10s combined on a Pixel 11) otherwise dominate the
        // latency of the very first "what else?" of a session. Fire-and-
        // forget: a skipped/failed warm-up just means generateWhatElse pays
        // the cost itself later. Skipped entirely in LIBRARY_ONLY mode --
        // no point loading ~2.7GB nothing will use.
        if (OnDeviceLlmConfig.getWhatElseSource(context) != OnDeviceLlmConfig.WhatElseSource.LIBRARY_ONLY) {
            scope.launch { OnDeviceLlm.warmUp(context, targetLanguage.code) }
        }
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

    // Same as assistantMicPermissionLauncher, but for the "How to say?" chip
    // when hands-free wasn't already running -- lands straight in
    // LISTENING_FOR_WORD (English) instead of LISTENING_DEFAULT, fired once
    // permission is granted from the middle of onTranslateChipTap's demo
    // speech (see its onComplete callback below).
    val translateWordMicPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            requestNotificationPermissionIfNeeded()
            startHandsFree(listenForWordFirst = true)
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

    // "How to say?" chip: text already in the field -> that IS the word to
    // translate, same as pressing the keyboard's Search key. But if that
    // text is only sitting there because a *previous* "how to say?" round
    // captured and spoke it (wordFromTranslateCapture), re-running onSend()
    // on it would just re-speak the same old word -- so clear it first.
    // Otherwise there's nothing to translate yet: while hands-free is
    // already running, fast-switch it into LISTENING_FOR_WORD (same as if
    // the target-language trigger phrase had just been spoken); while IDLE,
    // speak the trigger phrase aloud first -- same demo-on-tap the other
    // three command cards do -- and only then start hands-free landing
    // straight in LISTENING_FOR_WORD, so the record button lighting up
    // follows the caregiver actually hearing the command instead of firing
    // silently the instant they tap.
    fun onTranslateChipTap() {
        if (wordFromTranslateCapture) {
            input = ""
            result = null
            wordFromTranslateCapture = false
        } else if (input.isNotBlank()) {
            onSend()
            return
        }
        when (assistantState) {
            SpeechAssistant.State.IDLE -> speakTriggerPhrase(translateTriggerPhrase) {
                // The demo phrase takes a moment to play out, and this
                // fires once it's done -- if the caregiver tapped "Assistant
                // Lisa" (or anything else that moved the assistant on) while
                // it was still speaking, assistantState won't be IDLE
                // anymore by the time we get here. Starting hands-free
                // anyway would silently undo that reset. Only proceed if
                // nothing's changed since the tap.
                if (assistantState == SpeechAssistant.State.IDLE) {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        requestNotificationPermissionIfNeeded()
                        startHandsFree(listenForWordFirst = true)
                    } else {
                        translateWordMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
            SpeechAssistant.State.LISTENING_DEFAULT -> assistant.switchToListeningForWord()
            SpeechAssistant.State.LISTENING_FOR_WORD -> Unit // already there
        }
    }

    // Tapping the fox logo or the "Assistant Lisa" title both return the
    // screen to its opening state -- stops hands-free, clears the
    // field/result/errors and any expanded panels. Persistent settings
    // (server, language, trigger phrases) are left alone. Only the logo also
    // brings back the intro popup ([showIntroPopup]) -- the title alone just
    // clears the page.
    fun resetToStart(showIntroPopup: Boolean = false) {
        stopHandsFree()
        // Drop focus so the field isn't left selected -- a focused field
        // hides the "start typing" helper under the buttons.
        focusManager.clearFocus()
        input = ""
        result = null
        wordFromTranslateCapture = false
        onDeviceRelated = null
        errorText = null
        assistantError = null
        suggestionIndex = 0
        commandsDismissed = false
        showInstructions = true
        showSettings = false
        if (showIntroPopup) showIntro = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (showSettings) {
            SettingsScreen(
                isDark = isDark,
                onToggleDark = onToggleDark,
                targetLanguage = targetLanguage,
                onTargetLanguageChange = {
                    targetLanguage = it
                    LanguageConfig.setTargetLanguage(context, it)
                },
                translateTriggerPhrase = translateTriggerPhrase,
                onTranslateTriggerPhraseChange = ::onTranslateTriggerPhraseChange,
                meaningTriggerPhrase = meaningTriggerPhrase,
                onMeaningTriggerPhraseChange = ::onMeaningTriggerPhraseChange,
                nextSuggestionTriggerPhrase = nextSuggestionTriggerPhrase,
                onNextSuggestionTriggerPhraseChange = ::onNextSuggestionTriggerPhraseChange,
                nextSuggestionSupported = nextSuggestionSupported,
                answerTriggerPhrase = answerTriggerPhrase,
                onAnswerTriggerPhraseChange = ::onAnswerTriggerPhraseChange,
                curatedRelatedSupported = curatedRelatedSupported,
                serverUrl = serverUrl,
                onServerUrlChange = ::onServerUrlChange,
                apiKey = apiKey,
                onApiKeyChange = ::onApiKeyChange,
            )
        } else {
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
                        .clickable { resetToStart(showIntroPopup = true) },
                )
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            }
            Text(
                "Assistant Lisa",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.clickable { resetToStart() },
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

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            AssistantButton(
                phase = uiPhase,
                onClick = { onToggleAssistant() },
            )
            Text(
                uiPhase.subtitle(targetLanguage.displayName),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        assistantError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.CenterHorizontally),
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
                wordFromTranslateCapture = false
            },
            placeholder = {
                // Same size as the voice-command instructions
                // (InstructionsPanel's header subtitle), and lighter than
                // regular field text so it reads as a hint, not content.
                Text(
                    "Enter English or ${targetLanguage.displayName} Text",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            },
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

        // Suppressed once a translate-mode result is showing -- its own card
        // (below) already displays this same English text, so repeating it
        // here right under the search box would just be noise.
        val suppressTranscriptGloss = result?.mode == "translate" && result?.translation != null
        transcriptGloss?.let { gloss ->
            if (suppressTranscriptGloss) return@let
            // No background -- just the book icon (same one/tint as the
            // "what does that mean?" chip, greyed instead of orange) plus
            // the gloss, styled like every other English translation in the
            // app. 12.dp start padding matches TextFieldImpl's internal
            // HorizontalIconPadding so the book icon lines up with the
            // search field's leading magnifying-glass icon above it.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = null,
                    tint = bubbleContentColor(),
                    modifier = Modifier
                        .padding(start = 12.dp, end = 8.dp)
                        .size(18.dp),
                )
                Text(
                    gloss,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = bubbleContentColor(),
                )
            }
        }

        if (isLoading) {
            LinearProgressIndicator(
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        errorText?.let {
            Column {
                Text(it, color = MaterialTheme.colorScheme.error)
                if (offerCellularDownloadRetry) {
                    TextButton(
                        onClick = { onSend(allowCellularDownload = true) },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text("Download over cellular data")
                    }
                }
            }
        }

        // Persists independently of errorText (which clears on the next
        // lookup/send) since "no WiFi yet" can easily still be true well
        // after whatever triggered this notice -- stays up, with an action,
        // until the caregiver dismisses it or the download actually starts.
        if (modelDownloadNeedsWifi && OnDeviceLlmConfig.getModelState(context) != OnDeviceLlmConfig.ModelState.READY) {
            Column {
                Text(
                    "The on-device \"what else?\" model needs WiFi to download (~2.7GB).",
                    color = MaterialTheme.colorScheme.error,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextButton(
                        onClick = {
                            ModelDownloadWorker.enqueue(context, allowCellular = true)
                            modelDownloadNeedsWifi = false
                        },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text("Download over cellular data")
                    }
                    TextButton(
                        onClick = { modelDownloadNeedsWifi = false },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text("Not now")
                    }
                }
            }
        }

        // Translate mode gets its own card, separate from the related-
        // phrases card below -- the two are different kinds of content
        // (a direct answer vs. a list of suggestions) and merging them into
        // one card read as if the phrases belonged to the translation.
        if (result?.mode == "translate" && result?.translation != null) {
            val r = result!!
            val translation = r.translation!!
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Translation:", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                // Same size as the "Related phrases for: ..."
                                // query text in the related-phrases card below.
                                translation.ru,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            Text(
                                r.input,
                                style = MaterialTheme.typography.labelSmall,
                                fontStyle = FontStyle.Italic,
                                fontWeight = FontWeight.Normal,
                                color = bubbleContentColor(),
                            )
                        }
                        // Offsets past IconButton's own 12.dp touch-target
                        // padding so the icon itself lands 16.dp from the
                        // card edge -- same visual inset as the "read aloud"
                        // icon in the search box above.
                        IconButton(
                            onClick = { speaker?.speak(translation.ru) },
                            modifier = Modifier.offset(x = 12.dp),
                        ) {
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
                    }
                }
            }
        }

        // ON_DEMAND stays hidden until the caregiver actually asks, so a
        // premature "No related phrases" doesn't pop up unprompted the
        // moment a result with no library/AI suggestions comes back --
        // mirrors the standalone AI card's own eagerMode/whatElseRequested
        // gate below (which this val is now shared with).
        val eagerMode = OnDeviceLlmConfig.getPrefetchMode(context) == OnDeviceLlmConfig.PrefetchMode.EAGER
        if (relatedForDisplay.isNotEmpty() || aiPending || eagerMode || whatElseRequested) {
            result?.let { r ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // The related-phrases section below (relatedForDisplay)
                        // already folds in the Russian-only curated-library
                        // fallback via curatedRelatedSupported, and the AI path
                        // works for any target language -- so translate mode
                        // shares the exact same related-phrases rendering as
                        // expand mode instead of a separate, library-only,
                        // Russian-only block. This is what lets a typed phrase's
                        // eager/on-demand "what else?" suggestions actually show
                        // up here, the same as a spoken utterance's.
                        when {
                            relatedForDisplay.isNotEmpty() || aiPending -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text("Related phrases for:", style = MaterialTheme.typography.labelLarge)
                                    // Deliberately plain-text, not a subtler icon --
                                    // the user explicitly wants it obvious which
                                    // source answered, not a detail you have to
                                    // notice. See ON_DEVICE_LLM_PLAN.md Phase 7.
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                    ) {
                                        Text(
                                            // aiPending is only ever true mid-AI-generation, so
                                            // it reads as "AI" even before onDeviceRelated (and
                                            // therefore usingAiSuggestions) has anything in it.
                                            if (usingAiSuggestions || aiPending) "AI" else "Library",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                                if (relatedForDisplay.isNotEmpty()) {
                                    Text(
                                        r.input,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontStyle = FontStyle.Italic,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    RelatedPhraseList(relatedForDisplay, speakingIndex, ::speakRelated)
                                } else {
                                    // Same heading + phrase as the finished state above --
                                    // the phrase and its spinner share one line instead of
                                    // stacking, and there's no second "Generating
                                    // suggestions for ..." sentence repeating the phrase.
                                    PhrasePendingRow(r.input, onCancel = ::cancelWhatElseGeneration)
                                }
                            }
                            else -> Text(
                                "No related phrases for \"${r.input}\".",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // The on-device "what else?" suggestions get their own card
        // whenever there's no backend `result` to show them inside (true
        // for every non-Russian target always, and now true for Russian
        // too since a plain utterance no longer auto-runs the backend
        // "expand" lookup -- see handleAssistantUtterance).
        //
        // Visibility depends on the mode: EAGER shows this automatically
        // once there's something to show (that's the point of "eager" --
        // found via a real device test where gating it behind
        // whatElseRequested made EAGER indistinguishable from ON_DEMAND).
        // ON_DEMAND stays hidden until the caregiver actually asks, so a
        // premature "No suggestions" doesn't pop up unprompted -- gated on
        // whatElseRequested for that mode. aiAttempted (or
        // eagerSkippedForHeat) still decides *which* state to show once
        // visible (see below), and also keeps the card from flickering away
        // mid-generation (see onDeviceGenerating's comment). eagerMode itself
        // is computed once, above, and shared with the backend-result card's
        // own identical gate.
        if (result == null && lastUtterance.isNotBlank() && (eagerMode || whatElseRequested) &&
            (aiAttempted || eagerSkippedForHeat)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Related phrases for:", style = MaterialTheme.typography.labelLarge)
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primary,
                        ) {
                            Text(
                                "AI",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    when {
                        relatedForDisplay.isNotEmpty() -> {
                            Text(
                                lastUtterance,
                                style = MaterialTheme.typography.titleMedium,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            RelatedPhraseList(relatedForDisplay, speakingIndex, ::speakRelated)
                        }
                        aiPending ->
                            // Same heading + phrase as the finished state above --
                            // the phrase and its spinner share one line instead of
                            // stacking, and there's no second "Generating
                            // suggestions for ..." sentence repeating the phrase.
                            PhrasePendingRow(lastUtterance, onCancel = ::cancelWhatElseGeneration)
                        eagerSkippedForHeat ->
                            Text(
                                "Skipped generating suggestions for “$lastUtterance” -- " +
                                    "device is running warm. Say “$nextSuggestionTriggerPhrase” " +
                                    "to generate one anyway.",
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

        // Mirrors speakMeaningOfLast()/requestWhatElse()/
        // requestAnswerSuggestions()'s own idea of "is there anything to act
        // on": while genuinely hands-free listening, only lastUtterance
        // counts (same as a spoken trigger phrase would see) -- `input` is
        // only a valid fallback while IDLE. Shared by the instructions
        // panel's cards and the command chips below so both act the same
        // way a spoken trigger phrase would, not just play a TTS demo.
        val hasUtteranceToActOn = if (assistantState == SpeechAssistant.State.IDLE) {
            lastUtterance.isNotBlank() || input.isNotBlank()
        } else {
            lastUtterance.isNotBlank()
        }

        InstructionsPanel(
            expanded = showInstructions,
            onToggle = { showInstructions = !showInstructions },
            spokenLanguage = targetLanguage.displayName,
            translateTriggerPhrase = translateTriggerPhrase,
            meaningTriggerPhrase = meaningTriggerPhrase,
            nextSuggestionTriggerPhrase = nextSuggestionTriggerPhrase,
            answerTriggerPhrase = answerTriggerPhrase,
            onSpeakTranslate = { onTranslateChipTap() },
            onSpeakMeaning = {
                if (hasUtteranceToActOn) speakMeaningOfLast() else speakTriggerPhrase(meaningTriggerPhrase)
            },
            onSpeakNext = {
                if (hasUtteranceToActOn) requestWhatElse() else speakTriggerPhrase(nextSuggestionTriggerPhrase)
            },
            onSpeakAnswer = {
                if (hasUtteranceToActOn) requestAnswerSuggestions() else speakTriggerPhrase(answerTriggerPhrase)
            },
            onSpeakBubble = { text -> speaker?.speak(text) },
            wordExampleEn = wordExample.en,
            wordExampleTranslated = wordExample.translated,
            phraseExampleHeard = phraseExample.heard,
            phraseExampleHeardGloss = phraseExample.heardGloss,
            phraseExampleResponse = phraseExample.response,
            phraseExampleResponseGloss = phraseExample.responseGloss,
            showNextSuggestionStep = nextSuggestionSupported,
            showAnswerStep = curatedRelatedSupported,
            // Extra room on top of the Column's own 14dp gap -- the search
            // box sits right above this in the common (empty-result) case,
            // and the panel reads too crowded against it otherwise.
            modifier = Modifier.padding(top = 12.dp),
        )

        CommandChips(
            // Hands-free: shown until a command is used (commandsDismissed).
            // Stopping hands-free alone should NOT hide these -- the field
            // still holding the last transcript (assistantState flips to
            // IDLE, but the text isn't cleared) used to read as "you're
            // typing now" and hide the chips out from under you the moment
            // you tapped the mic to stop.
            visible = !commandsDismissed,
            items = buildList {
                // Nothing yet for meaning/what-else/answer to act on (fresh
                // app start, or hands-free was stopped/never started and the
                // field's still empty) -- these three used to silently no-op
                // in that case instead of the tap producing *any* feedback.
                // Same fallback the hands-free branch already uses: read the
                // trigger phrase aloud as a demo instead. (hasUtteranceToActOn
                // computed above, shared with the instructions panel's cards.)
                add(
                    CommandChipSpec(
                        CommandKind.TRANSLATE, translateTriggerPhrase,
                        TriggerPhraseConfig.TRANSLATE_TRIGGER_EN,
                    ) { onTranslateChipTap() },
                )
                add(
                    CommandChipSpec(
                        CommandKind.MEANING, meaningTriggerPhrase,
                        TriggerPhraseConfig.MEANING_TRIGGER_EN,
                    ) {
                        // There's something to act on (whether idle or
                        // actively listening): the tap IS the command, same
                        // as if the trigger phrase had just been spoken.
                        // Nothing to act on yet: tapping just demonstrates
                        // how to say the trigger phrase instead.
                        if (hasUtteranceToActOn) {
                            speakMeaningOfLast()
                        } else {
                            speakTriggerPhrase(meaningTriggerPhrase)
                        }
                    },
                )
                if (nextSuggestionSupported) {
                    add(
                        CommandChipSpec(
                            CommandKind.NEXT_SUGGESTION, nextSuggestionTriggerPhrase,
                            TriggerPhraseConfig.NEXT_SUGGESTION_TRIGGER_EN,
                        ) {
                            if (hasUtteranceToActOn) {
                                requestWhatElse()
                            } else {
                                speakTriggerPhrase(nextSuggestionTriggerPhrase)
                            }
                        },
                    )
                }
                if (curatedRelatedSupported) {
                    add(
                        CommandChipSpec(
                            CommandKind.ANSWER, answerTriggerPhrase,
                            TriggerPhraseConfig.ANSWER_TRIGGER_EN,
                        ) {
                            if (hasUtteranceToActOn) {
                                requestAnswerSuggestions()
                            } else {
                                speakTriggerPhrase(answerTriggerPhrase)
                            }
                        },
                    )
                }
            },
        )
        }
    }

    if (showSettings) {
        SettingsHeaderBar(
            onClose = { showSettings = false },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }

    if (showIntro) {
        IntroScreen(
            targetLanguage = targetLanguage,
            onTargetLanguageChange = {
                targetLanguage = it
                LanguageConfig.setTargetLanguage(context, it)
            },
            audience = audience,
            onAudienceChange = {
                audience = it
                AudienceConfig.setAudience(context, it)
            },
            onDismiss = {
                showIntro = false
                IntroConfig.setHasSeenIntro(context, true)
                // Get the "what else?" model downloading as soon as the
                // caregiver is done with the intro, rather than making them
                // discover the Settings download button on their own after
                // the command silently does nothing -- see
                // maybeStartModelDownload()'s comment for the WiFi handling.
                maybeStartModelDownload()
            },
        )
    }
    }
}

/** The phrase being generated for, styled the same as the finished state's
 * heading (italic, primary), plus a "Loading…" label + spinner sharing that
 * same line -- the "AI is still generating" state, so a slow on-device model
 * reads as "working" rather than "broken" or "empty", without a second
 * sentence repeating the phrase. Sized and tinted `primary` (bigger than a
 * bare spinner would be) so it actually catches the eye instead of blending
 * into the row. An optional trailing "X" lets the caregiver cancel a
 * generation that's dragging on instead of waiting it out. */
@Composable
private fun PhrasePendingRow(phrase: String, onCancel: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            phrase,
            style = MaterialTheme.typography.titleMedium,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.5.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Loading…",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        if (onCancel != null) {
            IconButton(onClick = onCancel, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Cancel generating suggestions",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(phrase.ru, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        phrase.glossEn,
                        style = MaterialTheme.typography.labelSmall,
                        fontStyle = FontStyle.Italic,
                        color = bubbleContentColor(),
                    )
                }
                // Same 12.dp offset as the translation card's speaker icon --
                // both land 16.dp from the card edge, matching the search
                // box's "read aloud" icon.
                IconButton(
                    onClick = { onSpeak(index) },
                    modifier = Modifier.offset(x = 12.dp),
                ) {
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
