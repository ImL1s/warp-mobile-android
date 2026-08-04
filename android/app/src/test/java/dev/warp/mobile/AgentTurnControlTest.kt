package dev.warp.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTurnControlTest {

    @Test
    fun testTurnControls_cancelStateTransition() {
        val manager = SessionManager.createForTesting()
        val responseBlock = WarpTimelineBlock.AssistantResponseBlock(
            id = "a-stream",
            sessionId = "s-1",
            turnIndex = 0,
            model = "claude-sonnet-4-6",
            content = "Partial stream...",
            status = AgentTurnStatus.STREAMING
        )
        manager.addTimelineBlock(responseBlock)

        manager.updateAssistantResponse("a-stream", "Partial stream...", AgentTurnStatus.CANCELLED)

        val updated = manager.appState.value.timelineBlocks.find { it.id == "a-stream" } as? WarpTimelineBlock.AssistantResponseBlock
        assertNotNull(updated)
        assertEquals(AgentTurnStatus.CANCELLED, updated?.status)
        assertEquals("Partial stream...", updated?.content)
    }

    @Test
    fun testTurnControls_pauseResumeStateTransition() {
        val manager = SessionManager.createForTesting()
        val responseBlock = WarpTimelineBlock.AssistantResponseBlock(
            id = "a-pause",
            sessionId = "s-1",
            turnIndex = 0,
            model = "claude-sonnet-4-6",
            content = "Streaming data...",
            status = AgentTurnStatus.STREAMING
        )
        manager.addTimelineBlock(responseBlock)

        // Pause
        manager.updateAssistantResponse("a-pause", "Streaming data...", AgentTurnStatus.PAUSED)
        var current = manager.appState.value.timelineBlocks.find { it.id == "a-pause" } as? WarpTimelineBlock.AssistantResponseBlock
        assertEquals(AgentTurnStatus.PAUSED, current?.status)

        // Resume
        manager.updateAssistantResponse("a-pause", "Streaming data... more data", AgentTurnStatus.STREAMING)
        current = manager.appState.value.timelineBlocks.find { it.id == "a-pause" } as? WarpTimelineBlock.AssistantResponseBlock
        assertEquals(AgentTurnStatus.STREAMING, current?.status)
        assertEquals("Streaming data... more data", current?.content)
    }

    @Test
    fun testTurnControls_retryTurn_resetsErrorState() {
        val manager = SessionManager.createForTesting()
        val responseBlock = WarpTimelineBlock.AssistantResponseBlock(
            id = "a-err",
            sessionId = "s-1",
            turnIndex = 0,
            model = "claude-sonnet-4-6",
            content = "",
            status = AgentTurnStatus.ERROR,
            errorMessage = "Network timeout"
        )
        manager.addTimelineBlock(responseBlock)

        // Retry resets state to CONNECTING / STREAMING and clears error message
        manager.updateAssistantResponse("a-err", "", AgentTurnStatus.STREAMING, errorMessage = null)

        val current = manager.appState.value.timelineBlocks.find { it.id == "a-err" } as? WarpTimelineBlock.AssistantResponseBlock
        assertEquals(AgentTurnStatus.STREAMING, current?.status)
        assertEquals(null, current?.errorMessage)
    }

    @Test
    fun testTurnControls_editPrompt_truncatesSubsequentTurns() {
        val manager = SessionManager.createForTesting()
        val u0 = WarpTimelineBlock.UserPromptBlock("u-0", "s-1", "Prompt 0", 0)
        val a0 = WarpTimelineBlock.AssistantResponseBlock("a-0", "s-1", 0, "model", "Resp 0", AgentTurnStatus.COMPLETED)
        val u1 = WarpTimelineBlock.UserPromptBlock("u-1", "s-1", "Prompt 1", 1)
        val a1 = WarpTimelineBlock.AssistantResponseBlock("a-1", "s-1", 1, "model", "Resp 1", AgentTurnStatus.COMPLETED)

        manager.setTimelineBlocks(listOf(u0, a0, u1, a1))
        assertEquals(4, manager.appState.value.timelineBlocks.size)

        // Edit u-0 prompt -> truncates history at turn 0
        val newU0 = u0.copy(prompt = "Prompt 0 Edited")
        val newA0 = a0.copy(content = "Resp 0 Re-generated", status = AgentTurnStatus.COMPLETED)
        manager.setTimelineBlocks(listOf(newU0, newA0))

        assertEquals(2, manager.appState.value.timelineBlocks.size)
        assertEquals("Prompt 0 Edited", (manager.appState.value.timelineBlocks[0] as WarpTimelineBlock.UserPromptBlock).prompt)
    }
}
