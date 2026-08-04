package dev.warp.mobile.clipboard

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.warp.mobile.WarpTerminalService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Asynchronous, coroutine-driven engine to stream large clipboard payloads (>1KB)
 * to the PTY in 4KB chunks using Dispatchers.IO with 2ms yield delays.
 */
object ChunkedPasteEngine {
    private const val TAG = "ChunkedPasteEngine"
    private const val CHUNK_SIZE_BYTES = 4096 // 4 KB
    private const val CHUNK_DELAY_MS = 2L     // 2 ms yield

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pasteJob: Job? = null
    private val pasting = AtomicBoolean(false)

    fun isPasting(): Boolean = pasting.get()

    fun cancelPaste() {
        if (pasting.compareAndSet(true, false)) {
            pasteJob?.cancel()
            pasteJob = null
            Log.i(TAG, "Clipboard paste cancelled by user (ESC / cancel)")
        }
    }

    fun streamPaste(context: Context, text: String, cmdId: String, onComplete: (() -> Unit)? = null) {
        cancelPaste() // Cancel any ongoing paste job first

        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) {
            onComplete?.invoke()
            return
        }

        pasting.set(true)
        pasteJob = scope.launch {
            try {
                var offset = 0
                while (isActive && offset < bytes.size && pasting.get()) {
                    val length = minOf(CHUNK_SIZE_BYTES, bytes.size - offset)
                    val chunk = bytes.copyOfRange(offset, offset + length)

                    val intent = Intent(WarpTerminalService.ACTION_WRITE).apply {
                        component = android.content.ComponentName(
                            context.packageName,
                            "${context.packageName}.PtyBroadcastReceiver"
                        )
                        putExtra("cmd_id", cmdId)
                        putExtra("data", chunk)
                    }
                    context.sendBroadcast(intent)

                    offset += length
                    if (offset < bytes.size) {
                        delay(CHUNK_DELAY_MS)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Chunked paste streaming error: ${t.message}")
            } finally {
                pasting.set(false)
                pasteJob = null
                onComplete?.invoke()
            }
        }
    }
}
