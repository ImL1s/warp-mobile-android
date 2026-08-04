package dev.warp.mobile.editor

import androidx.compose.ui.text.AnnotatedString
import dev.warp.mobile.test.BaseWarpUnitTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Task2AdversarialStressTest : BaseWarpUnitTest() {

    // =========================================================================
    // 1. GhostTextVisualTransformation OffsetMapping & Unicode Stress Tests
    // =========================================================================

    @Test
    fun testGhostTextVisualTransformation_emptyInputAndEmptySuffix() {
        val transformation = GhostTextVisualTransformation("")
        val input = AnnotatedString("")
        val transformed = transformation.filter(input)

        assertEquals("", transformed.text.text)
        val mapping = transformed.offsetMapping
        assertEquals(0, mapping.originalToTransformed(0))
        assertEquals(0, mapping.transformedToOriginal(0))
    }

    @Test
    fun testGhostTextVisualTransformation_emptyInputWithGhostSuffix() {
        val transformation = GhostTextVisualTransformation("git status")
        val input = AnnotatedString("")
        val transformed = transformation.filter(input)

        assertEquals("git status", transformed.text.text)

        val mapping = transformed.offsetMapping
        // originalToTransformed for empty original
        assertEquals(0, mapping.originalToTransformed(0))
        assertEquals(5, mapping.originalToTransformed(5)) // does not throw

        // transformedToOriginal for transformed range 0..10
        assertEquals(0, mapping.transformedToOriginal(0))
        assertEquals(0, mapping.transformedToOriginal(5))
        assertEquals(0, mapping.transformedToOriginal(10))
        assertEquals(0, mapping.transformedToOriginal(100))
    }

    @Test
    fun testGhostTextVisualTransformation_cjkMultiByteStrings() {
        val inputStr = "echo 繁體中文"
        val suffixStr = " --help 測試"
        val transformation = GhostTextVisualTransformation(suffixStr)
        val input = AnnotatedString(inputStr)
        val transformed = transformation.filter(input)

        val expectedFullText = inputStr + suffixStr
        assertEquals(expectedFullText, transformed.text.text)

        val mapping = transformed.offsetMapping
        val origLength = inputStr.length
        val transformedLength = expectedFullText.length

        // Verify originalToTransformed across CJK indices
        for (i in 0..origLength) {
            assertEquals(i, mapping.originalToTransformed(i))
        }

        // Verify transformedToOriginal across CJK transformed indices
        for (i in 0..transformedLength) {
            val expectedOrig = i.coerceAtMost(origLength)
            assertEquals(expectedOrig, mapping.transformedToOriginal(i))
        }
    }

    @Test
    fun testGhostTextVisualTransformation_emojiSurrogatePairsAndCombiningChars() {
        // "echo 🚀🎉 e\u0301" contains surrogate pairs (🚀, 🎉) and combining character (e + accent)
        val inputStr = "echo 🚀🎉 e\u0301"
        val suffixStr = " --verbose 😃"
        val transformation = GhostTextVisualTransformation(suffixStr)
        val input = AnnotatedString(inputStr)
        val transformed = transformation.filter(input)

        assertEquals(inputStr + suffixStr, transformed.text.text)

        val mapping = transformed.offsetMapping
        val origLength = inputStr.length

        // Verify extreme / boundary inputs never throw IndexOutOfBoundsException
        val boundaryIndices = listOf(-100, -1, 0, 1, origLength / 2, origLength, origLength + 1, 999999)
        for (idx in boundaryIndices) {
            val origTransferred = mapping.originalToTransformed(idx)
            assertEquals(idx, origTransferred)

            val transformedOrig = mapping.transformedToOriginal(idx)
            val expected = idx.coerceAtMost(origLength)
            assertEquals(expected, transformedOrig)
        }
    }

    @Test
    fun testGhostTextVisualTransformation_multiLineStrings() {
        val inputStr = "git commit\n-m \"test line 1\""
        val suffixStr = "\n-m \"test line 2\""
        val transformation = GhostTextVisualTransformation(suffixStr)
        val input = AnnotatedString(inputStr)
        val transformed = transformation.filter(input)

        assertEquals(inputStr + suffixStr, transformed.text.text)
        val mapping = transformed.offsetMapping
        assertEquals(inputStr.length, mapping.transformedToOriginal(inputStr.length + 5))
    }

    @Test
    fun testGhostTextVisualTransformation_massiveGhostSuffixPerformanceAndBounds() {
        val inputStr = "git"
        val massiveSuffix = " x".repeat(50000) // 100,000 characters
        val transformation = GhostTextVisualTransformation(massiveSuffix)
        val input = AnnotatedString(inputStr)
        val transformed = transformation.filter(input)

        val mapping = transformed.offsetMapping
        assertEquals(3, mapping.originalToTransformed(3))
        assertEquals(3, mapping.transformedToOriginal(100003))
        assertEquals(3, mapping.transformedToOriginal(9999999))
    }


    // =========================================================================
    // 2. SlashCommandRegistry Special Characters & Filtering Stress Tests
    // =========================================================================

    @Test
    fun testSlashCommandRegistry_singleSlashReturnsAllCommands() {
        val result = SlashCommandRegistry.filterCommands("/")
        assertEquals(SlashCommandRegistry.ALL_COMMANDS.size, result.size)
    }

    @Test
    fun testSlashCommandRegistry_doubleSlashReturnsAllCommandsWithoutCrash() {
        val result = SlashCommandRegistry.filterCommands("//")
        // "//" query is "/" which matches all commands containing "/"
        assertEquals(SlashCommandRegistry.ALL_COMMANDS.size, result.size)
    }

    @Test
    fun testSlashCommandRegistry_tripleSlashReturnsEmptyList() {
        val result = SlashCommandRegistry.filterCommands("///")
        // "///" query is "//" which matches no command name, title, or description
        assertTrue(result.isEmpty())
    }

    @Test
    fun testSlashCommandRegistry_slashQuestionMarkReturnsEmptyList() {
        val result = SlashCommandRegistry.filterCommands("/?")
        assertTrue(result.isEmpty())
    }

    @Test
    fun testSlashCommandRegistry_whitespaceAndEmptyInputs() {
        val resultEmpty = SlashCommandRegistry.filterCommands("")
        assertEquals(SlashCommandRegistry.ALL_COMMANDS.size, resultEmpty.size)

        val resultSpaces = SlashCommandRegistry.filterCommands("   ")
        assertEquals(SlashCommandRegistry.ALL_COMMANDS.size, resultSpaces.size)

        val resultSlashSpaces = SlashCommandRegistry.filterCommands("/   ")
        assertEquals(SlashCommandRegistry.ALL_COMMANDS.size, resultSlashSpaces.size)
    }

    @Test
    fun testSlashCommandRegistry_specialRegexCharsNoCrash() {
        val specialInputs = listOf("/*", "/[", "/(", "/$", "/^", "/\\", "/+", "/?", "/{", "/|")
        for (input in specialInputs) {
            val result = SlashCommandRegistry.filterCommands(input)
            assertNotNull(result) // Ensure no PatternSyntaxException or crash
        }
    }

    @Test
    fun testSlashCommandRegistry_cjkQueries() {
        val result1 = SlashCommandRegistry.filterCommands("/繁體中文")
        assertTrue(result1.isEmpty())

        val result2 = SlashCommandRegistry.filterCommands("/設定")
        assertTrue(result2.isEmpty())
    }

    @Test
    fun testSlashCommandRegistry_leadingAndInternalSpaces() {
        // " /ai" query is "/ai" which matches command "/ai" exactly (size = 1)
        val resultLeading = SlashCommandRegistry.filterCommands(" /ai")
        assertEquals(1, resultLeading.size)
        assertEquals("/ai", resultLeading[0].command)

        // "/   ai" query is "ai" which matches /ai and /search (whose description contains "AI") (size = 2)
        val resultInternal = SlashCommandRegistry.filterCommands("/   ai")
        assertEquals(2, resultInternal.size)
        assertTrue(resultInternal.any { it.command == "/ai" })
        assertTrue(resultInternal.any { it.command == "/search" })
    }

    @Test
    fun testSlashCommandRegistry_controlCharacters() {
        val resultNewline = SlashCommandRegistry.filterCommands("/ai\n")
        assertEquals(2, resultNewline.size)

        val resultTab = SlashCommandRegistry.filterCommands("/ai\t")
        assertEquals(2, resultTab.size)
    }

    @Test
    fun testSlashCommandRegistry_nonMatchingQuery() {
        val result = SlashCommandRegistry.filterCommands("/nonexistentcommand")
        assertTrue(result.isEmpty())
    }
}
