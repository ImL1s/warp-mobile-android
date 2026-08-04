package dev.warp.mobile.test

import dev.warp.mobile.ProcessState
import dev.warp.mobile.SessionManager
import dev.warp.mobile.SessionTab
import dev.warp.mobile.WarpAppState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionManagerTest : BaseWarpUnitTest() {

    private lateinit var sessionManager: SessionManager

    @Before
    override fun setUp() {
        super.setUp()
        sessionManager = SessionManager.createForTesting()
        sessionManager.resetForTesting()
    }

    @Test
    fun testInitialState_isEmpty() {
        val state = sessionManager.appState.value
        assertEquals(0, state.tabs.size)
        assertEquals(0, state.tabCount)
        assertNull(state.activeSessionId)
        assertNull(state.activeTab)
    }

    @Test
    fun testCreateSession_addsTabAndSetsActive() = runTest {
        val id1 = sessionManager.createSession(title = "Tab 1", cwd = "/home/warp")
        val state = sessionManager.appState.value

        assertEquals(1, state.tabs.size)
        assertEquals(id1, state.activeSessionId)
        assertNotNull(state.activeTab)
        assertEquals("Tab 1", state.activeTab?.title)
        assertEquals("/home/warp", state.activeTab?.cwd)
        assertEquals(ProcessState.INITIALIZING, state.activeTab?.processState)
    }

    @Test
    fun testSwitchSession_updatesActiveSession() = runTest {
        val id1 = sessionManager.createSession(title = "Tab 1")
        val id2 = sessionManager.createSession(title = "Tab 2")

        var state = sessionManager.appState.value
        assertEquals(2, state.tabs.size)
        assertEquals(id2, state.activeSessionId)

        val switched = sessionManager.switchSession(id1)
        assertTrue(switched)

        state = sessionManager.appState.value
        assertEquals(id1, state.activeSessionId)
        assertEquals("Tab 1", state.activeTab?.title)
    }

    @Test
    fun testCloseSession_removesTabAndSelectsFallback() = runTest {
        val id1 = sessionManager.createSession(title = "Tab 1")
        val id2 = sessionManager.createSession(title = "Tab 2")
        val id3 = sessionManager.createSession(title = "Tab 3")

        assertEquals(3, sessionManager.appState.value.tabs.size)
        assertEquals(id3, sessionManager.appState.value.activeSessionId)

        // Closing active tab id3 fallback to id2
        sessionManager.closeSession(id3)
        var state = sessionManager.appState.value
        assertEquals(2, state.tabs.size)
        assertEquals(id2, state.activeSessionId)

        // Closing non-active tab id1 keeps id2 active
        sessionManager.closeSession(id1)
        state = sessionManager.appState.value
        assertEquals(1, state.tabs.size)
        assertEquals(id2, state.activeSessionId)
    }

    @Test
    fun testUpdateProcessStateAndCwd_updatesTabMetadata() = runTest {
        val id = sessionManager.createSession(title = "Build")
        sessionManager.updateProcessState(id, ProcessState.RUNNING)
        sessionManager.updateCwd(id, "/src/warp")

        val tab = sessionManager.appState.value.activeTab
        assertNotNull(tab)
        assertEquals(ProcessState.RUNNING, tab?.processState)
        assertEquals("/src/warp", tab?.cwd)

        sessionManager.updateProcessState(id, ProcessState.EXITED, exitCode = 0)
        val exitedTab = sessionManager.appState.value.activeTab
        assertEquals(ProcessState.EXITED, exitedTab?.processState)
        assertEquals(0, exitedTab?.exitCode)
    }

    @Test
    fun testSessionPersistence_savesAndRestoresState() = runTest {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        context.filesDir.mkdirs()
        SessionManager.createForTesting(context).resetForTesting()

        val activeManager = SessionManager.createForTesting(context)
        activeManager.appState.value.tabs.toList().forEach { activeManager.closeSession(it.id) }

        val cwdDir = java.io.File(context.filesDir, "test_dir").apply { mkdirs() }
        val id1 = activeManager.createSession(title = "Backend", cwd = cwdDir.absolutePath)
        val id2 = activeManager.createSession(title = "Frontend", cwd = cwdDir.absolutePath)
        activeManager.switchSession(id1)

        val saved = activeManager.saveSessionState()
        assertTrue(saved)

        val sessionsFile = dev.warp.mobile.SessionPersistenceManager.getSessionFile(context)
        assertTrue(sessionsFile.exists())
        assertTrue(sessionsFile.length() > 0)

        val jsonOnDisk = dev.warp.mobile.SessionPersistenceManager.loadSessionState(context)
        assertNotNull(jsonOnDisk)
        assertTrue("Expected Backend in $jsonOnDisk", jsonOnDisk!!.contains("Backend"))
        assertTrue("Expected Frontend in $jsonOnDisk", jsonOnDisk.contains("Frontend"))

        val restoredManager = SessionManager.createForTesting(context)
        val restoredState = restoredManager.appState.value
        assertEquals(2, restoredState.tabs.size)
        assertEquals(id1, restoredState.activeSessionId)
        assertEquals("Backend", restoredState.activeTab?.title)
    }

    @Test
    fun testSessionPersistence_corruptedJsonFallback() = runTest {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        SessionManager.createForTesting(context).resetForTesting()

        val sessionsFile = dev.warp.mobile.SessionPersistenceManager.getSessionFile(context)
        sessionsFile.writeText("{ corrupted_json: [ invalid }")

        val manager = SessionManager.createForTesting(context)

        val bakFile = java.io.File(context.filesDir, "sessions.json.bak")
        assertTrue(bakFile.exists())

        // Verify graceful fallback to clean default session
        val state = manager.appState.value
        assertEquals(1, state.tabs.size)
        assertEquals("Terminal 1", state.activeTab?.title)
    }

    @Test
    fun testSessionPersistence_repeatedSaveRestoreCyclesAndActiveSwitch() = runTest {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        context.filesDir.mkdirs()
        SessionManager.createForTesting(context).resetForTesting()

        val manager = SessionManager.createForTesting(context)
        manager.appState.value.tabs.toList().forEach { manager.closeSession(it.id) }

        val dir1 = java.io.File(context.filesDir, "work_dir_1").apply { mkdirs() }
        val dir2 = java.io.File(context.filesDir, "work_dir_2").apply { mkdirs() }

        val id1 = manager.createSession(title = "Tab 1", cwd = dir1.absolutePath, program = "/system/bin/zsh", env = mapOf("K1" to "V1"))
        val id2 = manager.createSession(title = "Tab 2", cwd = dir2.absolutePath, program = "/system/bin/sh", env = mapOf("K2" to "V2"))

        var currentManager = manager
        currentManager.switchSession(id2)

        for (cycle in 0 until 10) {
            val saved = currentManager.saveSessionState()
            assertTrue(saved)

            val nextManager = SessionManager.createForTesting(context)
            val restoredState = nextManager.appState.value

            assertEquals(2, restoredState.tabs.size)
            val expectedActive = if (cycle % 2 == 0) id2 else id1
            assertEquals("Cycle $cycle active mismatch", expectedActive, restoredState.activeSessionId)

            val tab1 = restoredState.tabs.first { it.id == id1 }
            assertEquals("Tab 1", tab1.title)
            assertEquals(dir1.absolutePath, tab1.cwd)
            assertEquals("/system/bin/zsh", tab1.program)
            assertEquals("V1", tab1.env["K1"])

            val tab2 = restoredState.tabs.first { it.id == id2 }
            assertEquals("Tab 2", tab2.title)
            assertEquals(dir2.absolutePath, tab2.cwd)
            assertEquals("/system/bin/sh", tab2.program)
            assertEquals("V2", tab2.env["K2"])

            // Switch active session for next cycle
            val nextActive = if (cycle % 2 == 0) id1 else id2
            nextManager.switchSession(nextActive)
            currentManager = nextManager
        }
    }
}


