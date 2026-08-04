package dev.warp.mobile

import android.view.KeyEvent

/**
 * Decodes physical hardware key events and modifier combinations
 * into terminal byte sequences or application actions.
 */
object HardwareKeyDecoder {

    sealed class KeyDecodeResult {
        data class HandledBytes(val bytes: ByteArray) : KeyDecodeResult()
        object PerformPaste : KeyDecodeResult()
        object PerformCopy : KeyDecodeResult()
        object NotHandled : KeyDecodeResult()
    }

    fun decodeKeyEvent(event: KeyEvent, isSelectionActive: Boolean = false): KeyDecodeResult {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return KeyDecodeResult.NotHandled
        }

        val isCtrl = event.isCtrlPressed
        val isAlt = event.isAltPressed
        val isShift = event.isShiftPressed
        val isMeta = event.isMetaPressed

        // 1. Paste triggers: Shift+Insert, Ctrl+Shift+V, Cmd+V (Meta+V)
        if ((isShift && event.keyCode == KeyEvent.KEYCODE_INSERT) ||
            (isCtrl && isShift && event.keyCode == KeyEvent.KEYCODE_V) ||
            (isMeta && event.keyCode == KeyEvent.KEYCODE_V)
        ) {
            return KeyDecodeResult.PerformPaste
        }

        // 2. Ctrl combinations: Ctrl+A..Z mapped to (code & 0x1F)
        if (isCtrl && !isAlt && !isMeta) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_C -> {
                    return if (isSelectionActive) {
                        KeyDecodeResult.PerformCopy
                    } else {
                        KeyDecodeResult.HandledBytes(byteArrayOf(0x03)) // SIGINT
                    }
                }
                KeyEvent.KEYCODE_D -> return KeyDecodeResult.HandledBytes(byteArrayOf(0x04)) // EOF
                KeyEvent.KEYCODE_Z -> return KeyDecodeResult.HandledBytes(byteArrayOf(0x1A)) // SIGTSTP
                KeyEvent.KEYCODE_L -> return KeyDecodeResult.HandledBytes(byteArrayOf(0x0C)) // Form Feed / Clear
                KeyEvent.KEYCODE_A -> return KeyDecodeResult.HandledBytes(byteArrayOf(0x01)) // Home
                KeyEvent.KEYCODE_E -> return KeyDecodeResult.HandledBytes(byteArrayOf(0x05)) // End
                KeyEvent.KEYCODE_K -> return KeyDecodeResult.HandledBytes(byteArrayOf(0x0B)) // Kill Line
                KeyEvent.KEYCODE_R -> return KeyDecodeResult.HandledBytes(byteArrayOf(0x12)) // Reverse History Search
                KeyEvent.KEYCODE_W -> return KeyDecodeResult.HandledBytes(byteArrayOf(0x17)) // Delete Word
                else -> {
                    val unicode = event.unicodeChar
                    if (unicode != 0 && (unicode in 0x40..0x7F || unicode in 0x61..0x7A)) {
                        val ctrlByte = (unicode and 0x1F).toByte()
                        return KeyDecodeResult.HandledBytes(byteArrayOf(ctrlByte))
                    }
                }
            }
        }

        // 3. Alt combinations: Prepend ESC (0x1B)
        if (isAlt && !isCtrl && !isMeta) {
            val unicode = event.unicodeChar
            if (unicode != 0) {
                val charBytes = String(Character.toChars(unicode)).toByteArray(Charsets.UTF_8)
                return KeyDecodeResult.HandledBytes(byteArrayOf(0x1B) + charBytes)
            }
        }

        // 4. Special navigation & system control keys
        when (event.keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> return KeyDecodeResult.HandledBytes(byteArrayOf(0x1B))
            KeyEvent.KEYCODE_TAB -> {
                return if (isShift) {
                    KeyDecodeResult.HandledBytes(byteArrayOf(0x1B, '['.code.toByte(), 'Z'.code.toByte())) // Shift+Tab Back-Tab
                } else {
                    KeyDecodeResult.HandledBytes(byteArrayOf(0x09)) // Tab
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> return KeyDecodeResult.HandledBytes(byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte()))
            KeyEvent.KEYCODE_DPAD_DOWN -> return KeyDecodeResult.HandledBytes(byteArrayOf(0x1B, '['.code.toByte(), 'B'.code.toByte()))
            KeyEvent.KEYCODE_DPAD_RIGHT -> return KeyDecodeResult.HandledBytes(byteArrayOf(0x1B, '['.code.toByte(), 'C'.code.toByte()))
            KeyEvent.KEYCODE_DPAD_LEFT -> return KeyDecodeResult.HandledBytes(byteArrayOf(0x1B, '['.code.toByte(), 'D'.code.toByte()))
            KeyEvent.KEYCODE_MOVE_HOME -> return KeyDecodeResult.HandledBytes(byteArrayOf(0x1B, '['.code.toByte(), 'H'.code.toByte()))
            KeyEvent.KEYCODE_MOVE_END -> return KeyDecodeResult.HandledBytes(byteArrayOf(0x1B, '['.code.toByte(), 'F'.code.toByte()))
            KeyEvent.KEYCODE_PAGE_UP -> return KeyDecodeResult.HandledBytes(byteArrayOf(0x1B, '['.code.toByte(), '5'.code.toByte(), '~'.code.toByte()))
            KeyEvent.KEYCODE_PAGE_DOWN -> return KeyDecodeResult.HandledBytes(byteArrayOf(0x1B, '['.code.toByte(), '6'.code.toByte(), '~'.code.toByte()))
            KeyEvent.KEYCODE_FORWARD_DEL -> return KeyDecodeResult.HandledBytes(byteArrayOf(0x1B, '['.code.toByte(), '3'.code.toByte(), '~'.code.toByte()))
            KeyEvent.KEYCODE_DEL -> return KeyDecodeResult.HandledBytes(byteArrayOf(0x7F))
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> return KeyDecodeResult.HandledBytes(byteArrayOf('\n'.code.toByte()))
        }

        // 5. Hardware physical keyboard printable character fallback
        if (!isCtrl && !isAlt && !isMeta) {
            val unicode = event.unicodeChar
            if (unicode >= 0x20) {
                val utf8Bytes = String(Character.toChars(unicode)).toByteArray(Charsets.UTF_8)
                return KeyDecodeResult.HandledBytes(utf8Bytes)
            }
        }

        return KeyDecodeResult.NotHandled
    }
}
