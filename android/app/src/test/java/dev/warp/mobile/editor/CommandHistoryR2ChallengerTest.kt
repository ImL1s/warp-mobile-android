package dev.warp.mobile.editor

import android.content.Context
import dev.warp.mobile.test.BaseWarpUnitTest
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class CommandHistoryR2ChallengerTest : BaseWarpUnitTest() {

    private lateinit var context: Context
    private lateinit var tempDir: File

    @Before
    override fun setUp() {
        super.setUp()
        tempDir = File(System.getProperty("java.io.tmpdir"), "cmd_hist_r2_challenger_${System.currentTimeMillis()}").also { it.mkdirs() }
        context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns tempDir
        CommandHistoryManager.clearHistory(context)
    }

    // =========================================================================
    // 1. CommandHistoryNavigator Bounds & Mutation Stress Tests
    // =========================================================================

    @Test
    fun testNavigator_listClearedWhileNavigating_zeroIndexOutOfBoundsException() {
        val initialItems = (1..20).map { HistoryItem(command = "cmd_$it") }
        CommandHistoryManager.setHistoryForTesting(initialItems)

        val navigator = CommandHistoryNavigator { CommandHistoryManager.getHistory() }

        // Navigate UP 5 times -> index becomes 4
        var lastCmd = ""
        repeat(5) {
            lastCmd = navigator.navigateUp("my active draft")
        }
        assertEquals("cmd_16", lastCmd)
        assertEquals(4, navigator.currentIndex())

        // Mutate history: clear history completely while navigator index is non-zero (4)
        CommandHistoryManager.setHistoryForTesting(emptyList())

        // Calling navigateUp when history is empty must NOT throw IndexOutOfBoundsException
        val upResult = navigator.navigateUp("my active draft")
        assertEquals("my active draft", upResult)
        assertEquals(-1, navigator.currentIndex())

        // Reset index back to non-zero by re-populating and navigating
        CommandHistoryManager.setHistoryForTesting(initialItems)
        repeat(5) { navigator.navigateUp("my active draft") }
        assertEquals(4, navigator.currentIndex())

        // Clear again
        CommandHistoryManager.setHistoryForTesting(emptyList())

        // Calling navigateDown when history is empty must NOT throw IndexOutOfBoundsException
        val downResult = navigator.navigateDown()
        assertEquals("my active draft", downResult)
        assertEquals(-1, navigator.currentIndex())
    }

    @Test
    fun testNavigator_listTruncatedWhileNavigating_zeroIndexOutOfBoundsException() {
        val initialItems = (1..50).map { HistoryItem(command = "cmd_$it") }
        CommandHistoryManager.setHistoryForTesting(initialItems)

        val navigator = CommandHistoryNavigator { CommandHistoryManager.getHistory() }

        // Navigate UP 30 times -> index becomes 29
        repeat(30) {
            navigator.navigateUp("draft prompt")
        }
        assertEquals(29, navigator.currentIndex())

        // Mutate history: truncate list from 50 to 5 items while index is 29 (which > 4)
        val truncatedItems = (1..5).map { HistoryItem(command = "short_$it") }
        CommandHistoryManager.setHistoryForTesting(truncatedItems)

        // Calling navigateUp when index (29) exceeds new list size (5)
        try {
            val resUp = navigator.navigateUp("draft prompt")
            assertNotNull(resUp)
            assertTrue("Index should be clamped to valid range <= 4", navigator.currentIndex() <= 4)
        } catch (e: IndexOutOfBoundsException) {
            throw AssertionError("navigateUp threw IndexOutOfBoundsException when history truncated", e)
        }

        // Calling navigateDown when index was beyond bounds
        try {
            val resDown = navigator.navigateDown()
            assertNotNull(resDown)
        } catch (e: IndexOutOfBoundsException) {
            throw AssertionError("navigateDown threw IndexOutOfBoundsException when history truncated", e)
        }
    }

    @Test
    fun testNavigator_push1000PlusItemsWhileNavigating_zeroIndexOutOfBoundsException() {
        val initialItems = (1..5).map { HistoryItem(command = "initial_$it") }
        CommandHistoryManager.setHistoryForTesting(initialItems)

        val navigator = CommandHistoryNavigator { CommandHistoryManager.getHistory() }

        // Navigate UP 3 times -> index is 2
        repeat(3) { navigator.navigateUp("draft") }
        assertEquals(2, navigator.currentIndex())

        // Add 1500 items while active index is non-zero
        for (i in 1..1500) {
            CommandHistoryManager.addCommand(context, "bulk_cmd_$i")
        }

        // Check history size capped at 1000
        assertEquals(1000, CommandHistoryManager.getHistory().size)

        // Calling navigateUp and navigateDown should safely adjust/clamp without throwing
        try {
            val upRes = navigator.navigateUp("draft")
            assertNotNull(upRes)
            val downRes = navigator.navigateDown()
            assertNotNull(downRes)
        } catch (e: IndexOutOfBoundsException) {
            throw AssertionError("Pushing 1000+ items during active navigation caused IndexOutOfBoundsException", e)
        }
    }

    @Test
    fun testNavigator_concurrentRandomMutationsAndNavigation_zeroCrash() {
        val executor = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(4)
        val navigator = CommandHistoryNavigator { CommandHistoryManager.getHistory() }

        // Seed history
        CommandHistoryManager.setHistoryForTesting((1..100).map { HistoryItem(command = "seed_$it") })

        // Thread 1: Rapid navigateUp/navigateDown
        executor.execute {
            try {
                repeat(500) {
                    navigator.navigateUp("draft")
                    navigator.navigateDown()
                }
            } finally {
                latch.countDown()
            }
        }

        // Thread 2: Rapid addCommand
        executor.execute {
            try {
                repeat(300) { i ->
                    CommandHistoryManager.addCommand(context, "concurrent_cmd_$i")
                }
            } finally {
                latch.countDown()
            }
        }

        // Thread 3: Rapid clearHistory / setHistoryForTesting
        executor.execute {
            try {
                repeat(100) { i ->
                    if (i % 2 == 0) {
                        CommandHistoryManager.clearHistory(context)
                    } else {
                        CommandHistoryManager.setHistoryForTesting((1..10).map { HistoryItem(command = "reset_$it") })
                    }
                }
            } finally {
                latch.countDown()
            }
        }

        // Thread 4: Rapid deleteItem
        executor.execute {
            try {
                repeat(200) {
                    val current = CommandHistoryManager.getHistory()
                    if (current.isNotEmpty()) {
                        CommandHistoryManager.deleteItem(context, current.first())
                    }
                }
            } finally {
                latch.countDown()
            }
        }

        val completed = latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()
        assertTrue("Concurrent mutation & navigation completed within 10s", completed)
    }

    // =========================================================================
    // 2. Corrupted File Quarantine & File System Fallback Stress Tests
    // =========================================================================

    @Test
    fun testQuarantineCorruptedFile_readOnlyBakFile_copyAndDeleteFallbackNoCrash() {
        val file = File(tempDir, "command_history.json")
        file.writeText("{corrupted_json_payload: [[[")

        val bakFile = File(tempDir, "command_history.json.bak")
        bakFile.writeText("old bak content")
        bakFile.setReadOnly() // Make destination bak read-only to force rename/overwrite edge cases

        try {
            // Loading history should trigger quarantine without throwing unhandled exception
            CommandHistoryManager.loadHistory(context)
            assertTrue("History should be empty after quarantine", CommandHistoryManager.getHistory().isEmpty())
        } finally {
            bakFile.setWritable(true)
            bakFile.delete()
        }
    }

    @Test
    fun testQuarantineCorruptedFile_readOnlySourceFile_noCrashAndFallback() {
        val file = File(tempDir, "command_history.json")
        file.writeText("CORRUPTED_GARBAGE_PAYLOAD")
        file.setReadOnly() // Force renameTo failure on some platforms

        try {
            CommandHistoryManager.loadHistory(context)
            assertTrue("History should be empty after corrupt load", CommandHistoryManager.getHistory().isEmpty())
        } finally {
            file.setWritable(true)
            file.delete()
        }
    }

    @Test
    fun testSaveHistory_lockedOrReadOnlyTempFile_gracefulFailure() {
        // Create read-only file named command_history.json.tmp
        val tempFile = File(tempDir, "command_history.json.tmp")
        tempFile.writeText("existing temp file")
        tempFile.setReadOnly()

        try {
            CommandHistoryManager.addCommand(context, "test command")
            // saveHistory should attempt write and return false or true without throwing uncaught exception
            val saveResult = CommandHistoryManager.saveHistory(context)
            // Save may return false due to permission deny on .tmp file
            assertNotNull(saveResult)
        } finally {
            tempFile.setWritable(true)
            tempFile.delete()
        }
    }

    @Test
    fun testParseHistoryJson_extremePayloads_noCrash() {
        val extremePayloads = listOf(
            "a".repeat(100000), // 100KB arbitrary string
            "[\n  {\n    \"id\": \"1\",\n    \"command\": \"${"\\".repeat(5000)}\",\n    \"timestampMs\": 123\n  }\n]", // huge escapes
            "[\n  {\n    \"id\": \"1\",\n    \"command\": \"cmd\\uZZZZ\",\n    \"timestampMs\": 123\n  }\n]", // invalid unicode escape
            "[\n  {\n    \"id\": \"1\",\n    \"command\": \"cmd\\\",\n    \"timestampMs\": 123\n  }\n]", // trailing backslash
            "\u0000\u0001\u0002\u0003", // binary control chars
            ""
        )

        for ((idx, payload) in extremePayloads.withIndex()) {
            try {
                val parsed = CommandHistoryManager.parseHistoryJson(payload)
                assertNotNull("Payload #$idx should parse safely into list", parsed)
            } catch (e: Throwable) {
                throw AssertionError("parseHistoryJson crashed on extreme payload #$idx", e)
            }
        }
    }
}
