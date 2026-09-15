// Adapted from ggml-org/llama.cpp examples/llama.android (tag v0.4.0),
// lib/src/main/java/com/arm/aichat/InferenceEngine.kt -- repackaged under
// com.botlisa.llm, otherwise unchanged. See ON_DEVICE_LLM_PLAN.md Phase 2.
package com.botlisa.llm

import com.botlisa.llm.InferenceEngine.State
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface defining the core LLM inference operations.
 *
 * This is the Kotlin-facing API only -- the actual model math runs in native
 * C++ (llama.cpp) on the other side of a JNI (Java Native Interface) boundary.
 * JNI is the mechanism that lets Kotlin call into compiled C++ and get
 * results back, since Kotlin/the JVM can't run C++ code directly. See
 * [com.botlisa.llm.internal.InferenceEngineImpl] for where that boundary is
 * actually crossed.
 */
interface InferenceEngine {
    /**
     * Current state of the inference engine
     */
    val state: StateFlow<State>

    /**
     * Load a model from the given path.
     *
     * [pathToModel] points to a GGUF file -- llama.cpp's file format for a
     * quantized (compressed, lower-precision) set of model weights, small and
     * fast enough to run on a phone. "Loading" reads those weights from disk
     * into memory so the native code has something to run inference against.
     *
     * @throws UnsupportedArchitectureException if model architecture not supported
     */
    suspend fun loadModel(pathToModel: String)

    /**
     * Sends a system prompt to the loaded model
     */
    suspend fun setSystemPrompt(systemPrompt: String)

    /**
     * Sends a user prompt to the loaded model and returns a Flow of generated tokens.
     */
    fun sendUserPrompt(message: String, predictLength: Int = DEFAULT_PREDICT_LENGTH): Flow<String>

    /**
     * Runs a benchmark with the specified parameters.
     */
    suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1): String

    /**
     * Unloads the currently loaded model.
     */
    fun cleanUp()

    /**
     * Cleans up resources when the engine is no longer needed.
     */
    fun destroy()

    /**
     * States of the inference engine
     *
     * Loading the model and running inference happen on native code that must
     * be driven in a strict order (e.g. a model has to finish loading before
     * a prompt can be sent). This state machine exists so callers can check
     * "is it safe to do X right now?" in Kotlin, instead of finding out by
     * crashing or corrupting native state.
     */
    sealed class State {
        object Uninitialized : State()
        object Initializing : State()
        object Initialized : State()

        object LoadingModel : State()
        object UnloadingModel : State()
        object ModelReady : State()

        object Benchmarking : State()
        object ProcessingSystemPrompt : State()
        object ProcessingUserPrompt : State()

        object Generating : State()

        data class Error(val exception: Exception) : State()
    }

    companion object {
        const val DEFAULT_PREDICT_LENGTH = 1024
    }
}

// True while the engine is mid-way through a blocking native call (loading,
// unloading, benchmarking, or processing a prompt) -- callers can use this to
// disable UI actions that would otherwise race with that native work.
val State.isUninterruptible
    get() = this is State.Initializing ||
        this is State.LoadingModel ||
        this is State.UnloadingModel ||
        this is State.Benchmarking ||
        this is State.ProcessingSystemPrompt ||
        this is State.ProcessingUserPrompt

val State.isModelLoaded: Boolean
    get() = this is State.ModelReady ||
        this is State.Benchmarking ||
        this is State.ProcessingSystemPrompt ||
        this is State.ProcessingUserPrompt ||
        this is State.Generating

class UnsupportedArchitectureException : Exception()
