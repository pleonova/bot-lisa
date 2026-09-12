package com.botlisa.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.botlisa.llm.InferenceEngine
import com.botlisa.llm.LlmEngine
import com.botlisa.llm.isModelLoaded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * App-level integration seam for the on-device "что ещё" LLM: owns the
 * SDK/RAM eligibility gate, the model's lazy-load/idle-unload lifecycle, and
 * stitches PromptComposer (Phase 3) + the vendored InferenceEngine (Phase 2)
 * together. Same singleton-object-with-suspend-funs shape as the existing
 * OnDeviceTranslator.kt. See ON_DEVICE_LLM_PLAN.md Phase 4.
 *
 * NOT wired into MainActivity yet -- that's Phase 7. The model path and the
 * WhatElseSource/ModelState settings live in OnDeviceLlmConfig.kt (Phase 5),
 * not here.
 */
object OnDeviceLlm {
    private const val TAG = "OnDeviceLlm"

    enum class Availability { UNSUPPORTED_DEVICE, LOW_RAM, NOT_DOWNLOADED, LOADING, READY, ERROR }

    // The bundled model (Qwen3.5-4B, arch "qwen35") is a reasoning model
    // that, left alone, spends this entire budget inside a "<think>
    // Thinking Process..." block and never reaches the phrases (verified
    // on-device: ~5 tok/s on a Pixel 11, so 256 tokens truncates
    // mid-thought). llama_bridge.cpp now prefills an empty "<think></think>"
    // onto the assistant turn for reasoning templates -- the same thing
    // Qwen3's own enable_thinking=false does -- so generation goes straight
    // to the answer and 256 is ample for three short phrases. The <think>
    // stripping below stays as a backstop.
    private const val PREDICT_LENGTH = 256

    // See ON_DEVICE_LLM_PLAN.md Phase 2 risk notes: Build.VERSION.SDK_INT
    // bounds the Java heap, not the native mmap'd model + KV cache -- a
    // total-RAM floor is a separate, necessary check.
    private const val MIN_RAM_BYTES = 6L * 1024 * 1024 * 1024

    // "что ещё" is bursty/occasional, not continuous -- free the ~2.7GB+
    // resident model after this much idle time rather than for the app's
    // whole process lifetime.
    private const val IDLE_UNLOAD_MS = 5 * 60 * 1000L

    private val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val thinkTag = Regex("<think>.*?</think>\\s*", RegexOption.DOT_MATCHES_ALL)

    private var engine: InferenceEngine? = null
    private var modelLoaded = false
    // The system prompt currently baked into the loaded model. InferenceEngine
    // only accepts setSystemPrompt() once, right after loadModel() -- so the
    // persona/language system prompt is fixed for the lifetime of a load.
    // Tracking it lets us notice a language switch on a still-warm model and
    // reload rather than silently generate against the previous language's
    // prompt. Null whenever no model is loaded.
    private var loadedSystemPrompt: String? = null
    private var idleUnloadJob: Job? = null

    @Volatile private var isGenerating = false
    @Volatile private var lastError: Exception? = null

    fun isDeviceCapable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && hasEnoughRam(context)

    /**
     * True once [PowerManager.getCurrentThermalStatus] is above NONE --
     * i.e. the device is already running warm. Used to make EAGER prefetch
     * back off rather than pile more heat-generating inference onto a
     * device that's already elevated (measured directly: 20 back-to-back
     * "what else?" calls pushed a Pixel 11 from NONE to LIGHT in under two
     * minutes -- see android/app/benchmarks/README.md). API 29+; below
     * that this can't be observed and reports false (not elevated) --
     * moot in practice since [isDeviceCapable] already requires API 33+.
     */
    fun isThermallyElevated(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.currentThermalStatus > PowerManager.THERMAL_STATUS_NONE
    }

    private fun hasEnoughRam(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info.totalMem >= MIN_RAM_BYTES
    }

