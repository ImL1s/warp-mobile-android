package dev.warp.mobile

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.charset.StandardCharsets

object SessionPersistenceManager {
    private const val TAG = "SessionPersistence"
    private const val FILE_NAME = "sessions.json"

    fun getSessionFile(context: Context): File {
        return File(context.filesDir, FILE_NAME)
    }

    fun saveSessionState(context: Context, jsonState: String): Boolean {
        if (jsonState.isBlank()) return false
        val file = getSessionFile(context)
        return try {
            file.parentFile?.mkdirs()
            val tempFile = File(file.parentFile, "$FILE_NAME.tmp")
            tempFile.writeText(jsonState, StandardCharsets.UTF_8)
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
            true
        } catch (e: Throwable) {
            try { Log.e(TAG, "Failed atomic save", e) } catch (_: Throwable) {}
            false
        }
    }

    fun loadSessionState(context: Context): String? {
        val file = getSessionFile(context)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            file.readText(StandardCharsets.UTF_8)
        } catch (e: Throwable) {
            try { Log.e(TAG, "Failed to load session state", e) } catch (_: Throwable) {}
            null
        }
    }

    fun quarantineCorruptedFile(context: Context) {
        try {
            val file = getSessionFile(context)
            if (file.exists()) {
                val bakFile = File(context.filesDir, "$FILE_NAME.bak")
                if (bakFile.exists()) bakFile.delete()
                file.renameTo(bakFile)
            }
        } catch (e: Throwable) {
            try { Log.e(TAG, "Failed to quarantine corrupted session file", e) } catch (_: Throwable) {}
        }
    }

    fun clearSessionState(context: Context): Boolean {
        return try {
            val file = getSessionFile(context)
            File(context.filesDir, "$FILE_NAME.tmp").delete()
            File(context.filesDir, "$FILE_NAME.bak").delete()
            if (file.exists()) file.delete() else true
        } catch (e: Throwable) {
            false
        }
    }
}
