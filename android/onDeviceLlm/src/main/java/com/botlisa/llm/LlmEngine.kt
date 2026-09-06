// Adapted from ggml-org/llama.cpp examples/llama.android (tag v0.4.0),
// lib/src/main/java/com/arm/aichat/AiChat.kt -- repackaged under
// com.botlisa.llm and renamed to match this project's naming. See
// ON_DEVICE_LLM_PLAN.md Phase 2.
package com.botlisa.llm

import android.content.Context
import com.botlisa.llm.internal.InferenceEngineImpl

/**
 * Main entry point for the on-device LLM library.
 */
object LlmEngine {
    /**
     * Get the inference engine single instance.
     */
    fun getInferenceEngine(context: Context) = InferenceEngineImpl.getInstance(context)
}
