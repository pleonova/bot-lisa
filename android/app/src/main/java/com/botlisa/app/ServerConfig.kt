package com.botlisa.app

import android.content.Context

/**
 * Persists the backend base URL (and, once orchestration-service enforces
 * auth, the API key) so both survive app restarts and don't require a
 * rebuild to change (e.g. switching between emulator and a real device on
 * your LAN, or between local dev and the deployed cluster).
 */
object ServerConfig {

    private const val PREFS_NAME = "bot_lisa_prefs"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_API_KEY = "api_key"

    // Default: the Android emulator's alias for the host machine's localhost,
    // pointed at orchestration-service (port 8002) directly, since that's
    // where the caregiver-facing /assist endpoint lives.
    const val DEFAULT_BASE_URL = "http://10.0.2.2:8002"

    fun getBaseUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
    }

    fun setBaseUrl(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_BASE_URL, url).apply()
    }

    // Empty by default -- local dev's orchestration-service has no
    // ORCHESTRATION_API_KEY set, so it doesn't check this header. Required
    // once pointed at the deployed cluster, which does enforce it.
    fun getApiKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_API_KEY, "") ?: ""
    }

    fun setApiKey(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_API_KEY, key).apply()
    }
}
