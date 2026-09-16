package com.botlisa.app

import android.content.Context

/**
 * Who the caregiver is talking to -- asked on the intro screen (see
 * IntroScreen.kt). Same SharedPreferences pattern as GenderConfig.kt.
 *
 * Persisted only for now: the on-device persona (assets/llm_prompts/personas)
 * has just the one caregiver_infant.json register, so selecting ADULT doesn't
 * change generation yet -- this exists so the choice sticks once an adult
 * persona is added, rather than needing a second round of UI work.
 */
object AudienceConfig {
    private const val PREFS_NAME = "bot_lisa_prefs"
    private const val KEY_AUDIENCE = "audience"

    enum class Audience { BABY, ADULT }

    fun getAudience(context: Context): Audience {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_AUDIENCE, null) ?: return Audience.BABY
        return runCatching { Audience.valueOf(name) }.getOrDefault(Audience.BABY)
    }

    fun setAudience(context: Context, audience: Audience) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_AUDIENCE, audience.name).apply()
    }
}
