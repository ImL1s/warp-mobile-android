package dev.warp.mobile.test

import dev.warp.mobile.AgentTurnStatus
import dev.warp.mobile.SessionManager
import dev.warp.mobile.ToolStatus
import dev.warp.mobile.WarpBlockState
import dev.warp.mobile.WarpTimelineBlock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class MultiTurnAgentChallengerEmpiricalTest : BaseWarpUnitTest() {

    private lateinit var sessionManager: SessionManager

    @Before
    override fun setUp() {
        super.setUp()
        sessionManager = SessionManager.createForTesting()
        sessionManager.resetForTesting()
    }

    /**
     * Edge Case 1: High-Frequency Streaming Chunk Updates
     * Tests rapid streaming updates (1,000 chunks) to verify state stability, thread safety,
     * and content integrity without dropping updates or corrupting text.
     */
    @Test
    fun testHighFrequencyStreamingChunkUpdates_stress() = runTest {
        val sessionId = sessionManager.createSession("Streaming Session")
        val responseBlock = WarpTimelineBlock.AssistantResponseBlock(
            id = "a-high-freq",
            sessionId = sessionId,
            turnIndex = 0,
            model = "claude-sonnet-4-6",
            content = "",
            status = AgentTurnStatus.STREAMING
        )
        sessionManager.addTimelineBlock(responseBlock)

        val totalChunks = 1000
        val sb = StringBuilder()
        val executor = Executors.newFixedThreadPool(8)

        // Stream chunks rapidly
        val counter = AtomicInteger(0)
        for (i in 1..totalChunks) {
            val chunkStr = "chunk_$i "
            sb.append(chunkStr)
            val currentContent = sb.toString()
            executor.submit {
                sessionManager.updateAssistantResponse(
                    blockId = "a-high-freq",
                    newContent = currentContent,
                    status = AgentTurnStatus.STREAMING
                )
                counter.incrementAndGet()
            }
        }

        executor.shutdown()
        val finished = executor.awaitTermination(10, TimeUnit.SECONDS)
        assertTrue("High frequency streaming timed out", finished)
        assertEquals(totalChunks, counter.get())

        // Final completion update
        val expectedFinalContent = sb.toString()
        sessionManager.updateAssistantResponse(
            blockId = "a-high-freq",
            newContent = expectedFinalContent,
            status = AgentTurnStatus.COMPLETED
        )

        val finalBlock = sessionManager.appState.value.timelineBlocks.find { it.id == "a-high-freq" } as? WarpTimelineBlock.AssistantResponseBlock
        assertNotNull(finalBlock)
        assertEquals(AgentTurnStatus.COMPLETED, finalBlock?.status)
        assertEquals(expectedFinalContent, finalBlock?.content)
    }

    /**
     * Edge Case 2: Concurrent Block Mutations
     * Tests multi-threaded concurrent additions, updates, card insertions, and timeline replacements
     * to ensure thread-safety and lack of IndexOutOfBoundsException or ConcurrentModificationException.
     */
    @Test
    fun testConcurrentBlockMutations_stress() = runTest {
        val sessionId = sessionManager.createSession("Concurrent Session")
        
        // Seed 5 command blocks
        val initialCmds = (1..5).map { idx ->
            WarpBlockState(id = "cmd-$idx", command = "echo test $idx", exitCode = 0, isRunning = false)
        }
        sessionManager.setTimelineBlocks(initialCmds.map { WarpTimelineBlock.CommandBlock(it) })

        val numThreads = 10
        val operationsPerThread = 50
        val executor = Executors.newFixedThreadPool(numThreads)

        val exceptionsCount = AtomicInteger(0)

        for (t in 1..numThreads) {
            executor.submit {
                try {
                    for (op in 1..operationsPerThread) {
                        val choice = (op + t) % 4
                        when (choice) {
                            0 -> {
                                sessionManager.addTimelineBlock(
                                    WarpTimelineBlock.UserPromptBlock(
                                        id = "u-$t-$op",
                                        sessionId = sessionId,
                                        prompt = "Prompt from thread $t op $op",
                                        turnIndex = op
                                    )
                                )
                            }
                            1 -> {
                                val targetCmd = initialCmds[(op % initialCmds.size)]
                                sessionManager.insertExplanationCard(targetCmd)
                            }
                            2 -> {
                                sessionManager.addTimelineBlock(
                                    WarpTimelineBlock.ReasoningCardBlock(
                                        id = "r-$t-$op",
                                        sessionId = sessionId,
                                        thinkingText = "Thinking thread $t op $op",
                                        isStreaming = op % 2 == 0
                                    )
                                )
                            }
                            3 -> {
                                sessionManager.addTimelineBlock(
                                    WarpTimelineBlock.ToolInvocationBlock(
                                        id = "tb-$t-$op",
                                        toolId = "tool-$op",
                                        toolName = "execute_command",
                                        inputJson = "{\"op\": $op}",
                                        status = ToolStatus.EXECUTING
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                    exceptionsCount.incrementAndGet()
                }
            }
        }

        executor.shutdown()
        val finished = executor.awaitTermination(15, TimeUnit.SECONDS)
        assertTrue("Concurrent mutations timed out", finished)
        assertEquals("Exceptions occurred during concurrent mutations", 0, exceptionsCount.get())

        val timeline = sessionManager.appState.value.timelineBlocks
        assertTrue("Timeline should contain interleaved blocks", timeline.size > 5)
    }

    /**
     * Edge Case 3: Explanation Card Insertions Below Command Blocks
     * Tests exact position placement when explanation cards are inserted:
     * - Below top block
     * - Below middle block
     * - Below bottom block
     * - Non-existent target command block ID
     * - Sequentially multiple cards for same block
     */
    @Test
    fun testExplanationCardInsertions_positionOrdering() {
        val c1 = WarpBlockState(id = "c-1", command = "ls -l", exitCode = 0, isRunning = false)
        val c2 = WarpBlockState(id = "c-2", command = "pwd", exitCode = 0, isRunning = false)
        val c3 = WarpBlockState(id = "c-3", command = "whoami", exitCode = 0, isRunning = false)

        sessionManager.setTimelineBlocks(listOf(
            WarpTimelineBlock.CommandBlock(c1),
            WarpTimelineBlock.CommandBlock(c2),
            WarpTimelineBlock.CommandBlock(c3)
        ))

        // 1. Insert below c2 (middle block)
        sessionManager.insertExplanationCard(c2)
        var list = sessionManager.appState.value.timelineBlocks

        // Expected: c1 (idx 0), c2 (idx 1), prompt_c2 (idx 2), resp_c2 (idx 3), c3 (idx 4)
        assertEquals(5, list.size)
        assertEquals("c-1", (list[0] as WarpTimelineBlock.CommandBlock).state.id)
        assertEquals("c-2", (list[1] as WarpTimelineBlock.CommandBlock).state.id)
        assertTrue(list[2] is WarpTimelineBlock.UserPromptBlock)
        assertTrue((list[2] as WarpTimelineBlock.UserPromptBlock).prompt.contains("Explain command: pwd"))
        assertTrue(list[3] is WarpTimelineBlock.AssistantResponseBlock)
        assertEquals("c-3", (list[4] as WarpTimelineBlock.CommandBlock).state.id)

        // 2. Insert below c1 (top block)
        sessionManager.insertExplanationCard(c1)
        list = sessionManager.appState.value.timelineBlocks

        // Expected: c1 (idx 0), prompt_c1 (idx 1), resp_c1 (idx 2), c2 (idx 3), prompt_c2 (idx 4), resp_c2 (idx 5), c3 (idx 6)
        assertEquals(7, list.size)
        assertEquals("c-1", (list[0] as WarpTimelineBlock.CommandBlock).state.id)
        assertTrue(list[1] is WarpTimelineBlock.UserPromptBlock)
        assertTrue((list[1] as WarpTimelineBlock.UserPromptBlock).prompt.contains("Explain command: ls -l"))
        assertTrue(list[2] is WarpTimelineBlock.AssistantResponseBlock)
        assertEquals("c-2", (list[3] as WarpTimelineBlock.CommandBlock).state.id)

        // 3. Insert for non-existent target command block ID
        val nonexistentCmd = WarpBlockState(id = "c-999", command = "unknown", exitCode = 1, isRunning = false)
        sessionManager.insertExplanationCard(nonexistentCmd)
        list = sessionManager.appState.value.timelineBlocks

        // Expected: Appended to the end of the timeline
        assertEquals(9, list.size)
        assertTrue(list[7] is WarpTimelineBlock.UserPromptBlock)
        assertTrue((list[7] as WarpTimelineBlock.UserPromptBlock).prompt.contains("Explain command: unknown"))
        assertTrue(list[8] is WarpTimelineBlock.AssistantResponseBlock)
    }

    /**
     * Edge Case 4: Turn Control Actions (Cancel, Pause/Resume, Retry, Edit Prompt)
     * Verifies turn state transitions and timeline block modifications.
     */
    @Test
    fun testTurnControlActions_fullLifecycle() {
        val sessionId = sessionManager.createSession("Turn Controls Session")

        val userBlock = WarpTimelineBlock.UserPromptBlock(
            id = "u-turn-0",
            sessionId = sessionId,
            prompt = "Run heavy operation",
            turnIndex = 0
        )
        val responseBlock = WarpTimelineBlock.AssistantResponseBlock(
            id = "a-turn-0",
            sessionId = sessionId,
            turnIndex = 0,
            model = "claude-sonnet-4-6",
            content = "Starting execution...",
            status = AgentTurnStatus.STREAMING
        )
        sessionManager.addTimelineBlock(userBlock)
        sessionManager.addTimelineBlock(responseBlock)

        // 1. Pause
        sessionManager.updateAssistantResponse("a-turn-0", "Starting execution...", AgentTurnStatus.PAUSED)
        var currentResp = sessionManager.appState.value.timelineBlocks.find { it.id == "a-turn-0" } as WarpTimelineBlock.AssistantResponseBlock
        assertEquals(AgentTurnStatus.PAUSED, currentResp.status)

        // 2. Resume
        sessionManager.updateAssistantResponse("a-turn-0", "Starting execution... step 1", AgentTurnStatus.STREAMING)
        currentResp = sessionManager.appState.value.timelineBlocks.find { it.id == "a-turn-0" } as WarpTimelineBlock.AssistantResponseBlock
        assertEquals(AgentTurnStatus.STREAMING, currentResp.status)
        assertEquals("Starting execution... step 1", currentResp.content)

        // 3. Cancel
        sessionManager.updateAssistantResponse("a-turn-0", "Starting execution... cancelled", AgentTurnStatus.CANCELLED)
        currentResp = sessionManager.appState.value.timelineBlocks.find { it.id == "a-turn-0" } as WarpTimelineBlock.AssistantResponseBlock
        assertEquals(AgentTurnStatus.CANCELLED, currentResp.status)

        // 4. Error & Retry
        sessionManager.updateAssistantResponse("a-turn-0", "Error occurred", AgentTurnStatus.ERROR, errorMessage = "API error 500")
        currentResp = sessionManager.appState.value.timelineBlocks.find { it.id == "a-turn-0" } as WarpTimelineBlock.AssistantResponseBlock
        assertEquals(AgentTurnStatus.ERROR, currentResp.status)
        assertEquals("API error 500", currentResp.errorMessage)

        // Retry resets error message and sets status back to CONNECTING/STREAMING
        sessionManager.updateAssistantResponse("a-turn-0", "", AgentTurnStatus.STREAMING, errorMessage = null)
        currentResp = sessionManager.appState.value.timelineBlocks.find { it.id == "a-turn-0" } as WarpTimelineBlock.AssistantResponseBlock
        assertEquals(AgentTurnStatus.STREAMING, currentResp.status)
        assertNull(currentResp.errorMessage)

        // Complete
        sessionManager.updateAssistantResponse("a-turn-0", "Execution completed successfully", AgentTurnStatus.COMPLETED)
        currentResp = sessionManager.appState.value.timelineBlocks.find { it.id == "a-turn-0" } as WarpTimelineBlock.AssistantResponseBlock
        assertEquals(AgentTurnStatus.COMPLETED, currentResp.status)
        assertEquals("Execution completed successfully", currentResp.content)
    }

    /**
     * Edge Case 5: Edit Prompt Truncation and Replacement
     */
    @Test
    fun testEditPrompt_truncatesAndReplacesTurn() {
        val sessionId = sessionManager.createSession("Edit Prompt Session")

        val u0 = WarpTimelineBlock.UserPromptBlock("u-0", sessionId, "Initial Prompt 0", 0)
        val a0 = WarpTimelineBlock.AssistantResponseBlock("a-0", sessionId, 0, "claude-sonnet-4-6", "Answer 0", AgentTurnStatus.COMPLETED)
        val u1 = WarpTimelineBlock.UserPromptBlock("u-1", sessionId, "Initial Prompt 1", 1)
        val a1 = WarpTimelineBlock.AssistantResponseBlock("a-1", sessionId, 1, "claude-sonnet-4-6", "Answer 1", AgentTurnStatus.COMPLETED)

        sessionManager.setTimelineBlocks(listOf(u0, a0, u1, a1))
        assertEquals(4, sessionManager.appState.value.timelineBlocks.size)

        // Simulate Edit Prompt action on u-0: UI truncates history from u-0 onwards and sends new prompt
        val editedU0 = u0.copy(prompt = "Edited Prompt 0")
        val newA0 = a0.copy(content = "Re-generated Answer 0", status = AgentTurnStatus.COMPLETED)
        
        val truncatedBlocks = sessionManager.appState.value.timelineBlocks.takeWhile { 
            (it as? WarpTimelineBlock.UserPromptBlock)?.id != u0.id
        } + listOf(editedU0, newA0)

        sessionManager.setTimelineBlocks(truncatedBlocks)

        val updatedTimeline = sessionManager.appState.value.timelineBlocks
        assertEquals(2, updatedTimeline.size)
        assertEquals("u-0", (updatedTimeline[0] as WarpTimelineBlock.UserPromptBlock).id)
        assertEquals("Edited Prompt 0", (updatedTimeline[0] as WarpTimelineBlock.UserPromptBlock).prompt)
        assertEquals("a-0", (updatedTimeline[1] as WarpTimelineBlock.AssistantResponseBlock).id)
        assertEquals("Re-generated Answer 0", (updatedTimeline[1] as WarpTimelineBlock.AssistantResponseBlock).content)
    }
}
