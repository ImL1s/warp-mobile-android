package dev.warp.mobile.test

import dev.warp.mobile.ProcessState
import dev.warp.mobile.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerStressTest : BaseWarpUnitTest() {

    private lateinit var sessionManager: SessionManager

    @Before
    override fun setUp() {
        super.setUp()
        sessionManager = SessionManager.createForTesting()
        sessionManager.resetForTesting()
    }

    @Test
    fun testRapidTabCreation_100Tabs() = runTest {
        val createdIds = mutableListOf<String>()
        for (i in 1..100) {
            val id = sessionManager.createSession(title = "Tab $i", cwd = "/dir/$i")
            createdIds.add(id)
        }

        val state = sessionManager.appState.value
        assertEquals(100, state.tabs.size)
        assertEquals(100, state.tabCount)
        assertEquals(createdIds.last(), state.activeSessionId)
        assertEquals("Tab 100", state.activeTab?.title)

        // Close 50 tabs
        for (i in 0 until 50) {
            val closeId = createdIds[i]
            val closed = sessionManager.closeSession(closeId)
            assertTrue(closed)
        }

        val remainingState = sessionManager.appState.value
        assertEquals(50, remainingState.tabs.size)
        assertEquals(createdIds.last(), remainingState.activeSessionId)
    }

    @Test
    fun testConcurrentSessionStateUpdates() = runTest {
        val ids = (1..10).map { sessionManager.createSession(title = "Concurrent Tab $it") }

        // Multi-threaded concurrent status & Cwd updates
        val executor = Executors.newFixedThreadPool(10)
        val tasks = ids.mapIndexed { index, id ->
            Runnable {
                for (step in 1..100) {
                    val state = if (step % 2 == 0) ProcessState.RUNNING else ProcessState.INITIALIZING
                    sessionManager.updateProcessState(id, state)
                    sessionManager.updateCwd(id, "/cwd/tab-$index/step-$step")
                }
                sessionManager.updateProcessState(id, ProcessState.EXITED, exitCode = index)
            }
        }

        tasks.forEach { executor.submit(it) }
        executor.shutdown()
        val finished = executor.awaitTermination(10, TimeUnit.SECONDS)
        assertTrue("Executor timed out", finished)

        val finalState = sessionManager.appState.value
        assertEquals(10, finalState.tabs.size)

        ids.forEachIndexed { index, id ->
            val tab = finalState.tabs.find { it.id == id }
            assertNotNull(tab)
            assertEquals(ProcessState.EXITED, tab?.processState)
            assertEquals(index, tab?.exitCode)
            assertEquals("/cwd/tab-$index/step-100", tab?.cwd)
        }
    }

    @Test
    fun testRapidSwitchingAndClosing_edgeCases() = runTest {
        val id1 = sessionManager.createSession(title = "First")
        val id2 = sessionManager.createSession(title = "Second")
        val id3 = sessionManager.createSession(title = "Third")

        // Switch back and forth rapidly
        for (i in 1..50) {
            val target = if (i % 2 == 0) id1 else id2
            assertTrue(sessionManager.switchSession(target))
            assertEquals(target, sessionManager.appState.value.activeSessionId)
        }

        // Close non-existent tab returns false
        val invalidClose = sessionManager.closeSession("non-existent-id")
        assertEquals(false, invalidClose)

        // Switch to non-existent tab returns false
        val invalidSwitch = sessionManager.switchSession("non-existent-id")
        assertEquals(false, invalidSwitch)

        // Close active tab id2 (active was set in loop)
        sessionManager.switchSession(id2)
        sessionManager.closeSession(id2)

        // Active tab should fallback to remaining (id3 or id1)
        val remainingActive = sessionManager.appState.value.activeSessionId
        assertTrue(remainingActive == id1 || remainingActive == id3)

        // Close remaining tabs
        sessionManager.closeSession(id1)
        sessionManager.closeSession(id3)

        val emptyState = sessionManager.appState.value
        assertEquals(0, emptyState.tabs.size)
        assertNull(emptyState.activeSessionId)
        assertNull(emptyState.activeTab)
    }
}
