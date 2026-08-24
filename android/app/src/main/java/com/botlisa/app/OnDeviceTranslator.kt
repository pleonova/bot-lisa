package com.botlisa.app

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device English -> Russian fallback translation, used only when
 * orchestration-service's /assist reports no curated-library match
 * (`source != "curated"`). Runs entirely on-device via Google's ML Kit --
 * no API key, no network call at translate time (after a one-time model
 * download), no per-call cost. This is the "native Google Translate"
 * option: a real Google translation library, but the on-device one, not
 * the Cloud Translation API.
 *
 * Deliberately NOT used as the primary translation path: it produces
 * grammatically correct but standard/textbook Russian, not the warm,
 * diminutive-heavy "baby register" the curated phrase library (and the
 * backend's LLM fallback, if ANTHROPIC_API_KEY is ever set) are written to
 * match -- see the project roadmap for that tradeoff. Curated phrases
 * always win when one exists; this only fills the gap for everything else.
 */
object OnDeviceTranslator {

    private val translator by lazy {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.RUSSIAN)
            .build()
        Translation.getClient(options)
    }

    // Wifi-required by default (ML Kit's own default) -- the EN<->RU model is
    // a one-time ~30MB download, not worth burning someone's cellular data
    // plan on automatically. Once downloaded it's cached on-device and every
    // later call (wifi or not) is instant with no further download.
    private val downloadConditions = DownloadConditions.Builder()
        .requireWifi()
        .build()

    /**
     * Downloads the on-device model if needed, then translates [text].
     * Throws on failure (e.g. model not yet downloaded and no wifi
     * available right now) -- caller is responsible for catching this and
     * showing a friendly message, see MainActivity.kt's onSend().
     */
    suspend fun translate(text: String): String {
        translator.downloadModelIfNeeded(downloadConditions).await()
        return translator.translate(text).await()
    }

    // Intentionally never called from an Activity lifecycle method (e.g.
    // onDestroy): this object is a process-wide singleton, not scoped to one
    // screen, and closing it on rotation would leave the cached `translator`
    // permanently unusable after the Activity recreates -- the exact class of
    // bug the rotation fix elsewhere in this app just removed. The OS reclaims
    // it naturally when the whole app process dies. Exposed for a future
    // caller that genuinely owns the translator's lifetime.
    fun close() {
        translator.close()
    }
}

private suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result -> cont.resume(result) }
        addOnFailureListener { exception -> cont.resumeWithException(exception) }
        addOnCanceledListener { cont.cancel() }
    }
