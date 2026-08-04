package dev.warp.mobile.ai

import dev.warp.mobile.AiUsageTracker
import org.junit.Assert.assertEquals
import org.junit.Test

class AiUsageTrackerTest {

    @Test
    fun testAiUsageTracker_escapeRfc4180_formatting() {
        assertEquals("ls -la", AiUsageTracker.escapeRfc4180("ls -la"))
        assertEquals("\"echo a,b,c\"", AiUsageTracker.escapeRfc4180("echo a,b,c"))
        assertEquals("\"echo \"\"hello\"\"\"", AiUsageTracker.escapeRfc4180("echo \"hello\""))
        assertEquals("\"line1\nline2\"", AiUsageTracker.escapeRfc4180("line1\nline2"))
    }

    @Test
    fun testAiUsageTracker_parseUsageFromBody_extractsTokens() {
        val jsonBody = """
            {
                "id": "msg_123",
                "usage": {
                    "input_tokens": 150,
                    "output_tokens": 300
                }
            }
        """.trimIndent()

        val (input, output) = AiUsageTracker.parseUsageFromBody(jsonBody)
        assertEquals(150, input)
        assertEquals(300, output)

        val (zeroIn, zeroOut) = AiUsageTracker.parseUsageFromBody("invalid json")
        assertEquals(0, zeroIn)
        assertEquals(0, zeroOut)
    }

    @Test
    fun testAiUsageTracker_snapshotAndReset() {
        AiUsageTracker.resetSession()
        val emptySnap = AiUsageTracker.snapshot()
        assertEquals(0L, emptySnap.inputTokens)
        assertEquals(0L, emptySnap.outputTokens)
    }
}
