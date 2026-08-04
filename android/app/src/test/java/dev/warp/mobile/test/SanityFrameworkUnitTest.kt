package dev.warp.mobile.test

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SanityFrameworkUnitTest : BaseWarpUnitTest() {

    interface TestService {
        fun executeCommand(cmd: String): Int
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testFrameworkAndCoroutinesSanity() = runTest {
        val mockService = mockk<TestService>()
        every { mockService.executeCommand("ls") } returns 0

        val result = mockService.executeCommand("ls")
        assertEquals(0, result)
        verify(exactly = 1) { mockService.executeCommand("ls") }

        val session = WarpTestFixtures.createSessionHandle(id = "s-1", name = "main")
        WarpAssertHelpers.assertSessionStateValid(session)

        val blocks = listOf(WarpTestFixtures.createBlockCardState(blockId = "b1", command = "pwd"))
        WarpAssertHelpers.assertBlockTimelineEquals(blocks, blocks)

        WarpAssertHelpers.assertToolApprovalIntercepted("exec_command", approved = true, callbackResult = true)
        assertTrue("Sanity framework test passed", true)
    }
}
