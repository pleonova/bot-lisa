package com.rubotlisa.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class Phrase(
    val ru: String,
    val glossEn: String,
)

data class AssistResult(
    val mode: String, // "translate" (English in) or "expand" (Russian in)
    val source: String?, // translate mode only: "curated" | "live" | "mock"
    val input: String,
    val translation: Phrase?,
    val related: List<Phrase>,
    val latencyMs: Double,
)

/**
 * Talks to services/orchestration_service's caregiver-facing POST /assist
 * endpoint (distinct from /ask, which is the child-perception flow used
 * internally by ingestion-service).
 *
 * One text box, auto-detected on the backend: English in -> translation
 * (curated phrase library first, LLM fallback if no good match); Russian in
 * -> related phrases to expand the caregiver's own vocabulary.
 *
 * The server base URL is NOT hardcoded -- it's passed in by the caller (see
 * ServerConfig.kt), which persists it in SharedPreferences. Default points at
 * orchestration-service directly (port 8002), since /assist lives there.
 */
object ApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun sendAssist(baseUrl: String, text: String): AssistResult =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply { put("text", text) }

            val normalizedBaseUrl = baseUrl.trimEnd('/')
            val request = Request.Builder()
                .url("$normalizedBaseUrl/assist")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw java.io.IOException("HTTP ${response.code}: $bodyString")
                }
                val root = JSONObject(bodyString)
                AssistResult(
                    mode = root.getString("mode"),
                    source = if (root.isNull("source")) null else root.getString("source"),
                    input = root.getString("input"),
                    translation = root.optJSONObject("translation")?.toPhrase(),
                    related = root.getJSONArray("related").toPhraseList(),
                    latencyMs = root.getDouble("latency_ms"),
                )
            }
        }

    private fun JSONObject.toPhrase(): Phrase =
        Phrase(ru = getString("ru"), glossEn = getString("gloss_en"))

    private fun JSONArray.toPhraseList(): List<Phrase> =
        (0 until length()).map { getJSONObject(it).toPhrase() }
}
