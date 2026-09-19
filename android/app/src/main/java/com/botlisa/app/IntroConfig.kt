package com.botlisa.app

import android.content.Context

/**
 * Whether the caregiver has already dismissed the intro screen once. Same
 * SharedPreferences pattern as ThemeConfig.kt / GenderConfig.kt. Only gates
 * the *first-launch* auto-show -- tapping the fox logo shows the intro again
 * regardless of this flag (see LisaScreen.resetToStart()).
 */
object IntroConfig {
    private const val PREFS_NAME = "bot_lisa_prefs"
    private const val KEY_HAS_SEEN_INTRO = "has_seen_intro"
    private const val KEY_HAS_STARTED_ASSISTANT = "has_started_assistant"

    fun hasSeenIntro(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HAS_SEEN_INTRO, false)

    fun setHasSeenIntro(context: Context, seen: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_HAS_SEEN_INTRO, seen).apply()
    }

    // Whether the caregiver has ever tapped the record button to start Lisa
    // Assistant -- drives the one-time "STEP 1 / STEP 2" onboarding labels on
    // the home screen (LisaScreen), which point at the instructions panel and
    // the record button in turn and then never show again once this flips true.
    fun hasStartedAssistant(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HAS_STARTED_ASSISTANT, false)

    fun setHasStartedAssistant(context: Context, started: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_HAS_STARTED_ASSISTANT, started).apply()
    }
}
