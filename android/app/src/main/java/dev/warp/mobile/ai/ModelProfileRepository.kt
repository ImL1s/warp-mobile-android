package dev.warp.mobile.ai

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

object ModelProfileRepository {
    private const val PREFS_NAME = "warp-model-profiles"
    private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
    private const val KEY_CUSTOM_PROFILES_JSON = "custom_profiles_json"

    private val _activeProfile = MutableStateFlow(ModelProfile.CLAUDE_3_5_SONNET)
    val activeProfile: StateFlow<ModelProfile> = _activeProfile.asStateFlow()

    @Volatile
    private var isInitialized = false

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun init(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            val prefs = getPrefs(context)
            val activeId = prefs.getString(KEY_ACTIVE_PROFILE_ID, ModelProfile.CLAUDE_3_5_SONNET.id)
                ?: ModelProfile.CLAUDE_3_5_SONNET.id
            val allProfiles = getAllProfiles(context)
            val selected = allProfiles.find { it.id == activeId } ?: ModelProfile.CLAUDE_3_5_SONNET
            _activeProfile.value = selected
            isInitialized = true
        }
    }

    fun getAllProfiles(context: Context): List<ModelProfile> {
        val customProfiles = getCustomProfiles(context)
        return ModelProfile.BUILTIN_PROFILES + customProfiles
    }

    fun getActiveProfile(context: Context): ModelProfile {
        init(context)
        return _activeProfile.value
    }

    fun setActiveProfileId(context: Context, profileId: String): Boolean {
        val allProfiles = getAllProfiles(context)
        val target = allProfiles.find { it.id == profileId } ?: return false
        _activeProfile.value = target
        getPrefs(context).edit().putString(KEY_ACTIVE_PROFILE_ID, profileId).commit()
        return true
    }

    fun saveCustomProfile(context: Context, profile: ModelProfile): Boolean {
        if (!profile.validate()) return false
        val currentCustoms = getCustomProfiles(context).toMutableList()
        val index = currentCustoms.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            currentCustoms[index] = profile
        } else {
            currentCustoms.add(profile)
        }

        val jsonStr = try {
            val jsonArray = JSONArray()
            currentCustoms.forEach { 
                try {
                    jsonArray.put(org.json.JSONObject(it.toJson()))
                } catch (_: Throwable) {
                    jsonArray.put(it.toJson())
                }
            }
            val res = jsonArray.toString()
            if (!res.isNullOrBlank() && res.startsWith("[") && res.endsWith("]")) res else buildCustomsJsonString(currentCustoms)
        } catch (_: Throwable) {
            buildCustomsJsonString(currentCustoms)
        }

        getPrefs(context).edit().putString(KEY_CUSTOM_PROFILES_JSON, jsonStr).commit()

        if (_activeProfile.value.id == profile.id) {
            _activeProfile.value = profile
        }
        return true
    }

    private fun buildCustomsJsonString(profiles: List<ModelProfile>): String {
        return "[" + profiles.joinToString(",") { it.toJson() } + "]"
    }

    private fun getCustomProfiles(context: Context): List<ModelProfile> {
        val jsonStr = getPrefs(context).getString(KEY_CUSTOM_PROFILES_JSON, null) ?: return emptyList()
        if (jsonStr.isBlank()) return emptyList()
        val list = mutableListOf<ModelProfile>()
        try {
            val trimmed = jsonStr.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                val inner = trimmed.substring(1, trimmed.length - 1).trim()
                if (inner.isNotBlank()) {
                    val regex = Regex("""\{.*?\}""")
                    regex.findAll(inner).forEach { match ->
                        val prof = ModelProfile.fromJson(match.value)
                        if (prof.validate()) list.add(prof)
                    }
                }
            }
        } catch (_: Throwable) {}
        return list
    }

    fun resetToDefaults(context: Context) {
        getPrefs(context).edit().clear().commit()
        _activeProfile.value = ModelProfile.CLAUDE_3_5_SONNET
    }
}
