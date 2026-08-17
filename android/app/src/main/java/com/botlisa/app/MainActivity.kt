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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
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
 * switch between emulator and a real device.
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

    var input by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<AssistResult?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current
    var serverUrl by remember { mutableStateOf(ServerConfig.getBaseUrl(context)) }
    var showServerSettings by remember { mutableStateOf(false) }

    fun onServerUrlChange(newUrl: String) {
        serverUrl = newUrl
        ServerConfig.setBaseUrl(context, newUrl)
    }

    // Launches the system speech-to-text UI and fills the input field with
    // whatever it heard. Locale left as ru-RU by default since most dictation
    // here will be Russian phrases to expand on; English typed input works
    // fine too since it's just plain text either way.
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
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
                result = ApiClient.sendAssist(baseUrl = serverUrl, text = input)
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
                Text(if (showServerSettings) "Hide server settings" else "Server settings")
            }
        }
        Text(
            "Type an English word to translate it, or a Russian phrase to see related ones.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (showServerSettings) {
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { onServerUrlChange(it) },
                label = { Text("Server URL") },
                supportingText = { Text("Emulator: http://10.0.2.2:8002 · Real device: http://<mac-lan-ip>:8002") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
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

                    if (r.related.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        if (r.mode == "translate") {
                            Text("More related phrases:", style = MaterialTheme.typography.labelMedium)
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
