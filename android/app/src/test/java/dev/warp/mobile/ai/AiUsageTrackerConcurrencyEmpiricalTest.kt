package dev.warp.mobile.ai

import android.content.Context
import android.content.pm.ApplicationInfo
import dev.warp.mobile.AiUsageTracker
import dev.warp.mobile.test.BaseWarpUnitTest
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AiUsageTrackerConcurrencyEmpiricalTest : BaseWarpUnitTest() {

    private lateinit var context: Context
    private lateinit var tempDir: File

    @Before
    override fun setUp() {
        super.setUp()
        tempDir = File(System.getProperty("java.io.tmpdir"), "ai_usage_concurrency_${System.currentTimeMillis()}").also { it.mkdirs() }

        val appInfo = ApplicationInfo().apply {
            dataDir = tempDir.absolutePath
        }

        context = mockk<Context>(relaxed = true)
        every { context.applicationInfo } returns appInfo

        AiUsageTracker.resetSession()
    }

    /**
     * RFC-4180 Compliant CSV Parser for Verification.
     * Parses full CSV content handling quoted fields, escaped quotes (""), and embedded newlines.
     */
    private fun parseRfc4180Csv(csvContent: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        val currentField = StringBuilder()
        val currentRecord = mutableListOf<String>()
        var inQuotes = false
        var i = 0
        val len = csvContent.length

        while (i < len) {
            val c = csvContent[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < len && csvContent[i + 1] == '"') {
                        currentField.append('"')
                        i++ // Skip escaped quote
                    } else {
                        inQuotes = false
                    }
                } else {
                    currentField.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> {
                        currentRecord.add(currentField.toString())
                        currentField.clear()
                    }
                    '\n' -> {
                        currentRecord.add(currentField.toString())
                        currentField.clear()
                        records.add(currentRecord.toList())
                        currentRecord.clear()
                    }
                    '\r' -> {
                        if (i + 1 < len && csvContent[i + 1] == '\n') {
                            i++
                        }
                        currentRecord.add(currentField.toString())
                        currentField.clear()
                        records.add(currentRecord.toList())
                        currentRecord.clear()
                    }
                    else -> currentField.append(c)
                }
            }
            i++
        }

        if (currentField.isNotEmpty() || currentRecord.isNotEmpty()) {
            currentRecord.add(currentField.toString())
            records.add(currentRecord.toList())
        }

        return records
    }

    @Test
    fun testAiUsageTracker_heavyConcurrentWrites_50Threads_100WritesEach() {
        val numThreads = 50
        val writesPerThread = 100
        val totalExpectedWrites = numThreads * writesPerThread
        val executor = Executors.newFixedThreadPool(20)

        val successCounter = AtomicInteger(0)
        val errorCounter = AtomicInteger(0)

        val expectedRecords = ConcurrentLinkedQueue<ExpectedAuditEntry>()

        val specialCommands = listOf(
            "ls -la /tmp",
            "echo \"hello, world!\"",
            "echo \"line1\nline2\nline3\"",
            "rm -rf \"path with, commas, and \"\"quotes\"\"\"",
            "cat <<'EOF'\n{\n  \"key\": \"value with, comma\"\n}\nEOF",
            "curl -X POST -H \"Content-Type: application/json\" -d '{\"prompt\": \"hello\"}' https://api.openai.com/v1",
            "echo 'tabs\tand\tspaces'",
            "echo 'Unicode 🔥 🚀 ⚡ 漢字 繁體'",
            "eval \"\"\"nested \"\" quotes\"\"\"",
            "git commit -m \"fix(ai): handle special chars like \\n and , correctly\""
        )

        val models = listOf("claude-3-5-sonnet", "gpt-4o", "local-llama3")
        val approvalStates = listOf("AUTO_ALLOWED", "APPROVED", "REJECTED")

        for (t in 1..numThreads) {
            executor.submit {
                try {
                    for (w in 1..writesPerThread) {
                        val cmdIdx = (t * 31 + w * 17) % specialCommands.size
                        val modelIdx = (t + w) % models.size
                        val stateIdx = (t * 3 + w) % approvalStates.size

                        val cmd = specialCommands[cmdIdx]
                        val model = models[modelIdx]
                        val state = approvalStates[stateIdx]
                        val inTokens = 100 + t * 10 + w
                        val outTokens = 200 + t * 5 + w
                        val latency = 45L + w

                        AiUsageTracker.recordAudit(
                            context = context,
                            model = model,
                            inputTokens = inTokens,
                            outputTokens = outTokens,
                            latencyMs = latency,
                            commandString = cmd,
                            approvalState = state
                        )

                        expectedRecords.add(ExpectedAuditEntry(model, inTokens, outTokens, latency, cmd, state))
                        successCounter.incrementAndGet()
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                    errorCounter.incrementAndGet()
                }
            }
        }

        executor.shutdown()
        val finished = executor.awaitTermination(30, TimeUnit.SECONDS)
        assertTrue("50-thread concurrent CSV write benchmark must finish within 30s", finished)
        assertEquals("Zero errors during concurrent writes expected", 0, errorCounter.get())
        assertEquals(totalExpectedWrites, successCounter.get())

        // Read and parse output CSV
        val csvFile = File("${tempDir.absolutePath}/files/usr/var/log/warp-ai-usage.csv")
        assertTrue("CSV audit file must be created", csvFile.exists())

        val csvContent = csvFile.readText()
        val parsedRows = parseRfc4180Csv(csvContent)

        assertTrue("CSV content must not be empty", parsedRows.isNotEmpty())
        val header = parsedRows[0]
        assertEquals(
            List(7) { listOf("# timestamp", "model", "input_tokens", "output_tokens", "latency_ms", "command_string", "approval_state")[it] },
            header
        )

        val dataRows = parsedRows.drop(1)
        assertEquals("Total parsed data rows must match total concurrent writes", totalExpectedWrites, dataRows.size)

        // Verify structure of every single row
        for ((idx, row) in dataRows.withIndex()) {
            assertEquals("Row $idx must have exactly 7 columns", 7, row.size)
            assertTrue("Timestamp column 0 must be ISO 8601 UTC format", row[0].matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""")))
            assertTrue("Model column 1 must be valid", models.contains(row[1]))
            assertTrue("Input tokens column 2 must be positive int", row[2].toInt() > 0)
            assertTrue("Output tokens column 3 must be positive int", row[3].toInt() > 0)
            assertTrue("Latency ms column 4 must be positive long", row[4].toLong() >= 0)
            assertNotNull("Command string column 5 must not be null", row[5])
            assertTrue("Approval state column 6 must be valid", approvalStates.contains(row[6]))
        }
    }

    @Test
    fun testAiUsageTracker_rfc4180EscapingEdgeCases() {
        val testCases = mapOf(
            "simple" to "simple",
            "with,comma" to "\"with,comma\"",
            "with \"quotes\"" to "\"with \"\"quotes\"\"\"",
            "with\nnewline" to "\"with\nnewline\"",
            "with\r\ncrlf" to "\"with\r\ncrlf\"",
            "mixed \"quotes\", commas and\nnewlines" to "\"mixed \"\"quotes\"\", commas and\nnewlines\"",
            "\"" to "\"\"\"\"",
            "," to "\",\"",
            "\n" to "\"\n\"",
            "" to ""
        )

        for ((input, expected) in testCases) {
            val escaped = AiUsageTracker.escapeRfc4180(input)
            assertEquals("Failed RFC-4180 escaping for input: '$input'", expected, escaped)
        }
    }

    private data class ExpectedAuditEntry(
        val model: String,
        val inputTokens: Int,
        val outputTokens: Int,
        val latencyMs: Long,
        val commandString: String,
        val approvalState: String
    )
}
