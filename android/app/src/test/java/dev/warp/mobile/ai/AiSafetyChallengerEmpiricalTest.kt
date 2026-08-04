package dev.warp.mobile.ai

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import dev.warp.mobile.AiKeyStore
import dev.warp.mobile.AiUsageTracker
import dev.warp.mobile.test.BaseWarpUnitTest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AiSafetyChallengerEmpiricalTest : BaseWarpUnitTest() {

    private lateinit var context: Context
    private lateinit var tempDir: File
    private val prefsStore = ConcurrentHashMap<String, String>()

    @Before
    override fun setUp() {
        super.setUp()
        tempDir = File(System.getProperty("java.io.tmpdir"), "ai_safety_test_${System.currentTimeMillis()}").also { it.mkdirs() }
        
        val appInfo = ApplicationInfo().apply {
            dataDir = tempDir.absolutePath
        }

        val mockEditor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { mockEditor.putString(any(), any()) } answers {
            val k = arg<String>(0)
            val v = arg<String?>(1)
            if (v != null) {
                prefsStore[k] = v
            } else {
                prefsStore.remove(k)
            }
            mockEditor
        }
        every { mockEditor.remove(any()) } answers {
            val k = arg<String>(0)
            prefsStore.remove(k)
            mockEditor
        }
        every { mockEditor.clear() } answers {
            prefsStore.clear()
            mockEditor
        }
        every { mockEditor.apply() } returns Unit

        val mockPrefs = mockk<SharedPreferences>(relaxed = true)
        every { mockPrefs.getString(any(), any()) } answers {
            val k = arg<String>(0)
            val default = arg<String?>(1)
            prefsStore[k] ?: default
        }
        every { mockPrefs.edit() } returns mockEditor

        context = mockk<Context>(relaxed = true)
        every { context.applicationInfo } returns appInfo
        every { context.getSharedPreferences(any(), any()) } returns mockPrefs

        AiKeyStore.resetCacheForTesting()
        AiUsageTracker.resetSession()
        ModelProfileRepository.resetToDefaults(context)
    }

    /**
     * Edge Case 1: AiKeyStore Multi-Provider Storage, Legacy Migration, Redaction & Fallback
     */
    @Test
    fun testAiKeyStore_multiProviderStorageMigrationRedactionAndClear() {
        // 1. Multi-provider key saving and loading
        val antKey = "sk-ant-api03-secret1234567890abcdef"
        val oaiKey = "sk-proj-openaisecret9876543210fedcba"
        val customKey = "sk-custom-llama-key-1122334455667788"

        AiKeyStore.save(context, "anthropic", antKey)
        AiKeyStore.save(context, "openai", oaiKey)
        AiKeyStore.save(context, "llama_local", customKey)

        assertEquals(antKey, AiKeyStore.load(context, "anthropic"))
        assertEquals(oaiKey, AiKeyStore.load(context, "openai"))
        assertEquals(customKey, AiKeyStore.load(context, "llama_local"))

        // 2. Legacy key migration
        AiKeyStore.clear(context, "anthropic")
        prefsStore["anthropic-api-key"] = "sk-ant-legacy-key-999"
        
        // Loading legacy anthropic key should migrate to new schema
        val loadedLegacy = AiKeyStore.load(context, "anthropic")
        assertEquals("sk-ant-legacy-key-999", loadedLegacy)

        // 3. Redaction verification for different provider formats
        val antRedacted = AiKeyStore.redact(antKey)
        assertTrue("Anthropic redacted should start with Bearer sk-ant-", antRedacted.startsWith("Bearer sk-ant-a"))
        assertTrue("Anthropic redacted should end with last 4 chars", antRedacted.endsWith("cdef"))
        assertTrue("Anthropic redacted should contain masked middle", antRedacted.contains("***..."))

        val oaiRedacted = AiKeyStore.redact(oaiKey)
        assertTrue("OpenAI redacted should start with Bearer sk-proj-", oaiRedacted.startsWith("Bearer sk-proj-"))
        assertTrue("OpenAI redacted should end with last 4 chars", oaiRedacted.endsWith("dcba"))

        assertEquals("(no key)", AiKeyStore.redact(null))
        assertEquals("(no key)", AiKeyStore.redact(""))
        assertEquals("Bearer shortkey***...y123", AiKeyStore.redact("shortkey123"))

        // 4. Selective clearing
        AiKeyStore.clear(context, "anthropic")
        assertNull(AiKeyStore.load(context, "anthropic"))
        assertEquals(oaiKey, AiKeyStore.load(context, "openai"))

        // 5. Clear all
        AiKeyStore.clearAll(context)
        assertNull(AiKeyStore.load(context, "openai"))
        assertNull(AiKeyStore.load(context, "llama_local"))
    }

    /**
     * Edge Case 2: CommandApprovalManager State Machine (LOW risk auto-allow, HIGH risk APPROVE vs REJECT)
     */
    @Test
    fun testCommandApprovalManager_stateMachine_approveAndRejectFlows() = runTest {
        // 1. LOW Risk command: auto-allowed immediately
        var lowRiskCallbackCalled = false
        var lowRiskApproved: Boolean? = null
        CommandApprovalManager.requestApproval(context, "ls -la /tmp", "claude-3-5-sonnet") { approved ->
            lowRiskCallbackCalled = true
            lowRiskApproved = approved
        }
        assertTrue("Low-risk command should invoke callback immediately", lowRiskCallbackCalled)
        assertEquals(true, lowRiskApproved)
        assertEquals(ApprovalStatus.IDLE, CommandApprovalManager.status.value)
        assertNull(CommandApprovalManager.pendingApproval.value)

        // 2. HIGH Risk command: APPROVE flow
        var highRiskCallbackCalled = false
        var highRiskApproved: Boolean? = null
        val dangerousCmd = "rm -rf /data/user/0/dev.warp.mobile/cache"
        
        CommandApprovalManager.requestApproval(context, dangerousCmd, "gpt-4o") { approved ->
            highRiskCallbackCalled = true
            highRiskApproved = approved
        }

        assertFalse("High-risk command callback should NOT be called immediately", highRiskCallbackCalled)
        assertEquals(ApprovalStatus.WAITING_FOR_APPROVAL, CommandApprovalManager.status.value)
        
        val pending = CommandApprovalManager.pendingApproval.value
        assertNotNull("Pending approval object must be created", pending)
        assertEquals(dangerousCmd, pending?.command)
        assertEquals(RiskLevel.HIGH, pending?.riskLevel)
        assertEquals("gpt-4o", pending?.model)

        // Submit decision: APPROVED
        CommandApprovalManager.submitDecision(context, approved = true)

        assertTrue("Callback should be invoked after approval decision", highRiskCallbackCalled)
        assertEquals(true, highRiskApproved)
        assertNull("Pending approval should be cleared after decision", CommandApprovalManager.pendingApproval.value)
        assertEquals(ApprovalStatus.IDLE, CommandApprovalManager.status.value)

        // 3. HIGH Risk command: REJECT flow
        var rejectCallbackCalled = false
        var rejectApproved: Boolean? = null
        val sudoCmd = "sudo chmod -R 777 /etc"

        CommandApprovalManager.requestApproval(context, sudoCmd, "claude-3-5-sonnet") { approved ->
            rejectCallbackCalled = true
            rejectApproved = approved
        }

        assertEquals(ApprovalStatus.WAITING_FOR_APPROVAL, CommandApprovalManager.status.value)

        // Submit decision: REJECTED
        CommandApprovalManager.submitDecision(context, approved = false)

        assertTrue("Callback should be invoked after rejection decision", rejectCallbackCalled)
        assertEquals(false, rejectApproved)
        assertNull(CommandApprovalManager.pendingApproval.value)
        assertEquals(ApprovalStatus.IDLE, CommandApprovalManager.status.value)

        // 4. Edge case: submit decision with no pending approval
        CommandApprovalManager.submitDecision(context, approved = true)
        assertEquals(ApprovalStatus.IDLE, CommandApprovalManager.status.value)
    }

    /**
     * Edge Case 3: AiUsageTracker Concurrent 50-Coroutine 7-Column CSV Logging & RFC 4180 Escaping
     */
    @Test
    fun testAiUsageTracker_50CoroutinesConcurrentCsvAppendsAndRfc4180Escaping() {
        val numCoroutines = 50
        val recordsPerCoroutine = 10
        val executor = Executors.newFixedThreadPool(16)
        val successCounter = AtomicInteger(0)
        val exceptionCounter = AtomicInteger(0)

        val testCommands = listOf(
            "ls -la",
            "echo \"hello, world\"",
            "echo 'multi\nline\ncommand'",
            "rm -rf \"dir with, comma\r\nand newline\"",
            "git commit -m \"fix: update \"\"quotes\"\" and, commas\""
        )

        val approvalStates = listOf("AUTO_ALLOWED", "APPROVED", "REJECTED")

        for (c in 1..numCoroutines) {
            executor.submit {
                try {
                    for (r in 1..recordsPerCoroutine) {
                        val cmdIdx = (c + r) % testCommands.size
                        val appIdx = (c + r) % approvalStates.size
                        val cmd = testCommands[cmdIdx]
                        val state = approvalStates[appIdx]

                        AiUsageTracker.recordAudit(
                            context = context,
                            model = "claude-3-5-sonnet",
                            inputTokens = 100 + r,
                            outputTokens = 200 + r,
                            latencyMs = 50L + r,
                            commandString = cmd,
                            approvalState = state
                        )
                        successCounter.incrementAndGet()
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                    exceptionCounter.incrementAndGet()
                }
            }
        }

        executor.shutdown()
        val finished = executor.awaitTermination(15, TimeUnit.SECONDS)
        assertTrue("50 coroutines CSV write benchmark timed out", finished)
        assertEquals("Zero exceptions expected during concurrent CSV logging", 0, exceptionCounter.get())
        assertEquals(numCoroutines * recordsPerCoroutine, successCounter.get())

        // Read and verify CSV file integrity
        val primaryCsv = File("${context.applicationInfo.dataDir}/files/usr/var/log/warp-ai-usage.csv")
        val fallbackCsv = File("${context.applicationInfo.dataDir}/files/warp-ai-usage.csv")
        val csvFile = if (primaryCsv.exists()) primaryCsv else fallbackCsv

        assertTrue("CSV file must exist in primary or fallback path", csvFile.exists())
        val csvContent = csvFile.readText()
        assertTrue("CSV must start with 7-column header", csvContent.startsWith("# timestamp,model,input_tokens,output_tokens,latency_ms,command_string,approval_state"))

        // Split into lines considering quoted newlines
        val rawLines = csvContent.lines()
        assertTrue("CSV file should contain content", rawLines.isNotEmpty())
        assertEquals("# timestamp,model,input_tokens,output_tokens,latency_ms,command_string,approval_state", rawLines[0].trim())

        // Verify RFC 4180 escape helper directly
        assertEquals("simple_cmd", AiUsageTracker.escapeRfc4180("simple_cmd"))
        assertEquals("\"cmd, with, commas\"", AiUsageTracker.escapeRfc4180("cmd, with, commas"))
        assertEquals("\"echo \"\"quoted\"\"\"", AiUsageTracker.escapeRfc4180("echo \"quoted\""))
        assertEquals("\"line1\nline2\"", AiUsageTracker.escapeRfc4180("line1\nline2"))
    }

    /**
     * Edge Case 4: ModelProfile & Repository custom profile persistence and switching
     */
    @Test
    fun testModelProfileRepository_customProfilePersistenceAndSwitching() {
        // Initial state check
        val initialProfile = ModelProfileRepository.getActiveProfile(context)
        assertEquals("claude-3-5-sonnet", initialProfile.id)

        // Save valid custom profile
        val customProfile = ModelProfile(
            id = "custom-deepseek-r1",
            name = "DeepSeek R1 Local",
            provider = ProviderKind.CUSTOM_OPENAI,
            modelName = "deepseek-r1",
            endpointUrl = "http://127.0.0.1:11434/v1/chat/completions",
            temperature = 0.6f,
            maxTokens = 8192,
            topP = 0.95f,
            contextWindow = 64000,
            supportsTools = true,
            supportsStreaming = true,
            isBuiltin = false
        )

        assertTrue("Custom profile must pass self-validation", customProfile.validate())

        val saved = ModelProfileRepository.saveCustomProfile(context, customProfile)
        assertTrue("Saving valid custom profile should succeed", saved)

        // Verify custom profile appears in all profiles list
        val allProfiles = ModelProfileRepository.getAllProfiles(context)
        assertTrue("Custom profile should be listed in getAllProfiles (found: ${allProfiles.map { it.id }})", allProfiles.any { it.id == "custom-deepseek-r1" })

        // Switch active profile to custom
        val switched = ModelProfileRepository.setActiveProfileId(context, "custom-deepseek-r1")
        assertTrue("Switching active profile should succeed", switched)

        val activeCustom = ModelProfileRepository.getActiveProfile(context)
        assertEquals("custom-deepseek-r1", activeCustom.id)
        assertEquals("DeepSeek R1 Local", activeCustom.name)
        assertEquals(ProviderKind.CUSTOM_OPENAI, activeCustom.provider)

        // Save invalid profile (empty endpoint for custom provider) should fail
        val invalidCustom = customProfile.copy(id = "invalid-custom", endpointUrl = "")
        assertFalse("Saving custom profile with empty endpointUrl should fail validation", ModelProfileRepository.saveCustomProfile(context, invalidCustom))

        // Reset to defaults
        ModelProfileRepository.resetToDefaults(context)
        val resetProfile = ModelProfileRepository.getActiveProfile(context)
        assertEquals("claude-3-5-sonnet", resetProfile.id)
    }
}
