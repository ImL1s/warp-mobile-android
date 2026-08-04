package dev.warp.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockTimelineInterleavingTest {

    @Test
    fun testInterleaving_shellBlocksAndAgentBlocks_inTimeline() {
        val manager = SessionManager.createForTesting()

        val cmd1 = WarpBlockState(id = "c-100", command = "ls -la", exitCode = 0, isRunning = false)
        val cmd2 = WarpBlockState(id = "c-101", command = "git status", exitCode = 0, isRunning = false)

        manager.setTimelineBlocks(listOf(
            WarpTimelineBlock.CommandBlock(cmd1),
            WarpTimelineBlock.CommandBlock(cmd2)
        ))

        assertEquals(2, manager.appState.value.timelineBlocks.size)

        // User asks agent a question
        val prompt = WarpTimelineBlock.UserPromptBlock("u-1", "s-1", "What branch am I on?", 0)
        val response = WarpTimelineBlock.AssistantResponseBlock("a-1", "s-1", 0, "claude-sonnet-4-6", "You are on main branch.", AgentTurnStatus.COMPLETED)

        manager.addTimelineBlock(prompt)
        manager.addTimelineBlock(response)

        val timeline = manager.appState.value.timelineBlocks
        assertEquals(4, timeline.size)
        assertTrue(timeline[0] is WarpTimelineBlock.CommandBlock)
        assertTrue(timeline[1] is WarpTimelineBlock.CommandBlock)
        assertTrue(timeline[2] is WarpTimelineBlock.UserPromptBlock)
        assertTrue(timeline[3] is WarpTimelineBlock.AssistantResponseBlock)
    }

    @Test
    fun testBlockCard_explainButton_insertsExplanationCardBelowTargetBlock() {
        val manager = SessionManager.createForTesting()

        val cmd1 = WarpBlockState(id = "c-100", command = "du -sh *", exitCode = 0, output = "10M dir1\n20M dir2\n", isRunning = false)
        val cmd2 = WarpBlockState(id = "c-101", command = "top", exitCode = 0, isRunning = false)

        manager.setTimelineBlocks(listOf(
            WarpTimelineBlock.CommandBlock(cmd1),
            WarpTimelineBlock.CommandBlock(cmd2)
        ))

        // Trigger Explain on c-100
        manager.insertExplanationCard(cmd1)

        val timeline = manager.appState.value.timelineBlocks
        assertEquals(4, timeline.size)

        // Verify explanation prompt & response were inserted immediately below c-100 (indices 1 & 2), before c-101 (index 3)
        assertEquals("c-100", (timeline[0] as WarpTimelineBlock.CommandBlock).state.id)
        assertTrue(timeline[1] is WarpTimelineBlock.UserPromptBlock)
        assertTrue(timeline[2] is WarpTimelineBlock.AssistantResponseBlock)
        assertEquals("c-101", (timeline[3] as WarpTimelineBlock.CommandBlock).state.id)

        val userPrompt = timeline[1] as WarpTimelineBlock.UserPromptBlock
        val assistantResp = timeline[2] as WarpTimelineBlock.AssistantResponseBlock
        assertTrue(userPrompt.prompt.contains("Explain command: du -sh *"))
        assertTrue(assistantResp.content.contains("Explanation for `$ du -sh *`"))
    }

    @Test
    fun testToolInvocationBlock_statusTransitions() {
        val toolBlock = WarpTimelineBlock.ToolInvocationBlock(
            id = "tb-1",
            toolId = "t-exec-01",
            toolName = "execute_command",
            inputJson = "{\"command\":\"rm -rf /tmp/test\"}",
            output = null,
            status = ToolStatus.PENDING_APPROVAL
        )

        assertEquals(ToolStatus.PENDING_APPROVAL, toolBlock.status)

        val approvedBlock = toolBlock.copy(status = ToolStatus.APPROVED)
        assertEquals(ToolStatus.APPROVED, approvedBlock.status)

        val completedBlock = approvedBlock.copy(
            output = "Directory removed.",
            status = ToolStatus.COMPLETED
        )
        assertEquals(ToolStatus.COMPLETED, completedBlock.status)
        assertEquals("Directory removed.", completedBlock.output)
    }
}
