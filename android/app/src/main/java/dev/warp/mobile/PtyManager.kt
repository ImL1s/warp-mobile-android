package dev.warp.mobile

import android.util.Log

class PtyManager {

    private val sessions = mutableMapOf<String, Long>()

    @Synchronized
    fun spawn(cmdId: String, program: String, args: Array<String>, env: Map<String, String>): Boolean {
        // Kill any existing session with same cmdId before replacing (Fix #2: no orphan)
        sessions[cmdId]?.let { oldPtr ->
            NativeBridge.ptyKill(oldPtr)
            Log.i(LOG_TAG, "spawn: killed existing session cmdId=$cmdId ptr=$oldPtr")
        }
        val envFlat = env.map { (k, v) -> "$k=$v" }.toTypedArray()
        val ptr = NativeBridge.ptySpawn(program, args, envFlat)
        return if (ptr != 0L) {
            sessions[cmdId] = ptr
            Log.i(LOG_TAG, "spawn ok cmdId=$cmdId ptr=$ptr")
            true
        } else {
            sessions.remove(cmdId)
            Log.e(LOG_TAG, "spawn failed cmdId=$cmdId")
            false
        }
    }

    @Synchronized
    fun write(cmdId: String, data: ByteArray): Int {
        val ptr = sessions[cmdId] ?: return -1
        return NativeBridge.ptyWrite(ptr, data)
    }

    // readDirect is NOT @Synchronized — blocking libc::read must not hold the
    // class monitor. We increment Arc refcount (ptyAcquire) while holding the
    // lock, then release the lock before calling ptyRead. ptyRelease in finally
    // decrements the Arc so the session can be freed after kill.
    fun readDirect(cmdId: String, maxBytes: Int): ByteArray? {
        val ptr = synchronized(this) {
            val p = sessions[cmdId] ?: return null
            NativeBridge.ptyAcquire(p)
            p
        }
        return try {
            NativeBridge.ptyRead(ptr, maxBytes)
        } finally {
            NativeBridge.ptyRelease(ptr)
        }
    }

    @Synchronized
    fun resize(cmdId: String, rows: Short, cols: Short): Int {
        val ptr = sessions[cmdId] ?: return -1
        return NativeBridge.ptyResize(ptr, rows, cols)
    }

    @Synchronized
    fun kill(cmdId: String) {
        val ptr = sessions.remove(cmdId) ?: return
        NativeBridge.ptyKill(ptr)
        Log.i(LOG_TAG, "kill ok cmdId=$cmdId")
    }

    @Synchronized
    fun killAll() {
        for ((cmdId, ptr) in sessions) {
            NativeBridge.ptyKill(ptr)
            Log.i(LOG_TAG, "killAll: killed cmdId=$cmdId")
        }
        sessions.clear()
    }

    fun activeCount(): Int {
        return try {
            NativeBridge.activePtyCount()
        } catch (e: Throwable) {
            synchronized(this) { sessions.size }
        }
    }

    fun getExitStatus(cmdId: String): Int? {
        val ptr = synchronized(this) { sessions[cmdId] } ?: return null
        val status = NativeBridge.ptyGetExitStatus(ptr)
        return if (status == -2) null else status
    }

    companion object {
        private const val LOG_TAG = "WarpTerminal"
    }
}
