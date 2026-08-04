package dev.warp.mobile.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelProfileManagerTest {

    @Test
    fun testModelProfileManager_initializesWithBuiltInDefaults() {
        val presets = ModelProfile.BUILTIN_PROFILES
        assertEquals(5, presets.size)

        val ids = presets.map { it.id }
        assertTrue(ids.contains("claude-3-5-sonnet"))
        assertTrue(ids.contains("claude-3-5-haiku"))
        assertTrue(ids.contains("gpt-4o"))
        assertTrue(ids.contains("gpt-4o-mini"))
        assertTrue(ids.contains("ollama-local"))

        for (profile in presets) {
            assertTrue("Profile ${profile.id} must be valid", profile.validate())
        }
    }

    @Test
    fun testModelProfileManager_switchActiveProfile_updatesStateFlow() {
        val defaultProfile = ModelProfile.CLAUDE_3_5_SONNET
        assertEquals("claude-3-5-sonnet", defaultProfile.id)

        val haikuProfile = ModelProfile.CLAUDE_3_5_HAIKU
        assertEquals("claude-3-5-haiku", haikuProfile.id)
    }

    @Test
    fun testModelProfileManager_customProfileJson_parsesCorrectly() {
        val profile = ModelProfile(
            id = "custom-llama3",
            name = "Local Llama 3",
            provider = ProviderKind.CUSTOM_OPENAI,
            modelName = "llama3",
            endpointUrl = "http://localhost:11434/v1/chat/completions",
            temperature = 0.7f,
            maxTokens = 4096,
            topP = 0.9f,
            contextWindow = 8192,
            supportsTools = false,
            supportsStreaming = true,
            isBuiltin = false
        )

        val jsonStr = profile.toJson()
        val restored = ModelProfile.fromJson(jsonStr)

        assertEquals(profile.id, restored.id)
        assertEquals(profile.name, restored.name)
        assertEquals(profile.provider, restored.provider)
        assertEquals(profile.endpointUrl, restored.endpointUrl)
        assertTrue(restored.validate())
    }

    @Test
    fun testModelProfileManager_invalidParameters_validationFails() {
        val invalidTokens = ModelProfile.CLAUDE_3_5_SONNET.copy(maxTokens = 0)
        assertFalse(invalidTokens.validate())

        val invalidTempHigh = ModelProfile.CLAUDE_3_5_SONNET.copy(temperature = 1.5f)
        assertFalse(invalidTempHigh.validate())

        val invalidTempLow = ModelProfile.CLAUDE_3_5_SONNET.copy(temperature = -0.1f)
        assertFalse(invalidTempLow.validate())

        val invalidCustomUrl = ModelProfile.OLLAMA_LOCAL.copy(endpointUrl = "")
        assertFalse(invalidCustomUrl.validate())
    }
}
