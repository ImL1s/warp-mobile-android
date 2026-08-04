package dev.warp.mobile.editor

import dev.warp.mobile.ai.ModelProfile
import dev.warp.mobile.ai.ProviderKind
import dev.warp.mobile.test.BaseWarpUnitTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Task2R2ChallengerAdversarialTest : BaseWarpUnitTest() {

    // =========================================================================
    // 1. SlashCommandRegistry Whitespace Stress Tests
    // =========================================================================

    @Test
    fun testSlashCommandRegistry_leadingWhitespace_parsesCorrectly() {
        val resultAi = SlashCommandRegistry.filterCommands("   /ai")
        assertTrue("Query '   /ai' must return matching commands", resultAi.isNotEmpty())
        assertTrue("Query '   /ai' must include /ai command", resultAi.any { it.command == "/ai" })

        val resultClear = SlashCommandRegistry.filterCommands("   /clear")
        assertEquals(1, resultClear.size)
        assertEquals("/clear", resultClear[0].command)

        val resultSsh = SlashCommandRegistry.filterCommands("\t/ssh")
        assertEquals(1, resultSsh.size)
        assertEquals("/ssh", resultSsh[0].command)

        val resultHistory = SlashCommandRegistry.filterCommands("\n/history")
        assertEquals(1, resultHistory.size)
        assertEquals("/history", resultHistory[0].command)
    }

    @Test
    fun testSlashCommandRegistry_trailingWhitespace_parsesCorrectly() {
        val resultAi = SlashCommandRegistry.filterCommands("/ai   ")
        assertTrue("Query '/ai   ' must return matching commands", resultAi.isNotEmpty())
        assertTrue("Query '/ai   ' must include /ai command", resultAi.any { it.command == "/ai" })

        val resultClear = SlashCommandRegistry.filterCommands("/clear\t")
        assertEquals(1, resultClear.size)
        assertEquals("/clear", resultClear[0].command)

        val resultSsh = SlashCommandRegistry.filterCommands("/ssh\n")
        assertEquals(1, resultSsh.size)
        assertEquals("/ssh", resultSsh[0].command)
    }

    @Test
    fun testSlashCommandRegistry_mixedLeadingTrailingWhitespace_tabsAndNewlines() {
        val inputMixed = " \t\n  /history  \t\n "
        val result = SlashCommandRegistry.filterCommands(inputMixed)
        assertEquals(1, result.size)
        assertEquals("/history", result[0].command)

        val inputSlashOnlyWhitespace = " \t\n  /  \t\n "
        val resultSlash = SlashCommandRegistry.filterCommands(inputSlashOnlyWhitespace)
        assertEquals(SlashCommandRegistry.ALL_COMMANDS.size, resultSlash.size)
    }

    @Test
    fun testSlashCommandRegistry_whitespaceOnlyAndEmptyInputs_returnsAllCommands() {
        val inputs = listOf("", "   ", "\t", "\n", " \t \n ", "\r\n")
        for (input in inputs) {
            val result = SlashCommandRegistry.filterCommands(input)
            assertEquals("Input '${input.replace("\n", "\\n").replace("\t", "\\t")}' should return all commands", SlashCommandRegistry.ALL_COMMANDS.size, result.size)
        }
    }

    @Test
    fun testSlashCommandRegistry_internalWhitespaceAfterSlash() {
        val result1 = SlashCommandRegistry.filterCommands("  /   clear  ")
        assertEquals(1, result1.size)
        assertEquals("/clear", result1[0].command)

        val result2 = SlashCommandRegistry.filterCommands("\t/\t\tai\t")
        assertTrue(result2.any { it.command == "/ai" })
    }

    @Test
    fun testSlashCommandRegistry_nonSlashQueriesWithWhitespace() {
        val resultHist = SlashCommandRegistry.filterCommands(" \t history \n ")
        assertEquals(1, resultHist.size)
        assertEquals("/history", resultHist[0].command)

        val resultSearch = SlashCommandRegistry.filterCommands("\n search \t")
        assertEquals(1, resultSearch.size)
        assertEquals("/search", resultSearch[0].command)
    }


    // =========================================================================
    // 2. ModelProfile JSONObject Edge Cases & Exception Safety
    // =========================================================================

    @Test
    fun testModelProfileFromJson_emptyJsonObject_returnsValidProfileWithDefaults() {
        val jsonStr = "{}"
        val profile = ModelProfile.fromJson(jsonStr)

        assertNotNull(profile)
        assertEquals("", profile.id)
        assertEquals("", profile.name)
        assertEquals(ProviderKind.ANTHROPIC, profile.provider)
        assertEquals("", profile.modelName)
        assertNull(profile.endpointUrl)
        assertEquals(0.7f, profile.temperature, 0.001f)
        assertEquals(4096, profile.maxTokens)
        assertNull(profile.topP)
        assertEquals(128000, profile.contextWindow)
        assertTrue(profile.supportsTools)
        assertTrue(profile.supportsStreaming)
        assertFalse(profile.isBuiltin)
    }

    @Test
    fun testModelProfileFromJson_malformedJsonStrings_neverThrowsAndReturnsFallback() {
        val malformedInputs = listOf(
            "",
            "   ",
            "\t\n",
            "{invalid json",
            "not json at all",
            "12345",
            "[1, 2, 3]",
            "null",
            "{\"id\": \"test\", \"temperature\": "
        )

        for (input in malformedInputs) {
            val profile = try {
                ModelProfile.fromJson(input)
            } catch (e: Throwable) {
                throw AssertionError("ModelProfile.fromJson threw exception for input: '$input'", e)
            }
            assertNotNull("ModelProfile.fromJson must return non-null object for input: '$input'", profile)
        }
    }

    @Test
    fun testModelProfileFromJson_unexpectedDataTypes_handlesGracefullyWithoutCrashing() {
        // ID as int, name as boolean, provider as int
        val jsonNumbersAndBools = """
            {
                "id": 12345,
                "name": true,
                "provider": 999,
                "model_name": 888
            }
        """.trimIndent()

        val profile1 = ModelProfile.fromJson(jsonNumbersAndBools)
        assertNotNull(profile1)
        assertEquals("12345", profile1.id)
        assertEquals("true", profile1.name)
        assertEquals("888", profile1.modelName)
        assertEquals(ProviderKind.ANTHROPIC, profile1.provider)

        // Invalid types for numeric fields
        val jsonInvalidTypes = """
            {
                "id": "test-id",
                "name": "Test Name",
                "provider": "openai",
                "model_name": "gpt-4o",
                "temperature": "not_a_float",
                "max_tokens": "invalid_int",
                "top_p": "invalid_float",
                "context_window": "invalid_int",
                "supports_tools": "yes",
                "supports_streaming": 1,
                "is_builtin": "false"
            }
        """.trimIndent()

        val profile2 = ModelProfile.fromJson(jsonInvalidTypes)
        assertNotNull(profile2)
        assertEquals("test-id", profile2.id)
        assertEquals(ProviderKind.OPENAI, profile2.provider)

        // Nested objects/arrays in scalar fields
        val jsonNestedStructures = """
            {
                "id": {"nested": "id"},
                "name": ["name1", "name2"],
                "temperature": {"val": 0.5},
                "max_tokens": [100, 200]
            }
        """.trimIndent()

        val profile3 = ModelProfile.fromJson(jsonNestedStructures)
        assertNotNull(profile3)
    }

    @Test
    fun testModelProfileFromJson_explicitNullAndStringNullEndpointUrl() {
        val jsonExplicitNull = """
            {
                "id": "p1",
                "name": "Profile 1",
                "provider": "anthropic",
                "model_name": "claude-3-5-sonnet",
                "endpoint_url": null,
                "top_p": null
            }
        """.trimIndent()

        val profileNull = ModelProfile.fromJson(jsonExplicitNull)
        assertNull("Explicit null endpoint_url should be parsed as null", profileNull.endpointUrl)
        assertNull("Explicit null top_p should be parsed as null", profileNull.topP)

        val jsonStringNull = """
            {
                "id": "p2",
                "name": "Profile 2",
                "provider": "anthropic",
                "model_name": "claude-3-5-sonnet",
                "endpoint_url": "null"
            }
        """.trimIndent()

        val profileStrNull = ModelProfile.fromJson(jsonStringNull)
        assertNull("String literal 'null' endpoint_url should be treated as null", profileStrNull.endpointUrl)
    }

    @Test
    fun testModelProfileToJsonAndFromJson_roundTripConsistency() {
        val customProfile = ModelProfile(
            id = "custom-test-1",
            name = "Custom Model Test",
            provider = ProviderKind.CUSTOM_OPENAI,
            modelName = "custom-v1",
            endpointUrl = "https://api.example.com/v1",
            temperature = 0.35f,
            maxTokens = 2048,
            topP = 0.85f,
            contextWindow = 16384,
            supportsTools = true,
            supportsStreaming = false,
            isBuiltin = false
        )

        val jsonStr = customProfile.toJson()
        val restored = ModelProfile.fromJson(jsonStr)

        assertEquals(customProfile.id, restored.id)
        assertEquals(customProfile.name, restored.name)
        assertEquals(customProfile.provider, restored.provider)
        assertEquals(customProfile.modelName, restored.modelName)
        assertEquals(customProfile.endpointUrl, restored.endpointUrl)
        assertEquals(customProfile.temperature, restored.temperature, 0.001f)
        assertEquals(customProfile.maxTokens, restored.maxTokens)
        assertEquals(customProfile.topP!!, restored.topP!!, 0.001f)
        assertEquals(customProfile.contextWindow, restored.contextWindow)
        assertEquals(customProfile.supportsTools, restored.supportsTools)
        assertEquals(customProfile.supportsStreaming, restored.supportsStreaming)
        assertEquals(customProfile.isBuiltin, restored.isBuiltin)
        assertTrue(restored.validate())
    }
}
