package com.botlisa.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.botlisa.llm.InferenceEngine
import com.botlisa.llm.LlmEngine
import com.botlisa.llm.isModelLoaded
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

    // Qwen's reasoning mode isn't suppressed here the way our Homebrew
    // llama-server testing used `-rea off` -- ai_chat.cpp formats chat
    // messages with use_jinja=false (llama.cpp's simple built-in formatter,
    // not the model's own Jinja template, which is where `enable_thinking`
    // support would normally live). When thinking mode engages, it can run
    // the full predict-length budget without ever emitting `</think>`,
    // producing raw reasoning trace instead of phrases. Observed: a case
    // that finished cleanly (empty <think></think>) in ~3s vs. one that
    // never closed the tag and burned the full budget in ~74s. Bounding
    // predictLength caps the worst case; it doesn't fix the root cause.
    // See ON_DEVICE_LLM_PLAN.md Phase 4 -- deferred, needs a real fix
    // (system-prompt-level thinking suppression or a different chat
    // formatting path) before this ships.
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
    private var idleUnloadJob: Job? = null

    @Volatile private var isGenerating = false
    @Volatile private var lastError: Exception? = null

    fun isDeviceCapable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && hasEnoughRam(context)

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
     * Generates on-device "что ещё" suggestions for [heard]. Loads the
     * model on first call (or after an idle unload), reuses it while it
     * stays warm. Throws on failure -- callers (Phase 7) are expected to
     * catch and fall back to the network path, not surface this directly.
     */
    suspend fun generateWhatElse(context: Context, heard: String): List<Phrase> = mutex.withLock {
        idleUnloadJob?.cancel()
        isGenerating = true
        try {
            val prompt = PromptComposer.compose(context, heard)
            val eng = engine ?: LlmEngine.getInferenceEngine(context).also { engine = it }

            eng.state.filter { it == InferenceEngine.State.Initialized || it.isModelLoaded }.first()

            if (!modelLoaded) {
                try {
                    eng.loadModel(OnDeviceLlmConfig.modelFilePath(context))
                } catch (e: Exception) {
                    // A load failure here means the file on disk is bad (a
                    // truncated/corrupt download that slipped past
                    // ModelDownloadWorker's size check, or manual tampering)
                    // -- leaving it in place would make availability() keep
                    // reporting READY (it only checks File.exists()) and
                    // every future call would fail the same way. Delete it
                    // and fall back to NOT_DOWNLOADED so Settings offers a
                    // fresh download instead of a silently stuck feature.
                    // See ON_DEVICE_LLM_PLAN.md Phase 6.
                    File(OnDeviceLlmConfig.modelFilePath(context)).delete()
                    OnDeviceLlmConfig.setModelState(context, OnDeviceLlmConfig.ModelState.NOT_DOWNLOADED)
                    throw e
                }
                // InferenceEngine.setSystemPrompt() is a one-shot call only
                // valid right after loadModel() (it throws
                // IllegalStateException on any later call) -- gated here,
                // not called unconditionally per generateWhatElse call. Only
                // correct because our persona's system prompt is constant
                // across calls right now; if that ever becomes dynamic,
                // this needs its own "system prompt changed" tracking, not
                // just "model loaded". Found via a real crash on the
                // physical Pixel 11 -- a same-process Activity recreation
                // re-ran this code with the model still warm, exercising
                // the reuse path for the first time. See
                // ON_DEVICE_LLM_PLAN.md Phase 4.
                eng.setSystemPrompt(prompt.system)
                modelLoaded = true
            }

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
        } catch (e: Exception) {
            Log.e(TAG, "generateWhatElse failed", e)
            lastError = e
            throw e
        } finally {
            isGenerating = false
        }
    }

    private fun scheduleIdleUnload() {
        idleUnloadJob = ownerScope.launch {
            delay(IDLE_UNLOAD_MS)
            mutex.withLock {
                Log.i(TAG, "Idle timeout reached, unloading model")
                engine?.cleanUp()
                modelLoaded = false
            }
        }
    }
}
