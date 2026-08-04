package dev.warp.mobile.test

import dev.warp.mobile.ProcessState
import dev.warp.mobile.SessionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class RawModeConcurrencyEmpiricalTest : BaseWarpUnitTest() {

    private lateinit var sessionManager: SessionManager

    @Before
    override fun setUp() {
        super.setUp()
        sessionManager = SessionManager.createForTesting()
        sessionManager.resetForTesting()
    }

    @Test
    fun testHighThroughputConcurrentToggleRawModeConsistency() {
        val sm = SessionManager.createForTesting()
        val executor = Executors.newFixedThreadPool(8)
        val mismatchCount = AtomicInteger(0)

        val tasks = (1..8).map { threadId ->
            Runnable {
                for (i in 1..2000) {
                    val targetState = (i + threadId) % 2 == 0
                    sm.onToggleRawMode(targetState)

                    val isRawFlow = sm.isRawMode.value
                    val appStateRaw = sm.appState.value.isRawMode
                    if (isRawFlow != appStateRaw) {
                        mismatchCount.incrementAndGet()
                    }
                }
            }
        }

        tasks.forEach { executor.submit(it) }
        executor.shutdown()
        val finished = executor.awaitTermination(10, TimeUnit.SECONDS)
        assertTrue("Executor timed out", finished)

        val finalIsRawFlow = sm.isRawMode.value
        val finalAppStateRaw = sm.appState.value.isRawMode
        println("Empirical Stress Result: Mismatch count during concurrent toggling = ${mismatchCount.get()}")
        println("Final StateFlow values: isRawMode.value=$finalIsRawFlow, appState.value.isRawMode=$finalAppStateRaw")

        assertEquals(
            "StateFlow divergence detected: isRawMode.value ($finalIsRawFlow) != appState.value.isRawMode ($finalAppStateRaw). Mismatches: ${mismatchCount.get()}",
            finalIsRawFlow,
            finalAppStateRaw
        )
    }

    @Test
    fun testBackgroundSessionExit_resetsActiveSessionRawMode() = runTest {
        val sm = SessionManager.createForTesting()
        val sid1 = sm.createSession("Active Session 1")
        val sid2 = sm.createSession("Background Session 2")

        // Switch to session 1 and enable raw mode (e.g. htop running in sid1)
        sm.switchSession(sid1)
        sm.onToggleRawMode(true)
        assertTrue(sm.isRawMode.value)
        assertTrue(sm.appState.value.isRawMode)

        // Background session 2 exits
        sm.updateProcessState(sid2, ProcessState.EXITED, exitCode = 0)

        // EMPIRICAL TEST: Did background session 2 exit force sid1 out of raw mode?
        val activeRawModeAfterBgExit = sm.isRawMode.value
        println("Empirical Test: Active session raw mode after background session exit = $activeRawModeAfterBgExit")
        
        // Assert if background process exit incorrectly forces raw mode off
        assertFalse(
            "CRITICAL BUG: Background session process exit reset active session's raw mode to false!",
            activeRawModeAfterBgExit == false && sm.appState.value.activeSessionId == sid1
        )
    }
}
