package com.botlisa.app

import android.content.Context
import org.json.JSONArray

/**
 * Per-language user overrides for the caregiver_infant "what else?" few-shot
 * demos (see PromptComposer.kt and
 * assets/llm_prompts/examples/few_shot_examples.caregiver_infant.by_language.json,
 * whose own _comment anticipates exactly this). Each override is the same
 * `few_shot_examples` JSON array shape the asset file uses -- exactly two
 * demos, each a `heard` phrase plus three `responses` -- stored whole per
 * language code so PromptComposer can swap it in without needing to know
 * about individual fields.
 *
 * Storage mirrors TriggerPhraseConfig.kt's per-language-keyed pattern. No
 * override present (the common case) means PromptComposer keeps using the
 * bundled asset default for that language.
 */
object FewShotExamplesConfig {
    private const val PREFS_NAME = "bot_lisa_prefs"
    private const val KEY_PREFIX = "few_shot_examples_override_"

    fun getOverride(context: Context, languageCode: String): JSONArray? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PREFIX + languageCode, null) ?: return null
        return runCatching { JSONArray(json) }.getOrNull()
    }

    fun setOverride(context: Context, languageCode: String, demos: JSONArray) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PREFIX + languageCode, demos.toString()).apply()
    }

    fun clearOverride(context: Context, languageCode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_PREFIX + languageCode).apply()
    }
}
