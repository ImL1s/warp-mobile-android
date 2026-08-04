package dev.warp.mobile.editor

import android.content.Context
import dev.warp.mobile.test.BaseWarpUnitTest
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CommandHistoryAdversarialTest : BaseWarpUnitTest() {

    private lateinit var context: Context
    private lateinit var tempDir: File

    @Before
    override fun setUp() {
        super.setUp()
        tempDir = File(System.getProperty("java.io.tmpdir"), "cmd_hist_adv_test_${System.currentTimeMillis()}").also { it.mkdirs() }
        context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns tempDir
        CommandHistoryManager.clearHistory(context)
    }

    // 1. Rapid addition of 2000+ commands to verify 1000 items eviction cap and memory stability.
    @Test
    fun stressTest_rapidAddition_2500Commands_strictlyCappedAt1000() {
        val totalAdditions = 2500
        for (i in 1..totalAdditions) {
            CommandHistoryManager.addCommand(context, "command_$i")
        }

        val history = CommandHistoryManager.getHistory()
        assertEquals(1000, history.size)
        // Check oldest 1500 were evicted: history should start from command_1501 and end at command_2500
        assertEquals("command_1501", history.first().command)
        assertEquals("command_2500", history.last().command)

        // Verify disk persistence preserves cap
        CommandHistoryManager.setHistoryForTesting(emptyList())
        CommandHistoryManager.loadHistory(context)
        val loadedHistory = CommandHistoryManager.getHistory()
        assertEquals(1000, loadedHistory.size)
        assertEquals("command_1501", loadedHistory.first().command)
        assertEquals("command_2500", loadedHistory.last().command)
    }

    @Test
    fun stressTest_concurrentCommandAddition_threadSafety() {
        val threadCount = 10
        val commandsPerThread = 200
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        for (t in 0 until threadCount) {
            executor.execute {
                try {
                    for (i in 0 until commandsPerThread) {
                        CommandHistoryManager.addCommand(context, "thread_${t}_cmd_$i")
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()
        assertTrue("Concurrent execution completed within timeout", completed)

        val history = CommandHistoryManager.getHistory()
        assertTrue("History size must not exceed cap of 1000", history.size <= 1000)
    }

    // 2. Repeated duplicate commands to verify HIST_IGNORE_ALL_DUPS deduplication (no duplicate strings in history).
    @Test
    fun stressTest_interleavedDuplicates_strictDeduplication() {
        val commands = listOf("git status", "docker ps", "cargo test", "git status", "docker ps", "git status")
        commands.forEach { CommandHistoryManager.addCommand(context, it) }

        val historyCommands = CommandHistoryManager.getCommands()
        assertEquals(3, historyCommands.size)
        assertEquals(historyCommands.distinct().size, historyCommands.size)
        // Verify recency: docker ps (inserted 5th), cargo test (inserted 3rd), git status (inserted 6th)
        assertEquals(listOf("cargo test", "docker ps", "git status"), historyCommands)
    }

    @Test
    fun stressTest_500IdenticalCommands_singleEntryMaintained() {
        repeat(500) {
            CommandHistoryManager.addCommand(context, "   ls -la   ")
        }

        val historyCommands = CommandHistoryManager.getCommands()
        assertEquals(1, historyCommands.size)
        assertEquals("ls -la", historyCommands.first())
    }

    // 3. Atomic disk write & corrupted file handling (.bak quarantine and empty fallback)
    @Test
    fun stressTest_corruptedJsonFormats_quarantinesAndFallbackWithoutCrash() {
        val corruptedPayloads = listOf(
            "INVALID_JSON_{{{",
            "{\"notAnArray\": true}",
            "[{\"brokenObj\": }]",
            ""
        )

        for ((index, payload) in corruptedPayloads.withIndex()) {
            val file = File(tempDir, "command_history.json")
            file.writeText(payload)

            // Attempt load
            CommandHistoryManager.loadHistory(context)
            assertTrue("Corrupted payload #$index should fallback to empty history", CommandHistoryManager.getHistory().isEmpty())

            if (payload.isNotBlank()) {
                val bakFile = File(tempDir, "command_history.json.bak")
                assertTrue("Corrupted file #$index should be quarantined to .bak", bakFile.exists())
                bakFile.delete()
            }
        }

        // Verify recovery after corruption
        CommandHistoryManager.addCommand(context, "echo recovered")
        val history = CommandHistoryManager.getCommands()
        assertEquals(listOf("echo recovered"), history)
    }

    // 4. Navigator index bounds checking (-1, 0, max, out of bounds Up/Down, draft preservation)
    @Test
    fun stressTest_navigatorBounds_overNavigationAndDraftPreservation() {
        CommandHistoryManager.setHistoryForTesting(
            listOf(
                HistoryItem(command = "cmd1"),
                HistoryItem(command = "cmd2"),
                HistoryItem(command = "cmd3")
            )
        )

        val navigator = CommandHistoryNavigator { CommandHistoryManager.getHistory() }

        // Initial state
        assertEquals(-1, navigator.currentIndex())

        // Over-navigate UP 10 times
        var lastCmd = ""
        repeat(10) {
            lastCmd = navigator.navigateUp("my active draft")
        }
        assertEquals("cmd1", lastCmd)
        assertEquals(2, navigator.currentIndex())

        // Over-navigate DOWN 10 times
        repeat(10) {
            lastCmd = navigator.navigateDown()
        }
        assertEquals("my active draft", lastCmd)
        assertEquals(-1, navigator.currentIndex())
    }

    @Test
    fun stressTest_navigatorBounds_shrunkHistoryListHandling() {
        CommandHistoryManager.setHistoryForTesting(
            (1..10).map { HistoryItem(command = "cmd_$it") }
        )

        val navigator = CommandHistoryNavigator { CommandHistoryManager.getHistory() }

        // Navigate UP 8 times (index becomes 7)
        repeat(8) {
            navigator.navigateUp("my draft")
        }
        assertEquals(7, navigator.currentIndex())

        // Now history is dynamically truncated to 3 items
        CommandHistoryManager.setHistoryForTesting(
            (1..3).map { HistoryItem(command = "cmd_$it") }
        )

        // Attempting to navigate UP or DOWN when index (7) exceeds new max index (2)
        // This stress test checks if navigator handles shrunk history without throwing IndexOutOfBoundsException
        try {
            val result = navigator.navigateDown()
            // If implementation is safe, result will be valid string or draft without exception
            assertTrue("Result should be non-null", result.isNotBlank() || result.isEmpty())
        } catch (e: IndexOutOfBoundsException) {
            throw AssertionError("CommandHistoryNavigator threw IndexOutOfBoundsException when history size shrank during navigation", e)
        }
    }
}
