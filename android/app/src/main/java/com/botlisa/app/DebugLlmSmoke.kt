package com.botlisa.app

import android.content.Context
import android.util.Log

/**
 * Phase 2/4 debug scaffold -- exercises the real end-to-end path
 * (OnDeviceLlm.generateWhatElse, using the model file placed by hand via
 * adb push, not the real download flow yet). Kept until Phase 7's real
 * MainActivity wiring replaces it; see ON_DEVICE_LLM_PLAN.md.
 */
private const val TAG = "OnDeviceLlmSmoke"

suspend fun runLlmSmokeTest(context: Context) {
    Log.i(TAG, "availability: ${OnDeviceLlm.availability(context)}")

    val start = System.currentTimeMillis()
    val phrases = OnDeviceLlm.generateWhatElse(context, "спокойной ночи")
    val ms = System.currentTimeMillis() - start

    Log.i(TAG, "Generated ${phrases.size} phrase(s) in ${ms} ms:")
    phrases.forEach { Log.i(TAG, "  - ${it.ru}") }
}
