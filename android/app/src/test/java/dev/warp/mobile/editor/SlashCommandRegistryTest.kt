package dev.warp.mobile.editor

import dev.warp.mobile.test.BaseWarpUnitTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandRegistryTest : BaseWarpUnitTest() {

    @Test
    fun testRegistry_allCommandsRegistered() {
        val commands = SlashCommandRegistry.getCommands()
        assertEquals(8, commands.size)
        assertTrue(commands.any { it.command == "/ai" })
        assertTrue(commands.any { it.command == "/clear" })
        assertTrue(commands.any { it.command == "/history" })
        assertTrue(commands.any { it.command == "/search" })
        assertTrue(commands.any { it.command == "/ssh" })
        assertTrue(commands.any { it.command == "/settings" })
        assertTrue(commands.any { it.command == "/split" })
        assertTrue(commands.any { it.command == "/help" })
    }

    @Test
    fun testRegistry_filterCommands() {
        val filteredAi = SlashCommandRegistry.filterCommands("/ai")
        assertEquals(1, filteredAi.size)
        assertEquals("/ai", filteredAi[0].command)

        val filteredHist = SlashCommandRegistry.filterCommands("hist")
        assertEquals(1, filteredHist.size)
        assertEquals("/history", filteredHist[0].command)

        val filteredClear = SlashCommandRegistry.filterCommands("/clear")
        assertEquals(1, filteredClear.size)
        assertEquals("/clear", filteredClear[0].command)

        val filteredEmpty = SlashCommandRegistry.filterCommands("/")
        assertEquals(8, filteredEmpty.size)
    }
}
