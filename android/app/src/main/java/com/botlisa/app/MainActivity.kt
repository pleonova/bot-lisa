package com.botlisa.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LisaScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LisaScreen() {
    val scope = rememberCoroutineScope()

    // rememberSaveable (not plain remember) for anything the user would be upset to lose on
    // an Activity recreation -- most commonly a screen rotation. Plain `remember` state is
    // wiped when the Activity is destroyed and recreated, which is what was happening here:
    // rotating the phone reset the whole screen back to its empty starting state. isLoading
    // is deliberately left as plain `remember`: if a request was in flight during rotation,
    // its coroutine (scoped to this composition) is gone either way, so persisting `true`
    // would leave the button stuck disabled forever with nothing left to ever set it false.
    var input by rememberSaveable { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by rememberSaveable { mutableStateOf<String?>(null) }
    var result by rememberSaveable { mutableStateOf<AssistResult?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current
    var serverUrl by remember { mutableStateOf(ServerConfig.getBaseUrl(context)) }
    var apiKey by remember { mutableStateOf(ServerConfig.getApiKey(context)) }
    var showServerSettings by rememberSaveable { mutableStateOf(false) }

    // Target language for translation + spoken output -- NOT the
    // related-phrases/expand mode, which stays Russian-only regardless. See
    // LanguageConfig.kt for why.
    var targetLanguage by remember { mutableStateOf(LanguageConfig.getTargetLanguage(context)) }
    var languageMenuExpanded by remember { mutableStateOf(false) }

    // Speaks translation results aloud -- see TranslationSpeaker.kt. Rebuilt
    // whenever the target language changes (or on rotation, like any other
    // `remember`); the previous instance is shut down first so the TTS
    // engine doesn't leak.
    var speaker by remember { mutableStateOf<TranslationSpeaker?>(null) }
    DisposableEffect(targetLanguage) {
        val current = TranslationSpeaker(context, targetLanguage.ttsLocale)
        speaker = current
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

    // Launches the system speech-to-text UI and fills the input field with
    // whatever it heard. Locale is intentionally left as Russian regardless
    // of the target-language setting above: dictation here mainly serves
    // related-phrase/expand mode, which stays Russian-only (see
    // LanguageConfig.kt). Voice input that actually follows the target
    // language needs per-utterance language detection -- a bigger piece
    // tracked as the project roadmap's "Speech Assistant" phase, not done
    // here.
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {
            val spoken = activityResult.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                input = spoken
            }
        }
    }

    fun launchSpeechRecognizer() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, SupportedLanguages.RUSSIAN.code)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a Russian phrase, or type English to translate…")
        }
        runCatching { speechLauncher.launch(intent) }
            .onFailure { errorText = "No speech recognizer available on this device." }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) launchSpeechRecognizer() }

    fun onMicClick() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) launchSpeechRecognizer() else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun onSend() {
        if (input.isBlank()) return
        errorText = null
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
            } catch (e: IOException) {
                errorText = "Couldn't reach the server: ${e.message}"
            } catch (e: Exception) {
                errorText = "Something went wrong: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Bot Lisa", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { showServerSettings = !showServerSettings }) {
                Text(if (showServerSettings) "Hide settings" else "Settings")
            }
        }
        Text(
            "Type an English word to translate it into ${targetLanguage.displayName}, " +
                "or a Russian phrase to see related ones from the curated library.",
            style = MaterialTheme.typography.bodyMedium,
        )

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

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("English word/phrase, or Russian phrase") },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = { onMicClick() }) {
                    Icon(Icons.Filled.Mic, contentDescription = "Dictate")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )

        Button(
            onClick = { onSend() },
            enabled = input.isNotBlank() && !isLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Send, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isLoading) "Looking up…" else "Look up")
        }

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        errorText?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        result?.let { r ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (r.mode == "translate" && r.translation != null) {
                        Text("Translation:", style = MaterialTheme.typography.labelLarge)
                        Text(r.translation.ru, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "\"${r.input}\" · source: ${r.source} · ${r.latencyMs.toInt()} ms",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text("Related phrases:", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "for \"${r.input}\" · ${r.latencyMs.toInt()} ms",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    // The related-phrases list always comes from the Russian
                    // curated library. In expand mode that's the whole point, so
                    // always show it; in translate mode it's only relevant when
                    // the target language actually is Russian.
                    val showRelated = r.related.isNotEmpty() &&
                        (r.mode == "expand" || targetLanguage.code == SupportedLanguages.RUSSIAN.code)
                    if (showRelated) {
                        Spacer(Modifier.height(4.dp))
                        if (r.mode == "translate") {
                            Text("More related phrases (Russian curated library):", style = MaterialTheme.typography.labelMedium)
                        }
                        r.related.forEach { phrase ->
                            Column {
                                Text("• ${phrase.ru}", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "   ${phrase.glossEn}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