    /**
     * Cheap, synchronous status check -- for gating whether to attempt
     * [generateWhatElse] and for a Settings status line (Phase 5).
     */
    fun availability(context: Context): Availability {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return Availability.UNSUPPORTED_DEVICE
        if (!hasEnoughRam(context)) return Availability.LOW_RAM
        if (isGenerating) return Availability.LOADING
        if (lastError != null) return Availability.ERROR
        // Both checks matter, for opposite failure modes: modelState catches
        // a file that's merely present but not actually a finished download
        // (ModelDownloadWorker writes bytes to this same path incrementally
        // while state is DOWNLOADING -- checking existence alone would treat
        // a half-downloaded file as READY and hand it to loadModel(), which
        // then "corrects" the ostensibly-corrupt file by deleting it, wiping
        // out perfectly good download progress). File.exists() still matters
        // too, so a manually-deleted file self-corrects instead of leaving a
        // stale READY status behind, per ModelState's own doc comment. See
        // ON_DEVICE_LLM_PLAN.md Phase 6 -- found via a real device test that
        // deleted a 1.2GB in-progress download this way.
        if (OnDeviceLlmConfig.getModelState(context) != OnDeviceLlmConfig.ModelState.READY) {
            return Availability.NOT_DOWNLOADED
        }
        if (!File(OnDeviceLlmConfig.modelFilePath(context)).exists()) return Availability.NOT_DOWNLOADED
        return Availability.READY
    }

    /**
     * Whether a [generateWhatElse] call is worth attempting right now:
     * everything [availability] requires for READY, except a previous
     * failure ([lastError]) is treated as retryable rather than latching the
     * feature off until the app restarts. A still-running generation
     * (LOADING) is not retryable -- the mutex would just serialize behind
     * it. Use this for the prefetch gate; [availability] stays exact for a
     * status line.
     */
    fun canGenerate(context: Context): Boolean =
        when (availability(context)) {
            Availability.READY, Availability.ERROR -> true
            else -> false
        }

    /**
     * Generates on-device "what else" suggestions for [heard], in the target
     * language given by [languageCode] (a SupportedLanguages code, e.g.
     * "ru-RU"). The persona/few-shot prompt is built per language by
     * PromptComposer. Loads the model on first call (or after an idle
     * unload), reuses it while it stays warm -- but reloads it when
     * [languageCode] changes, since its system prompt can only be set once
     * per load. Throws on failure -- callers (Phase 7) are expected to catch
     * and fall back to the network path, not surface this directly.
     */
    suspend fun generateWhatElse(context: Context, heard: String, languageCode: String): List<Phrase> = mutex.withLock {
        idleUnloadJob?.cancel()
        isGenerating = true
        // Clear any latched error from a prior attempt -- this call is the
        // retry. A fresh failure below re-sets it.
        lastError = null
        try {
            val prompt = PromptComposer.compose(context, heard, languageCode)
            val eng = ensureModelReady(context, prompt.system)

            val sb = StringBuilder()
            eng.sendUserPrompt(prompt.user, predictLength = PREDICT_LENGTH).collect { token -> sb.append(token) }

            val raw = sb.toString()
            // An opened-but-never-closed <think> means generation ran out
            // of budget mid-reasoning -- everything from there on is
            // reasoning trace, not an answer. Better to return nothing than
            // present it as phrases.
            val cleaned = if (raw.contains("<think>") && !raw.contains("</think>")) {
                raw.substringBefore("<think>")
            } else {
                thinkTag.replace(raw, "")
            }

            val phrases = cleaned
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(3)
                .map { Phrase(ru = it, glossEn = "") }
                .toList()

            lastError = null
            scheduleIdleUnload()
            phrases
        } catch (e: CancellationException) {
            // A superseded prefetch (new utterance / language switch cancels
            // the in-flight one) is not a failure -- don't record it as
            // lastError, or availability() would report ERROR from then on
            // and every later request would silently fall back to the
            // library.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "generateWhatElse failed", e)
            lastError = e
            throw e
        } finally {
            isGenerating = false
        }
    }

