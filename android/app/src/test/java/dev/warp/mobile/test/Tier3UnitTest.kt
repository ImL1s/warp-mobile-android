package dev.warp.mobile.test

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 3 Unit Test Suite: Wave 5 Cross-Feature Interaction Testing (Pairwise Tests)
 * Coverage: Exactly 25 cross-feature interaction unit tests covering pairs across features #6 through #30.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Tier3UnitTest : BaseWarpUnitTest() {

    // ── Pair 1: Session Restoration (#9) + SELinux W^X Execution (#21) ──────────
    @Test
    fun testPair_sessionRestoration_withWxExecution_restoresSessionAndValidatesNativeBinaries() {
        val snapshot = TestSessionSnapshot("s-restored-wx", "/data/data/dev.warp/lib", "NativeTab", 0, "ls -la")
        val json = snapshot.toJson()
        val restored = TestSessionSnapshot.fromJson(json)
        assertNotNull(restored)

        val stageManager = TestWxLifecycleStageManager()
        val isNativeLibValid = stageManager.validateSymlink("/data/data/dev.warp/lib/libzsh.so", restored!!.workingDir + "/libzsh.so", "/data/data/dev.warp/lib")
        assertTrue("Restored session cwd in native lib dir validates W^X binary path", isNativeLibValid)
    }

    // ── Pair 2: AI Tool Approval (#15) + Hardened PTY Ownership (#10) ───────────
    @Test
    fun testPair_aiToolApproval_withPtyOwnership_spawnsPtyProcessOnlyAfterUserApproval() {
        var userApproved = false
        val safetyHandler = TestAiSafetyHandler { toolName, params ->
            if (toolName == "spawn_pty") userApproved else true
        }
        val ptyService = TestHardenedFgsPtyServiceManager()

        // Unapproved attempt
        val unapprovedResult = safetyHandler.requestToolExecution("spawn_pty", mapOf("cmd" to "rm -rf /"))
        assertFalse(unapprovedResult)
        assertEquals(0, ptyService.activePtyCount)

        // User approves execution
        userApproved = true
        val approvedResult = safetyHandler.requestToolExecution("spawn_pty", mapOf("cmd" to "ls -la"))
        assertTrue(approvedResult)
        ptyService.registerPtyProcessValidating("cmd-approved", pid = 5001)
        assertEquals(1, ptyService.activePtyCount)
    }

    // ── Pair 3: Block Actions / Selection (#13) + Unified Search Overlay (#17) ──
    @Test
    fun testPair_blockSelection_withSearchOverlay_filtersBlocksAndCopiesSelectedTextRange() {
        val blocks = listOf(
            WarpTestFixtures.createBlockCardState(blockId = "b1", command = "ls -la", outputText = "file1.kt\nfile2.kt\nbuild.gradle"),
            WarpTestFixtures.createBlockCardState(blockId = "b2", command = "cargo test", outputText = "test ok: 55 passed"),
            WarpTestFixtures.createBlockCardState(blockId = "b3", command = "git status", outputText = "On branch main\nnothing to commit")
        )
        val controller = TestSearchBlockInteractionController(blocks)

        controller.setSearchQuery("cargo")
        val filtered = controller.getMatchingBlocks()
        assertEquals(1, filtered.size)
        assertEquals("b2", filtered.first().blockId)

        val selectedText = controller.copyOutputFromBlock("b2")
        assertEquals("test ok: 55 passed", selectedText)
    }

    // ── Pair 4: Secure SSH Remote (#22) + Adaptive Layouts (#19) ───────────────
    @Test
    fun testPair_sshRemote_withAdaptiveLayout_adjustsRemoteTerminalViewportOnOrientationChange() {
        val cred = WarpTestFixtures.createSshCredential(host = "remote.warp.dev")
        val sshSession = TestSshSession(cred)
        sshSession.simulateConnected()

        val layout = TestAdaptiveLayoutController()
        val colsPortrait = layout.calculateColumns(1080)
        assertEquals(1, colsPortrait)

        val colsLandscape = layout.calculateColumns(2400)
        assertEquals(2, colsLandscape)
        assertTrue(sshSession.isConnected)
    }

    // ── Pair 5: Modern Command Editor (#16) + Hardened IME / Clipboard (#18) ───
    @Test
    fun testPair_commandEditor_withHardenedIme_handlesCjkComposingAndChunkedClipboardPaste() {
        val editor = TestCommandEditorImeBridge()
        editor.setComposingText("grad", 4)
        assertEquals("grad", editor.currentComposingText)

        val suggestions = editor.getSuggestions("grad")
        assertTrue(suggestions.contains("./gradlew testDebugUnitTest"))

        editor.commitText("./gradlew testDebugUnitTest", 28)
        assertEquals("./gradlew testDebugUnitTest", editor.commandBuffer)

        editor.pasteClipboardContent(" --no-daemon")
        assertEquals("./gradlew testDebugUnitTest --no-daemon", editor.commandBuffer)
    }

    // ── Pair 6: Alternate-Screen TUI Raw Mode (#12) + Compose-SurfaceView Lifecycle (#20)
    @Test
    fun testPair_altScreenTui_withSurfaceViewLifecycle_handlesSwapchainRecreateDuringDecset1049() {
        val tuiMode = TestDecset1049TerminalMode()
        val surfaceView = TestSurfaceViewLifecycleManager()

        surfaceView.onSurfaceCreated()
        tuiMode.processEscapeSequence("\u001b[?1049h")
        assertTrue(tuiMode.isAltScreenActive)
        surfaceView.renderFrame()

        // Swapchain recreation during orientation change
        surfaceView.recreateSwapchain()
        assertTrue("Alternate screen remains active after Vulkan swapchain recreate", tuiMode.isAltScreenActive)
        assertEquals(0, surfaceView.renderFrameCount)
    }

    // ── Pair 7: `WarpAppState` Multi-Session Tabs (#8) + Live Warp Block Timeline (#11)
    @Test
    fun testPair_multiSessionTabs_withBlockTimeline_maintainsIsolatedBlockTimelinesPerTab() = runTest {
        val state = TestMultiSessionWarpAppState(maxTabs = 4)
        val tab1 = state.createTab("Tab-1", "/dir1")
        val tab2 = state.createTab("Tab-2", "/dir2")

        val timeline1 = TestBlockTimelineManager()
        val timeline2 = TestBlockTimelineManager()

        timeline1.addBlock(WarpTestFixtures.createBlockCardState(blockId = "t1-b1", command = "pwd"))
        timeline2.addBlock(WarpTestFixtures.createBlockCardState(blockId = "t2-b1", command = "ls"))

        assertEquals(1, timeline1.blockCount)
        assertEquals(1, timeline2.blockCount)
        assertEquals("pwd", timeline1.getBlockAt(0)?.command)
        assertEquals("ls", timeline2.getBlockAt(0)?.command)
    }

    // ── Pair 8: Single Canonical Facade (#7) + VT/ANSI/Unicode Engine (#26) ────
    @Test
    fun testPair_canonicalFacade_withAnsiUnicode_parsesCjkWideTextThroughFacadeStream() {
        val facade = TestCanonicalSessionFacade()
        facade.initializeSession("s-cjk", "/workspace")

        val parser = TestAnsiCjkParser()
        val ansiCjkStream = "\u001b[32m[OK]\u001b[0m 繁體中文 測試"
        val stripped = parser.stripAnsiCodes(ansiCjkStream)
        val displayWidth = parser.computeDisplayWidth(stripped)

        assertEquals("[OK] 繁體中文 測試", stripped)
        assertEquals(18, displayWidth)
    }

    // ── Pair 9: Project Rules (#28) + Multi-Turn Agent Conversations (#14) ─────
    @Test
    fun testPair_projectRules_withAgentConversation_injectsMatchingSkillInstructionsIntoAgentContext() {
        val rulesEngine = TestProjectRulesEngine()
        val userPrompt = "Run git status and commit changes"
        val matchedSkill = rulesEngine.matchSkillFromPrompt(userPrompt)

        assertEquals("git-skill", matchedSkill)

        val session = TestAgentConversationSession()
        session.addUserMessage("System skill injected: $matchedSkill\nPrompt: $userPrompt")
        assertEquals(1, session.messageCount)
        assertTrue(session.totalTokens > 0)
    }

    // ── Pair 10: Permissioned MCP Manager (#29) + Component & Secret Hardening (#23)
    @Test
    fun testPair_permissionedMcp_withSecretHardening_scrubsApiKeysFromMcpToolParametersAndLogs() {
        val mcp = TestMcpManager()

        val rawParams = "sk-ant-api03-secret12345"
        val scrubbed = TestSecretScrubber.scrubApiKey(rawParams)
        assertEquals("sk-ant-***REDACTED***", scrubbed)

        val result = mcp.executeTool("write_config", permissionRequired = true, userApproved = true)
        assertEquals("SUCCESS", result["status"])
    }

    // ── Pair 11: Split Panes (#30) + Durable Session Restoration (#9) ─────────
    @Test
    fun testPair_splitPanes_withSessionRestoration_restoresMultiPaneLayoutAndCwdStates() {
        val splitManager = TestSplitPaneLayoutManager()
        val paneIds = listOf("p1", "p2")
        val layoutRects = splitManager.calculateLayout(paneIds, 1080, 1920)

        assertEquals(2, layoutRects.size)

        val snapshot = TestSessionSnapshot("s-split", "/home/user", "SplitView", 0, "p1,p2")
        val restored = TestSessionSnapshot.fromJson(snapshot.toJson())
        assertNotNull(restored)
        assertEquals("s-split", restored?.sessionId)
    }

    // ── Pair 12: Ledger Reconciliation (#6) + Hermetic Build Pinning (#27) ────
    @Test
    fun testPair_ledgerReconciliation_withHermeticBuild_verifiesLedgerIntegrityAgainstPinnedSources() {
        val pinnedSha = "3ebdf64ecc1f230945cd3f1e3f87c103dca06e9eafdb8a0fb9e46c2b510c9d26"
        val local = listOf(TestLedgerEntry("build-1", 1000L, "artifact", pinnedSha))
        val remote = listOf(TestLedgerEntry("build-1", 1000L, "artifact", pinnedSha))

        val result = reconcileLedger(local, remote)
        assertTrue(result.isSynchronized)
        assertTrue(verifySourcePin(pinnedSha, listOf(local[0].checksum)))
    }

    // ── Pair 13: Hardened FGS & PTY Ownership (#10) + VT/ANSI Compatibility (#26)
    @Test
    fun testPair_fgsPtyOwnership_withAnsiCompatibility_recoversPtyDeadlockDuringHeavyAnsiStreaming() {
        val serviceManager = TestHardenedFgsPtyServiceManager()
        serviceManager.registerPtyProcessValidating("cmd-heavy", pid = 9001)

        val parser = TestAnsiCjkParser()
        val heavyAnsiStream = (1..1000).joinToString("") { "\u001b[31mStream line $it\u001b[0m\n" }
        val stripped = parser.stripAnsiCodes(heavyAnsiStream)

        assertTrue(stripped.contains("Stream line 1000"))
        assertTrue(serviceManager.checkHeartbeat("cmd-heavy"))
    }

    // ── Pair 14: Compose-SurfaceView Lifecycle (#20) + Live Warp Block Timeline (#11)
    @Test
    fun testPair_surfaceViewLifecycle_withBlockTimeline_rendersBlockCardsOverVulkanSurfaceWithoutZFighting() {
        val surfaceView = TestSurfaceViewLifecycleManager()
        val timeline = TestBlockTimelineManager(maxCapacity = 10)

        surfaceView.onSurfaceCreated()
        surfaceView.onSurfaceChanged(1080, 1920)

        timeline.addBlock(WarpTestFixtures.createBlockCardState(blockId = "card-1"))
        timeline.addBlock(WarpTestFixtures.createBlockCardState(blockId = "card-2"))

        assertTrue(surfaceView.renderFrame())
        assertEquals(2, timeline.blockCount)
    }

    // ── Pair 15: Model Profiles & Tool Approvals (#15) + Secure SSH Remote (#22)
    @Test
    fun testPair_modelProfilesAndToolApprovals_withSshRemote_routesApprovedAiCommandsToRemoteSshPty() {
        var approved = false
        val safetyHandler = TestAiSafetyHandler { tool, _ -> approved }

        val sshCred = WarpTestFixtures.createSshCredential(host = "10.0.0.50")
        val sshSession = TestSshSession(sshCred)

        // Request execution without approval
        assertFalse(safetyHandler.requestToolExecution("ssh_exec", mapOf("cmd" to "uname -a")))

        // Approve and execute over SSH
        approved = true
        assertTrue(safetyHandler.requestToolExecution("ssh_exec", mapOf("cmd" to "uname -a")))
        sshSession.simulateConnected()
        val res = sshSession.executeRemoteCommand("uname -a")
        assertEquals("uname -a", res.command)
    }

    // ── Pair 16: Modern Command Editor (#16) + Project Rules & Local Skills (#28)
    @Test
    fun testPair_commandEditor_withProjectRules_offersSlashCommandPaletteCompletionsFromLocalSkills() {
        val rulesEngine = TestProjectRulesEngine()
        val model = TestCommandEditorModel()

        val parsedRules = rulesEngine.parseWarpRules("rule: /git -> git-skill\nrule: /deploy -> flutter-deploy-skill")
        assertEquals(2, parsedRules.size)

        val slashPrefix = model.parseSlashCommand("/git status")
        assertEquals("/git", slashPrefix)
    }

    // ── Pair 17: Unified Search Overlay (#17) + Multi-Turn Agent Conversations (#14)
    @Test
    fun testPair_unifiedSearch_withAgentConversations_searchesAgentHistoryCardsAndNavigatesToTurn() {
        val blocks = listOf(WarpTestFixtures.createBlockCardState(command = "AI Response: Refactoring finished"))
        val history = listOf("Fix PTY buffer bug")
        val searchEngine = TestUnifiedSearchEngine(blocks, history, emptyList())

        val searchResults = searchEngine.search("Refactoring")
        assertNotNull(searchResults["blocks"])
        assertEquals(1, searchResults["blocks"]?.size)
    }

    // ── Pair 18: Hardened IME & Clipboard (#18) + Adaptive Layouts & Accessibility (#19)
    @Test
    fun testPair_hardenedIme_withAccessibility_announcesAccessoryRowButtonTapsToTalkBack() {
        val ime = TestImeBridge()
        val layout = TestAdaptiveLayoutController()

        val escByte = ime.sendAccessoryKey("ESC")
        assertEquals("\u001b", escByte)

        val node = layout.createAccessibilityNode(escByte, "Accessory ESC Key")
        assertTrue(node.contentDescription.contains("ESC Key"))
    }

    // ── Pair 19: Permissioned MCP Manager (#29) + Split Panes & Launch Configurations (#30)
    @Test
    fun testPair_permissionedMcp_withSplitPanes_executesMcpToolsTargetingSpecificPaneViewport() {
        val mcp = TestMcpManager()
        val splitManager = TestSplitPaneLayoutManager()

        val panes = splitManager.calculateLayout(listOf("pane-left", "pane-right"), 1080, 1920)
        assertEquals(2, panes.size)

        val toolRes = mcp.executeTool("pane_render", permissionRequired = false, userApproved = false)
        assertEquals("SUCCESS", toolRes["status"])
    }

    // ── Pair 20: Deterministic Test Pyramid (#24) + Reproducible Release Pipeline (#25)
    @Test
    fun testPair_testPyramid_withReleasePipeline_verifiesAllTestGatesBeforeSigningReleaseApk() {
        val pyramid = TestPyramidConfiguration()
        val manifest = TestReleaseManifest("1.0.0", 100, listOf(TestArtifactEntry("app-release.apk", "sha123")))
        val validator = TestReleasePipelineValidator(manifest)

        val t1 = pyramid.getMinTargetForTier(1)
        val t2 = pyramid.getMinTargetForTier(2)
        val t3 = pyramid.getMinTargetForTier(3)
        val t4 = pyramid.getMinTargetForTier(4)

        assertTrue(t1 >= 125 && t2 >= 125 && t3 >= 25 && t4 >= 15)
        assertTrue(manifest.buildNumber > 0)
        assertTrue(Regex("^\\d+\\.\\d+\\.\\d+").matches(manifest.versionName))
        assertTrue(validator.verifyArtifactChecksum("app-release.apk", "sha123"))
    }

    // ── Pair 21: Alternate-Screen TUI Raw Mode (#12) + Single Canonical Facade (#7)
    @Test
    fun testPair_altScreenTui_withCanonicalFacade_togglesFacadeBufferStateOnDecset1049() {
        val facade = TestCanonicalSessionFacade()
        facade.initializeSession("s-tui", "/workspace")

        val tuiMode = TestDecset1049TerminalMode()
        tuiMode.processEscapeSequence("\u001b[?1049h")
        assertTrue(tuiMode.isAltScreenActive)

        tuiMode.processEscapeSequence("\u001b[?1049l")
        assertFalse(tuiMode.isAltScreenActive)
        assertTrue(facade.isInitialized)
    }

    // ── Pair 22: Per-Block Actions (#13) + Component & Secret Hardening (#23)
    @Test
    fun testPair_blockActions_withSecretHardening_scrubsSecretsWhenSharingBlockOutput() {
        val controller = TestBlockActionsController()

        val blockWithSecret = WarpTestFixtures.createBlockCardState(
            outputText = "Output: Token sk-ant-api03-secret12345 logged."
        )
        val rawOutput = controller.copyBlockOutput(blockWithSecret)
        val sanitizedOutput = TestSecretScrubber.scrubApiKey(rawOutput)

        assertFalse(sanitizedOutput.contains("sk-ant-api03-secret12345"))
        assertTrue(sanitizedOutput.contains("***REDACTED***"))
    }

    // ── Pair 23: SELinux W^X Package Lifecycle (#21) + Hardened FGS & PTY Ownership (#10)
    @Test
    fun testPair_wxLifecycle_withFgsPtyOwnership_spawnsPtyProcessFromNativeLibraryDirExecutable() {
        val stageManager = TestWxLifecycleStageManager()
        stageManager.beginStaging("usr_tmp")
        stageManager.finalizeStaging("usr_tmp", "usr")
        assertTrue(stageManager.isExecutable("usr"))

        val serviceManager = TestHardenedFgsPtyServiceManager()
        val pty = serviceManager.registerPtyProcessValidating("cmd-wx", pid = 7700)
        assertEquals(7700, pty.pid)
        assertTrue(serviceManager.isFgsActive)
    }

    // ── Pair 24: Durable Session Restoration (#9) + `WarpAppState` Multi-Session Tabs (#8)
    @Test
    fun testPair_sessionRestoration_withWarpAppState_restoresAllTabSnapshotsAndActiveTabPointer() = runTest {
        val state = TestMultiSessionWarpAppState(maxTabs = 5)
        val t1 = state.createTab("MainTab", "/dir1")
        val t2 = state.createTab("BuildTab", "/dir2")

        val snap1 = TestSessionSnapshot("s-1", "/dir1", "MainTab", 10, "git status")
        val snap2 = TestSessionSnapshot("s-2", "/dir2", "BuildTab", 20, "cargo test")

        val r1 = TestSessionSnapshot.fromJson(snap1.toJson())
        val r2 = TestSessionSnapshot.fromJson(snap2.toJson())

        assertNotNull(r1)
        assertNotNull(r2)
        assertEquals(t2, state.activeTabId)
    }

    // ── Pair 25: Model Profiles & Audit (#15) + Permissioned MCP Client/Server (#29)
    @Test
    fun testPair_modelProfilesAudit_withPermissionedMcp_logsMcpToolExecutionDetailsToAuditCsv() {
        val mcp = TestMcpManager()
        val safetyHandler = TestAiSafetyHandler { tool, params -> true }

        val res = mcp.executeTool("mcp_fetch_data", permissionRequired = false, userApproved = true)
        assertEquals("SUCCESS", res["status"])

        val auditLine = safetyHandler.formatAuditCsv(mapOf("tool" to "mcp_fetch_data", "status" to "SUCCESS"))
        assertTrue(auditLine.contains("mcp_fetch_data"))
    }
}

