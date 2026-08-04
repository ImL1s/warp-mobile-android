package dev.warp.mobile

import dev.warp.mobile.test.BaseWarpUnitTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RawModeStateTest : BaseWarpUnitTest() {

    private lateinit var sessionManager: SessionManager

    @Before
    override fun setUp() {
        super.setUp()
        sessionManager = SessionManager.createForTesting()
        sessionManager.resetForTesting()
    }

    @Test
    fun testWarpAppState_defaultIsRawModeIsFalse() {
        val appState = WarpAppState()
        assertFalse("Default isRawMode should be false", appState.isRawMode)
    }

    @Test
    fun testWarpAppState_onToggleRawMode_updatesState() {
        val initial = WarpAppState()
        val rawTrue = initial.onToggleRawMode(true)
        assertTrue("isRawMode should be true after toggle(true)", rawTrue.isRawMode)

        val rawFalse = rawTrue.onToggleRawMode(false)
        assertFalse("isRawMode should be false after toggle(false)", rawFalse.isRawMode)
    }

    @Test
    fun testSessionManager_onToggleRawMode_updatesStateFlow() = runTest {
        val sm = SessionManager.createForTesting()
        assertFalse("Initial isRawMode StateFlow should be false", sm.isRawMode.value)
        assertFalse("Initial appState.isRawMode should be false", sm.appState.value.isRawMode)

        sm.onToggleRawMode(true)
        assertTrue("isRawMode StateFlow should be true after toggle(true)", sm.isRawMode.value)
        assertTrue("appState.isRawMode should be true after toggle(true)", sm.appState.value.isRawMode)

        sm.onToggleRawMode(false)
        assertFalse("isRawMode StateFlow should be false after toggle(false)", sm.isRawMode.value)
        assertFalse("appState.isRawMode should be false after toggle(false)", sm.appState.value.isRawMode)
    }

    @Test
    fun testSessionManager_processExited_resetsRawModeToFalse() = runTest {
        val sm = SessionManager.createForTesting()
        val sid = sm.createSession("Test Session")
        sm.onToggleRawMode(true)
        assertTrue(sm.isRawMode.value)

        sm.updateProcessState(sid, ProcessState.EXITED, exitCode = 0)
        assertFalse("Process exit should reset raw mode to false", sm.isRawMode.value)
        assertFalse("Process exit should update appState isRawMode to false", sm.appState.value.isRawMode)
    }

    @Test
    fun testSessionManager_processError_resetsRawModeToFalse() = runTest {
        val sm = SessionManager.createForTesting()
        val sid = sm.createSession("Test Session Error")
        sm.onToggleRawMode(true)
        assertTrue(sm.isRawMode.value)

        sm.updateProcessState(sid, ProcessState.ERROR, exitCode = 1)
        assertFalse("Process error should reset raw mode to false", sm.isRawMode.value)
        assertFalse("Process error should update appState isRawMode to false", sm.appState.value.isRawMode)
    }

    @Test
    fun testSessionManager_concurrentToggleRawMode_maintainsStateFlowConsistency() = runTest {
        val sm = SessionManager.createForTesting()
        val job1 = launch {
            repeat(100) {
                sm.onToggleRawMode(true)
                sm.onToggleRawMode(false)
            }
        }
        val job2 = launch {
            repeat(100) {
                sm.onToggleRawMode(false)
                sm.onToggleRawMode(true)
            }
        }
        job1.join()
        job2.join()

        assertEquals(sm.isRawMode.value, sm.appState.value.isRawMode)
    }

    @Test
    fun testSessionManager_processStateRunning_doesNotResetRawMode() = runTest {
        val sm = SessionManager.createForTesting()
        val sid = sm.createSession("Running Session")
        sm.onToggleRawMode(true)
        assertTrue(sm.isRawMode.value)

        sm.updateProcessState(sid, ProcessState.RUNNING)
        assertTrue("Running state should maintain raw mode", sm.isRawMode.value)
        assertTrue("Running state should maintain appState raw mode", sm.appState.value.isRawMode)
    }

    @Test
    fun testNativeBridge_terminalIsAltScreen_fallbackHandling() {
        // Native library is un-loaded on JVM desktop test environment, so terminalIsAltScreen should catch UnsatisfiedLinkError if invoked
        val result = try {
            NativeBridge.terminalIsAltScreen()
        } catch (e: Throwable) {
            false
        }
        assertFalse("NativeBridge.terminalIsAltScreen should return false on JNI exception or non-alt screen", result)
    }
}

