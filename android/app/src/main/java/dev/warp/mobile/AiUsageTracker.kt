package dev.warp.mobile

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * M6-S06 & Issue #15: per-session token usage tracker + 7-column CSV audit log.
 *
 * Tracks cumulative input/output token counts for the current process
 * lifetime.
 *
 * Persistence: per-request audit entries are appended to
 *   $PREFIX/var/log/warp-ai-usage.csv (or <dataDir>/files/warp-ai-usage.csv fallback)
 *
 * Schema (7 columns):
 *   timestamp,model,input_tokens,output_tokens,latency_ms,command_string,approval_state
 */
object AiUsageTracker {
    private const val LOG_TAG = "WarpAiUsage"
    private const val CSV_FILENAME = "warp-ai-usage.csv"

    private val sessionInputTokens = AtomicLong(0)
    private val sessionOutputTokens = AtomicLong(0)
    private val sessionGhostCalls = AtomicLong(0)
    private val sessionAgentCalls = AtomicLong(0)

    /** Latency p95 sentinels: rolling list of last 100 latencies per kind. */
    private val ghostLatencies = ArrayDeque<Long>()
    private val agentLatencies = ArrayDeque<Long>()

    /** Lock for CSV appends across threads. */
    private val csvLock = Any()

    /**
     * Record one completed AI call. Updates in-memory counters and writes to CSV.
     */
    fun record(
        context: Context,
        kind: String,
        model: String,
        inputTokens: Int,
        outputTokens: Int,
        latencyMs: Long
    ) {
        sessionInputTokens.addAndGet(inputTokens.toLong())
        sessionOutputTokens.addAndGet(outputTokens.toLong())
        when (kind) {
            "ghost" -> {
                sessionGhostCalls.incrementAndGet()
                synchronized(ghostLatencies) {
                    ghostLatencies.addLast(latencyMs)
                    while (ghostLatencies.size > 100) ghostLatencies.removeFirst()
                }
            }
            "agent" -> {
                sessionAgentCalls.incrementAndGet()
                synchronized(agentLatencies) {
                    agentLatencies.addLast(latencyMs)
                    while (agentLatencies.size > 100) agentLatencies.removeFirst()
                }
            }
        }
        recordAudit(
            context = context,
            model = model,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            latencyMs = latencyMs,
            commandString = "",
            approvalState = "AUTO_ALLOWED"
        )

        val p95 = when (kind) {
            "ghost" -> synchronized(ghostLatencies) { percentile(ghostLatencies, 0.95) }
            "agent" -> synchronized(agentLatencies) { percentile(agentLatencies, 0.95) }
            else -> 0L
        }
        val tokenCap = if (kind == "ghost") 200 else 2000
        if (outputTokens > tokenCap * 1.5) {
            Log.w(
                LOG_TAG,
                "token cap exceeded: kind=$kind output=$outputTokens cap=$tokenCap p95latency=$p95"
            )
        }
    }

    /**
     * Records a 7-column CSV audit row to warp-ai-usage.csv with thread-safety and RFC 4180 escaping.
     */
    fun recordAudit(
        context: Context,
        model: String,
        inputTokens: Int,
        outputTokens: Int,
        latencyMs: Long,
        commandString: String,
        approvalState: String
    ) {
        synchronized(csvLock) {
            try {
                val primaryDir = File("${context.applicationInfo.dataDir}/files/usr/var/log")
                val logDir = if (primaryDir.exists() || primaryDir.mkdirs()) {
                    primaryDir
                } else {
                    File("${context.applicationInfo.dataDir}/files").also { if (!it.exists()) it.mkdirs() }
                }
                val csv = File(logDir, CSV_FILENAME)
                val isNew = !csv.exists()
                val escapedCmd = escapeRfc4180(commandString)
                val timestamp = timestampUtc()

                csv.appendText(
                    buildString {
                        if (isNew) {
                            append("# timestamp,model,input_tokens,output_tokens,latency_ms,command_string,approval_state\n")
                        }
                        append(timestamp)
                        append(',').append(model)
                        append(',').append(inputTokens)
                        append(',').append(outputTokens)
                        append(',').append(latencyMs)
                        append(',').append(escapedCmd)
                        append(',').append(approvalState)
                        append('\n')
                    }
                )
            } catch (e: Throwable) {
                Log.w(LOG_TAG, "CSV append failed: ${e.message}")
            }
        }
    }

    /**
     * RFC 4180 double-quote escaping helper.
     */
    fun escapeRfc4180(field: String): String {
        val needsQuotes = field.contains(',') || field.contains('"') || field.contains('\n') || field.contains('\r')
        val escaped = field.replace("\"", "\"\"")
        return if (needsQuotes) "\"$escaped\"" else escaped
    }

    fun parseUsageFromBody(body: String): Pair<Int, Int> {
        if (body.isBlank()) return 0 to 0
        try {
            val inputRegex = Regex(""""input_tokens"\s*:\s*(\d+)""")
            val outputRegex = Regex(""""output_tokens"\s*:\s*(\d+)""")
            val inputMatch = inputRegex.find(body)?.groupValues?.get(1)?.toIntOrNull()
            val outputMatch = outputRegex.find(body)?.groupValues?.get(1)?.toIntOrNull()

            if (inputMatch != null || outputMatch != null) {
                return (inputMatch ?: 0) to (outputMatch ?: 0)
            }
        } catch (_: Throwable) {}

        return try {
            val usage = JSONObject(body).optJSONObject("usage") ?: return 0 to 0
            val input = usage.optInt("input_tokens", 0)
            val output = usage.optInt("output_tokens", 0)
            input to output
        } catch (_: Throwable) {
            0 to 0
        }
    }

    data class Snapshot(
        val ghostCalls: Long,
        val agentCalls: Long,
        val inputTokens: Long,
        val outputTokens: Long,
        val ghostP95Ms: Long,
        val agentP95Ms: Long,
    )

    fun snapshot(): Snapshot {
        val gp95 = synchronized(ghostLatencies) { percentile(ghostLatencies, 0.95) }
        val ap95 = synchronized(agentLatencies) { percentile(agentLatencies, 0.95) }
        return Snapshot(
            ghostCalls = sessionGhostCalls.get(),
            agentCalls = sessionAgentCalls.get(),
            inputTokens = sessionInputTokens.get(),
            outputTokens = sessionOutputTokens.get(),
            ghostP95Ms = gp95,
            agentP95Ms = ap95,
        )
    }

    fun resetSession() {
        sessionInputTokens.set(0)
        sessionOutputTokens.set(0)
        sessionGhostCalls.set(0)
        sessionAgentCalls.set(0)
        synchronized(ghostLatencies) { ghostLatencies.clear() }
        synchronized(agentLatencies) { agentLatencies.clear() }
    }

    private fun timestampUtc(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(Date())

    private fun percentile(samples: ArrayDeque<Long>, p: Double): Long {
        if (samples.isEmpty()) return 0L
        val sorted = samples.toLongArray().also { it.sort() }
        val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}