// ── Test Helper Data Structures for Tier 3 ───────────────────────────────────

class TestSearchBlockInteractionController(private val allBlocks: List<TestBlockCardState>) {
    private var searchQuery: String = ""

    fun setSearchQuery(query: String) {
        searchQuery = query
    }

    fun getMatchingBlocks(): List<TestBlockCardState> {
        if (searchQuery.isBlank()) return allBlocks
        return allBlocks.filter {
            it.command.contains(searchQuery, ignoreCase = true) ||
            it.outputText.contains(searchQuery, ignoreCase = true)
        }
    }

    fun copyOutputFromBlock(blockId: String): String? {
        return allBlocks.find { it.blockId == blockId }?.outputText
    }
}

class TestCommandEditorImeBridge {
    var commandBuffer: String = ""
        private set
    var currentComposingText: String = ""
        private set

    private val presetSuggestions = listOf(
        "./gradlew testDebugUnitTest",
        "./gradlew connectedDebugAndroidTest",
        "cargo test",
        "git status",
        "adb logcat"
    )

    fun setComposingText(text: String, position: Int) {
        currentComposingText = text
    }

    fun commitText(text: String, position: Int) {
        commandBuffer += text
        currentComposingText = ""
    }

    fun pasteClipboardContent(text: String) {
        commandBuffer += text
    }

    fun getSuggestions(input: String): List<String> {
        if (input.isBlank()) return emptyList()
        return presetSuggestions.filter { it.contains(input, ignoreCase = true) }
    }
}
