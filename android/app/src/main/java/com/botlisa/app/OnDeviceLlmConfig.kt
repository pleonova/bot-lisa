package com.botlisa.app

import android.content.Context
import java.io.File

/**
 * Settings for the on-device "что ещё" LLM feature. Same SharedPreferences
 * file and getter/setter shape as TriggerPhraseConfig.kt/ThemeConfig.kt. See
 * ON_DEVICE_LLM_PLAN.md Phase 5.
 *
 * [modelFilePath] lives here (not in OnDeviceLlm.kt) so this object is the
 * single source of truth for where the model is expected to be -- Phase 6's
 * download worker writes there, OnDeviceLlm.kt reads from here to load it.
 *
 * [ModelState] is a UI-facing status flag for the Settings screen, distinct
 * from OnDeviceLlm.Availability (which is the functional gate for whether
 * generateWhatElse should be attempted, and checks the model file's
 * existence directly rather than trusting this persisted flag -- so a
 * manually-deleted file self-corrects instead of leaving a stale "ready"
 * status behind). Nothing sets this to anything but NOT_DOWNLOADED yet;
 * Phase 6's download worker will drive DOWNLOADING/READY/FAILED.
 */
object OnDeviceLlmConfig {
    private const val PREFS_NAME = "bot_lisa_prefs"
    private const val KEY_MODEL_STATE = "on_device_llm_model_state"
    private const val KEY_PREFETCH_MODE = "on_device_llm_prefetch_mode"
    private const val MODEL_FILE_NAME = "qwen3.5-4b-q4_k_m.gguf"

    enum class WhatElseSource { BOTH, AI_ONLY, LIBRARY_ONLY }
    enum class ModelState { NOT_DOWNLOADED, DOWNLOADING, READY, FAILED }

    // EAGER generates on-device suggestions after every hands-free
    // utterance, so they're ready instantly if the caregiver asks "what
    // else?" -- at the cost of running a multi-second, multi-core inference
    // for every phrase said, whether or not that command ever gets used.
    // Measured directly: 20 back-to-back inferences pushed a Pixel 11 from
    // thermal status NONE to LIGHT in under two minutes, and heavy compute
    // + heat is the classic combination that accelerates battery capacity
    // fade. EAGER still backs off on its own once the device is thermally
    // elevated (see OnDeviceLlm.isThermallyElevated) -- ON_DEMAND skips the
    // eager path entirely and only generates when the trigger is actually
    // spoken, trading a few seconds of wait for far fewer inferences per
    // session. See ON_DEVICE_LLM_PLAN.md.
    enum class PrefetchMode { EAGER, ON_DEMAND }

    fun modelFilePath(context: Context): String =
        File(context.filesDir, MODEL_FILE_NAME).absolutePath

    // Always BOTH -- AI_ONLY and LIBRARY_ONLY existed as a user-facing
    // Settings picker, but LIBRARY_ONLY has no content for any non-Russian
    // target language, which meant picking it silently hid the "what else?"
    // command entirely for every other language -- confusing enough (and
    // easy enough to land on by accident while exploring Settings) that the
    // picker was removed in favor of always using the source that degrades
    // gracefully. See SettingsScreen.kt's former WhatElseSourcePicker.
    // KEY_WHAT_ELSE_SOURCE is intentionally no longer read -- an old
    // persisted value from before this change is simply ignored.
    fun getWhatElseSource(context: Context): WhatElseSource = WhatElseSource.BOTH

    fun getModelState(context: Context): ModelState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_MODEL_STATE, null) ?: return ModelState.NOT_DOWNLOADED
        return runCatching { ModelState.valueOf(name) }.getOrDefault(ModelState.NOT_DOWNLOADED)
    }

    fun setModelState(context: Context, state: ModelState) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODEL_STATE, state.name).apply()
    }

    fun getPrefetchMode(context: Context): PrefetchMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_PREFETCH_MODE, null) ?: return PrefetchMode.EAGER
        return runCatching { PrefetchMode.valueOf(name) }.getOrDefault(PrefetchMode.EAGER)
    }

    fun setPrefetchMode(context: Context, mode: PrefetchMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PREFETCH_MODE, mode.name).apply()
    }
}
