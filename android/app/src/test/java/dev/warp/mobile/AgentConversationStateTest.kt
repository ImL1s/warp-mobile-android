package dev.warp.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationStateTest {

    @Test
    fun testMultiTurnConversation_accumulatesContextInState() {
        val manager = SessionManager.createForTesting()
        val sessionId = manager.createSession("Agent Session")

        val prompt1 = WarpTimelineBlock.UserPromptBlock(
            id = "u-1",
            sessionId = sessionId,
            prompt = "How do I check disk space?",
            turnIndex = 0
        )
        val response1 = WarpTimelineBlock.AssistantResponseBlock(
            id = "a-1",
            sessionId = sessionId,
            turnIndex = 0,
            model = "claude-sonnet-4-6",
            content = "Use `df -h`.",
            status = AgentTurnStatus.COMPLETED
        )

        manager.addTimelineBlock(prompt1)
        manager.addTimelineBlock(response1)

        val state1 = manager.appState.value
        assertEquals(2, state1.timelineBlocks.size)
        assertEquals("u-1", state1.timelineBlocks[0].id)
        assertEquals("a-1", state1.timelineBlocks[1].id)

        val prompt2 = WarpTimelineBlock.UserPromptBlock(
            id = "u-2",
            sessionId = sessionId,
            prompt = "How about current directory size?",
            turnIndex = 1
        )
        val response2 = WarpTimelineBlock.AssistantResponseBlock(
            id = "a-2",
            sessionId = sessionId,
            turnIndex = 1,
            model = "claude-sonnet-4-6",
            content = "Use `du -sh .`.",
            status = AgentTurnStatus.COMPLETED
        )

        manager.addTimelineBlock(prompt2)
        manager.addTimelineBlock(response2)

        val state2 = manager.appState.value
        assertEquals(4, state2.timelineBlocks.size)
        assertEquals("u-2", state2.timelineBlocks[2].id)
        assertEquals("a-2", state2.timelineBlocks[3].id)
    }

    @Test
    fun testWarpAppState_timelineBlocks_maintainsHeterogeneousBlocks() {
        val cmdBlock = WarpTimelineBlock.CommandBlock(
            WarpBlockState(id = "c-1", command = "ls -la", exitCode = 0, isRunning = false)
        )
        val userBlock = WarpTimelineBlock.UserPromptBlock(
            id = "u-1",
            sessionId = "s-1",
            prompt = "Explain `ls -la`",
            turnIndex = 0
        )
        val reasoningBlock = WarpTimelineBlock.ReasoningCardBlock(
            id = "r-1",
            sessionId = "s-1",
            thinkingText = "Analyzing flag -la...",
            isStreaming = false
        )
        val toolBlock = WarpTimelineBlock.ToolInvocationBlock(
            id = "t-1",
            toolId = "exec-1",
            toolName = "execute_command",
            inputJson = "{\"command\":\"ls -la\"}",
            output = "total 0",
            status = ToolStatus.COMPLETED
        )
        val assistantBlock = WarpTimelineBlock.AssistantResponseBlock(
            id = "a-1",
            sessionId = "s-1",
            turnIndex = 0,
            model = "claude-sonnet-4-6",
            content = "`ls -la` lists all files including hidden ones.",
            status = AgentTurnStatus.COMPLETED
        )

        val appState = WarpAppState(
            timelineBlocks = listOf(cmdBlock, userBlock, reasoningBlock, toolBlock, assistantBlock)
        )

        assertEquals(5, appState.timelineBlocks.size)
        assertEquals(1, appState.effectiveBlocks.size)
        assertEquals("c-1", appState.effectiveBlocks[0].id)
    }

    @Test
    fun testDurableSessionRestoration_restoresAgentTurnHistory() {
        val manager = SessionManager.createForTesting()
        manager.createSession("Test")
        val commandState = WarpBlockState(id = "b-100", command = "pwd", exitCode = 0, output = "/usr/bin\n", isRunning = false)
        val dumpJson = "[{\"id\":\"b-100\",\"command\":\"pwd\",\"exit_code\":0,\"output\":\"/usr/bin\\n\",\"is_running\":false}]"

        manager.appState.value.updateBlocksFromDump(dumpJson)
        val updated = manager.appState.value.updateBlocksFromDump(dumpJson)

        assertEquals(1, updated.blocks.size)
        assertEquals("b-100", updated.blocks[0].id)
        assertEquals(1, updated.timelineBlocks.size)
        assertTrue(updated.timelineBlocks[0] is WarpTimelineBlock.CommandBlock)
    }
}
