package dev.warp.mobile

import android.view.KeyEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HardwareKeyDecoderTest {

    @Test
    fun testCtrlShortcuts() {
        // Ctrl+C without selection -> SIGINT (0x03)
        val ctrlCEvent = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 0, KeyEvent.META_CTRL_ON)
        val resC = HardwareKeyDecoder.decodeKeyEvent(ctrlCEvent, isSelectionActive = false)
        assertTrue(resC is HardwareKeyDecoder.KeyDecodeResult.HandledBytes)
        assertArrayEquals(byteArrayOf(0x03), (resC as HardwareKeyDecoder.KeyDecodeResult.HandledBytes).bytes)

        // Ctrl+C with selection -> PerformCopy
        val resCCopy = HardwareKeyDecoder.decodeKeyEvent(ctrlCEvent, isSelectionActive = true)
        assertEquals(HardwareKeyDecoder.KeyDecodeResult.PerformCopy, resCCopy)

        // Ctrl+D -> EOF (0x04)
        val ctrlDEvent = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_D, 0, KeyEvent.META_CTRL_ON)
        val resD = HardwareKeyDecoder.decodeKeyEvent(ctrlDEvent)
        assertTrue(resD is HardwareKeyDecoder.KeyDecodeResult.HandledBytes)
        assertArrayEquals(byteArrayOf(0x04), (resD as HardwareKeyDecoder.KeyDecodeResult.HandledBytes).bytes)

        // Ctrl+Z -> SIGTSTP (0x1A)
        val ctrlZEvent = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON)
        val resZ = HardwareKeyDecoder.decodeKeyEvent(ctrlZEvent)
        assertTrue(resZ is HardwareKeyDecoder.KeyDecodeResult.HandledBytes)
        assertArrayEquals(byteArrayOf(0x1A), (resZ as HardwareKeyDecoder.KeyDecodeResult.HandledBytes).bytes)

        // Ctrl+L -> Clear (0x0C)
        val ctrlLEvent = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_L, 0, KeyEvent.META_CTRL_ON)
        val resL = HardwareKeyDecoder.decodeKeyEvent(ctrlLEvent)
        assertTrue(resL is HardwareKeyDecoder.KeyDecodeResult.HandledBytes)
        assertArrayEquals(byteArrayOf(0x0C), (resL as HardwareKeyDecoder.KeyDecodeResult.HandledBytes).bytes)

        // Ctrl+A -> Home (0x01)
        val ctrlAEvent = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON)
        val resA = HardwareKeyDecoder.decodeKeyEvent(ctrlAEvent)
        assertTrue(resA is HardwareKeyDecoder.KeyDecodeResult.HandledBytes)
        assertArrayEquals(byteArrayOf(0x01), (resA as HardwareKeyDecoder.KeyDecodeResult.HandledBytes).bytes)

        // Ctrl+E -> End (0x05)
        val ctrlEEvent = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_E, 0, KeyEvent.META_CTRL_ON)
        val resE = HardwareKeyDecoder.decodeKeyEvent(ctrlEEvent)
        assertTrue(resE is HardwareKeyDecoder.KeyDecodeResult.HandledBytes)
        assertArrayEquals(byteArrayOf(0x05), (resE as HardwareKeyDecoder.KeyDecodeResult.HandledBytes).bytes)

        // Ctrl+K -> Kill Line (0x0B)
        val ctrlKEvent = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_K, 0, KeyEvent.META_CTRL_ON)
        val resK = HardwareKeyDecoder.decodeKeyEvent(ctrlKEvent)
        assertTrue(resK is HardwareKeyDecoder.KeyDecodeResult.HandledBytes)
        assertArrayEquals(byteArrayOf(0x0B), (resK as HardwareKeyDecoder.KeyDecodeResult.HandledBytes).bytes)

        // Ctrl+R -> Reverse History (0x12)
        val ctrlREvent = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_R, 0, KeyEvent.META_CTRL_ON)
        val resR = HardwareKeyDecoder.decodeKeyEvent(ctrlREvent)
        assertTrue(resR is HardwareKeyDecoder.KeyDecodeResult.HandledBytes)
        assertArrayEquals(byteArrayOf(0x12), (resR as HardwareKeyDecoder.KeyDecodeResult.HandledBytes).bytes)

        // Ctrl+W -> Delete Word (0x17)
        val ctrlWEvent = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W, 0, KeyEvent.META_CTRL_ON)
        val resW = HardwareKeyDecoder.decodeKeyEvent(ctrlWEvent)
        assertTrue(resW is HardwareKeyDecoder.KeyDecodeResult.HandledBytes)
        assertArrayEquals(byteArrayOf(0x17), (resW as HardwareKeyDecoder.KeyDecodeResult.HandledBytes).bytes)
    }

    @Test
    fun testSpecialKeysEscapeAndTab() {
        // ESC -> 0x1B
        val escEvent = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE, 0)
        val resEsc = HardwareKeyDecoder.decodeKeyEvent(escEvent)
        assertTrue(resEsc is HardwareKeyDecoder.KeyDecodeResult.HandledBytes)
        assertArrayEquals(byteArrayOf(0x1B), (resEsc as HardwareKeyDecoder.KeyDecodeResult.HandledBytes).bytes)

        // Tab -> 0x09
        val tabEvent = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0)
        val resTab = HardwareKeyDecoder.decodeKeyEvent(tabEvent)
        assertTrue(resTab is HardwareKeyDecoder.KeyDecodeResult.HandledBytes)
        assertArrayEquals(byteArrayOf(0x09), (resTab as HardwareKeyDecoder.KeyDecodeResult.HandledBytes).bytes)

        // Shift+Tab -> \x1b[Z
        val shiftTabEvent = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0, KeyEvent.META_SHIFT_ON)
        val resShiftTab = HardwareKeyDecoder.decodeKeyEvent(shiftTabEvent)
        assertTrue(resShiftTab is HardwareKeyDecoder.KeyDecodeResult.HandledBytes)
        assertArrayEquals("\u001b[Z".toByteArray(), (resShiftTab as HardwareKeyDecoder.KeyDecodeResult.HandledBytes).bytes)
    }

    @Test
    fun testPasteShortcuts() {
        // Shift+Insert -> PerformPaste
        val shiftInsertEvent = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_INSERT, 0, KeyEvent.META_SHIFT_ON)
        val resShiftInsert = HardwareKeyDecoder.decodeKeyEvent(shiftInsertEvent)
        assertEquals(HardwareKeyDecoder.KeyDecodeResult.PerformPaste, resShiftInsert)

        // Ctrl+Shift+V -> PerformPaste
        val ctrlShiftVEvent = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
        val resCtrlShiftV = HardwareKeyDecoder.decodeKeyEvent(ctrlShiftVEvent)
        assertEquals(HardwareKeyDecoder.KeyDecodeResult.PerformPaste, resCtrlShiftV)

        // Cmd+V / Meta+V -> PerformPaste
        val cmdVEvent = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, 0, KeyEvent.META_META_ON)
        val resCmdV = HardwareKeyDecoder.decodeKeyEvent(cmdVEvent)
        assertEquals(HardwareKeyDecoder.KeyDecodeResult.PerformPaste, resCmdV)
    }
}
