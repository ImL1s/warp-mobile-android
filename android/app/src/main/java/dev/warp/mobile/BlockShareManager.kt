package dev.warp.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle

enum class ShareFormat {
    PLAIN_TEXT,
    JSON
}

object BlockShareManager {

    fun formatPlainText(block: WarpBlockState): String {
        return buildString {
            if (block.command.isNotEmpty()) {
                append("$ ").append(block.command).append('\n')
            }
            if (block.output.isNotEmpty()) {
                append(block.output)
                if (!block.output.endsWith('\n')) {
                    append('\n')
                }
            }
            val exit = block.exitCode ?: 0
            if (exit != 0 || block.command.isNotEmpty() || block.output.isNotEmpty()) {
                append("[exit ").append(exit).append("]\n")
            }
        }.trimEnd()
    }

    fun formatJson(block: WarpBlockState): String {
        val escapedId = escapeJsonString(block.id)
        val escapedCommand = escapeJsonString(block.command)
        val escapedOutput = escapeJsonString(block.output)
        return """
        {
          "id": "$escapedId",
          "command": "$escapedCommand",
          "exitCode": ${block.exitCode ?: 0},
          "durationMs": ${block.durationMs ?: 0L},
          "output": "$escapedOutput",
          "isRunning": ${block.isRunning},
          "timestamp": ${block.timestamp}
        }
        """.trimIndent()
    }

    private fun escapeJsonString(input: String): String {
        val sb = StringBuilder(input.length)
        for (c in input) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code in 0..0x1F) {
                        sb.append(String.format("\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.toString()
    }


    fun copyToClipboardWithSensitiveFlag(
        context: Context,
        label: String,
        text: String,
        isSensitive: Boolean = true
    ) {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        val clipData = ClipData.newPlainText(label, text)
        if (isSensitive && Build.VERSION.SDK_INT >= 33) {
            clipData.description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        clipboardManager.setPrimaryClip(clipData)
    }

    fun shareBlock(
        context: Context,
        block: WarpBlockState,
        format: ShareFormat = ShareFormat.PLAIN_TEXT
    ) {
        val payload = when (format) {
            ShareFormat.PLAIN_TEXT -> formatPlainText(block)
            ShareFormat.JSON -> formatJson(block)
        }

        val mimeType = when (format) {
            ShareFormat.PLAIN_TEXT -> "text/plain"
            ShareFormat.JSON -> "application/json"
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_TEXT, payload)
            putExtra(Intent.EXTRA_TITLE, "Share Command Block")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val chooserIntent = Intent.createChooser(shareIntent, "Share Block Output").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooserIntent)
    }
}
