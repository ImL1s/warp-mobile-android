package dev.warp.mobile.panes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchConfigTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testRoundTripSaveLoad() {
        val file = tempFolder.newFile("config.json")
        val config = LaunchConfiguration(
            panes = listOf(
                LaunchPane(cwd = "/tmp", envVars = mapOf("A" to "B"), startupCommand = "ls")
            )
        )
        
        LaunchConfigManager.save(config, file)
        
        val loaded = LaunchConfigManager.load(file)
        assertEquals(config, loaded)
    }

    @Test
    fun testMissingFileReturnsNull() {
        val file = File(tempFolder.root, "does_not_exist.json")
        val loaded = LaunchConfigManager.load(file)
        assertNull(loaded)
    }

    @Test
    fun testCorruptJsonReturnsNull() {
        val file = tempFolder.newFile("corrupt.json")
        file.writeText("{ invalid json ")
        
        val loaded = LaunchConfigManager.load(file)
        assertNull(loaded)
    }

    @Test
    fun testMultiplePanesWithEnvVars() {
        val file = tempFolder.newFile("multi_config.json")
        val config = LaunchConfiguration(
            panes = listOf(
                LaunchPane(cwd = "/tmp1", envVars = mapOf("K1" to "V1"), startupCommand = "cmd1"),
                LaunchPane(cwd = "/tmp2", envVars = mapOf("K2" to "V2"), startupCommand = "cmd2")
            )
        )
        
        LaunchConfigManager.save(config, file)
        
        val loaded = LaunchConfigManager.load(file)
        assertEquals(2, loaded?.panes?.size)
        assertEquals("/tmp1", loaded?.panes?.get(0)?.cwd)
        assertEquals("V2", loaded?.panes?.get(1)?.envVars?.get("K2"))
    }
    
    @Test
    fun testEmptyPanes() {
        val file = tempFolder.newFile("empty.json")
        val config = LaunchConfiguration(panes = emptyList())
        
        LaunchConfigManager.save(config, file)
        
        val loaded = LaunchConfigManager.load(file)
        assertEquals(0, loaded?.panes?.size)
    }
}
