package com.botlisa.app

import android.content.Context

/**
 * Whether tapping beside the record button (left or right of the
 * button/subtitle/arrow, not on any of them) freezes the subtitle's pulse
 * in its darker/final color instead of continuing to animate -- a Settings
 * toggle (off by default) since not every caregiver wants that tap to do
 * anything. Same SharedPreferences file as the other *Config objects.
 */
object PulseFreezeConfig {
    private const val PREFS_NAME = "bot_lisa_prefs"
    private const val KEY_ENABLED = "pulse_freeze_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
