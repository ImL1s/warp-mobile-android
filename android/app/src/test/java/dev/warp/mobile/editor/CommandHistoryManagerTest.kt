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

class CommandHistoryManagerTest : BaseWarpUnitTest() {

    private lateinit var context: Context
    private lateinit var tempDir: File

    @Before
    override fun setUp() {
        super.setUp()
        tempDir = File(System.getProperty("java.io.tmpdir"), "cmd_hist_test_${System.currentTimeMillis()}").also { it.mkdirs() }
        context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns tempDir
        CommandHistoryManager.clearHistory(context)
    }

    @Test
    fun testAddCommand_deduplicationAndEviction() {
        CommandHistoryManager.addCommand(context, "git status")
        CommandHistoryManager.addCommand(context, "docker ps")
        CommandHistoryManager.addCommand(context, "git status")

        val history = CommandHistoryManager.getCommands()
        assertEquals(2, history.size)
        assertEquals("docker ps", history[0])
        assertEquals("git status", history[1])
    }

    @Test
    fun testAddCommand_blankRejectionAndLengthTruncation() {
        CommandHistoryManager.addCommand(context, "   ")
        assertTrue(CommandHistoryManager.getHistory().isEmpty())

        val longCmd = "a".repeat(5000)
        CommandHistoryManager.addCommand(context, longCmd)
        val history = CommandHistoryManager.getCommands()
        assertEquals(1, history.size)
        assertEquals(4096, history[0].length)
    }

    @Test
    fun testPersistenceAndQuarantine() {
        CommandHistoryManager.addCommand(context, "cargo test")
        CommandHistoryManager.addCommand(context, "npm run build")

        // Reload history from disk
        CommandHistoryManager.setHistoryForTesting(emptyList())
        CommandHistoryManager.loadHistory(context)
        assertEquals(listOf("cargo test", "npm run build"), CommandHistoryManager.getCommands())

        // Corrupt file
        val file = File(tempDir, "command_history.json")
        file.writeText("invalid json string {[[}")

        CommandHistoryManager.loadHistory(context)
        assertTrue(CommandHistoryManager.getHistory().isEmpty())
        val bakFile = File(tempDir, "command_history.json.bak")
        assertTrue(bakFile.exists())
    }

    @Test
    fun testCommandHistoryNavigator_draftPreservationAndNavigation() {
        CommandHistoryManager.setHistoryForTesting(
            listOf(
                HistoryItem(command = "first"),
                HistoryItem(command = "second"),
                HistoryItem(command = "third")
            )
        )

        val navigator = CommandHistoryNavigator { CommandHistoryManager.getHistory() }
        assertEquals(-1, navigator.currentIndex())

        // Navigate Up: -1 -> 0 ("third")
        assertEquals("third", navigator.navigateUp("my draft"))
        assertEquals(0, navigator.currentIndex())

        // Navigate Up: 0 -> 1 ("second")
        assertEquals("second", navigator.navigateUp("third"))
        assertEquals(1, navigator.currentIndex())

        // Navigate Up: 1 -> 2 ("first")
        assertEquals("first", navigator.navigateUp("second"))
        assertEquals(2, navigator.currentIndex())

        // Boundary Up: stays at 2 ("first")
        assertEquals("first", navigator.navigateUp("first"))
        assertEquals(2, navigator.currentIndex())

        // Navigate Down: 2 -> 1 ("second")
        assertEquals("second", navigator.navigateDown())
        assertEquals(1, navigator.currentIndex())

        // Navigate Down: 1 -> 0 ("third")
        assertEquals("third", navigator.navigateDown())
        assertEquals(0, navigator.currentIndex())

        // Navigate Down: 0 -> -1 (restores draft)
        assertEquals("my draft", navigator.navigateDown())
        assertEquals(-1, navigator.currentIndex())
    }
}
