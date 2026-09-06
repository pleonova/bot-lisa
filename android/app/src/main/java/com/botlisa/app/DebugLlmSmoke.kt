package com.botlisa.app

import android.content.Context
import android.util.Log
import com.botlisa.llm.InferenceEngine
import com.botlisa.llm.LlmEngine
import com.botlisa.llm.isModelLoaded
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Phase 2 debug scaffold -- runs one real end-to-end generation against a
 * model file placed by hand at [modelPath] (adb push, not the real download
 * flow yet). Kept until Phase 7's real MainActivity wiring replaces it; see
 * ON_DEVICE_LLM_PLAN.md.
 *
 * Not the real "что ещё" prompt yet -- just proves loadModel/setSystemPrompt/
 * sendUserPrompt round-trip through JNI with a real GGUF, logging latency
 * and the generated text.
 */
private const val TAG = "OnDeviceLlmSmoke"

suspend fun runLlmSmokeTest(
    context: Context,
    modelPath: String = File(context.filesDir, "model.gguf").absolutePath,
) {
    val engine = LlmEngine.getInferenceEngine(context)

    Log.i(TAG, "Waiting for engine to initialize...")
    engine.state.filter { it == InferenceEngine.State.Initialized }.first()

    val loadStart = System.currentTimeMillis()
    Log.i(TAG, "Loading model from $modelPath ...")
    engine.loadModel(modelPath)
    Log.i(TAG, "Model loaded in ${System.currentTimeMillis() - loadStart} ms")

    engine.setSystemPrompt(
        "You are helping the caregiver who is talking to a small boy. " +
            "Everything you produce is in Russian and must be grammatically correct. " +
            "Reply with the phrases only -- one per line, no numbering, no preamble."
    )

    val genStart = System.currentTimeMillis()
    val sb = StringBuilder()
    engine.sendUserPrompt("Give me three follows to this: спокойной ночи").collect { token ->
        sb.append(token)
    }
    val genMs = System.currentTimeMillis() - genStart
    Log.i(TAG, "Generated in ${genMs} ms:\n$sb")
    Log.i(TAG, "isModelLoaded after run: ${engine.state.value.isModelLoaded}")
}
