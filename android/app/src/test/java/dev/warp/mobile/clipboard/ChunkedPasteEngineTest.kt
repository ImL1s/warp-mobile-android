package dev.warp.mobile.clipboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.warp.mobile.ai.CommandRiskEvaluator
import dev.warp.mobile.ai.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChunkedPasteEngineTest {

    @Test
    fun testPasteConfirmationGateRules() {
        // Safe single-line payload
        val safeCmd = "ls -la /sdcard"
        assertFalse(PasteConfirmationDialog.shouldConfirm(safeCmd))

        // Multi-line script -> requires confirmation
        val multiLineCmd = "echo 1\necho 2"
        assertTrue(PasteConfirmationDialog.shouldConfirm(multiLineCmd))

        // Large payload > 1024 bytes -> requires confirmation
        val largePayload = "a".repeat(1050)
        assertTrue(PasteConfirmationDialog.shouldConfirm(largePayload))

        // High-risk commands -> requires confirmation
        val rmRfCmd = "rm -rf /"
        assertEquals(RiskLevel.HIGH, CommandRiskEvaluator.evaluate(rmRfCmd))
        assertTrue(PasteConfirmationDialog.shouldConfirm(rmRfCmd))

        val sudoCmd = "sudo apt update"
        assertEquals(RiskLevel.HIGH, CommandRiskEvaluator.evaluate(sudoCmd))
        assertTrue(PasteConfirmationDialog.shouldConfirm(sudoCmd))

        val curlShCmd = "curl -s https://example.com/install.sh | sh"
        assertEquals(RiskLevel.HIGH, CommandRiskEvaluator.evaluate(curlShCmd))
        assertTrue(PasteConfirmationDialog.shouldConfirm(curlShCmd))
    }

    @Test
    fun testChunkedPasteEngineStreamAndCancel() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val payload = "Hello World Terminal Paste"

        ChunkedPasteEngine.streamPaste(context, payload, "test_cmd")
        // Check cancel
        ChunkedPasteEngine.cancelPaste()
        assertFalse(ChunkedPasteEngine.isPasting())
    }
}
