package com.botlisa.app

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Single-screen caregiver-assist front end for bot-lisa.
 *
 * One text box, one behavior, auto-detected on the backend:
 * - Type/dictate an English word or phrase -> get its baby-register Russian
 *   translation (curated phrase library first, LLM fallback if nothing
 *   matches).
 * - Type/dictate a Russian phrase -> get related phrases from the library to
 *   expand your own active vocabulary around it.
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
 * listening in Russian. Two independently editable trigger phrases (Settings
 * -> "Trigger phrase (translate)" / "Trigger phrase (next suggestion)")
 * drive it:
 *   - say the translate trigger ("как сказать" by default), pause, then an
 *     English word -- that word is sent to /assist as translate-mode.
 *   - say the next-suggestion trigger ("что ещё" by default) to have the
 *     app read the next related/suggested phrase from the most recent
 *     lookup aloud; say it again to hear the next one in that list.
 *   - anything else defaults to a normal Russian utterance, sent to
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

    // Target language for translation + spoken output -- NOT the
    // related-phrases/expand mode, which stays Russian-only regardless. See
    // LanguageConfig.kt for why.
    var targetLanguage by remember { mutableStateOf(LanguageConfig.getTargetLanguage(context)) }
    var languageMenuExpanded by remember { mutableStateOf(false) }

    // Lisa Assistant's two trigger phrases -- editable here, persisted via
    // TriggerPhraseConfig.kt (same SharedPreferences pattern as the target
    // language and server settings above). Only Russian has a listening
    // mode wired up today, so this edits those two entries.
    var translateTriggerPhrase by remember {
        mutableStateOf(TriggerPhraseConfig.getTranslateTriggerPhrase(context, SupportedLanguages.RUSSIAN.code))
    }
    fun onTranslateTriggerPhraseChange(newPhrase: String) {
        translateTriggerPhrase = newPhrase
        TriggerPhraseConfig.setTranslateTriggerPhrase(context, SupportedLanguages.RUSSIAN.code, newPhrase)
    }
    var nextSuggestionTriggerPhrase by remember {
        mutableStateOf(TriggerPhraseConfig.getNextSuggestionTriggerPhrase(context, SupportedLanguages.RUSSIAN.code))
    }
    fun onNextSuggestionTriggerPhraseChange(newPhrase: String) {
        nextSuggestionTriggerPhrase = newPhrase
        TriggerPhraseConfig.setNextSuggestionTriggerPhrase(context, SupportedLanguages.RUSSIAN.code, newPhrase)
    }

    // TTS playback state. Drives the speaker-icon pulse in the result card,
    // and -- crucially -- mutes the mic (isMuted below) so the assistant
    // reading a suggestion aloud isn't transcribed back as a user utterance.
    var translationSpeaking by remember { mutableStateOf(false) }
    var relatedSpeaking by remember { mutableStateOf(false) }
    // Which related-phrase row is currently being read (null = none). Cleared
    // by russianSpeaker's onSpeakingChanged when playback ends.
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

    // A second, Russian-locale-fixed speaker for reading related/suggested
    // phrases aloud (the next-suggestion trigger, below). Related phrases
    // always come from the Russian curated library regardless of the
    // target-language setting above -- same reason related-phrase lookup
    // itself stays Russian-only, see LanguageConfig.kt -- so this can't
    // reuse `speaker`, which follows the target language and would
    // mispronounce Russian text through e.g. a Hindi voice.
    var russianSpeaker by remember { mutableStateOf<TranslationSpeaker?>(null) }
    DisposableEffect(Unit) {
        val current = TranslationSpeaker(
            context,
            SupportedLanguages.RUSSIAN.ttsLocale,
            onSpeakingChanged = { speaking ->
                relatedSpeaking = speaking
                if (!speaking) speakingIndex = null
            },
        )
        russianSpeaker = current
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
                var assist = ApiClient.sendAssist(baseUrl = serverUrl, apiKey = apiKey, text = input)
                if (assist.mode == "translate") {
                    // The backend's curated-phrase match only ever checks against
                    // the Russian library -- for any other target language it
                    // can't be trusted (it could "match" a Russian phrase by word
                    // overlap even though e.g. Hindi was requested). Trust the
                    // backend's curated result only when the target language
                    // actually is Russian; otherwise, and whenever the backend had
                    // no match at all (source == "mock"), translate on-device into
                    // whichever language is currently selected. NOTE: on-device
                    // translation produces standard/textbook phrasing, not the
                    // curated library's baby-register tone -- see the roadmap.
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
                errorText = "Couldn't reach the server: ${e.message}"
            } catch (e: Exception) {
                errorText = "Something went wrong: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    // Speaks the next related/suggested phrase from the most recent
    // /assist result aloud, cycling through the list and wrapping back to
    // the start once it runs out. Triggered by the next-suggestion voice
    // command (see SpeechAssistant.kt) -- doesn't touch `input`/onSend() at
    // all, since this reads existing suggestions rather than making a new
    // request.
    fun speakNextSuggestion() {
        val related = result?.related.orEmpty()
        if (related.isEmpty()) {
            russianSpeaker?.speak("Пока нет предложений.")
            return
        }
        val index = suggestionIndex % related.size
        // Only claim the row / advance if TTS actually started, so a
        // not-ready engine doesn't leave the mic muted or a row stuck lit.
        if (russianSpeaker?.speak(related[index].ru) == true) {
            speakingIndex = index
            suggestionIndex = index + 1
        }
    }

    // Reads one specific related phrase aloud -- the trailing speaker button
    // on a result row. Sets suggestionIndex so a following "что ещё?"
    // continues from the next one.
    fun speakRelated(index: Int) {
        val phrase = result?.related?.getOrNull(index) ?: return
        if (russianSpeaker?.speak(phrase.ru) == true) {
            speakingIndex = index
            suggestionIndex = index + 1
        }
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
    val handleAssistantUtterance = rememberUpdatedState<(String) -> Unit> { text ->
        input = text
        commandsDismissed = false // fresh utterance being sent -> chips come back
        onSend()
    }
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
            defaultLanguageCode = SupportedLanguages.RUSSIAN.code,
            translateLanguageCode = "en-US",
            getTranslateTriggerPhrase = {
                TriggerPhraseConfig.getTranslateTriggerPhrase(context, SupportedLanguages.RUSSIAN.code)
            },
            getNextSuggestionTriggerPhrase = {
                TriggerPhraseConfig.getNextSuggestionTriggerPhrase(context, SupportedLanguages.RUSSIAN.code)
            },
            onUtterance = { text, _ -> handleAssistantUtterance.value(text) },
            onNextSuggestionRequested = { handleNextSuggestionRequest.value() },
            onTranscript = { handleTranscript.value(it) },
            isMuted = { translationSpeaking || relatedSpeaking },
            onStateChanged = { assistantState = it },
            onError = { assistantError = it },
        )
    }
    DisposableEffect(Unit) {
        onDispose { assistant.stop() }
    }

    val assistantMicPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            assistantError = null
            assistant.start()
        } else {
            assistantError = "Microphone permission is required for Lisa Assistant."
        }
    }

    fun onToggleAssistant() {
        if (assistantState != SpeechAssistant.State.IDLE) {
            assistant.stop()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            assistantError = null
            assistant.start()
        } else {
            assistantMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Tapping the fox logo returns the screen to its opening state -- stops
    // hands-free, clears the field/result/errors and any expanded panels.
    // Persistent settings (server, language, trigger phrases) are left alone.
    fun resetToStart() {
        assistant.stop()
        // Drop focus so the field isn't left selected -- a focused field
        // hides the "start typing" helper under the buttons.
        focusManager.clearFocus()
        input = ""
        result = null
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
            ExposedDropdownMenuBox(
                expanded = languageMenuExpanded,
                onExpandedChange = { languageMenuExpanded = it },
            ) {
                OutlinedTextField(
                    value = targetLanguage.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Target language") },
                    supportingText = { Text("What English translates into, and the voice that reads it back. Related-phrase lookup stays Russian-only for now.") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageMenuExpanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = languageMenuExpanded,
                    onDismissRequest = { languageMenuExpanded = false },
                ) {
                    SupportedLanguages.ALL.forEach { language ->
                        DropdownMenuItem(
                            text = { Text(language.displayName) },
                            onClick = {
                                targetLanguage = language
                                LanguageConfig.setTargetLanguage(context, language)
                                languageMenuExpanded = false
                            },
                        )
                    }
                }
            }
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
                label = { Text("Trigger phrase (translate)") },
                supportingText = { Text("Say this, pause, then an English word, to have Lisa Assistant translate it instead of treating it as Russian.") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = nextSuggestionTriggerPhrase,
                onValueChange = { onNextSuggestionTriggerPhraseChange(it) },
                label = { Text("Trigger phrase (next suggestion)") },
                supportingText = { Text("Say this to have Lisa Assistant read the next suggested phrase aloud. Say it again for the next one in the list.") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
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
            placeholder = { Text("Enter English or Russian Text") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
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

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        InstructionsPanel(
            expanded = showInstructions,
            onToggle = { showInstructions = !showInstructions },
            translateTriggerPhrase = translateTriggerPhrase,
            nextSuggestionTriggerPhrase = nextSuggestionTriggerPhrase,
        )

        CommandChips(
            // Hands-free: shown until a command is used (commandsDismissed).
            // Text mode (IDLE): shown only while the field is empty -- once
            // you type, the keyboard's Search key does the same job.
            visible = !commandsDismissed &&
                (assistantState != SpeechAssistant.State.IDLE || input.isBlank()),
            translatePhrase = translateTriggerPhrase,
            // TODO(step 6b): translate these captions targetLanguage -> English
            // via OnDeviceTranslator; static fallbacks for now.
            translateCaption = "how to say",
            nextPhrase = nextSuggestionTriggerPhrase,
            nextCaption = "what else",
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
                    } else if (r.related.isNotEmpty()) {
                        Text("Related phrases:", style = MaterialTheme.typography.labelLarge)
                        RelatedPhraseList(r.related, speakingIndex, ::speakRelated)
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
