package com.botlisa.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persists the backend base URL (plain prefs -- not sensitive) and the API
 * key (encrypted prefs -- see below) so both survive app restarts and don't
 * require a rebuild to change (e.g. switching between emulator and a real
 * device on your LAN, or between local dev and the deployed cluster).
 */
object ServerConfig {

    private const val PREFS_NAME = "bot_lisa_prefs"
    private const val KEY_BASE_URL = "base_url"

    private const val SECURE_PREFS_NAME = "bot_lisa_secure_prefs"
    private const val KEY_API_KEY = "api_key"

    // Default: the Android emulator's alias for the host machine's localhost,
    // pointed at orchestration-service (port 8002) directly, since that's
    // where the caregiver-facing /assist endpoint lives.
    const val DEFAULT_BASE_URL = "http://10.0.2.2:8002"

    fun getBaseUrl(context: Context): String {
        // SharedPreferences: Android's basic on-device key-value store for
        // small settings like this one.
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
    }

    fun setBaseUrl(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_BASE_URL, url).apply()
    }

    // Stored in EncryptedSharedPreferences (AES256-GCM, master key held in
    // the Android Keystore) rather than plain SharedPreferences, since this
    // is a credential, not a UI preference -- a rooted device, an adb
    // backup, or a leaked file-manager screenshot shouldn't be able to read
    // it back out as plaintext.
    //
    // Empty by default -- local dev's orchestration-service has no
    // ORCHESTRATION_API_KEY set, so it doesn't check this header. Required
    // once pointed at the deployed cluster, which does enforce it.
    fun getApiKey(context: Context): String {
        return securePrefs(context).getString(KEY_API_KEY, "") ?: ""
    }

    fun setApiKey(context: Context, key: String) {
        securePrefs(context).edit().putString(KEY_API_KEY, key).apply()
    }

    private fun securePrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
