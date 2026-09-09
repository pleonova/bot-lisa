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
    val availability = OnDeviceLlm.availability(context)
    Log.i(TAG, "availability: $availability")
    if (availability != OnDeviceLlm.Availability.READY) {
        // Phase 6 added a real (in-progress or previously-attempted)
        // download alongside this hand-pushed-model scaffold. Without this
        // check, this unconditional-on-every-launch smoke test would hand
        // an in-progress download's partial file straight to loadModel(),
        // which fails to parse it and "corrects" the supposed corruption by
        // deleting it -- destroying real download progress. Found via a
        // real device test that lost a 1.2GB partial download this way.
        return
    }

    val start = System.currentTimeMillis()
    val phrases = OnDeviceLlm.generateWhatElse(context, "спокойной ночи")
    val ms = System.currentTimeMillis() - start

    Log.i(TAG, "Generated ${phrases.size} phrase(s) in ${ms} ms:")
    phrases.forEach { Log.i(TAG, "  - ${it.ru}") }
}
