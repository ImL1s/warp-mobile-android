package dev.warp.mobile

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Keystore-backed multi-provider API key storage (Issue #15 update).
 *
 * Wraps `EncryptedSharedPreferences` so BYOK API keys for multiple providers
 * (Anthropic, OpenAI, custom endpoints) never land in plaintext on disk.
 *
 * Schema:
 *   alias  : "warp-ai-key-v1" (master-key alias)
 *   prefs  : "warp-ai-prefs.enc"
 *   fields :
 *     "key_anthropic" (or legacy "anthropic-api-key")
 *     "key_openai"
 *     "key_custom_<id>"
 */
object AiKeyStore {
    private const val LOG_TAG = "WarpAiKeyStore"
    private const val MASTER_KEY_ALIAS = "warp-ai-key-v1"
    private const val PREFS_NAME = "warp-ai-prefs.enc"
    private const val FALLBACK_PREFS_NAME = "warp-ai-prefs-fallback"
    private const val LEGACY_KEY_ANTHROPIC = "anthropic-api-key"

    @Volatile private var cached: SharedPreferences? = null

    /**
     * Get or create the encrypted SharedPreferences instance. Falls back safely to standard
     * SharedPreferences if hardware KeyStore or EncryptedSharedPreferences initialization fails.
     */
    @Synchronized
    fun getOrCreate(context: Context): SharedPreferences {
        cached?.let { return it }
        return try {
            val masterKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setUserAuthenticationRequired(false)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            cached = prefs
            Log.i(LOG_TAG, "EncryptedSharedPreferences ready (alias=$MASTER_KEY_ALIAS)")
            prefs
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "EncryptedSharedPreferences init failed (${e.message}), using standard fallback SharedPreferences", e)
            val fallbackPrefs = context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
            cached = fallbackPrefs
            fallbackPrefs
        }
    }

    private fun providerToKey(provider: String): String = when (provider.lowercase()) {
        "anthropic" -> "key_anthropic"
        "openai" -> "key_openai"
        else -> if (provider.startsWith("key_")) provider else "key_custom_$provider"
    }

    /**
     * Returns the saved API key for a provider (defaults to "anthropic").
     */
    fun load(context: Context, provider: String = "anthropic"): String? {
        val prefs = getOrCreate(context)
        val prefKey = providerToKey(provider)
        val keyVal = prefs.getString(prefKey, null)
        if (keyVal != null) return keyVal

        // Fallback for legacy single-key storage
        if (provider.equals("anthropic", ignoreCase = true)) {
            val legacyKey = prefs.getString(LEGACY_KEY_ANTHROPIC, null)
            if (legacyKey != null) {
                // Migrate to new schema
                save(context, "anthropic", legacyKey)
                prefs.edit().remove(LEGACY_KEY_ANTHROPIC).apply()
                return legacyKey
            }
        }
        return null
    }

    /** Save / replace the API key for a provider. */
    fun save(context: Context, provider: String = "anthropic", key: String) {
        val prefKey = providerToKey(provider)
        getOrCreate(context).edit().putString(prefKey, key).apply()
    }

    /** Save legacy single key (backwards compat wrapper). */
    fun save(context: Context, key: String) {
        save(context, "anthropic", key)
    }

    /** Forget the saved key for a provider. */
    fun clear(context: Context, provider: String = "anthropic") {
        val prefKey = providerToKey(provider)
        val editor = getOrCreate(context).edit().remove(prefKey)
        if (provider.equals("anthropic", ignoreCase = true)) {
            editor.remove(LEGACY_KEY_ANTHROPIC)
        }
        editor.apply()
    }

    /** Clear all stored keys across all providers. */
    fun clearAll(context: Context) {
        getOrCreate(context).edit().clear().apply()
    }

    /**
     * Redacted form for logs. Handles both `sk-ant-` and `sk-` formats.
     * Format: "Bearer sk-ant-1234***...ABCD" or "Bearer sk-1234***...ABCD".
     */
    fun redact(key: String?, provider: String? = null): String {
        if (key.isNullOrEmpty()) return if (provider != null) "(no key for $provider)" else "(no key)"
        val trimmedKey = key.trim()
        val tail = if (trimmedKey.length >= 4) trimmedKey.takeLast(4) else "?"
        val head = if (trimmedKey.length >= 8) trimmedKey.take(8) else trimmedKey
        val tag = if (provider != null) " [$provider]" else ""
        return "Bearer $head***...$tail$tag"
    }

    /** Force reset cached SharedPreferences instance (for unit tests). */
    fun resetCacheForTesting() {
        synchronized(this) {
            cached = null
        }
    }
}