    /**
     * Loads the model and processes [systemPrompt] if that isn't already
     * done -- shared by [generateWhatElse] and [warmUp] so there is exactly
     * one place that knows how to get from "engine exists" to "ready for a
     * user prompt". Caller must hold [mutex]. Returns the ready engine.
     */
    private suspend fun ensureModelReady(context: Context, systemPrompt: String): InferenceEngine {
        val eng = engine ?: LlmEngine.getInferenceEngine(context).also { engine = it }

        // A warm model still carries the previous call's system prompt. If
        // the caregiver switched target language since then, drop and
        // reload it -- setSystemPrompt() can't be called a second time.
        if (modelLoaded && systemPrompt != loadedSystemPrompt) {
            Log.i(TAG, "System prompt changed (language switch); reloading model")
            // cleanUp() unloads synchronously (runBlocking on its own
            // dispatcher) -- keep it off whatever thread called us.
            withContext(Dispatchers.IO) { eng.cleanUp() }
            modelLoaded = false
            loadedSystemPrompt = null
        }

        eng.state.filter { it == InferenceEngine.State.Initialized || it.isModelLoaded }.first()

        if (!modelLoaded) {
            try {
                eng.loadModel(OnDeviceLlmConfig.modelFilePath(context))
            } catch (e: IllegalStateException) {
                // The engine just wasn't in a loadable state -- a prior
                // generation hadn't fully settled, or an Activity
                // recreation raced this. Nothing is wrong with the
                // downloaded file. Reset the engine so it's not stuck in
                // Error, and let the next call retry. Do NOT touch the
                // download. See ON_DEVICE_LLM_PLAN.md Phase 4.
                runCatching { withContext(Dispatchers.IO) { eng.cleanUp() } }
                throw e
            } catch (e: Exception) {
                // The file itself won't load -- a truncated/corrupt
                // download, or a model architecture this llama.cpp build
                // doesn't support. Reset the engine out of its Error
                // state and mark the model FAILED so availability() stops
                // handing it back and the prefetch stops retrying -- but
                // KEEP the ~2.7GB on disk. Re-downloading from Settings
                // resumes/repairs via HTTP Range instead of pulling the
                // whole model again, and a one-off failure never throws
                // away a good file. See ON_DEVICE_LLM_PLAN.md Phase 6.
                runCatching { withContext(Dispatchers.IO) { eng.cleanUp() } }
                OnDeviceLlmConfig.setModelState(context, OnDeviceLlmConfig.ModelState.FAILED)
                throw e
            }
            // InferenceEngine.setSystemPrompt() is a one-shot call only
            // valid right after loadModel() (it throws IllegalStateException
            // on any later call) -- so it's gated to the load path here, not
            // called per generateWhatElse call. The system prompt is now
            // per-language, so a still-warm model carrying a stale one is
            // force-reloaded above (loadedSystemPrompt tracks what's baked
            // in). The load-path gating also matters for a same-process
            // Activity recreation re-running this with the model still warm
            // -- found via a real crash on the physical Pixel 11. See
            // ON_DEVICE_LLM_PLAN.md Phase 4.
            eng.setSystemPrompt(systemPrompt)
            loadedSystemPrompt = systemPrompt
            modelLoaded = true
        }
        return eng
    }

    /**
     * Pre-loads the model and processes [languageCode]'s system prompt
     * without generating anything. Model load (~5s) + system-prompt
     * processing (~5s on a Pixel 11, since the persona + few-shot examples
     * are a few hundred tokens) together dominate the *first* "what else?"
     * request's latency far more than actual generation does (~2s) -- call
     * this as soon as it's plausible the caregiver will ask (e.g. when
     * hands-free listening starts) so that cost is paid while they're still
     * getting the mic going, not after they've already spoken and are
     * waiting on the result card. A no-op if the device/model isn't ready,
     * or if a warm model already carries this language's system prompt.
     * Never throws -- a failed warm-up just means the next real
     * [generateWhatElse] call pays the cost (and reports it) instead.
     */
    suspend fun warmUp(context: Context, languageCode: String) {
        if (availability(context) != Availability.READY) return
        mutex.withLock {
            idleUnloadJob?.cancel()
            try {
                val systemPrompt = PromptComposer.compose(context, "", languageCode).system
                if (modelLoaded && systemPrompt == loadedSystemPrompt) return@withLock
                ensureModelReady(context, systemPrompt)
                scheduleIdleUnload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Deliberately not lastError -- a failed warm-up shouldn't
                // pre-emptively disable the real request that follows it.
                Log.e(TAG, "warmUp failed", e)
            }
        }
    }

    private fun scheduleIdleUnload() {
        idleUnloadJob = ownerScope.launch {
            delay(IDLE_UNLOAD_MS)
            mutex.withLock {
                Log.i(TAG, "Idle timeout reached, unloading model")
                engine?.cleanUp()
                modelLoaded = false
                loadedSystemPrompt = null
            }
        }
    }
}
