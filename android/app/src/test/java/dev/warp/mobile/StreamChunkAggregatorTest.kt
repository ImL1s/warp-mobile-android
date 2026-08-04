package dev.warp.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamChunkAggregatorTest {

    @Test
    fun testChunkAggregator_batchesHighFrequencyChunks() {
        val manager = SessionManager.createForTesting()
        val responseBlock = WarpTimelineBlock.AssistantResponseBlock(
            id = "a-stream",
            sessionId = "s-1",
            turnIndex = 0,
            model = "claude-sonnet-4-6",
            content = "",
            status = AgentTurnStatus.STREAMING
        )
        manager.addTimelineBlock(responseBlock)

        val sb = StringBuilder()
        for (i in 1..100) {
            sb.append("chunk $i ")
            manager.updateAssistantResponse("a-stream", sb.toString(), AgentTurnStatus.STREAMING)
        }

        val finalBlock = manager.appState.value.timelineBlocks.find { it.id == "a-stream" } as WarpTimelineBlock.AssistantResponseBlock
        assertEquals(AgentTurnStatus.STREAMING, finalBlock.status)
        assertTrue(finalBlock.content.startsWith("chunk 1 "))
        assertTrue(finalBlock.content.endsWith("chunk 100 "))
    }

    @Test
    fun testChunkAggregator_utf8BoundarySafety() {
        // Simulates receiving partial CJK text chunks
        val manager = SessionManager.createForTesting()
        val responseBlock = WarpTimelineBlock.AssistantResponseBlock(
            id = "a-cjk",
            sessionId = "s-1",
            turnIndex = 0,
            model = "claude-sonnet-4-6",
            content = "",
            status = AgentTurnStatus.STREAMING
        )
        manager.addTimelineBlock(responseBlock)

        val chunk1 = "你"
        val chunk2 = "好"
        val chunk3 = "，世界！"

        manager.updateAssistantResponse("a-cjk", chunk1, AgentTurnStatus.STREAMING)
        manager.updateAssistantResponse("a-cjk", chunk1 + chunk2, AgentTurnStatus.STREAMING)
        manager.updateAssistantResponse("a-cjk", chunk1 + chunk2 + chunk3, AgentTurnStatus.COMPLETED)

        val finalBlock = manager.appState.value.timelineBlocks.find { it.id == "a-cjk" } as WarpTimelineBlock.AssistantResponseBlock
        assertEquals(AgentTurnStatus.COMPLETED, finalBlock.status)
        assertEquals("你好，世界！", finalBlock.content)
    }

    @Test
    fun testChunkAggregator_doneAndErrorStateTransitions() {
        val manager = SessionManager.createForTesting()
        val responseBlock = WarpTimelineBlock.AssistantResponseBlock(
            id = "a-done-err",
            sessionId = "s-1",
            turnIndex = 0,
            model = "claude-sonnet-4-6",
            content = "Partial data...",
            status = AgentTurnStatus.STREAMING
        )
        manager.addTimelineBlock(responseBlock)

        // Error transition preserves partial output
        manager.updateAssistantResponse("a-done-err", "Partial data...", AgentTurnStatus.ERROR, errorMessage = "Connection reset by peer")

        val errBlock = manager.appState.value.timelineBlocks.find { it.id == "a-done-err" } as WarpTimelineBlock.AssistantResponseBlock
        assertEquals(AgentTurnStatus.ERROR, errBlock.status)
        assertEquals("Partial data...", errBlock.content)
        assertEquals("Connection reset by peer", errBlock.errorMessage)

        // Complete transition
        manager.updateAssistantResponse("a-done-err", "Complete data finished.", AgentTurnStatus.COMPLETED, errorMessage = null)
        val doneBlock = manager.appState.value.timelineBlocks.find { it.id == "a-done-err" } as WarpTimelineBlock.AssistantResponseBlock
        assertEquals(AgentTurnStatus.COMPLETED, doneBlock.status)
        assertEquals("Complete data finished.", doneBlock.content)
        assertEquals(null, doneBlock.errorMessage)
    }
}
