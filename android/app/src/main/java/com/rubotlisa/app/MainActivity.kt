package com.rubotlisa.app

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
import java.util.Locale

/**
 * Single-screen front end for ru-bot-lisa.
 *
 * Flow: type or dictate a transcript -> POST to ingestion-service's
 * /event/voice endpoint -> show the returned Russian phrase.
 *
 * Networking config lives in ApiClient.kt (BASE_URL). Defaults to
 * http://10.0.2.2:8003, which is how the Android emulator reaches
 * "localhost:8003" on your dev machine.
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

    var transcript by remember { mutableStateOf("") }
    var routineHint by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<AskResult?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current
    var serverUrl by remember { mutableStateOf(ServerConfig.getBaseUrl(context)) }
    var showServerSettings by remember { mutableStateOf(false) }

    fun onServerUrlChange(newUrl: String) {
        serverUrl = newUrl
        ServerConfig.setBaseUrl(context, newUrl)
    }

    // Launches the system speech-to-text UI and fills the transcript field
    // with whatever it heard.
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {
            val spoken = activityResult.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                transcript = spoken
            }
        }
    }

    fun launchSpeechRecognizer() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say something in Russian…")
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
        if (transcript.isBlank()) return
        errorText = null
        isLoading = true
        scope.launch {
            try {
                result = ApiClient.sendVoiceEvent(
                    baseUrl = serverUrl,
                    transcript = transcript,
                    routineHint = routineHint.ifBlank { null },
                )
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
            Text("RU Bot Lisa", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { showServerSettings = !showServerSettings }) {
                Text(if (showServerSettings) "Hide server settings" else "Server settings")
            }
        }
        Text(
            "Type or say what the caregiver/child said. Lisa will reply with a grounded Russian phrase.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (showServerSettings) {
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { onServerUrlChange(it) },
                label = { Text("Server URL") },
                supportingText = { Text("Emulator: http://10.0.2.2:8003 · Real device: http://<mac-lan-ip>:8003") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }

        OutlinedTextField(
            value = transcript,
            onValueChange = { transcript = it },
            label = { Text("Transcript") },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = { onMicClick() }) {
                    Icon(Icons.Filled.Mic, contentDescription = "Dictate")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )

        OutlinedTextField(
            value = routineHint,
            onValueChange = { routineHint = it },
            label = { Text("Routine hint (optional, e.g. sleep, bath, meal)") },
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = { onSend() },
            enabled = transcript.isNotBlank() && !isLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Send, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isLoading) "Sending…" else "Send")
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
                    Text("Lisa says:", style = MaterialTheme.typography.labelLarge)
                    Text(r.ru, style = MaterialTheme.typography.headlineSmall)
                    Text("mode: ${r.mode} · ${r.latencyMs.toInt()} ms", style = MaterialTheme.typography.bodySmall)
                    if (r.groundingPhrases.isNotEmpty()) {
                        Text("Grounding phrases:", style = MaterialTheme.typography.labelMedium)
                        r.groundingPhrases.forEach { phrase ->
                            Text("• $phrase", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
