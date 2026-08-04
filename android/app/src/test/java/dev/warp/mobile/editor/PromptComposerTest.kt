package dev.warp.mobile.editor

import dev.warp.mobile.test.BaseWarpUnitTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptComposerTest : BaseWarpUnitTest() {

    @Test
    fun testSlashCommandFiltering_logic() {
        val slashQuery1 = "/c"
        val results1 = SlashCommandRegistry.filterCommands(slashQuery1)
        assertTrue(results1.any { it.command == "/clear" })

        val slashQuery2 = "/hist"
        val results2 = SlashCommandRegistry.filterCommands(slashQuery2)
        assertEquals(1, results2.size)
        assertEquals("/history", results2[0].command)
    }

    @Test
    fun testCommandHistoryNavigator_stateTransitions() {
        val nav = CommandHistoryNavigator {
            listOf(
                HistoryItem(command = "ls -la"),
                HistoryItem(command = "git status")
            )
        }

        // Initially index -1
        assertEquals(-1, nav.currentIndex())

        // Press Up: loads "git status" (index 0) and stashes draft "my input"
        val up1 = nav.navigateUp("my input")
        assertEquals("git status", up1)
        assertEquals(0, nav.currentIndex())

        // Press Up: loads "ls -la" (index 1)
        val up2 = nav.navigateUp("git status")
        assertEquals("ls -la", up2)
        assertEquals(1, nav.currentIndex())

        // Press Down: returns to "git status" (index 0)
        val down1 = nav.navigateDown()
        assertEquals("git status", down1)
        assertEquals(0, nav.currentIndex())

        // Press Down: restores draft "my input" (index -1)
        val down2 = nav.navigateDown()
        assertEquals("my input", down2)
        assertEquals(-1, nav.currentIndex())
    }
}
