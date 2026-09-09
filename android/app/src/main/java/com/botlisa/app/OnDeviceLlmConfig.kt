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
    private const val KEY_WHAT_ELSE_SOURCE = "on_device_llm_what_else_source"
    private const val KEY_MODEL_STATE = "on_device_llm_model_state"
    private const val MODEL_FILE_NAME = "qwen3.5-4b-q4_k_m.gguf"

    enum class WhatElseSource { BOTH, AI_ONLY, LIBRARY_ONLY }
    enum class ModelState { NOT_DOWNLOADED, DOWNLOADING, READY, FAILED }

    fun modelFilePath(context: Context): String =
        File(context.filesDir, MODEL_FILE_NAME).absolutePath

    fun getWhatElseSource(context: Context): WhatElseSource {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_WHAT_ELSE_SOURCE, null) ?: return WhatElseSource.BOTH
        return runCatching { WhatElseSource.valueOf(name) }.getOrDefault(WhatElseSource.BOTH)
    }

    fun setWhatElseSource(context: Context, source: WhatElseSource) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_WHAT_ELSE_SOURCE, source.name).apply()
    }

    fun getModelState(context: Context): ModelState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_MODEL_STATE, null) ?: return ModelState.NOT_DOWNLOADED
        return runCatching { ModelState.valueOf(name) }.getOrDefault(ModelState.NOT_DOWNLOADED)
    }

    fun setModelState(context: Context, state: ModelState) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODEL_STATE, state.name).apply()
    }
}
