package com.botlisa.llm

/**
 * Phase 1 stub -- proves the Gradle/NDK/CMake/JNI pipeline round-trips
 * before llama.cpp is vendored in Phase 2. Delete once Phase 2's real
 * InferenceEngine lands. See ON_DEVICE_LLM_PLAN.md.
 */
object Smoke {
    init {
        System.loadLibrary("ondevicellm")
    }

    external fun hello(): String
}
