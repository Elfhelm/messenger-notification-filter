package com.example.messengerthreadblocker.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the user's Gemini API key securely using EncryptedSharedPreferences,
 * with a fallback to private SharedPreferences if hardware/keystore crypto is unavailable.
 */
class ApiKeyStore(context: Context) {

    companion object {
        private const val ENCRYPTED_PREFS_NAME = "gemini_secure_prefs"
        private const val FALLBACK_PREFS_NAME = "gemini_fallback_prefs"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_ONBOARDING_DISMISSED = "onboarding_dismissed"
    }

    private val prefs: SharedPreferences = createPrefs(context.applicationContext)

    private fun createPrefs(appContext: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            appContext.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun getApiKey(): String? {
        return prefs.getString(KEY_API_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun saveApiKey(apiKey: String) {
        prefs.edit()
            .putString(KEY_API_KEY, apiKey.trim())
            .putBoolean(KEY_ONBOARDING_DISMISSED, true)
            .apply()
    }

    fun clearApiKey() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    fun hasApiKey(): Boolean = getApiKey() != null

    fun isOnboardingDismissed(): Boolean = prefs.getBoolean(KEY_ONBOARDING_DISMISSED, false)
}
