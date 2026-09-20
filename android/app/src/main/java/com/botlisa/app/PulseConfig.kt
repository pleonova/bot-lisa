package com.botlisa.app

import android.content.Context

/**
 * Whether the record-button subtitle pulses at all -- a plain Settings
 * toggle, on by default. When off, the subtitle just sits at its
 * darker/final color (see MainActivity's effectivePulse) instead of
 * animating. Same SharedPreferences file as the other *Config objects.
 */
object PulseConfig {
    private const val PREFS_NAME = "bot_lisa_prefs"
    private const val KEY_ENABLED = "pulse_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
