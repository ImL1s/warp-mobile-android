package dev.warp.mobile.editor

import androidx.compose.ui.text.AnnotatedString
import dev.warp.mobile.test.BaseWarpUnitTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GhostCompletionEngineTest : BaseWarpUnitTest() {

    @Test
    fun testGhostCompletion_historyPriorityOverDictionaryAndAi() {
        val history = listOf(
            HistoryItem(command = "git checkout main"),
            HistoryItem(command = "cargo check")
        )

        // Matches history first
        val suggestion = GhostCompletionEngine.getGhostSuggestion("git", history) { "git push origin main" }
        assertEquals("git checkout main", suggestion)

        // Matches dictionary when not in history
        val dictSuggestion = GhostCompletionEngine.getGhostSuggestion("dock", history) { null }
        assertEquals("docker ps", dictSuggestion)

        // Falls back to AI when neither history nor dictionary matches
        val aiSuggestion = GhostCompletionEngine.getGhostSuggestion("custom-cmd", history) { "custom-cmd --flag" }
        assertEquals("custom-cmd --flag", aiSuggestion)

        // Blank input returns null
        assertNull(GhostCompletionEngine.getGhostSuggestion("", history))
    }

    @Test
    fun testGhostSuffix_extraction() {
        assertEquals(" status", GhostCompletionEngine.getGhostSuffix("git", "git status"))
        assertEquals("", GhostCompletionEngine.getGhostSuffix("git", "git"))
        assertEquals("", GhostCompletionEngine.getGhostSuffix("git", "docker ps"))
        assertEquals("", GhostCompletionEngine.getGhostSuffix("", "git status"))
    }

    @Test
    fun testGhostTextVisualTransformation_offsetMappingAndFormatting() {
        val transformation = GhostTextVisualTransformation(" status")
        val input = AnnotatedString("git")
        val transformed = transformation.filter(input)

        assertEquals("git status", transformed.text.text)

        val mapping = transformed.offsetMapping
        assertEquals(0, mapping.originalToTransformed(0))
        assertEquals(3, mapping.originalToTransformed(3))
        assertEquals(3, mapping.transformedToOriginal(10))
    }
}
