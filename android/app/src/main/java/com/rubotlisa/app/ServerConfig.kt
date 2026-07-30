package com.rubotlisa.app

import android.content.Context

/**
 * Persists the backend base URL so it survives app restarts and doesn't
 * require a rebuild to change (e.g. switching between emulator and a real
 * device on your LAN).
 */
object ServerConfig {

    private const val PREFS_NAME = "ru_bot_lisa_prefs"
    private const val KEY_BASE_URL = "base_url"

    // Default: the Android emulator's alias for the host machine's localhost.
    const val DEFAULT_BASE_URL = "http://10.0.2.2:8003"

    fun getBaseUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
    }

    fun setBaseUrl(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_BASE_URL, url).apply()
    }
}
