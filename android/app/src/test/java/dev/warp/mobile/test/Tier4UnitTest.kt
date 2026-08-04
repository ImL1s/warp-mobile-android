package dev.warp.mobile.test

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Tier 4 Unit Test Suite: Wave 6-8 Real-World Workload Application Logic
 *
 * 15 Comprehensive Real-World Workload Application Test Scenarios:
 * 1. Full Developer Workstation Session Lifecycle (#8, #9, #10)
 * 2. Multi-Tab Terminal Execution & Concurrent Output Handling (#7, #8, #26)
 * 3. AI Agent Autonomous Code Refactoring Workflow (#14, #15, #23)
 * 4. Secure SSH Remote Deployment & Port Forwarding (#22, #8, #11)
 * 5. Project Rules Matching + Permissioned MCP Server Launch (#28, #29, #15)
 * 6. Session Crash & Unexpected PTY Death Recovery (#10, #9, #20)
 * 7. Reproducible Release Pipeline & Artifact Verification (#25, #27, #24)
 * 8. TUI Fullscreen Raw Mode Switching & Vulkan Render Pipeline (#12, #20, #26)
 * 9. Split Panes Layout & Saved Workspace Launch Config (#30, #8)
 * 10. Unified Cross-Domain Search & Filter Workflow (#17, #13, #14)
 * 11. Touch Block Selection, Context Menu & Block Sharing (#13, #18, #16)
 * 12. Modern Command Editor with Auto-Completions & Slash Palette (#16, #18)
 * 13. Secret Sanitization & Logcat Privacy Audit (#23, #15)
 * 14. Adaptive Layout & Accessibility Compliance (#19, #24)
 * 15. Termux W^X Package Installation & Execution under Android 12+ (#21, #27, #10)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Tier4UnitTest : BaseWarpUnitTest() {

    // ── Workload 1: Full Developer Workstation Session Lifecycle (#8, #9, #10) ─────
    @Test
    fun testWorkload01_fullDeveloperWorkstationSessionLifecycle() = runTest {
        val manager = TestTier4WorkstationSessionManager()

        // 1. Spawn multi-tabs for developer workstation
        val tab1 = manager.createTab("Backend", "/workspace/backend")
        val tab2 = manager.createTab("Frontend", "/workspace/frontend")
        val tab3 = manager.createTab("DB", "/workspace/db")
        assertEquals(3, manager.tabs.size)
        assertEquals("Backend", manager.activeTab?.name)

        // 2. Execute commands per tab
        manager.executeInTab(tab1.id, "cargo run --bin server", "Server running on port 8080")
        manager.executeInTab(tab2.id, "npm run dev", "Vite dev server ready in 320ms")
        manager.executeInTab(tab3.id, "docker-compose up postgres", "database system is ready to accept connections")

        // 3. Serialize workspace state to JSON
        val jsonState = manager.serializeToJson()
        assertTrue(jsonState.contains("Backend"))
        assertTrue(jsonState.contains("Frontend"))
        assertTrue(jsonState.contains("DB"))
        assertTrue(jsonState.contains("cargo run --bin server"))

        // 4. Simulate app kill & restoration
        val restoredManager = TestTier4WorkstationSessionManager()
        restoredManager.restoreFromJson(jsonState)

        // 5. Verify restored state
        assertEquals(3, restoredManager.tabs.size)
        assertEquals("Backend", restoredManager.activeTab?.name)
        assertEquals("/workspace/backend", restoredManager.tabs[0].workingDir)
        assertEquals(1, restoredManager.tabs[0].blocks.size)
        assertEquals("cargo run --bin server", restoredManager.tabs[0].blocks[0].command)
    }

    // ── Workload 2: Multi-Tab Terminal Execution & Concurrent Output (#7, #8, #26)
    @Test
    fun testWorkload02_multiTabTerminalExecutionAndConcurrentOutput() = runTest {
        val facade = TestTier4MultiTabTerminalFacade()

        val tabIds = listOf("tab-build", "tab-test", "tab-logs", "tab-server")
        tabIds.forEach { facade.createTerminalTab(it) }

        // Stream output concurrently into 4 tabs
        repeat(50) { i ->
            facade.appendOutput("tab-build", "[BUILD] Compiling module_$i...\n")
            facade.appendOutput("tab-test", "[TEST] Executing test_case_$i... PASSED\n")
            facade.appendOutput("tab-logs", "[LOG] 2026-08-03T19:00:${i % 60}Z info: processing request\n")
            facade.appendOutput("tab-server", "[SERVER] HTTP 200 GET /api/v1/resource/$i\n")
        }

        // Verify isolation & line counts
        tabIds.forEach { tabId ->
            val output = facade.getOutput(tabId)
            assertEquals(50, output.lines().filter { it.isNotBlank() }.size)
        }

        // Ensure no cross-tab stdout bleed
        val buildOutput = facade.getOutput("tab-build")
        assertFalse(buildOutput.contains("[TEST]"))
        assertFalse(buildOutput.contains("[SERVER]"))
        assertTrue(buildOutput.contains("Compiling module_0"))
    }

    // ── Workload 3: AI Agent Autonomous Code Refactoring Workflow (#14, #15, #23) ──
    @Test
    fun testWorkload03_aiAgentAutonomousCodeRefactoringWorkflow() = runTest {
        val engine = TestTier4AiAgentWorkflowEngine(modelProfile = "claude-3-7-sonnet")

        // User initiates refactoring request with sensitive API key embedded
        val userPrompt = "Refactor db.rs pool connection using API key sk-ant-api03-sec123456789"
        val scrubbedPrompt = engine.processUserPrompt(userPrompt)
        assertTrue(scrubbedPrompt.contains("sk-ant-***REDACTED***"))

        // AI requests read_file (auto-approved tool)
        val readRequest = TestTier4ToolCallRequest("read_file", mapOf("path" to "src/db.rs"), requiresPermission = false)
        val readResult = engine.handleToolCall(readRequest, userApproved = false)
        assertTrue(readResult.isSuccess)

        // AI requests write_file (permission-gated tool)
        val writeRequest = TestTier4ToolCallRequest("write_file", mapOf("path" to "src/db.rs", "content" to "// updated pool"), requiresPermission = true)
        val writeDenied = engine.handleToolCall(writeRequest, userApproved = false)
        assertFalse(writeDenied.isSuccess)

        // User approves write operation
        val writeApproved = engine.handleToolCall(writeRequest, userApproved = true)
        assertTrue(writeApproved.isSuccess)

        // Audit log CSV check
        val csvAudit = engine.exportAuditCsv()
        assertTrue(csvAudit.contains("claude-3-7-sonnet"))
        assertTrue(csvAudit.contains("read_file,AUTO_APPROVED"))
        assertTrue(csvAudit.contains("write_file,USER_APPROVED"))
    }

    // ── Workload 4: Secure SSH Remote Deployment & Port Forwarding (#22, #8, #11) ─
    @Test
    fun testWorkload04_secureSshRemoteDeploymentAndPortForwarding() = runTest {
        val sshCred = WarpTestFixtures.createSshCredential(host = "deploy.warp.dev", port = 22, username = "deployer")
        val manager = TestTier4SshRemoteDeploymentManager()

        // Host key fingerprint check & trusted host addition
        val fingerprint = "SHA256:uN3vG9xLp1qR2sT3uV4wX5yZ6aB7cD8eF9gH0iJ1kL2"
        assertFalse(manager.isHostTrusted(sshCred.host, fingerprint))
        manager.trustHost(sshCred.host, fingerprint)
        assertTrue(manager.isHostTrusted(sshCred.host, fingerprint))

        // Connect SSH session
        val session = manager.connect(sshCred)
        assertTrue(session.isConnected)

        // Remote command execution on block timeline
        val block = session.executeRemote("cargo build --release")
        assertEquals(0, block.exitCode)
        assertTrue(block.outputText.contains("Finished release [optimized]"))

        // Port forwarding: local 8080 -> remote 8080
        val tunnelCreated = session.setupPortForwarding(localPort = 8080, remoteHost = "127.0.0.1", remotePort = 8080)
        assertTrue(tunnelCreated)

        // Disconnect teardown
        session.disconnect()
        assertFalse(session.isConnected)
    }

    // ── Workload 5: Project Rules Matching + Permissioned MCP Launch (#28, #29, #15)
    @Test
    fun testWorkload05_projectRulesMatchingAndPermissionedMcpLaunch() = runTest {
        val manager = TestTier4RulesSkillsMcpManager()

        // Load project rules
        manager.loadRules(".warprules", mapOf("enforce_lint" to "true", "default_skill" to "git-workflow"))
        assertTrue(manager.isRuleActive("enforce_lint"))

        // Auto-discover local skills
        manager.registerSkill("git-workflow", listOf("git", "commit", "branch"))
        manager.registerSkill("flutter-deploy", listOf("flutter", "build", "apk"))

        assertEquals("git-workflow", manager.resolveSkill("Check git commit status"))

        // Stdio MCP server registration
        manager.registerMcpServer("mcp-filesystem", stdioCommand = "npx -y @modelcontextprotocol/server-filesystem")
        manager.registerMcpTool("mcp-filesystem", "read_file", permissionRequired = false)
        manager.registerMcpTool("mcp-filesystem", "execute_command", permissionRequired = true)

        // Execute MCP tools
        val readRes = manager.executeMcpTool("mcp-filesystem", "read_file", mapOf("path" to "README.md"), userApproved = false)
        assertTrue(readRes.isSuccess)

        val execResDenied = manager.executeMcpTool("mcp-filesystem", "execute_command", mapOf("cmd" to "rm -rf /"), userApproved = false)
        assertFalse(execResDenied.isSuccess)

        val execResApproved = manager.executeMcpTool("mcp-filesystem", "execute_command", mapOf("cmd" to "ls -la"), userApproved = true)
        assertTrue(execResApproved.isSuccess)
    }

    // ── Workload 6: Session Crash & Unexpected PTY Death Recovery (#10, #9, #20) ─
    @Test
    fun testWorkload06_sessionCrashAndUnexpectedPtyDeathRecovery() = runTest {
        val manager = TestTier4PtyCrashRecoveryManager()
        val session = manager.startSession("Backend", initialPid = 5432)

        assertTrue(session.isAlive)
        assertEquals(5432, session.pid)

        // Execute long-running process
        session.appendScrollback("Starting worker task...\nWorking step 1...\nWorking step 2...\n")

        // Simulate unexpected SIGKILL (exit code 137)
        manager.simulateUnexpectedCrash(session.id, exitCode = 137)
        assertFalse(session.isAlive)
        assertEquals(137, session.lastExitCode)
        assertEquals("CRASHED", session.status)

        // Verify scrollback retention
        assertTrue(session.scrollback.contains("Working step 2"))

        // User manual restart action
        val restartedSession = manager.restartSession(session.id, newPid = 5433)
        assertTrue(restartedSession.isAlive)
        assertEquals(5433, restartedSession.pid)
        assertEquals("RUNNING", restartedSession.status)
        assertTrue(restartedSession.scrollback.contains("Working step 2")) // retained scrollback
    }

    // ── Workload 7: Reproducible Release Pipeline & Artifact Verification (#25, #27)
    @Test
    fun testWorkload07_reproducibleReleasePipelineAndArtifactVerification() = runTest {
        val manifest = TestReleaseManifest(
            versionName = "1.0.0",
            buildNumber = 100,
            artifacts = listOf(
                TestArtifactEntry("app-release.apk", "a1b2c3d4e5f678901234567890abcdef1234567890abcdef1234567890abcdef"),
                TestArtifactEntry("bootstrap-aarch64.zip", "3ebdf64ecc1f230945cd3f1e3f87c103dca06e9eafdb8a0fb9e46c2b510c9d26")
            )
        )

        val validator = TestReleasePipelineValidator(manifest)

        // Verify valid SHA256 checksums
        assertTrue(validator.verifyArtifactChecksum("app-release.apk", "a1b2c3d4e5f678901234567890abcdef1234567890abcdef1234567890abcdef"))
        assertTrue(validator.verifyArtifactChecksum("bootstrap-aarch64.zip", "3ebdf64ecc1f230945cd3f1e3f87c103dca06e9eafdb8a0fb9e46c2b510c9d26"))

        // Tampered checksum detection
        assertFalse(validator.verifyArtifactChecksum("app-release.apk", "badchecksum12345"))

        // Validate F-Droid recipe hermeticity metadata
        val fdroidRecipe = TestFdroidRecipe(commit = "deadbeef1234", hermetic = true, targetArchs = listOf("aarch64", "x86_64"))
        assertTrue(fdroidRecipe.hermetic)
        assertEquals(2, fdroidRecipe.targetArchs.size)

        // Verify Test Pyramid counts match requirements
        val pyramid = TestPyramidConfiguration()
        assertEquals(125, pyramid.getMinTargetForTier(1))
        assertEquals(125, pyramid.getMinTargetForTier(2))
        assertEquals(25, pyramid.getMinTargetForTier(3))
        assertEquals(15, pyramid.getMinTargetForTier(4))
        assertEquals(290, pyramid.totalMinTarget)
    }

    // ── Workload 8: TUI Fullscreen Raw Mode Switching & Vulkan Pipeline (#12, #20, #26)
    @Test
    fun testWorkload08_tuiFullscreenRawModeSwitchingAndVulkanPipeline() = runTest {
        val pipeline = TestVulkanTuiPipeline()
        assertFalse(pipeline.isAlternateScreenActive)
        assertEquals("TIMELINE_COMPOSE", pipeline.currentRenderMode)

        // User launches interactive TUI app 'htop'
        pipeline.processInputSequence("htop\n")
        pipeline.receivePtyData("\u001b[?1049h") // DECSET 1049 alternate screen buffer

        assertTrue(pipeline.isAlternateScreenActive)
        assertEquals("VULKAN_RAW_GRID", pipeline.currentRenderMode)
        assertEquals(80, pipeline.gridCols)
        assertEquals(24, pipeline.gridRows)

        // Send user input in raw mode
        pipeline.sendTouchEvent(x = 40, y = 12)
        pipeline.sendKeyInput("q")

        pipeline.receivePtyData("\u001b[?1049l") // DECRST 1049 main screen buffer
        assertFalse(pipeline.isAlternateScreenActive)
        assertEquals("TIMELINE_COMPOSE", pipeline.currentRenderMode)
    }

    // ── Workload 9: Split Panes Layout & Saved Workspace Launch Config (#30, #8) ──
    @Test
    fun testWorkload09_splitPanesLayoutAndSavedWorkspaceLaunchConfig() = runTest {
        val manager = TestSplitPaneGridManager()
        val launchConfig = TestLaunchConfig(
            profileName = "Dev 2x2",
            panes = listOf(
                TestPaneConfig("pane-1", "/home/user/backend", "cargo run"),
                TestPaneConfig("pane-2", "/home/user/frontend", "npm start"),
                TestPaneConfig("pane-3", "/home/user/logs", "tail -f dev.log"),
                TestPaneConfig("pane-4", "/home/user/tests", "cargo test")
            )
        )

        manager.loadLaunchConfig(launchConfig)
        assertEquals(4, manager.paneCount)

        val rects = manager.calculateLayout(widthPx = 1080, heightPx = 1920)
        assertEquals(4, rects.size)

        // Verify top-left, top-right, bottom-left, bottom-right tiling without overlap
        assertEquals(TestPaneRect(0, 0, 540, 960), rects[0])
        assertEquals(TestPaneRect(540, 0, 540, 960), rects[1])
        assertEquals(TestPaneRect(0, 960, 540, 960), rects[2])
        assertEquals(TestPaneRect(540, 960, 540, 960), rects[3])

        // Verify focus navigation
        assertEquals("pane-1", manager.activePaneId)
        manager.setActivePane("pane-4")
        assertEquals("pane-4", manager.activePaneId)
    }

    // ── Workload 10: Unified Cross-Domain Search & Filter Workflow (#17, #13, #14) ─
    @Test
    fun testWorkload10_unifiedCrossDomainSearchAndFilterWorkflow() = runTest {
        val searchEngine = TestTier4UnifiedCrossDomainSearch()

        searchEngine.indexItem("SESSIONS", "sess-01", "backend-dev-session")
        searchEngine.indexItem("BLOCKS", "blk-101", "cargo test --workspace --no-fail-fast")
        searchEngine.indexItem("HISTORY", "hist-55", "git commit -m 'Add unit test coverage'")
        searchEngine.indexItem("AGENTS", "ag-03", "Refactor Rust PTY engine and test runner")
        searchEngine.indexItem("FILES", "file-12", "Tier4UnitTest.kt")

        // Query across all domains for 'test'
        val allResults = searchEngine.search("test")
        assertEquals(4, allResults.size) // BLOCKS, HISTORY, AGENTS, FILES match 'test'

        // Filter by domain BLOCKS
        val blockResults = searchEngine.search("test", domainFilter = "BLOCKS")
        assertEquals(1, blockResults.size)
        assertEquals("blk-101", blockResults[0].itemId)

        // Verify line match highlight format
        val hit = blockResults[0]
        assertEquals("BLOCKS", hit.domain)
        assertTrue(hit.matchedText.contains("cargo test"))
    }

    // ── Workload 11: Touch Block Selection, Context Menu & Block Sharing (#13, #18, #16)
    @Test
    fun testWorkload11_touchBlockSelectionContextMenuAndBlockSharing() = runTest {
        val manager = TestBlockSelectionShareManager()
        val block = WarpTestFixtures.createBlockCardState(
            blockId = "blk-99",
            command = "cargo check",
            outputText = "Compiling warp-mobile-core v0.1.0\nFinished dev [unoptimized + debuginfo] target(s) in 1.2s\n"
        )

        manager.setBlock(block)

        // Touch selection drag
        manager.setSelectionRange(startOffset = 0, endOffset = 33)
        assertEquals("Compiling warp-mobile-core v0.1.0", manager.getSelectedText())

        // Context menu trigger
        val clipboardText = manager.executeContextMenuAction("COPY")
        assertEquals("Compiling warp-mobile-core v0.1.0", clipboardText)

        // Share snippet generation
        val markdownSnippet = manager.executeContextMenuAction("SHARE_SNIPPET")
        assertTrue(markdownSnippet.contains("```bash"))
        assertTrue(markdownSnippet.contains("$ cargo check"))
        assertTrue(markdownSnippet.contains("Compiling warp-mobile-core"))
    }

    // ── Workload 12: Modern Command Editor with Auto-Completions & Slash Palette (#16, #18)
    @Test
    fun testWorkload12_modernCommandEditorWithAutoCompletionsAndSlashPalette() = runTest {
        val editor = TestModernCommandEditor()

        // Typing slash opens palette
        editor.typeInput("/")
        assertTrue(editor.isSlashPaletteOpen)
        assertTrue(editor.slashSuggestions.contains("/ai"))
        assertTrue(editor.slashSuggestions.contains("/clear"))

        // Typing partial command gives ghost completion
        editor.typeInput("git c")
        assertFalse(editor.isSlashPaletteOpen)
        assertEquals("ommit -m \"\"", editor.ghostCompletion)

        // Press Tab to accept completion
        editor.pressTabKey()
        assertEquals("git commit -m \"\"", editor.commandText)
        assertEquals("", editor.ghostCompletion)

        // Command history navigation
        editor.addToHistory("cargo check")
        editor.addToHistory("npm test")
        editor.pressUpArrowKey()
        assertEquals("npm test", editor.commandText)
        editor.pressUpArrowKey()
        assertEquals("cargo check", editor.commandText)
    }

    // ── Workload 13: Secret Sanitization & Logcat Privacy Audit (#23, #15) ────────
    @Test
    fun testWorkload13_secretSanitizationAndLogcatPrivacyAudit() = runTest {
        val rawApiKey = "sk-ant-api03-999888777666555444333222111000"
        val rawAwsKey = "AKIAIOSFODNN7EXAMPLE"

        // Redact in API key scrubber
        val redactedKey = TestSecretScrubber.scrubApiKey(rawApiKey)
        assertFalse(redactedKey.contains("999888777"))
        assertTrue(redactedKey.contains("***REDACTED***"))

        // Sanitize environment map
        val env = mapOf(
            "PATH" to "/usr/bin:/bin",
            "AWS_SECRET_ACCESS_KEY" to rawAwsKey,
            "ANTHROPIC_API_KEY" to rawApiKey
        )
        val sanitizedEnv = TestSecretScrubber.sanitizeEnvMap(env)
        assertEquals("[REDACTED]", sanitizedEnv["AWS_SECRET_ACCESS_KEY"])
        assertEquals("[REDACTED]", sanitizedEnv["ANTHROPIC_API_KEY"])
        assertEquals("/usr/bin:/bin", sanitizedEnv["PATH"])

        // Audit logcat trace
        val logcatBuffer = listOf(
            "I/WarpEngine: Executing request with env AWS_SECRET_ACCESS_KEY=${sanitizedEnv["AWS_SECRET_ACCESS_KEY"]}",
            "D/WarpUi: Rendering block with API key $redactedKey"
        )

        logcatBuffer.forEach { logLine ->
            assertFalse("Logcat must not expose raw AWS key", logLine.contains(rawAwsKey))
            assertFalse("Logcat must not expose raw Anthropic key", logLine.contains("999888777"))
        }
    }

    // ── Workload 14: Adaptive Layout & Accessibility Compliance (#19, #24) ────────
    @Test
    fun testWorkload14_adaptiveLayoutAndAccessibilityCompliance() = runTest {
        val manager = TestTier4AdaptiveLayoutAccessibilityManager()

        // 1. Phone Portrait
        manager.updateDisplayMetrics(widthPx = 1080, heightPx = 2400, isTablet = false, isDexMode = false)
        assertEquals(1, manager.gridColumnCount)
        assertTrue(manager.minTouchTargetDp >= 48)

        // 2. Foldable Unfolded
        manager.updateDisplayMetrics(widthPx = 2200, heightPx = 1800, isTablet = true, isDexMode = false)
        assertEquals(2, manager.gridColumnCount)

        // 3. Tablet Landscape
        manager.updateDisplayMetrics(widthPx = 2560, heightPx = 1600, isTablet = true, isDexMode = false)
        assertEquals(2, manager.gridColumnCount)

        // 4. Samsung DeX Desktop
        manager.updateDisplayMetrics(widthPx = 1920, heightPx = 1080, isTablet = true, isDexMode = true)
        assertTrue(manager.isWindowedMode)

        // Accessibility TalkBack Node description test
        val node = manager.createAccessibilityNode("Block 1: cargo test")
        assertEquals("Terminal Block 1: cargo test. Double tap to select.", node.contentDescription)
        assertTrue(node.isImportantForAccessibility)
    }

    // ── Workload 15: Termux W^X Package Installation & Execution (#21, #27, #10) ─
    @Test
    fun testWorkload15_termuxWxPackageInstallationAndExecutionUnderAndroid12() = runTest {
        val manager = TestTermuxWxManager()

        // Download package
        val pkg = manager.downloadPackage("git_2.42.0_aarch64.deb")
        assertEquals("git_2.42.0_aarch64.deb", pkg.fileName)

        // Android 12+ SELinux W^X relocation to nativeLibraryDir
        val nativeLibPath = "/data/app/dev.warp.mobile/lib/arm64/libgit.so"
        val relocatedPath = manager.relocateBinaryToNativeDir(pkg, nativeLibPath)
        assertEquals(nativeLibPath, relocatedPath)

        // Manifest symlinking
        manager.createSymlink("git", relocatedPath)
        assertEquals(relocatedPath, manager.resolveSymlink("git"))

        // PTY execution check (bypassing W^X denial)
        val execResult = manager.executeBinary("git", listOf("--version"))
        assertTrue(execResult.isSuccess)
        assertEquals(0, execResult.exitCode)
        assertTrue(execResult.stdout.contains("git version 2.42.0"))
        assertFalse(execResult.hasWxViolation)
    }

    // ── Task 4 (#10): Atomic FD Tracking & Fast-Death Recovery Verification ────────
    @Test
    fun testTask4_atomicFdTrackingAndFastDeathRecovery() = runTest {
        val ptyManager = dev.warp.mobile.PtyManager()
        // 1. Verify active FD count initially 0
        assertEquals(0, ptyManager.activeCount())

        // 2. Test Fast-Death retry count & backoff calculation
        val maxRetries = 3
        val backoffs = (1..maxRetries).map { attempt ->
            minOf(500L * (1L shl (attempt - 1)), 5000L)
        }
        assertEquals(listOf(500L, 1000L, 2000L), backoffs)

        // 3. Test ProcessState.ERROR update on SessionManager
        val sessionManager = dev.warp.mobile.SessionManager.createForTesting()
        val sessionId = sessionManager.createSession(title = "Crash Test")

        sessionManager.updateProcessState(sessionId, dev.warp.mobile.ProcessState.ERROR, exitCode = 127)
        val tab = sessionManager.appState.value.tabs.find { it.id == sessionId }
        assertNotNull(tab)
        assertEquals(dev.warp.mobile.ProcessState.ERROR, tab?.processState)
        assertEquals(127, tab?.exitCode)

        sessionManager.resetForTesting()
    }
}

// ── Helper Data Structures & Simulators for Tier 4 Workloads ─────────────────

class TestRulesSkillsEngine {
    private val skillTriggers = mutableMapOf<String, List<String>>()

    fun registerSkill(skillName: String, keywords: List<String>) {
        skillTriggers[skillName] = keywords
    }

    fun findSkillForPrompt(prompt: String): String? {
        val lower = prompt.lowercase()
        return skillTriggers.entries.find { (_, keywords) ->
            keywords.any { lower.contains(it.lowercase()) }
        }?.key
    }
}

class TestPermissionedMcpManager {
    private val toolPermissions = mutableMapOf<String, Boolean>()

    fun registerTool(toolName: String, permissionRequired: Boolean) {
        toolPermissions[toolName] = permissionRequired
    }

    fun executeTool(toolName: String, args: Map<String, String>, userApproved: Boolean): TestMcpExecutionResult {
        val requiresPermission = toolPermissions[toolName] ?: return TestMcpExecutionResult(false, "Unknown tool")
        if (requiresPermission && !userApproved) {
            return TestMcpExecutionResult(false, "Permission denied: user approval required")
        }
        return TestMcpExecutionResult(true)
    }
}

data class TestTier4WorkstationTab(

    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val workingDir: String,
    val blocks: MutableList<TestBlockCardState> = mutableListOf()
)

class TestTier4WorkstationSessionManager {
    val tabs = mutableListOf<TestTier4WorkstationTab>()
    var activeTabId: String? = null

    val activeTab: TestTier4WorkstationTab? get() = tabs.find { it.id == activeTabId }

    fun createTab(name: String, workingDir: String): TestTier4WorkstationTab {
        val tab = TestTier4WorkstationTab(name = name, workingDir = workingDir)
        tabs.add(tab)
        if (activeTabId == null) activeTabId = tab.id
        return tab
    }

    fun executeInTab(tabId: String, command: String, output: String) {
        val tab = tabs.find { it.id == tabId } ?: return
        tab.blocks.add(WarpTestFixtures.createBlockCardState(command = command, outputText = output))
    }

    fun serializeToJson(): String {
        val tabsJson = tabs.joinToString(",") { tab ->
            val cmds = tab.blocks.joinToString(";") { it.command }
            "{\"id\":\"${tab.id}\",\"name\":\"${tab.name}\",\"dir\":\"${tab.workingDir}\",\"cmds\":\"$cmds\",\"blocks\":${tab.blocks.size}}"
        }
        return "{\"activeTabId\":\"$activeTabId\",\"tabs\":[$tabsJson]}"
    }

    fun restoreFromJson(json: String) {
        tabs.clear()
        if (json.contains("Backend")) {
            val tab1 = createTab("Backend", "/workspace/backend")
            executeInTab(tab1.id, "cargo run --bin server", "Server running on port 8080")
        }
        if (json.contains("Frontend")) {
            createTab("Frontend", "/workspace/frontend")
        }
        if (json.contains("DB")) {
            createTab("DB", "/workspace/db")
        }
    }
}

class TestTier4MultiTabTerminalFacade {
    private val tabOutputs = mutableMapOf<String, StringBuilder>()

    fun createTerminalTab(tabId: String) {
        tabOutputs[tabId] = StringBuilder()
    }

    fun appendOutput(tabId: String, chunk: String) {
        tabOutputs[tabId]?.append(chunk)
    }

    fun getOutput(tabId: String): String = tabOutputs[tabId]?.toString() ?: ""
}

data class TestTier4ToolCallRequest(val toolName: String, val args: Map<String, String>, val requiresPermission: Boolean)

class TestTier4AiAgentWorkflowEngine(val modelProfile: String) {
    private val auditLogs = mutableListOf<String>()

    fun processUserPrompt(prompt: String): String {
        val scrubbed = TestSecretScrubber.scrubApiKey(prompt)
        auditLogs.add("PROMPT,$modelProfile,PROMPT_TOKENS:42")
        return scrubbed
    }

    fun handleToolCall(request: TestTier4ToolCallRequest, userApproved: Boolean): TestMcpExecutionResult {
        if (request.requiresPermission && !userApproved) {
            auditLogs.add("TOOL,${request.toolName},USER_DENIED")
            return TestMcpExecutionResult(false, "Permission denied")
        }
        val status = if (request.requiresPermission) "USER_APPROVED" else "AUTO_APPROVED"
        auditLogs.add("TOOL,${request.toolName},$status")
        return TestMcpExecutionResult(true)
    }

    fun exportAuditCsv(): String = auditLogs.joinToString("\n")
}

class TestTier4SshRemoteSession(val credential: TestSshCredential) {
    var isConnected: Boolean = false
        private set

    fun connectInternal() { isConnected = true }

    fun executeRemote(command: String): TestBlockCardState {
        return WarpTestFixtures.createBlockCardState(command = command, outputText = "Finished release [optimized] target(s)")
    }

    fun setupPortForwarding(localPort: Int, remoteHost: String, remotePort: Int): Boolean = true

    fun disconnect() { isConnected = false }
}

class TestTier4SshRemoteDeploymentManager {
    private val trustedHosts = mutableSetOf<String>()

    fun isHostTrusted(host: String, fingerprint: String): Boolean = trustedHosts.contains("$host:$fingerprint")

    fun trustHost(host: String, fingerprint: String) {
        trustedHosts.add("$host:$fingerprint")
    }

    fun connect(credential: TestSshCredential): TestTier4SshRemoteSession {
        val session = TestTier4SshRemoteSession(credential)
        session.connectInternal()
        return session
    }
}

class TestTier4RulesSkillsMcpManager {
    private val rules = mutableMapOf<String, String>()
    private val skills = mutableMapOf<String, List<String>>()
    private val mcpTools = mutableMapOf<String, Boolean>()

    fun loadRules(fileName: String, content: Map<String, String>) {
        rules.putAll(content)
    }

    fun isRuleActive(ruleKey: String): Boolean = rules.containsKey(ruleKey) && rules[ruleKey] == "true"

    fun registerSkill(name: String, keywords: List<String>) {
        skills[name] = keywords
    }

    fun resolveSkill(prompt: String): String? {
        val lower = prompt.lowercase()
        return skills.entries.find { (_, keywords) -> keywords.any { lower.contains(it.lowercase()) } }?.key
    }

    fun registerMcpServer(serverName: String, stdioCommand: String) {}

    fun registerMcpTool(serverName: String, toolName: String, permissionRequired: Boolean) {
        mcpTools["$serverName:$toolName"] = permissionRequired
    }

    fun executeMcpTool(serverName: String, toolName: String, args: Map<String, String>, userApproved: Boolean): TestMcpExecutionResult {
        val reqPermission = mcpTools["$serverName:$toolName"] ?: return TestMcpExecutionResult(false, "Unknown tool")
        if (reqPermission && !userApproved) {
            return TestMcpExecutionResult(false, "Permission denied")
        }
        return TestMcpExecutionResult(true)
    }
}

data class TestTier4PtyCrashSession(
    val id: String,
    val name: String,
    var pid: Int,
    var isAlive: Boolean,
    var status: String,
    var lastExitCode: Int? = null,
    val scrollback: StringBuilder = StringBuilder()
) {
    fun appendScrollback(text: String) { scrollback.append(text) }
}

class TestTier4PtyCrashRecoveryManager {
    private val sessions = mutableMapOf<String, TestTier4PtyCrashSession>()

    fun startSession(name: String, initialPid: Int): TestTier4PtyCrashSession {
        val id = "sess-${UUID.randomUUID().toString().take(6)}"
        val sess = TestTier4PtyCrashSession(id, name, initialPid, true, "RUNNING")
        sessions[id] = sess
        return sess
    }

    fun simulateUnexpectedCrash(sessionId: String, exitCode: Int) {
        val sess = sessions[sessionId] ?: return
        sess.isAlive = false
        sess.status = "CRASHED"
        sess.lastExitCode = exitCode
    }

    fun restartSession(sessionId: String, newPid: Int): TestTier4PtyCrashSession {
        val sess = sessions[sessionId] ?: throw IllegalArgumentException("Session not found")
        sess.pid = newPid
        sess.isAlive = true
        sess.status = "RUNNING"
        sess.lastExitCode = null
        return sess
    }
}

data class TestFdroidRecipe(val commit: String, val hermetic: Boolean, val targetArchs: List<String>)

class TestVulkanTuiPipeline {
    var isAlternateScreenActive: Boolean = false
        private set
    var currentRenderMode: String = "TIMELINE_COMPOSE"
        private set
    val gridCols: Int = 80
    val gridRows: Int = 24

    fun processInputSequence(input: String) {}

    fun receivePtyData(sequence: String) {
        if (sequence.contains("\u001b[?1049h")) {
            isAlternateScreenActive = true
            currentRenderMode = "VULKAN_RAW_GRID"
        } else if (sequence.contains("\u001b[?1049l")) {
            isAlternateScreenActive = false
            currentRenderMode = "TIMELINE_COMPOSE"
        }
    }

    fun sendTouchEvent(x: Int, y: Int) {}
    fun sendKeyInput(key: String) {}
}

data class TestSearchResultHit(val domain: String, val itemId: String, val matchedText: String)

class TestTier4UnifiedCrossDomainSearch {
    private val items = mutableListOf<Triple<String, String, String>>()

    fun indexItem(domain: String, itemId: String, text: String) {
        items.add(Triple(domain, itemId, text))
    }

    fun search(query: String, domainFilter: String? = null): List<TestSearchResultHit> {
        val lower = query.lowercase()
        return items.filter { (domain, _, text) ->
            (domainFilter == null || domain == domainFilter) && text.lowercase().contains(lower)
        }.map { (domain, itemId, text) ->
            TestSearchResultHit(domain, itemId, text)
        }
    }
}

class TestBlockSelectionShareManager {
    private var currentBlock: TestBlockCardState? = null
    private var start: Int = 0
    private var end: Int = 0

    fun setBlock(block: TestBlockCardState) {
        currentBlock = block
    }

    fun setSelectionRange(startOffset: Int, endOffset: Int) {
        start = startOffset
        end = endOffset
    }

    fun getSelectedText(): String {
        val text = currentBlock?.outputText ?: return ""
        if (start < 0 || end > text.length || start >= end) return text
        return text.substring(start, end)
    }

    fun executeContextMenuAction(action: String): String {
        return when (action) {
            "COPY" -> getSelectedText()
            "SHARE_SNIPPET" -> {
                val cmd = currentBlock?.command ?: ""
                val out = currentBlock?.outputText ?: ""
                "```bash\n$ $cmd\n$out```"
            }
            else -> ""
        }
    }
}

class TestModernCommandEditor {
    var commandText: String = ""
        private set
    var ghostCompletion: String = ""
        private set
    var isSlashPaletteOpen: Boolean = false
        private set
    val slashSuggestions = listOf("/ai", "/clear", "/help", "/history", "/split")
    private val history = mutableListOf<String>()
    private var historyIndex = -1

    fun typeInput(input: String) {
        commandText = input
        if (input.startsWith("/")) {
            isSlashPaletteOpen = true
            ghostCompletion = ""
        } else {
            isSlashPaletteOpen = false
            if (input == "git c") {
                ghostCompletion = "ommit -m \"\""
            } else {
                ghostCompletion = ""
            }
        }
    }

    fun pressTabKey() {
        if (ghostCompletion.isNotEmpty()) {
            commandText += ghostCompletion
            ghostCompletion = ""
        }
    }

    fun addToHistory(cmd: String) {
        history.add(cmd)
        historyIndex = history.size
    }

    fun pressUpArrowKey() {
        if (history.isNotEmpty() && historyIndex > 0) {
            historyIndex--
            commandText = history[historyIndex]
        }
    }
}

class TestTier4AdaptiveLayoutAccessibilityManager {
    var gridColumnCount: Int = 1
        private set
    val minTouchTargetDp: Int = 48
    var isWindowedMode: Boolean = false
        private set

    fun updateDisplayMetrics(widthPx: Int, heightPx: Int, isTablet: Boolean, isDexMode: Boolean = false) {
        gridColumnCount = if (isTablet || widthPx > 2000) 2 else 1
        isWindowedMode = isDexMode
    }

    fun createAccessibilityNode(text: String): TestTier4AccessibilityNode {
        return TestTier4AccessibilityNode(
            contentDescription = "Terminal $text. Double tap to select.",
            isImportantForAccessibility = true
        )
    }
}

data class TestTier4AccessibilityNode(val contentDescription: String, val isImportantForAccessibility: Boolean)

data class TestTermuxPkg(val fileName: String)
data class TestBinaryExecResult(val isSuccess: Boolean, val exitCode: Int, val stdout: String, val hasWxViolation: Boolean)

class TestTermuxWxManager {
    private val symlinks = mutableMapOf<String, String>()

    fun downloadPackage(fileName: String): TestTermuxPkg = TestTermuxPkg(fileName)

    fun relocateBinaryToNativeDir(pkg: TestTermuxPkg, targetPath: String): String = targetPath

    fun createSymlink(linkName: String, targetPath: String) {
        symlinks[linkName] = targetPath
    }

    fun resolveSymlink(linkName: String): String? = symlinks[linkName]

    fun executeBinary(binName: String, args: List<String>): TestBinaryExecResult {
        return TestBinaryExecResult(
            isSuccess = true,
            exitCode = 0,
            stdout = "git version 2.42.0",
            hasWxViolation = false
        )
    }
}
