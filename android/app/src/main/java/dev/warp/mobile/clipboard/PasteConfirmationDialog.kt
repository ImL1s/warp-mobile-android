package dev.warp.mobile.clipboard

import android.app.AlertDialog
import android.content.Context
import android.widget.ScrollView
import android.widget.TextView
import dev.warp.mobile.ai.CommandRiskEvaluator
import dev.warp.mobile.ai.RiskLevel

/**
 * Safety confirmation dialog for multi-line scripts, high-risk commands (e.g. rm -rf, sudo),
 * or large clipboard payloads.
 */
object PasteConfirmationDialog {

    fun shouldConfirm(text: String): Boolean {
        if (text.contains("\n") || text.contains("\r")) return true
        if (text.length > 1024) return true
        if (CommandRiskEvaluator.evaluate(text) == RiskLevel.HIGH) return true
        return false
    }

    fun show(
        context: Context,
        text: String,
        onConfirmPaste: (String) -> Unit
    ) {
        val lineCount = text.split("\n", "\r\n").size
        val byteCount = text.toByteArray(Charsets.UTF_8).size
        val isHighRisk = CommandRiskEvaluator.evaluate(text) == RiskLevel.HIGH
        val isMultiLine = lineCount > 1

        val title = if (isHighRisk) {
            "⚠️ High Risk Clipboard Paste"
        } else if (isMultiLine) {
            "⚠️ Confirm Multi-Line Paste"
        } else {
            "⚠️ Confirm Large Clipboard Paste"
        }

        val message = StringBuilder()
        if (isHighRisk) {
            message.append("WARNING: Dangerous command pattern detected in payload!\n")
        }
        message.append("Payload size: $byteCount bytes, $lineCount line(s).\n")
        if (isMultiLine) {
            message.append("Pasting multi-line text into terminal will execute lines immediately.\n")
        }

        val previewView = TextView(context).apply {
            val previewText = if (text.length > 500) text.substring(0, 500) + "...\n[Truncated]" else text
            setText(previewText)
            textSize = 12f
            setPadding(32, 16, 32, 16)
            typeface = android.graphics.Typeface.MONOSPACE
        }

        val scrollView = ScrollView(context).apply {
            addView(previewView)
        }

        val builder = AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message.toString())
            .setView(scrollView)
            .setPositiveButton("Paste") { _, _ ->
                onConfirmPaste(text)
            }
            .setNegativeButton("Cancel", null)

        if (isMultiLine) {
            builder.setNeutralButton("Paste Single Line") { _, _ ->
                val sanitized = text.replace("\r\n", " ").replace("\n", " ").replace("\r", " ")
                onConfirmPaste(sanitized)
            }
        }

        builder.show()
    }
}
