package com.botlisa.app

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device English -> [target language] fallback translation, used when
 * orchestration-service's /assist reports no trustworthy curated-library
 * match -- either no match at all (`source == "mock"`), or the target
 * language isn't Russian, in which case a "curated match" can't be trusted
 * anyway (the library only has Russian content) -- see MainActivity.kt's
 * onSend(). Runs entirely on-device via Google's ML Kit -- no API key, no
 * network call at translate time (after a one-time per-language model
 * download), no per-call cost.
 *
 * Deliberately NOT used as the primary translation path: it produces
 * grammatically correct but standard/textbook phrasing, not the warm,
 * diminutive-heavy "baby register" the curated phrase library (and the
 * backend's LLM fallback, if ANTHROPIC_API_KEY is ever set) are written to
 * match for Russian -- see the project roadmap for that tradeoff.
 *
 * Caches one Translator per target language, so switching languages in
 * Settings and switching back doesn't re-download a model already fetched.
 */
object OnDeviceTranslator {

    private val translators = mutableMapOf<String, Translator>()

    private fun translatorFor(mlKitLanguage: String): Translator =
        translators.getOrPut(mlKitLanguage) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(mlKitLanguage)
                .build()
            Translation.getClient(options)
        }

    // Wifi-required by default (ML Kit's own default) -- each target
    // language is its own one-time ~30MB download, not worth burning
    // someone's cellular data plan on automatically. Once downloaded it's
    // cached on-device and every later call (wifi or not) is instant.
    private val downloadConditions = DownloadConditions.Builder()
        .requireWifi()
        .build()

    /**
     * Downloads the on-device model for [target] if needed, then translates
     * [text] into it. Throws on failure (e.g. model not yet downloaded and
     * no wifi available right now) -- caller is responsible for catching
     * this and showing a friendly message, see MainActivity.kt's onSend().
     */
    suspend fun translate(text: String, target: TargetLanguage): String {
        val translator = translatorFor(target.mlKitLanguage)
        translator.downloadModelIfNeeded(downloadConditions).await()
        return translator.translate(text).await()
    }

    // Intentionally never called from an Activity lifecycle method (e.g.
    // onDestroy): this object is a process-wide singleton, not scoped to one
    // screen, and closing it on rotation would leave every cached translator
    // permanently unusable after the Activity recreates -- the exact class of
    // bug the rotation fix elsewhere in this app just removed. The OS reclaims
    // it naturally when the whole app process dies. Exposed for a future
    // caller that genuinely owns the translators' lifetime.
    fun closeAll() {
        translators.values.forEach { it.close() }
        translators.clear()
    }
}

private suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result -> cont.resume(result) }
        addOnFailureListener { exception -> cont.resumeWithException(exception) }
        addOnCanceledListener { cont.cancel() }
    }
