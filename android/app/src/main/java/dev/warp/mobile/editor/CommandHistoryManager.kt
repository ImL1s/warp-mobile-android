package dev.warp.mobile.editor

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

data class HistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val command: String,
    val timestampMs: Long = System.currentTimeMillis()
)

object CommandHistoryManager {
    private const val TAG = "CommandHistoryManager"
    private const val FILE_NAME = "command_history.json"
    private const val MAX_HISTORY_ITEMS = 1000
    private const val MAX_CMD_LENGTH = 4096

    private val lock = Any()
    private val historyList = mutableListOf<HistoryItem>()

    fun addCommand(context: Context, command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        val sanitized = if (trimmed.length > MAX_CMD_LENGTH) trimmed.substring(0, MAX_CMD_LENGTH) else trimmed

        synchronized(lock) {
            // HIST_IGNORE_ALL_DUPS: remove older duplicate entries matching command
            historyList.removeAll { it.command == sanitized }

            // Evict oldest if capacity reached
            while (historyList.size >= MAX_HISTORY_ITEMS) {
                historyList.removeAt(0)
            }

            val item = HistoryItem(
                id = "hist-${System.currentTimeMillis()}-${historyList.size}",
                command = sanitized,
                timestampMs = System.currentTimeMillis()
            )
            historyList.add(item)
        }
        try {
            saveHistory(context)
        } catch (_: Throwable) {}
    }

    fun getHistory(): List<HistoryItem> = synchronized(lock) {
        historyList.toList()
    }

    fun getCommands(): List<String> = synchronized(lock) {
        historyList.map { it.command }
    }

    fun deleteItem(context: Context, item: HistoryItem) {
        synchronized(lock) {
            historyList.removeAll { it.id == item.id || it.command == item.command }
        }
        try { saveHistory(context) } catch (_: Throwable) {}
    }

    fun clearHistory(context: Context) {
        synchronized(lock) {
            historyList.clear()
        }
        try { saveHistory(context) } catch (_: Throwable) {}
    }

    fun loadHistory(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists() || file.length() == 0L) return
        synchronized(lock) {
            try {
                val jsonStr = file.readText(StandardCharsets.UTF_8)
                val items = parseHistoryJson(jsonStr)
                if (items.isEmpty() && jsonStr.isNotBlank() && jsonStr.trim() != "[]") {
                    Log.e(TAG, "Corrupted command history file, quarantining")
                    quarantineCorruptedFile(context)
                    historyList.clear()
                } else {
                    historyList.clear()
                    historyList.addAll(items)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Corrupted command history file, quarantining", e)
                quarantineCorruptedFile(context)
                historyList.clear()
            }
        }
    }

    fun saveHistory(context: Context): Boolean {
        val file = File(context.filesDir, FILE_NAME)
        val jsonStr = synchronized(lock) { formatHistoryJson(historyList) }
        return try {
            val tempFile = File(context.filesDir, "$FILE_NAME.tmp")
            tempFile.writeText(jsonStr, StandardCharsets.UTF_8)
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed atomic command history save", e)
            false
        }
    }

    private fun quarantineCorruptedFile(context: Context) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) {
                val bakFile = File(context.filesDir, "$FILE_NAME.bak")
                if (bakFile.exists()) bakFile.delete()
                if (!file.renameTo(bakFile)) {
                    file.copyTo(bakFile, overwrite = true)
                    file.delete()
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * For unit test isolation: initialize in-memory state directly without Context.
     */
    internal fun setHistoryForTesting(items: List<HistoryItem>) {
        synchronized(lock) {
            historyList.clear()
            historyList.addAll(items)
        }
    }

    internal fun formatHistoryJson(items: List<HistoryItem>): String {
        val sb = StringBuilder()
        sb.append("[\n")
        items.forEachIndexed { index, item ->
            val escapedCmd = item.command
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
            sb.append("  {\n")
            sb.append("    \"id\": \"${item.id}\",\n")
            sb.append("    \"command\": \"$escapedCmd\",\n")
            sb.append("    \"timestampMs\": ${item.timestampMs}\n")
            sb.append("  }")
            if (index < items.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("]")
        return sb.toString()
    }

    internal fun parseHistoryJson(jsonStr: String): List<HistoryItem> {
        val result = mutableListOf<HistoryItem>()
        if (jsonStr.isBlank()) return result
        try {
            val jsonArray = org.json.JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue
                val id = obj.optString("id", "")
                val cmd = obj.optString("command", "")
                val ts = obj.optLong("timestampMs", System.currentTimeMillis())
                if (cmd.isNotBlank()) {
                    result.add(HistoryItem(id = if (id.isBlank()) UUID.randomUUID().toString() else id, command = cmd, timestampMs = ts))
                }
            }
            if (result.isNotEmpty()) return result
        } catch (_: Throwable) {}

        val idRegex = Regex(""""id"\s*:\s*"([^"]+)"""")
        val tsRegex = Regex(""""timestampMs"\s*:\s*(\d+)""")

        val blocks = jsonStr.split("}")
        for (block in blocks) {
            val id = idRegex.find(block)?.groupValues?.get(1) ?: continue
            val ts = tsRegex.find(block)?.groupValues?.get(1)?.toLongOrNull() ?: System.currentTimeMillis()

            val cmdIdx = block.indexOf("\"command\"")
            if (cmdIdx == -1) continue
            val colonIdx = block.indexOf(':', cmdIdx)
            if (colonIdx == -1) continue
            val startQuote = block.indexOf('"', colonIdx + 1)
            if (startQuote == -1) continue

            var endQuote = -1
            var p = startQuote + 1
            while (p < block.length) {
                if (block[p] == '\\') {
                    p += 2
                } else if (block[p] == '"') {
                    endQuote = p
                    break
                } else {
                    p++
                }
            }
            if (endQuote == -1) continue
            val rawCmd = block.substring(startQuote + 1, endQuote)

            val unescapedCmd = rawCmd
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\\", "\\")
            if (unescapedCmd.isNotBlank()) {
                result.add(HistoryItem(id = id, command = unescapedCmd, timestampMs = ts))
            }
        }
        return result
    }
}

class CommandHistoryNavigator(private val getHistorySupplier: () -> List<HistoryItem> = { CommandHistoryManager.getHistory() }) {
    private var historyIndex: Int = -1
    private var draftPrompt: String = ""

    fun navigateUp(currentInput: String): String {
        val history = getHistorySupplier()
        if (history.isEmpty()) {
            historyIndex = -1
            return currentInput
        }

        historyIndex = historyIndex.coerceIn(-1, history.size - 1)

        if (historyIndex == -1) {
            draftPrompt = currentInput
        }

        val maxIndex = history.size - 1
        if (historyIndex < maxIndex) {
            historyIndex++
        }

        val targetIndex = (history.size - 1 - historyIndex).coerceIn(0, history.size - 1)
        return history[targetIndex].command
    }

    fun navigateDown(): String {
        val history = getHistorySupplier()
        if (history.isEmpty()) {
            historyIndex = -1
            return draftPrompt
        }

        historyIndex = historyIndex.coerceIn(-1, history.size - 1)

        if (historyIndex <= -1) {
            historyIndex = -1
            return draftPrompt
        }

        historyIndex--
        if (historyIndex <= -1) {
            historyIndex = -1
            return draftPrompt
        } else {
            val targetIndex = (history.size - 1 - historyIndex).coerceIn(0, history.size - 1)
            return history[targetIndex].command
        }
    }

    fun reset() {
        historyIndex = -1
        draftPrompt = ""
    }

    fun currentIndex(): Int = historyIndex
}
