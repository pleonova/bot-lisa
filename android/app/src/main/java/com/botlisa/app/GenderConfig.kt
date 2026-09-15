package com.botlisa.app

import android.content.Context

/**
 * The child's gender, substituted into the caregiver_infant persona's
 * {gender} placeholder ("talking to a small {gender}") -- see
 * PromptComposer.compose. Overrides the "boy" default hardcoded in
 * assets/llm_prompts/personas/caregiver_infant.json. Same SharedPreferences
 * pattern as OnDeviceLlmConfig.kt.
 */
object GenderConfig {
    private const val PREFS_NAME = "bot_lisa_prefs"
    private const val KEY_GENDER = "child_gender"

    enum class Gender(val promptValue: String, val displayName: String) {
        BOY("boy", "Boy"),
        GIRL("girl", "Girl"),
        CHILD("child", "Prefer not to say"),
    }

    fun getGender(context: Context): Gender {
        // SharedPreferences is Android's simple built-in key-value store --
        // a small file of settings that survives app restarts, backed by
        // the given name and read/written through this handle.
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_GENDER, null) ?: return Gender.BOY
        return runCatching { Gender.valueOf(name) }.getOrDefault(Gender.BOY)
    }

    fun setGender(context: Context, gender: Gender) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_GENDER, gender.name).apply()
    }
}
