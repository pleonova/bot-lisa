package com.botlisa.app

import android.content.Context

/**
 * Dark-mode override for the app theme, set by the Settings toggle.
 *
 * `null` / unset = follow the system setting; `true` / `false` = an explicit
 * choice the caregiver made. Same SharedPreferences file as the other
 * *Config objects.
 */
object ThemeConfig {
    private const val PREFS_NAME = "bot_lisa_prefs"
    private const val KEY_DARK_MODE = "dark_mode_override"

    fun getDarkOverride(context: Context): Boolean? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_DARK_MODE)) prefs.getBoolean(KEY_DARK_MODE, false) else null
    }

    fun setDarkOverride(context: Context, dark: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DARK_MODE, dark).apply()
    }
}
