package com.rubotlisa.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AskResult(
    val ru: String,
    val mode: String,
    val groundingPhrases: List<String>,
    val latencyMs: Double,
)

/**
 * Talks to services/ingestion_service (POST /event/voice), which forwards to
 * orchestration-service and returns its /ask response.
 *
 * BASE_URL defaults to the Android emulator's alias for the host machine's
 * localhost. If you're running on a real device, point this at your dev
 * machine's LAN IP instead (e.g. "http://192.168.1.42:8003").
 */
object ApiClient {

    private const val BASE_URL = "http://10.0.2.2:8003"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun sendVoiceEvent(transcript: String, routineHint: String?): AskResult =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("transcript", transcript)
                if (routineHint != null) put("routine_hint", routineHint)
            }

            val request = Request.Builder()
                .url("$BASE_URL/event/voice")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw java.io.IOException("HTTP ${response.code}: $bodyString")
                }
                val root = JSONObject(bodyString)
                val ask = root.getJSONObject("response")
                val phrases = ask.getJSONArray("grounding_phrases")
                AskResult(
                    ru = ask.getString("ru"),
                    mode = ask.getString("mode"),
                    groundingPhrases = (0 until phrases.length()).map { phrases.getString(it) },
                    latencyMs = ask.getDouble("latency_ms"),
                )
            }
        }
}
