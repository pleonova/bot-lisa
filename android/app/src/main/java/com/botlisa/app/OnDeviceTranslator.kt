package com.botlisa.app

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Thrown when the on-device model still hasn't downloaded after
 * [OnDeviceTranslator]'s timeout -- almost always because the device isn't
 * on wifi yet (ML Kit's requireWifi() gate) and this is the first time
 * [languageName] has been used. Distinct from other ML Kit failures so
 * callers can show the caregiver a specific, actionable reason instead of a
 * generic "translation failed".
 */
class ModelDownloadRequiredException(languageName: String) : Exception(
    "The $languageName translation model hasn't downloaded yet -- connect to wifi, or allow downloading over cellular data."
)

/**
 * On-device Google ML Kit translation, both directions:
 * - `translate` (English -> target language): the fallback used when
 *   orchestration-service's /assist reports no trustworthy curated-library
 *   match -- either no match at all (`source == "mock"`), or the target
 *   language isn't Russian (the library only has Russian content) -- see
 *   MainActivity.kt's onSend().
 * - `translateToEnglish` (target language -> English): the small-print gloss
 *   shown under the hands-free transcript.
 *
 * Runs entirely on-device -- no API key, no network call at translate time
 * (after a one-time per-language, per-direction model download), no
 * per-call cost.
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

    private fun translatorFor(source: String, target: String): Translator =
        translators.getOrPut("$source>$target") {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build()
            Translation.getClient(options)
        }

    // Wifi-required by default (ML Kit's own default) -- each target
    // language is its own one-time ~30MB download, not worth burning
    // someone's cellular data plan on automatically. Once downloaded it's
    // cached on-device and every later call (wifi or not) is instant.
    // Callers can pass allowCellular = true (after the caregiver explicitly
    // opts in, see MainActivity.kt's "Download over cellular data" retry) to
    // use [cellularAllowedConditions] instead.
    private val wifiOnlyConditions = DownloadConditions.Builder()
        .requireWifi()
        .build()
    private val cellularAllowedConditions = DownloadConditions.Builder().build()

    private fun downloadConditionsFor(allowCellular: Boolean) =
        if (allowCellular) cellularAllowedConditions else wifiOnlyConditions

    // ML Kit's downloadModelIfNeeded() task only completes once its network
    // condition is satisfied -- if that condition (wifi, by default) is
    // never met, it never fails, it just never resolves. Without a timeout
    // that leaves the caller's coroutine (and isLoading, in
    // MainActivity.kt's onSend()) suspended forever instead of falling back
    // or showing an error, which is exactly what happened for a language
    // whose model hadn't been downloaded yet (e.g. Mandarin on a
    // cellular-only device) while other, already-cached languages kept
    // working. Generous enough to cover a full ~30MB download over slow
    // cellular, not just a wifi wait.
    private const val DOWNLOAD_TIMEOUT_MS = 45_000L

    /**
     * Downloads the on-device model for [target] if needed, then translates
     * [text] into it. Requires wifi for that download unless [allowCellular]
     * is true. Throws [ModelDownloadRequiredException] if the model still
     * isn't ready after the timeout, or a plain [Exception] on any other ML
     * Kit failure -- caller is responsible for catching this and showing a
     * friendly message, see MainActivity.kt's onSend().
     */
    suspend fun translate(text: String, target: TargetLanguage, allowCellular: Boolean = false): String {
        val translator = translatorFor(TranslateLanguage.ENGLISH, target.mlKitLanguage)
        try {
            return withTimeout(DOWNLOAD_TIMEOUT_MS) {
                translator.downloadModelIfNeeded(downloadConditionsFor(allowCellular)).await()
                translator.translate(text).await()
            }
        } catch (_: TimeoutCancellationException) {
            throw ModelDownloadRequiredException(target.displayName)
        }
    }

    /**
     * Reverse direction: translates [text] (assumed to be in [from]'s
     * language) into English -- used for the small-print gloss under the
     * transcript. Own one-time per-language model download, same conditions.
     */
    suspend fun translateToEnglish(text: String, from: TargetLanguage, allowCellular: Boolean = false): String {
        if (from.mlKitLanguage == TranslateLanguage.ENGLISH) return text
        val translator = translatorFor(from.mlKitLanguage, TranslateLanguage.ENGLISH)
        try {
            return withTimeout(DOWNLOAD_TIMEOUT_MS) {
                translator.downloadModelIfNeeded(downloadConditionsFor(allowCellular)).await()
                translator.translate(text).await()
            }
        } catch (_: TimeoutCancellationException) {
            throw ModelDownloadRequiredException(from.displayName)
        }
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
