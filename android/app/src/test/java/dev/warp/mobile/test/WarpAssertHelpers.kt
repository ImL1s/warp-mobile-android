package dev.warp.mobile.test

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

object WarpAssertHelpers {
    fun assertBlockTimelineEquals(
        expected: List<TestBlockCardState>,
        actual: List<TestBlockCardState>
    ) {
        assertEquals("Block timeline size mismatch", expected.size, actual.size)
        expected.zip(actual).forEachIndexed { idx, (exp, act) ->
            assertEquals("Block ID mismatch at index $idx", exp.blockId, act.blockId)
            assertEquals("Block command mismatch at index $idx", exp.command, act.command)
            assertEquals("Block exitCode mismatch at index $idx", exp.exitCode, act.exitCode)
            assertEquals("Block output mismatch at index $idx", exp.outputText, act.outputText)
            assertEquals("Block isRunning mismatch at index $idx", exp.isRunning, act.isRunning)
        }
    }

    fun assertSessionStateValid(session: TestSessionHandle) {
        assertNotNull("Session handle ID must not be null", session.id)
        assertTrue("Session handle ID must not be empty", session.id.isNotBlank())
        assertTrue(
            "Session working dir must start with slash or home (~)",
            session.workingDir.startsWith("/") || session.workingDir.startsWith("~")
        )
        assertTrue("Session created timestamp must be positive", session.createdAtEpochMs > 0)
    }

    fun assertToolApprovalIntercepted(
        toolName: String,
        approved: Boolean,
        callbackResult: Boolean
    ) {
        assertTrue("Tool name must not be blank", toolName.isNotBlank())
        assertEquals(
            "Tool approval interception state mismatch for tool '$toolName'",
            approved,
            callbackResult
        )
    }

    fun assertSearchResultGrouped(
        results: Map<String, List<TestSearchQueryResult>>,
        expectedDomains: List<String>
    ) {
        assertNotNull("Search result map must not be null", results)
        expectedDomains.forEach { domain ->
            assertTrue(
                "Search results must contain domain key: $domain",
                results.containsKey(domain)
            )
        }
    }

    fun assertMcpRequestValid(jsonRpcMessage: String) {
        assertNotNull("JSON-RPC message must not be null", jsonRpcMessage)
        assertTrue("JSON-RPC message must contain jsonrpc field", jsonRpcMessage.contains("\"jsonrpc\""))
        assertTrue("JSON-RPC message must contain method or result field", jsonRpcMessage.contains("\"method\"") || jsonRpcMessage.contains("\"result\""))
    }

    fun assertPaneDimensionsValid(
        paneRatio: Float,
        minRatio: Float = 0.1f,
        maxRatio: Float = 0.9f
    ) {
        assertTrue(
            "Pane ratio $paneRatio must be between $minRatio and $maxRatio",
            paneRatio in minRatio..maxRatio
        )
    }

    fun assertRulesVerdictAllowed(verdict: String) {
        assertTrue(
            "Verdict '$verdict' must be ALLOW or DENY",
            verdict == "ALLOW" || verdict == "DENY" || verdict == "PROMPT"
        )
    }
}

