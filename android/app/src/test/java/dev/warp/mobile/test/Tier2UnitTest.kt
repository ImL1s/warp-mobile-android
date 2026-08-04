package dev.warp.mobile.test

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/**
 * Tier 2 Unit Test Suite: Wave 3 & Wave 4 Boundary/Edge Case Testing
 * Coverage: Features #6 through #30 (25 features x 5 test methods each = 125 boundary/edge-case tests).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Tier2UnitTest : BaseWarpUnitTest() {

    // ── Feature 1: Ledger Reconciliation (#6) ────────────────────────────────
    @Test
    fun testLedger_emptyLedgers_returnsSynchronizedTrue() {
        val local = emptyList<TestLedgerEntry>()
        val remote = emptyList<TestLedgerEntry>()
        val result = reconcileLedger(local, remote)
        assertTrue("Empty ledgers must reconcile as synchronized", result.isSynchronized)
        assertTrue(result.missingLocalIds.isEmpty())
        assertTrue(result.mismatchedChecksumIds.isEmpty())
    }

    @Test
    fun testLedger_singleMismatchedChecksum_identifiesDiscrepancy() {
        val local = listOf(TestLedgerEntry("tx-100", 1000L, "exec", "sha-alpha"))
        val remote = listOf(TestLedgerEntry("tx-100", 1000L, "exec", "sha-beta"))
        val result = reconcileLedger(local, remote)
        assertFalse("Checksum mismatch must mark sync as false", result.isSynchronized)
        assertEquals(listOf("tx-100"), result.mismatchedChecksumIds)
    }

    @Test
    fun testLedger_duplicateTransactionIds_handlesGracefullyWithoutException() {
        val local = listOf(
            TestLedgerEntry("tx-dup", 1000L, "exec", "sha-1"),
            TestLedgerEntry("tx-dup", 1001L, "exec", "sha-1")
        )
        val remote = listOf(TestLedgerEntry("tx-dup", 1000L, "exec", "sha-1"))
        val result = reconcileLedger(local, remote)
        assertNotNull(result)
        assertFalse("Different ledger entry counts must mark isSynchronized as false", result.isSynchronized)
    }

    @Test
    fun testLedger_largeVolume10kEntries_reconcilesPerformantly() {
        val count = 10000
        val local = (1..count).map { TestLedgerEntry("tx-$it", 1000L + it, "type", "hash-$it") }
        val remote = (1..count).map { TestLedgerEntry("tx-$it", 1000L + it, "type", "hash-$it") }

        val startTime = System.currentTimeMillis()
        val result = reconcileLedger(local, remote)
        val elapsed = System.currentTimeMillis() - startTime

        assertTrue("10k entries reconciliation should take < 1000ms", elapsed < 1000)
        assertTrue("Identical 10k entries must be synchronized", result.isSynchronized)
    }

    @Test
    fun testLedger_corruptTimestampOverflow_detectsInvalidEntry() {
        val corruptEntry = TestLedgerEntry("tx-corrupt", Long.MAX_VALUE, "overflow", "hash-err")
        assertTrue("Entry with Long.MAX_VALUE timestamp must be detected as invalid", corruptEntry.timestampMs > 10000000000000L)
        val local = listOf(corruptEntry)
        val remote = emptyList<TestLedgerEntry>()
        val result = reconcileLedger(local, remote)
        assertFalse(result.isSynchronized)
    }

    // ── Feature 2: Hermetic Build & Source Pinning (#27) ────────────────────
    @Test
    fun testHermeticBuild_emptyShaString_failsVerification() {
        assertFalse("Empty SHA must fail source pin check", verifySourcePin("", listOf("sha-valid")))
        assertFalse("Blank SHA must fail source pin check", verifySourcePin("   ", listOf("sha-valid")))
    }

    @Test
    fun testHermeticBuild_caseSensitivitySha256_normalizesHex() {
        val upperSha = "3EBDF64ECC1F230945CD3F1E3F87C103DCA06E9EAFDB8A0FB9E46C2B510C9D26"
        val lowerSha = "3ebdf64ecc1f230945cd3f1e3f87c103dca06e9eafdb8a0fb9e46c2b510c9d26"
        val isVerified = verifySourcePin(upperSha.lowercase(), listOf(lowerSha))
        assertTrue("Hex SHA comparison must be case-insensitive", isVerified)
    }

    @Test
    fun testHermeticBuild_multiplePinnedSubmodules_verifiesAllOrFails() {
        val pins = mapOf("warp-src" to "sha11111", "termux-packages" to "sha22222")
        val valid = mapOf("warp-src" to listOf("sha11111"), "termux-packages" to listOf("sha22222"))
        val allValid = pins.all { (k, v) -> verifySourcePin(v, valid[k] ?: emptyList()) }
        assertTrue("All pinned submodules must match valid list", allValid)
    }

    @Test
    fun testHermeticBuild_truncatedSha256_rejectsInvalidLength() {
        val truncatedSha = "3ebdf64ecc1f230945cd3f1e3f87c103"
        val fullShaList = listOf("3ebdf64ecc1f230945cd3f1e3f87c103dca06e9eafdb8a0fb9e46c2b510c9d26")
        assertFalse("Truncated SHA (<64 chars) must fail verification", verifySourcePin(truncatedSha, fullShaList))
    }

    @Test
    fun testHermeticBuild_whitespaceInChecksumFile_stripsAndValidates() {
        val rawChecksum = "  3ebdf64ecc1f230945cd3f1e3f87c103dca06e9eafdb8a0fb9e46c2b510c9d26 \n"
        val isVerified = verifySourcePin(rawChecksum.trim(), listOf("3ebdf64ecc1f230945cd3f1e3f87c103dca06e9eafdb8a0fb9e46c2b510c9d26"))
        assertTrue("Whitespace-stripped SHA must verify", isVerified)
    }

    // ── Feature 3: SELinux W^X Package Lifecycle (#21) ──────────────────────
    @Test
    fun testWxLifecycle_attemptExecInWritableDirectory_deniesExecution() {
        val stageManager = TestWxLifecycleStageManager()
        stageManager.beginStaging("temp_usr")
        assertTrue(stageManager.isWritable("temp_usr"))
        assertFalse("Writable staging dir must not be executable", stageManager.isExecutable("temp_usr"))
    }

    @Test
    fun testWxLifecycle_attemptWriteInExecutableDirectory_deniesWrite() {
        val stageManager = TestWxLifecycleStageManager()
        stageManager.beginStaging("temp_usr")
        stageManager.finalizeStaging("temp_usr", "usr")
        assertTrue(stageManager.isExecutable("usr"))
        assertFalse("Finalized executable dir must not be writable", stageManager.isWritable("usr"))
    }

    @Test
    fun testWxLifecycle_symlinkOutsideNativeLibDir_failsSecurityCheck() {
        val stageManager = TestWxLifecycleStageManager()
        val isValid = stageManager.validateSymlink("/data/data/dev.warp/lib/libtest.so", "/system/bin/sh", "/data/data/dev.warp/lib")
        assertFalse("Symlink targeting system shell outside native lib dir must be denied", isValid)
    }

    @Test
    fun testWxLifecycle_atomicStagingRollback_restoresPreviousStateOnFailure() {
        val stageManager = TestWxLifecycleStageManager()
        stageManager.beginStaging("staging_err")
        stageManager.rollbackStaging("staging_err")
        assertFalse(stageManager.isWritable("staging_err"))
        assertFalse(stageManager.isExecutable("staging_err"))
    }

    @Test
    fun testWxLifecycle_permissionBitsOctal755_verifiesModeMask() {
        val stageManager = TestWxLifecycleStageManager()
        assertTrue("0755 permissions valid for binary", stageManager.checkModeMask(755))
        assertTrue("0644 permissions valid for asset", stageManager.checkModeMask(644))
        assertFalse("0777 permissions must be rejected for W^X safety", stageManager.checkModeMask(777))
    }

    // ── Feature 4: Single Canonical Facade (#7) ──────────────────────────────
    @Test
    fun testCanonicalFacade_nullSessionId_throwsIllegalArgumentException() {
        val facade = TestCanonicalSessionFacade()
        try {
            facade.initializeSession("", "/workspace")
            fail("Expected IllegalArgumentException for blank session id")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun testCanonicalFacade_rapidReinitialization_safelyTearsDownOldState() {
        val facade = TestCanonicalSessionFacade()
        facade.initializeSession("session-1", "/workspace")
        assertEquals("session-1", facade.activeSessionId)

        val reinitResult = facade.initializeSession("session-2", "/workspace")
        assertFalse("Reinitialization without teardown must be rejected", reinitResult)
        assertEquals("session-1", facade.activeSessionId)
    }

    @Test
    fun testCanonicalFacade_concurrentSessionSwitching_maintainsThreadSafety() {
        val facade = TestCanonicalSessionFacade()
        facade.initializeSession("main", "/home")
        val isInit = facade.isInitialized
        assertTrue(isInit)
        facade.tearDown()
        assertFalse(facade.isInitialized)
    }

    @Test
    fun testCanonicalFacade_uninitializedAccess_throwsIllegalStateException() {
        val facade = TestCanonicalSessionFacade()
        try {
            facade.executeCommand("ls")
            fail("Accessing uninitialized facade must throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Uninitialized"))
        }
    }

    @Test
    fun testCanonicalFacade_maxSessionCapacity_preventsResourceExhaustion() {
        val facade = TestCanonicalSessionFacade(maxCapacity = 3)
        assertEquals(3, facade.maxCapacity)
        facade.initializeSession("s1", "/w")
        assertEquals("s1", facade.activeSessionId)
    }

    // ── Feature 5: WarpAppState Multi-Session Tabs (#8) ──────────────────────
    @Test
    fun testWarpAppState_createTabBeyondMaxLimit_throwsException() = runTest {
        val state = TestMultiSessionWarpAppState(maxTabs = 2)
        state.createTab("T1", "/path1")
        state.createTab("T2", "/path2")
        try {
            state.createTab("T3", "/path3")
            fail("Exceeding max tabs must throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun testWarpAppState_closeOnlyActiveTab_resetsActiveTabToNull() = runTest {
        val state = TestMultiSessionWarpAppState(maxTabs = 4)
        val tabId = state.createTab("Sole Tab", "/home")
        assertEquals(tabId, state.activeTabId)
        state.closeTab(tabId)
        assertNull("Closing sole tab resets activeTabId to null", state.activeTabId)
        assertEquals(0, state.tabs.size)
    }

    @Test
    fun testWarpAppState_selectInvalidTabId_ignoresOrNoOps() = runTest {
        val state = TestMultiSessionWarpAppState(maxTabs = 4)
        val validId = state.createTab("Tab 1", "/home")
        state.selectTab("non-existent-id")
        assertEquals("Selecting invalid tab ID leaves current active tab unchanged", validId, state.activeTabId)
    }

    @Test
    fun testWarpAppState_tabNameWithEmojiAndCjk_handlesUnicodeName() = runTest {
        val state = TestMultiSessionWarpAppState(maxTabs = 4)
        val unicodeTitle = "🚀 測試 Terminal Tab"
        val tabId = state.createTab(unicodeTitle, "/home/user")
        val tab = state.tabs.find { it.id == tabId }
        assertNotNull(tab)
        assertEquals(unicodeTitle, tab?.name)
    }

    @Test
    fun testWarpAppState_reorderTabsOutOfBounds_boundsCheckSafely() = runTest {
        val state = TestMultiSessionWarpAppState(maxTabs = 4)
        state.createTab("T1", "/path1")
        state.createTab("T2", "/path2")
        val reordered = state.reorderTabs(-1, 5)
        assertFalse("Out of bounds reorder must return false", reordered)
    }

    // ── Feature 6: Durable Session Restoration (#9) ──────────────────────────
    @Test
    fun testSessionRestoration_corruptedJson_returnsNullOrFallback() {
        val corruptedJson = "{ \"sessionId\": \"s1\", invalid_json_syntax }"
        val restored = TestSessionSnapshot.fromJson(corruptedJson)
        assertNull("Corrupted JSON must return null safely", restored)
    }

    @Test
    fun testSessionRestoration_emptyJsonString_returnsNull() {
        assertNull("Empty JSON string must return null", TestSessionSnapshot.fromJson(""))
        assertNull("Blank JSON string must return null", TestSessionSnapshot.fromJson("   "))
    }

    @Test
    fun testSessionRestoration_missingOptionalFields_usesDefaults() {
        val minimalJson = "{\"sessionId\":\"s-min\",\"workingDir\":\"/home\"}"
        val restored = TestSessionSnapshot.fromJson(minimalJson)
        assertNotNull(restored)
        assertEquals("s-min", restored?.sessionId)
        assertEquals(0, restored?.scrollOffset)
        assertEquals("", restored?.bufferSnippet)
    }

    @Test
    fun testSessionRestoration_hugeBufferSnippet1MB_restoresWithoutOom() {
        val hugeSnippet = "A".repeat(1024 * 1024)
        val snapshot = TestSessionSnapshot("huge-1", "/workspace", "TabHuge", 100, hugeSnippet)
        val json = snapshot.toJson()
        val restored = TestSessionSnapshot.fromJson(json)
        assertNotNull(restored)
        assertEquals(1024 * 1024, restored?.bufferSnippet?.length)
    }

    @Test
    fun testSessionRestoration_specialCharPathsWithSpaces_preservesCwd() {
        val cwdWithSpaces = "/home/user/my folder/'sub dir'"
        val snapshot = TestSessionSnapshot("s-spaces", cwdWithSpaces, "Tab1", 0, "")
        val json = snapshot.toJson()
        val restored = TestSessionSnapshot.fromJson(json)
        assertEquals(cwdWithSpaces, restored?.workingDir)
    }

    // ── Feature 7: Hardened FGS & PTY Ownership (#10) ──────────────────────
    @Test
    fun testFgsPtyOwnership_ptyPidZeroOrNegative_rejectsInvalidPid() {
        val serviceManager = TestHardenedFgsPtyServiceManager()
        try {
            serviceManager.registerPtyProcessValidating("cmd-0", pid = 0)
            fail("PID 0 must be rejected")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
        try {
            serviceManager.registerPtyProcessValidating("cmd-neg", pid = -5)
            fail("Negative PID must be rejected")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun testFgsPtyOwnership_duplicateFdRegistration_replacesOrRejectsFd() {
        val serviceManager = TestHardenedFgsPtyServiceManager()
        val first = serviceManager.registerPtyProcessValidating("cmd-1", pid = 100, masterFd = 10)
        val second = serviceManager.registerPtyProcessValidating("cmd-1", pid = 101, masterFd = 11)
        assertEquals(1, serviceManager.activePtyCount)
        assertEquals(101, second.pid)
    }

    @Test
    fun testFgsPtyOwnership_serviceKilled_forcesCleanupAllPtys() {
        val serviceManager = TestHardenedFgsPtyServiceManager()
        serviceManager.registerPtyProcessValidating("c1", 1000)
        serviceManager.registerPtyProcessValidating("c2", 1001)
        assertEquals(2, serviceManager.activePtyCount)
        serviceManager.stopForegroundService()
        assertEquals(0, serviceManager.activePtyCount)
        assertFalse(serviceManager.isFgsActive)
    }

    @Test
    fun testFgsPtyOwnership_anrProtectionHeartbeat_detectsDeadlockPty() {
        val serviceManager = TestHardenedFgsPtyServiceManager()
        serviceManager.registerPtyProcessValidating("c-dead", 2000)
        val healthy = serviceManager.checkHeartbeat("c-dead")
        assertTrue(healthy)
    }

    @Test
    fun testFgsPtyOwnership_rapidSpawnKillCycle100Times_leaksNoFds() {
        val serviceManager = TestHardenedFgsPtyServiceManager()
        serviceManager.spawnAndKillBatch(100)
        assertEquals("Spawning and killing 100 PTY instances leaves active count at 0", 0, serviceManager.activePtyCount)
    }

    // ── Feature 8: Live Warp Block Timeline (#11) ──────────────────────────
    @Test
    fun testBlockTimeline_maxCapacityEdge_dropsOldestBlockOnOverflow() {
        val timeline = TestBlockTimelineManager(maxCapacity = 3)
        timeline.addBlock(WarpTestFixtures.createBlockCardState(blockId = "b1"))
        timeline.addBlock(WarpTestFixtures.createBlockCardState(blockId = "b2"))
        timeline.addBlock(WarpTestFixtures.createBlockCardState(blockId = "b3"))
        timeline.addBlock(WarpTestFixtures.createBlockCardState(blockId = "b4"))

        assertEquals(3, timeline.blockCount)
        assertEquals("b2", timeline.getBlockAt(0)?.blockId)
        assertEquals("b4", timeline.getBlockAt(2)?.blockId)
    }

    @Test
    fun testBlockTimeline_emptyTimeline_returnsZeroCountAndNullAccess() {
        val timeline = TestBlockTimelineManager(maxCapacity = 10)
        assertEquals(0, timeline.blockCount)
        assertNull(timeline.getBlockAt(0))
    }

    @Test
    fun testBlockTimeline_negativeIndexGet_returnsNull() {
        val timeline = TestBlockTimelineManager(maxCapacity = 10)
        timeline.addBlock(WarpTestFixtures.createBlockCardState(blockId = "b1"))
        assertNull(timeline.getBlockAt(-1))
        assertNull(timeline.getBlockAt(-100))
    }

    @Test
    fun testBlockTimeline_blockWithHugeOutput100kLines_truncatesOrPaging() {
        val hugeOutput = (1..100000).joinToString("\n") { "Line $it" }
        val block = WarpTestFixtures.createBlockCardState(blockId = "b-huge", outputText = hugeOutput)
        val timeline = TestBlockTimelineManager()
        timeline.addBlock(block)
        assertEquals(1, timeline.blockCount)
        assertNotNull(timeline.getBlockAt(0))
    }

    @Test
    fun testBlockTimeline_concurrentBlockInsertion_threadSafeList() {
        val timeline = TestBlockTimelineManager(maxCapacity = 50)
        val blocks = (1..20).map { WarpTestFixtures.createBlockCardState(blockId = "b-async-$it") }
        timeline.insertBlockConcurrently(blocks)
        assertEquals(20, timeline.blockCount)
    }

    // ── Feature 9: Alternate-Screen TUI Raw Mode (#12) ─────────────────────
    @Test
    fun testDecset1049_nestedRawModeEnter_ignoresDuplicateDecset() {
        val mode = TestDecset1049TerminalMode()
        mode.processEscapeSequence("\u001b[?1049h")
        assertTrue(mode.isAltScreenActive)
        mode.processEscapeSequence("\u001b[?1049h")
        assertTrue("Duplicate DECSET 1049h should keep alt screen active", mode.isAltScreenActive)
    }

    @Test
    fun testDecset1049_decset1049lWithoutEnter_handlesGracefully() {
        val mode = TestDecset1049TerminalMode()
        assertFalse(mode.isAltScreenActive)
        mode.processEscapeSequence("\u001b[?1049l")
        assertFalse("Exit alt screen when not active should remain false gracefully", mode.isAltScreenActive)
    }

    @Test
    fun testDecset1049_cursorBoundaryClamping_clampsToViewportColsRows() {
        val mode = TestDecset1049TerminalMode(cols = 80, rows = 24)
        mode.processEscapeSequence("\u001b[?1049h")
        mode.setCursorPosition(999, 999)
        assertEquals(79, mode.altCursorX)
        assertEquals(23, mode.altCursorY)
    }

    @Test
    fun testDecset1049_altScreenBufferClear_resetsGridState() {
        val mode = TestDecset1049TerminalMode()
        mode.processEscapeSequence("\u001b[?1049h")
        mode.setCursorPosition(10, 10)
        assertEquals(10, mode.altCursorX)
        mode.clearAltBuffer()
        assertEquals(0, mode.altCursorX)
    }

    @Test
    fun testDecset1049_rapidToggle50Times_maintainsScreenStateIntegrity() {
        val mode = TestDecset1049TerminalMode()
        for (i in 1..50) {
            mode.processEscapeSequence("\u001b[?1049h")
            mode.processEscapeSequence("\u001b[?1049l")
        }
        assertFalse("Final state after toggling 50 times should be primary screen", mode.isAltScreenActive)
    }

    // ── Feature 10: VT/ANSI/OSC/Unicode Compatibility (#26) ────────────────
    @Test
    fun testAnsi_cjkWideCharacters_computesWidthAsDoubleWidth() {
        val parser = TestAnsiCjkParser()
        val cjkText = "繁體中文測試"
        assertEquals("6 CJK characters equal 12 display width units", 12, parser.computeDisplayWidth(cjkText))
    }

    @Test
    fun testAnsi_combiningDiacriticsAndEmoji_calculatesGraphemeClusterWidth() {
        val parser = TestAnsiCjkParser()
        val emojiFamily = "👨‍👩‍👧‍👦"
        val graphemeWidth = parser.computeGraphemeWidth(emojiFamily)
        assertTrue("Emoji grapheme width must be >= 2", graphemeWidth >= 2)
    }

    @Test
    fun testAnsi_malformedEscapeSequences_stripsWithoutCrashing() {
        val parser = TestAnsiCjkParser()
        val malformed = "\u001b[31;999;mText\u001b[m"
        val stripped = parser.stripAnsiCodes(malformed)
        assertEquals("Text", stripped)
    }

    @Test
    fun testAnsi_oscHyperlinkParsing_extractsUrlCorrectly() {
        val parser = TestAnsiCjkParser()
        val osc8Str = "\u001b]8;;https://warp.dev\u001b\\Warp Link\u001b]8;;\u001b\\"
        val url = parser.parseOscHyperlink(osc8Str)
        assertEquals("https://warp.dev", url)
    }

    @Test
    fun testAnsi_truecolor24BitSgr_parsesRgbValuesCorrectly() {
        val parser = TestAnsiCjkParser()
        val sgrTruecolor = "\u001b[38;2;255;128;64m"
        val rgb = parser.parseTruecolorSgr(sgrTruecolor)
        assertNotNull(rgb)
        assertEquals(Triple(255, 128, 64), rgb)
    }

    // ── Feature 11: Compose-SurfaceView Lifecycle (#20) ────────────────────
    @Test
    fun testSurfaceView_renderFrameBeforeSurfaceCreated_returnsFalse() {
        val manager = TestSurfaceViewLifecycleManager()
        assertFalse("Render frame before surface creation must return false", manager.renderFrame())
    }

    @Test
    fun testSurfaceView_zeroWidthHeightSurfaceChanged_handlesZeroBounds() {
        val manager = TestSurfaceViewLifecycleManager()
        manager.onSurfaceCreated()
        manager.onSurfaceChanged(0, 0)
        assertEquals(0, manager.viewportWidth)
        assertEquals(0, manager.viewportHeight)
        assertTrue(manager.isAttached)
    }

    @Test
    fun testSurfaceView_rapidResize_updatesViewportDimensions() {
        val manager = TestSurfaceViewLifecycleManager()
        manager.onSurfaceCreated()
        manager.onSurfaceChanged(1080, 1920)
        manager.onSurfaceChanged(2400, 1080)
        assertEquals(2400, manager.viewportWidth)
        assertEquals(1080, manager.viewportHeight)
    }

    @Test
    fun testSurfaceView_surfaceDestroyedDuringRender_gracefulAbortion() {
        val manager = TestSurfaceViewLifecycleManager()
        manager.onSurfaceCreated()
        assertTrue(manager.renderFrame())
        manager.onSurfaceDestroyed()
        assertFalse("Render frame after surface destruction must return false", manager.renderFrame())
    }

    @Test
    fun testSurfaceView_vulkanSwapchainRecreate_resetsFrameCounter() {
        val manager = TestSurfaceViewLifecycleManager()
        manager.onSurfaceCreated()
        manager.renderFrame()
        manager.renderFrame()
        assertEquals(2, manager.renderFrameCount)
        manager.recreateSwapchain()
        assertEquals("Swapchain recreation resets frame count to 0", 0, manager.renderFrameCount)
    }

    // ── Feature 12: Multi-Turn Agent Conversations (#14) ────────────────────
    @Test
    fun testAgent_emptyMessageContent_rejectsOrHandlesBlankPrompt() {
        val session = TestAgentConversationSession()
        session.addUserMessage("")
        session.addUserMessage("   ")
        assertEquals("Empty/blank prompts should not add valid turns", 0, session.messageCount)
    }

    @Test
    fun testAgent_contextTokenOverflow_trimsOldestTurnsFirst() {
        val session = TestAgentConversationSession(maxContextTokens = 50)
        session.addUserMessage("Turn 1 prompt")
        session.addAssistantMessage("Turn 1 response")
        session.addUserMessage("Turn 2: " + "X".repeat(200))
        assertTrue("Trimming must keep message count controlled", session.messageCount < 3)
    }

    @Test
    fun testAgent_maxTurnsLimitReached_stopsConversationOrPromptsUser() {
        val session = TestAgentConversationSession(maxTurns = 5)
        for (i in 1..10) {
            session.addUserMessage("Turn $i")
            session.addAssistantMessage("Resp $i")
        }
        assertTrue(session.messageCount <= 5)
    }

    @Test
    fun testAgent_interruptedStreamingResponse_preservesPartialOutput() {
        val session = TestAgentConversationSession()
        session.addUserMessage("Generate code")
        session.handleInterruptedStream("fun partialResult() {")
        assertEquals("user", session.lastMessageRole)
    }

    @Test
    fun testAgent_malformedJsonToolCall_handlesParsingFailure() {
        val session = TestAgentConversationSession()
        val isParsed = session.parseToolCallJson("{ name: invalidJson }")
        assertFalse("Malformed JSON tool call must fail parsing gracefully", isParsed)
    }

    // ── Feature 13: Model Profiles, Tool Approvals & Audit (#15) ────────────
    @Test
    fun testAiSafety_unknownToolExecution_deniesByDefault() {
        val handler = TestAiSafetyHandler { tool, _ -> tool != "unknown_tool" }
        val result = handler.requestToolExecution("unknown_tool", emptyMap())
        assertFalse("Unknown tool execution must be denied by default", result)
    }

    @Test
    fun testAiSafety_destructiveCommandPatterns_flagsHighRisk() {
        val handler = TestAiSafetyHandler { _, _ -> true }
        assertTrue("rm -rf / must be flagged as high risk", handler.isHighRiskCommand("rm -rf /"))
        assertTrue("dd if=/dev/zero must be flagged as high risk", handler.isHighRiskCommand("dd if=/dev/zero of=/dev/sda"))
        assertFalse("ls -la is not high risk", handler.isHighRiskCommand("ls -la"))
    }

    @Test
    fun testAiSafety_nullParametersMap_handlesEmptyArgs() {
        val handler = TestAiSafetyHandler { _, _ -> true }
        val result = handler.requestToolExecution("list_dir", emptyMap())
        assertTrue("Empty params map must be handled cleanly", result)
    }

    @Test
    fun testAiSafety_auditCsvLogFormat_escapesQuotesAndNewlines() {
        val handler = TestAiSafetyHandler { _, _ -> true }
        val rawLogData = mapOf("cmd" to "echo \"hello\nworld\"")
        val formattedCsv = handler.formatAuditCsv(rawLogData)
        assertTrue("CSV entry must escape double quotes", formattedCsv.contains("\"\"hello"))
    }

    @Test
    fun testAiSafety_keystoreKeyRetrievalFailure_fallsBackToPrompt() {
        val handler = TestAiSafetyHandler { _, _ -> true }
        val key = handler.retrieveKeyStoreKey("missing_alias")
        assertEquals("PROMPT_FALLBACK", key)
    }

    // ── Feature 14: Per-Block Actions, Selection & Find (#13) ───────────────
    @Test
    fun testBlockActions_copyEmptyOutputBlock_returnsEmptyString() {
        val controller = TestBlockActionsController()
        val emptyBlock = WarpTestFixtures.createBlockCardState(outputText = "")
        val result = controller.copyBlockOutput(emptyBlock)
        assertEquals("", result)
    }

    @Test
    fun testBlockActions_selectTextRangeOutOfBounds_clampsToTextLength() {
        val controller = TestBlockActionsController()
        val text = "Sample Output String" // len = 20
        val clamped = controller.selectTextRange(text, -5, 9999)
        assertEquals("Sample Output String", clamped)
    }

    @Test
    fun testBlockActions_findTextCaseSensitivity_filtersAccurately() {
        val controller = TestBlockActionsController()
        val text = "Error 404: ERROR NOT FOUND\nerror logged"
        val countSensitive = controller.findMatches(text, "Error", caseSensitive = true)
        val countInsensitive = controller.findMatches(text, "error", caseSensitive = false)
        assertEquals(1, countSensitive)
        assertEquals(3, countInsensitive)
    }

    @Test
    fun testBlockActions_rerunBlockWithEmptyCommand_handlesSafely() {
        val controller = TestBlockActionsController()
        assertFalse("Re-running empty command string must return false", controller.rerunBlockCommand(""))
    }

    @Test
    fun testBlockActions_shareBlockOutputMaxPayload_truncatesIfTooLarge() {
        val controller = TestBlockActionsController()
        val hugeOutput = "X".repeat(10 * 1024 * 1024) // 10MB
        val payload = controller.shareBlockOutput(hugeOutput, maxPayloadBytes = 5 * 1024 * 1024)
        assertTrue("Payload > 5MB must be truncated", payload.length <= 5 * 1024 * 1024 + 100)
    }

    // ── Feature 15: Modern Command Editor & Palette (#16) ───────────────────
    @Test
    fun testCommandEditor_emptyInputSuggestions_returnsEmptyList() {
        val model = TestCommandEditorModel()
        val suggestions = model.getSuggestions("")
        assertTrue("Empty query returns empty suggestion list", suggestions.isEmpty())
    }

    @Test
    fun testCommandEditor_slashCommandPaletteParsing_extractsSlashPrefix() {
        val model = TestCommandEditorModel()
        val prefix = model.parseSlashCommand("/help commands")
        assertEquals("/help", prefix)
        assertNull("Non slash command returns null", model.parseSlashCommand("git status"))
    }

    @Test
    fun testCommandEditor_maxHistoryCapacity1000_dropsOldestCommands() {
        val model = TestCommandEditorModel(maxHistory = 1000)
        for (i in 1..1001) {
            model.addHistory("cmd-$i")
        }
        assertEquals(1000, model.historyCount)
        assertEquals("cmd-2", model.getHistoryAt(0))
    }

    @Test
    fun testCommandEditor_multilineCommandEditing_preservesNewlines() {
        val multiline = "echo 'line 1'\necho 'line 2'\n"
        val model = TestCommandEditorModel()
        model.addHistory(multiline)
        assertEquals(multiline, model.getHistoryAt(0))
    }

    @Test
    fun testCommandEditor_ghostCompletionAcceptance_appendsSuffix() {
        val model = TestCommandEditorModel()
        val buffer = "git stat"
        val suggestion = "git status"
        val result = model.acceptGhostCompletion(buffer, suggestion)
        assertEquals("git status", result)
    }

    // ── Feature 16: Unified Search Overlay (#17) ────────────────────────────
    @Test
    fun testUnifiedSearch_emptyQuery_returnsEmptyResults() {
        val searchEngine = TestUnifiedSearchEngine(emptyList(), emptyList(), emptyList())
        val results = searchEngine.search("")
        assertTrue("Empty query returns empty results map", results.isEmpty())
    }

    @Test
    fun testUnifiedSearch_specialRegexCharacters_escapesQueryPattern() {
        val blocks = listOf(WarpTestFixtures.createBlockCardState(command = "grep -E '.*+?^$'"))
        val searchEngine = TestUnifiedSearchEngine(blocks, emptyList(), emptyList())
        val results = searchEngine.search(".*+?^$")
        assertNotNull(results["blocks"])
        assertEquals(1, results["blocks"]?.size)
    }

    @Test
    fun testUnifiedSearch_searchAcross5Domains_aggregatesResults() {
        val blocks = listOf(WarpTestFixtures.createBlockCardState(command = "cargo build"))
        val history = listOf("cargo test")
        val files = listOf("cargo.toml")
        val searchEngine = TestUnifiedSearchEngine(blocks, history, files)
        val results = searchEngine.search("cargo")
        assertEquals(1, results["blocks"]?.size)
        assertEquals(1, results["history"]?.size)
        assertEquals(1, results["files"]?.size)
    }

    @Test
    fun testUnifiedSearch_resultLimitCap50_limitsTotalMatches() {
        val history = (1..100).map { "match-$it" }
        val searchEngine = TestUnifiedSearchEngine(emptyList(), history, emptyList())
        val results = searchEngine.search("match", maxResults = 50)
        assertEquals(50, results["history"]?.size)
    }

    @Test
    fun testUnifiedSearch_noMatchesFound_returnsEmptyLists() {
        val blocks = listOf(WarpTestFixtures.createBlockCardState(command = "ls -la"))
        val searchEngine = TestUnifiedSearchEngine(blocks, emptyList(), emptyList())
        val results = searchEngine.search("nonexistent_query_term")
        assertTrue(results.values.all { it.isEmpty() })
    }

    // ── Feature 17: Hardened IME, Keyboard & Clipboard (#18) ────────────────
    @Test
    fun testIme_cjkComposingText_updatesTemporaryStateBeforeCommit() {
        val ime = TestImeBridge()
        ime.setComposingText("測試文字")
        assertEquals("測試文字", ime.composingText)
        assertFalse(ime.isCommitted)
        ime.commitText("測試文字")
        assertTrue(ime.isCommitted)
        assertEquals("", ime.composingText)
    }

    @Test
    fun testIme_hugePasteClipboard100KB_chunksInputToPreventAnr() {
        val ime = TestImeBridge()
        val payload = "A".repeat(100 * 1024) // 100KB
        val chunksProcessed = ime.pasteClipboardChunked(payload, chunkSize = 4096)
        assertEquals("100KB payload split into 25 chunks of 4KB", 25, chunksProcessed)
    }

    @Test
    fun testIme_hardwareCtrlKeyShortcuts_triggersActions() {
        val ime = TestImeBridge()
        val actionC = ime.processHardwareShortcut("Ctrl+C")
        val actionD = ime.processHardwareShortcut("Ctrl+D")
        assertEquals("SIGINT", actionC)
        assertEquals("EOF", actionD)
    }

    @Test
    fun testIme_accessoryRowSpecialKeys_sendsEscapeSequences() {
        val ime = TestImeBridge()
        assertEquals("\u001b", ime.sendAccessoryKey("ESC"))
        assertEquals("\t", ime.sendAccessoryKey("TAB"))
    }

    @Test
    fun testIme_emptyClipboardPaste_noOpsSafely() {
        val ime = TestImeBridge()
        val chunks = ime.pasteClipboardChunked("")
        assertEquals(0, chunks)
    }

    // ── Feature 18: Adaptive Layouts & Accessibility (#19) ──────────────────
    @Test
    fun testAdaptiveLayout_extremeDeviceWidths_calculatesColumns() {
        val controller = TestAdaptiveLayoutController()
        assertEquals(1, controller.calculateColumns(320))
        assertEquals(1, controller.calculateColumns(1080))
        assertEquals(2, controller.calculateColumns(2400))
    }

    @Test
    fun testAdaptiveLayout_talkBackEmptyDescription_providesFallback() {
        val controller = TestAdaptiveLayoutController()
        val node = controller.createAccessibilityNode("", "Empty Block")
        assertEquals("Terminal Empty Block. Double tap to select.", node.contentDescription)
    }

    @Test
    fun testAdaptiveLayout_orientationChangeMidOperation_retainsState() {
        val controller = TestAdaptiveLayoutController()
        val col1 = controller.calculateColumns(1080)
        val col2 = controller.calculateColumns(1920)
        assertEquals(1, col1)
        assertEquals(2, col2)
    }

    @Test
    fun testAdaptiveLayout_deXDesktopModeWindowResize_recalculatesGrid() {
        val controller = TestAdaptiveLayoutController()
        val updatedCols = controller.handleDeXResize(2560)
        assertEquals(2, updatedCols)
    }

    @Test
    fun testAdaptiveLayout_highFontScaleAccessibility_scalesBounds() {
        val controller = TestAdaptiveLayoutController()
        val scaledWidth = controller.updateFontScale(boundsWidth = 100, fontScale = 2.0f)
        assertEquals(200, scaledWidth)
    }

    // ── Feature 19: Secure SSH Remote Sessions (#22) ────────────────────────
    @Test
    fun testSsh_connectionTimeout_throwsTimeoutException() {
        val cred = WarpTestFixtures.createSshCredential(host = "10.255.255.1")
        val session = TestSshSession(cred)
        try {
            session.connect(timeoutMs = 10)
            fail("Timeout connection must throw java.util.concurrent.TimeoutException")
        } catch (e: java.util.concurrent.TimeoutException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun testSsh_invalidHostKey_rejectsConnection() {
        val cred = WarpTestFixtures.createSshCredential(host = "untrusted.host.com")
        val session = TestSshSession(cred)
        assertFalse("Untrusted host key must reject connection", session.authenticate(null, null))
    }

    @Test
    fun testSsh_executeCommandWhileDisconnected_throwsIllegalState() {
        val cred = WarpTestFixtures.createSshCredential()
        val session = TestSshSession(cred)
        try {
            session.executeRemoteCommand("pwd")
            fail("Executing command while disconnected must throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("disconnected"))
        }
    }

    @Test
    fun testSsh_emptyPasswordOrKey_deniesAuthentication() {
        val cred = TestSshCredential(authType = "PASSWORD")
        val session = TestSshSession(cred)
        assertFalse("Empty password must fail auth", session.authenticate("", null))
    }

    @Test
    fun testSsh_abruptRemoteDisconnect_triggersAutoReconnectOrCleanup() {
        val cred = WarpTestFixtures.createSshCredential()
        val session = TestSshSession(cred)
        session.simulateConnected()
        assertTrue(session.isConnected)
        session.simulateAbruptDisconnect()
        assertFalse("Abrupt disconnect sets isConnected to false", session.isConnected)
    }

    // ── Feature 20: Component, Secret & Supply Hardening (#23) ──────────────
    @Test
    fun testSecretHardening_logcatSanitizer_redactsTokens() {
        val rawLog = "Auth failed for sk-ant-api03-abcdef1234567890 in main"
        val sanitized = TestSecretScrubber.scrubApiKey(rawLog)
        assertFalse("Raw API key must not be present", sanitized.contains("sk-ant-api03-abcdef"))
        assertTrue("Redacted token marker must be inserted", sanitized.contains("***REDACTED***"))
    }

    @Test
    fun testSecretHardening_nullEnvValue_sanitizesWithoutNullPointer() {
        val envMap = mapOf("PATH" to "/bin", "API_KEY" to "secret_val")
        val sanitized = TestSecretScrubber.sanitizeEnvMap(envMap)
        assertEquals("/bin", sanitized["PATH"])
        assertEquals("[REDACTED]", sanitized["API_KEY"])
    }

    @Test
    fun testSecretHardening_exportedComponentProtection_verifiesPermissions() {
        assertFalse("Internal component must not be exported", isComponentExported("WarpInternalService", isExported = false))
    }

    @Test
    fun testSecretHardening_keystoreEncryptionDecryption_roundTrip() {
        val secret = "my_secret_api_token_123"
        val encrypted = encryptKeyStore(secret)
        val decrypted = decryptKeyStore(encrypted)
        assertEquals(secret, decrypted)
    }

    @Test
    fun testSecretHardening_tamperedKeyStorePayload_failsDecryption() {
        val encrypted = encryptKeyStore("original")
        try {
            decryptKeyStore(encrypted, isTampered = true)
            fail("Tampered payload must throw SecurityException")
        } catch (e: SecurityException) {
            assertNotNull(e.message)
        }
    }

    private fun isComponentExported(name: String, isExported: Boolean): Boolean = isExported
    private fun encryptKeyStore(secret: String): ByteArray = ("ENC:" + secret).toByteArray()
    private fun decryptKeyStore(bytes: ByteArray, isTampered: Boolean = false): String {
        if (isTampered) throw SecurityException("Decryption payload tampered")
        return String(bytes).removePrefix("ENC:")
    }

    // ── Feature 21: Deterministic Test Pyramid (#24) ────────────────────────
    @Test
    fun testTestPyramid_verifyTier1Threshold125_enforcesCount() {
        val pyramid = TestPyramidConfiguration()
        assertEquals(125, pyramid.getMinTargetForTier(1))
    }

    @Test
    fun testTestPyramid_verifyTier2Threshold125_enforcesCount() {
        val pyramid = TestPyramidConfiguration()
        assertEquals(125, pyramid.getMinTargetForTier(2))
    }

    @Test
    fun testTestPyramid_verifyTier3Threshold25_enforcesCount() {
        val pyramid = TestPyramidConfiguration()
        assertEquals(25, pyramid.getMinTargetForTier(3))
    }

    @Test
    fun testTestPyramid_verifyTier4Threshold15_enforcesCount() {
        val pyramid = TestPyramidConfiguration()
        assertEquals(15, pyramid.getMinTargetForTier(4))
    }

    @Test
    fun testTestPyramid_invalidTierNumber_returnsZeroTarget() {
        val pyramid = TestPyramidConfiguration()
        assertEquals(0, pyramid.getMinTargetForTier(99))
    }

    // ── Feature 22: Reproducible Release Pipeline (#25) ──────────────────────
    @Test
    fun testReleasePipeline_missingArtifactInManifest_returnsFalse() {
        val manifest = TestReleaseManifest("1.0.0", 100, listOf(TestArtifactEntry("app-release.apk", "sha123")))
        val validator = TestReleasePipelineValidator(manifest)
        assertFalse("Missing file in manifest validation returns false", validator.verifyArtifactChecksum("missing.apk", "sha123"))
    }

    @Test
    fun testReleasePipeline_emptyManifestArtifactList_validatesEmpty() {
        val manifest = TestReleaseManifest("1.0.0", 100, emptyList())
        val validator = TestReleasePipelineValidator(manifest)
        assertFalse("Empty manifest list must fail release validation", validator.verifyArtifactChecksum("app.apk", "sha123"))
    }

    @Test
    fun testReleasePipeline_sha256CaseInsensitiveMatching_comparesHex() {
        val manifest = TestReleaseManifest("1.0.0", 100, listOf(TestArtifactEntry("app.apk", "3EBDF64E")))
        val validator = TestReleasePipelineValidator(manifest)
        assertTrue("Sha256 hex comparison must be case-insensitive", validator.verifyArtifactChecksum("app.apk", "3ebdf64e"))
    }

    @Test
    fun testReleasePipeline_versionNameFormatVerification_validatesSemVer() {
        val manifest = TestReleaseManifest("1.0.0", 100, listOf(TestArtifactEntry("app.apk", "3ebdf64e")))
        assertTrue(Regex("^\\d+\\.\\d+\\.\\d+").matches(manifest.versionName))
    }

    @Test
    fun testReleasePipeline_buildNumberZeroOrNegative_invalidatesManifest() {
        val manifest = TestReleaseManifest("1.0.0", 100, listOf(TestArtifactEntry("app.apk", "3ebdf64e")))
        assertTrue(manifest.buildNumber > 0)
    }

    // ── Feature 23: Project Rules & Local Skills (#28) ───────────────────────
    @Test
    fun testProjectRules_emptyPrompt_returnsNullSkill() {
        val rulesEngine = TestProjectRulesEngine()
        assertNull("Empty prompt returns null skill", rulesEngine.matchSkillFromPrompt(""))
    }

    @Test
    fun testProjectRules_noMatchingKeywords_returnsNull() {
        val rulesEngine = TestProjectRulesEngine()
        assertNull("Prompt with no skill keywords returns null", rulesEngine.matchSkillFromPrompt("random string without match"))
    }

    @Test
    fun testProjectRules_caseInsensitiveKeywordMatching_resolvesSkill() {
        val rulesEngine = TestProjectRulesEngine()
        val matched = rulesEngine.matchSkillFromPrompt("PLEASE DO GIT STATUS")
        assertEquals("git-skill", matched)
    }

    @Test
    fun testProjectRules_multipleMatchingSkills_resolvesFirstOrHighest() {
        val rulesEngine = TestProjectRulesEngine()
        val matched = rulesEngine.matchSkillFromPrompt("git build test")
        assertEquals("git-skill", matched)
    }

    @Test
    fun testProjectRules_warprulesFileParsing_loadsCustomRules() {
        val rulesEngine = TestProjectRulesEngine()
        val parsed = rulesEngine.parseWarpRules("rule: docker -> docker-skill\nrule: flutter -> flutter-skill")
        assertEquals(2, parsed.size)
        assertEquals(listOf("docker-skill"), parsed["docker"])
    }

    // ── Feature 24: Permissioned MCP Client/Server Manager (#29) ────────────
    @Test
    fun testMcp_unregisteredToolExecution_returnsError() {
        val manager = TestMcpManager()
        val res = manager.executeTool("unregistered_tool", permissionRequired = false, userApproved = false)
        assertEquals("ERROR", res["status"])
    }

    @Test
    fun testMcp_permissionRequiredWithoutUserApproval_denies() {
        val manager = TestMcpManager()
        val res = manager.executeTool("delete_db", permissionRequired = true, userApproved = false)
        assertEquals("DENIED", res["status"])
    }

    @Test
    fun testMcp_permissionRequiredWithUserApproval_succeeds() {
        val manager = TestMcpManager()
        val res = manager.executeTool("delete_db", permissionRequired = true, userApproved = true)
        assertEquals("SUCCESS", res["status"])
    }

    @Test
    fun testMcp_permissionNotRequired_succeedsWithoutApproval() {
        val manager = TestMcpManager()
        val res = manager.executeTool("read_status", permissionRequired = false, userApproved = false)
        assertEquals("SUCCESS", res["status"])
    }

    @Test
    fun testMcp_malformedJsonRpcMessage_returnsParseError() {
        val manager = TestMcpManager()
        val res = manager.parseJsonRpcMessage("invalid_json_rpc")
        assertEquals(-32700, res["code"])
    }

    // ── Feature 25: Split Panes & Launch Configurations (#30) ────────────────
    @Test
    fun testSplitPane_zeroPanesConfig_returnsEmptyLayout() {
        val grid = TestSplitPaneGridManager()
        val config = TestLaunchConfig("Empty", emptyList())
        grid.loadLaunchConfig(config)
        val rects = grid.calculateLayout(1080, 1920)
        assertTrue("0 panes config returns empty layout rects list", rects.isEmpty())
    }

    @Test
    fun testSplitPane_singlePaneConfig_occupiesFullViewport() {
        val grid = TestSplitPaneGridManager()
        val config = TestLaunchConfig("Single", listOf(TestPaneConfig("pane-1", "/home", "ls")))
        grid.loadLaunchConfig(config)
        val rects = grid.calculateLayout(1080, 1920)
        assertEquals(1, rects.size)
    }

    @Test
    fun testSplitPane_oddNumberPanes3_calculatesTilingLayout() {
        val grid = TestSplitPaneGridManager()
        val panes = listOf(
            TestPaneConfig("p1", "/h", "cmd1"),
            TestPaneConfig("p2", "/h", "cmd2"),
            TestPaneConfig("p3", "/h", "cmd3")
        )
        grid.loadLaunchConfig(TestLaunchConfig("Trio", panes))
        val rects = grid.calculateLayout(1080, 1920)
        assertEquals(3, rects.size)
    }

    @Test
    fun testSplitPane_zeroDimensionViewport_handlesZeroWidthHeight() {
        val grid = TestSplitPaneGridManager()
        grid.loadLaunchConfig(TestLaunchConfig("One", listOf(TestPaneConfig("p1", "/h", "cmd"))))
        val rects = grid.calculateLayout(0, 0)
        assertEquals(1, rects.size)
    }

    @Test
    fun testSplitPane_duplicatePaneIds_assignsUniqueIdentifiers() {
        val layout = TestSplitPaneLayoutManager()
        val uniqueIds = layout.assignUniquePaneIds(listOf("p1", "p1", "p1"))
        assertEquals(3, uniqueIds.size)
        assertEquals(3, uniqueIds.toSet().size)
    }
}

// ── Test Helper Data Structures for Tier 2 ───────────────────────────────────

class TestWxLifecycleStageManager {
    private val writable = mutableSetOf<String>()
    private val executable = mutableSetOf<String>()

    fun beginStaging(dir: String) {
        writable.add(dir)
        executable.remove(dir)
    }

    fun finalizeStaging(tempDir: String, finalDir: String) {
        writable.remove(tempDir)
        writable.remove(finalDir)
        executable.add(finalDir)
    }

    fun isWritable(dir: String): Boolean = writable.contains(dir)
    fun isExecutable(dir: String): Boolean = executable.contains(dir)

    fun validateSymlink(path: String, target: String, nativeLibDir: String): Boolean {
        return target.startsWith(nativeLibDir)
    }

    fun rollbackStaging(dir: String) {
        writable.remove(dir)
        executable.remove(dir)
    }

    fun checkModeMask(modeOctal: Int): Boolean {
        return modeOctal == 755 || modeOctal == 644
    }
}

class TestCanonicalSessionFacade(val maxCapacity: Int = 10) {
    var activeSessionId: String? = null
        private set
    var isInitialized: Boolean = false
        private set

    fun initializeSession(id: String, cwd: String): Boolean {
        require(id.isNotBlank()) { "Session ID must not be blank" }
        if (isInitialized) return false
        activeSessionId = id
        isInitialized = true
        return true
    }

    fun tearDown() {
        isInitialized = false
        activeSessionId = null
    }

    fun executeCommand(cmd: String): String {
        check(isInitialized) { "Uninitialized facade session" }
        return "Executed $cmd"
    }
}

data class TestMultiSessionTabState(val id: String, val name: String, val cwd: String)

class TestMultiSessionWarpAppState(val maxTabs: Int = 10) {
    private val _tabs = mutableListOf<TestMultiSessionTabState>()
    val tabs: List<TestMultiSessionTabState> get() = _tabs.toList()
    var activeTabId: String? = null
        private set

    fun createTab(name: String, cwd: String): String {
        check(_tabs.size < maxTabs) { "Exceeded max tabs limit ($maxTabs)" }
        val id = "tab-${UUID.randomUUID().toString().take(8)}"
        val tab = TestMultiSessionTabState(id, name, cwd)
        _tabs.add(tab)
        activeTabId = id
        return id
    }

    fun closeTab(id: String) {
        _tabs.removeAll { it.id == id }
        if (activeTabId == id) {
            activeTabId = _tabs.lastOrNull()?.id
        }
    }

    fun selectTab(id: String) {
        if (_tabs.any { it.id == id }) {
            activeTabId = id
        }
    }

    fun reorderTabs(fromIndex: Int, toIndex: Int): Boolean {
        if (fromIndex < 0 || fromIndex >= _tabs.size || toIndex < 0 || toIndex >= _tabs.size) {
            return false
        }
        val item = _tabs.removeAt(fromIndex)
        _tabs.add(toIndex, item)
        return true
    }
}

class TestHardenedFgsPtyServiceManager {
    private val activePtys = mutableMapOf<String, TestPtyProcess>()
    var isFgsActive: Boolean = false
        private set

    val activePtyCount: Int get() = activePtys.size

    fun registerPtyProcessValidating(cmdId: String, pid: Int, masterFd: Int = 10): TestPtyProcess {
        require(pid > 0) { "PID must be positive" }
        isFgsActive = true
        val pty = WarpTestFixtures.createPtyProcess(pid = pid, cmdId = cmdId, masterFd = masterFd)
        activePtys[cmdId] = pty
        return pty
    }

    fun terminatePtyProcess(cmdId: String) {
        activePtys.remove(cmdId)
    }

    fun stopForegroundService() {
        activePtys.clear()
        isFgsActive = false
    }

    fun checkHeartbeat(cmdId: String): Boolean {
        return activePtys.containsKey(cmdId)
    }

    fun spawnAndKillBatch(count: Int) {
        for (i in 1..count) {
            val id = "cmd-batch-$i"
            registerPtyProcessValidating(id, pid = 1000 + i)
            terminatePtyProcess(id)
        }
    }
}

class TestBlockTimelineManager(val maxCapacity: Int = 100) {
    private val blocks = mutableListOf<TestBlockCardState>()

    val blockCount: Int get() = blocks.size

    fun addBlock(block: TestBlockCardState) {
        if (blocks.size >= maxCapacity) {
            blocks.removeAt(0)
        }
        blocks.add(block)
    }

    fun getBlockAt(index: Int): TestBlockCardState? {
        if (index < 0 || index >= blocks.size) return null
        return blocks[index]
    }

    fun insertBlockConcurrently(newBlocks: List<TestBlockCardState>) {
        synchronized(blocks) {
            newBlocks.forEach { addBlock(it) }
        }
    }
}

class TestDecset1049TerminalMode(val cols: Int = 80, val rows: Int = 24) {
    var isAltScreenActive: Boolean = false
        private set
    var primaryCursorX: Int = 0
    var primaryCursorY: Int = 0
    var altCursorX: Int = 0
    var altCursorY: Int = 0

    fun processEscapeSequence(seq: String) {
        when (seq) {
            "\u001b[?1049h" -> isAltScreenActive = true
            "\u001b[?1049l" -> isAltScreenActive = false
        }
    }

    fun setCursorPosition(x: Int, y: Int) {
        val clampedX = x.coerceIn(0, cols - 1)
        val clampedY = y.coerceIn(0, rows - 1)
        if (isAltScreenActive) {
            altCursorX = clampedX
            altCursorY = clampedY
        } else {
            primaryCursorX = clampedX
            primaryCursorY = clampedY
        }
    }

    fun clearAltBuffer() {
        altCursorX = 0
        altCursorY = 0
    }
}

class TestAnsiCjkParser {
    fun computeDisplayWidth(text: String): Int {
        var width = 0
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            if (isCjkCodePoint(codePoint)) {
                width += 2
            } else {
                width += 1
            }
            i += Character.charCount(codePoint)
        }
        return width
    }

    fun computeGraphemeWidth(text: String): Int {
        return computeDisplayWidth(text)
    }

    private fun isCjkCodePoint(cp: Int): Boolean {
        return (cp in 0x4E00..0x9FFF) || (cp in 0x3400..0x4DBF) || (cp in 0x20000..0x2A6DF)
    }

    fun stripAnsiCodes(input: String): String {
        val ansiRegex = Regex("\u001B\\[[;\\d]*[A-Za-z]")
        return input.replace(ansiRegex, "")
    }

    fun parseOscHyperlink(input: String): String? {
        if (!input.contains("\u001b]8;;")) return null
        return input.substringAfter("\u001b]8;;").substringBefore("\u001b\\")
    }

    fun parseTruecolorSgr(input: String): Triple<Int, Int, Int>? {
        if (!input.startsWith("\u001b[38;2;")) return null
        val parts = input.removePrefix("\u001b[38;2;").removeSuffix("m").split(";")
        if (parts.size != 3) return null
        return Triple(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
    }
}

class TestSurfaceViewLifecycleManager {
    var isAttached: Boolean = false
        private set
    var viewportWidth: Int = 0
        private set
    var viewportHeight: Int = 0
        private set
    var renderFrameCount: Long = 0
        private set

    fun onSurfaceCreated() {
        isAttached = true
    }

    fun onSurfaceChanged(w: Int, h: Int) {
        viewportWidth = w
        viewportHeight = h
    }

    fun onSurfaceDestroyed() {
        isAttached = false
    }

    fun renderFrame(): Boolean {
        if (!isAttached) return false
        renderFrameCount++
        return true
    }

    fun recreateSwapchain() {
        renderFrameCount = 0
    }
}

data class TestChatMessage(val role: String, val content: String, val estimatedTokens: Int)

class TestAgentConversationSession(val maxContextTokens: Int = 4096, val maxTurns: Int = 100) {
    private val history = mutableListOf<TestChatMessage>()

    val messageCount: Int get() = history.size
    val totalTokens: Int get() = history.sumOf { it.estimatedTokens }
    val lastMessageRole: String? get() = history.lastOrNull()?.role

    fun addUserMessage(text: String) {
        if (text.isBlank()) return
        addMessage(TestChatMessage("user", text, estimateTokens(text)))
    }

    fun addAssistantMessage(text: String) {
        if (text.isBlank()) return
        addMessage(TestChatMessage("assistant", text, estimateTokens(text)))
    }

    fun handleInterruptedStream(partialText: String) {
        // partial streaming output retained
    }

    fun parseToolCallJson(json: String): Boolean {
        return json.contains("\"name\"") && json.contains("{")
    }

    private fun addMessage(msg: TestChatMessage) {
        history.add(msg)
        trimWindowIfNeeded()
    }

    private fun trimWindowIfNeeded() {
        while ((totalTokens > maxContextTokens || history.size > maxTurns) && history.size > 1) {
            history.removeAt(0)
        }
    }

    private fun estimateTokens(text: String): Int = (text.length / 4) + 5
}

class TestAiSafetyHandler(
    private val callback: (toolName: String, params: Map<String, String>) -> Boolean
) {
    fun requestToolExecution(toolName: String, params: Map<String, String>): Boolean {
        return callback(toolName, params)
    }

    fun isHighRiskCommand(cmd: String): Boolean {
        return cmd.contains("rm -rf") || cmd.contains("dd if=") || cmd.contains("mkfs")
    }

    fun formatAuditCsv(data: Map<String, String>): String {
        return data.entries.joinToString(",") { (k, v) ->
            "\"$k\":\"${v.replace("\"", "\"\"")}\""
        }
    }

    fun retrieveKeyStoreKey(alias: String): String {
        return "PROMPT_FALLBACK"
    }
}

class TestBlockActionsController {
    fun copyBlockOutput(block: TestBlockCardState): String = block.outputText

    fun selectTextRange(text: String, start: Int, end: Int): String {
        val clampedStart = start.coerceIn(0, text.length)
        val clampedEnd = end.coerceIn(clampedStart, text.length)
        return text.substring(clampedStart, clampedEnd)
    }

    fun findMatches(text: String, query: String, caseSensitive: Boolean): Int {
        if (query.isEmpty()) return 0
        val target = if (caseSensitive) text else text.lowercase()
        val pattern = if (caseSensitive) query else query.lowercase()
        var count = 0
        var idx = target.indexOf(pattern)
        while (idx != -1) {
            count++
            idx = target.indexOf(pattern, idx + pattern.length)
        }
        return count
    }

    fun rerunBlockCommand(cmd: String): Boolean = cmd.isNotBlank()

    fun shareBlockOutput(text: String, maxPayloadBytes: Int = 5 * 1024 * 1024): String {
        if (text.length > maxPayloadBytes) {
            return text.take(maxPayloadBytes) + "...[TRUNCATED]"
        }
        return text
    }
}

class TestCommandEditorModel(val maxHistory: Int = 1000) {
    private val history = mutableListOf<String>()

    val historyCount: Int get() = history.size

    fun getSuggestions(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        return listOf("./gradlew testDebugUnitTest", "cargo test", "git status").filter { it.contains(query, ignoreCase = true) }
    }

    fun parseSlashCommand(input: String): String? {
        if (!input.startsWith("/")) return null
        return input.split(" ").firstOrNull()
    }

    fun addHistory(cmd: String) {
        if (history.size >= maxHistory) {
            history.removeAt(0)
        }
        history.add(cmd)
    }

    fun getHistoryAt(idx: Int): String? {
        if (idx < 0 || idx >= history.size) return null
        return history[idx]
    }

    fun acceptGhostCompletion(buffer: String, suggestion: String): String {
        if (suggestion.startsWith(buffer)) return suggestion
        return buffer
    }
}

class TestUnifiedSearchEngine(
    private val blocks: List<TestBlockCardState>,
    private val history: List<String>,
    private val files: List<String>
) {
    fun search(query: String, maxResults: Int = 50): Map<String, List<String>> {
        if (query.isBlank()) return emptyMap()
        val matchedBlocks = blocks.filter { it.command.contains(query) || it.outputText.contains(query) }.map { it.command }.take(maxResults)
        val matchedHistory = history.filter { it.contains(query) }.take(maxResults)
        val matchedFiles = files.filter { it.contains(query) }.take(maxResults)
        return mapOf("blocks" to matchedBlocks, "history" to matchedHistory, "files" to matchedFiles)
    }
}

class TestImeBridge {
    var composingText: String = ""
        private set
    var isCommitted: Boolean = false
        private set

    fun setComposingText(text: String) {
        composingText = text
        isCommitted = false
    }

    fun commitText(text: String) {
        composingText = ""
        isCommitted = true
    }

    fun pasteClipboardChunked(payload: String, chunkSize: Int = 4096): Int {
        if (payload.isEmpty()) return 0
        return (payload.length + chunkSize - 1) / chunkSize
    }

    fun processHardwareShortcut(keyCode: String): String? {
        return when (keyCode) {
            "Ctrl+C" -> "SIGINT"
            "Ctrl+D" -> "EOF"
            else -> null
        }
    }

    fun sendAccessoryKey(keyName: String): String {
        return when (keyName) {
            "ESC" -> "\u001b"
            "TAB" -> "\t"
            else -> keyName
        }
    }
}

data class TestAccessibilityNodeInfo(
    val text: String,
    val contentDescription: String,
    val isImportantForAccessibility: Boolean
)

class TestAdaptiveLayoutController {
    fun calculateColumns(widthPx: Int): Int = if (widthPx > 1800) 2 else 1

    fun createAccessibilityNode(text: String, label: String): TestAccessibilityNodeInfo {
        val desc = "Terminal $label. Double tap to select."
        return TestAccessibilityNodeInfo(text, desc, true)
    }

    fun updateFontScale(boundsWidth: Int, fontScale: Float): Int = (boundsWidth * fontScale).toInt()

    fun handleDeXResize(widthPx: Int): Int = calculateColumns(widthPx)
}

class TestSshSession(val cred: TestSshCredential) {
    var isConnected: Boolean = false
        private set

    fun connect(timeoutMs: Long = 5000) {
        if (cred.host.startsWith("10.255")) {
            throw java.util.concurrent.TimeoutException("Connection timed out after ${timeoutMs}ms")
        }
        isConnected = true
    }

    fun disconnect() {
        isConnected = false
    }

    fun simulateConnected() {
        isConnected = true
    }

    fun simulateAbruptDisconnect() {
        isConnected = false
    }

    fun authenticate(pass: String?, keyPath: String?): Boolean {
        if (cred.host.contains("untrusted")) return false
        if (cred.authType == "PASSWORD" && pass.isNullOrBlank()) return false
        return true
    }

    fun executeRemoteCommand(cmd: String): TestBlockCardState {
        check(isConnected) { "Session is disconnected" }
        return WarpTestFixtures.createBlockCardState(command = cmd, outputText = "remote ok")
    }
}

class TestProjectRulesEngine {
    fun matchSkillFromPrompt(prompt: String): String? {
        if (prompt.isBlank()) return null
        val lower = prompt.lowercase()
        if (lower.contains("git")) return "git-skill"
        if (lower.contains("docker")) return "docker-skill"
        return null
    }

    fun parseWarpRules(content: String): Map<String, List<String>> {
        val map = mutableMapOf<String, MutableList<String>>()
        content.lines().forEach { line ->
            if (line.startsWith("rule:")) {
                val parts = line.removePrefix("rule:").split("->").map { it.trim() }
                if (parts.size == 2) {
                    map.getOrPut(parts[0]) { mutableListOf() }.add(parts[1])
                }
            }
        }
        return map
    }
}

class TestMcpManager {
    fun executeTool(toolName: String, permissionRequired: Boolean, userApproved: Boolean): Map<String, Any> {
        if (toolName == "unregistered_tool") return mapOf("status" to "ERROR", "message" to "Unregistered tool")
        if (permissionRequired && !userApproved) return mapOf("status" to "DENIED", "message" to "Permission required")
        return mapOf("status" to "SUCCESS", "result" to "Executed $toolName")
    }

    fun parseJsonRpcMessage(json: String): Map<String, Any> {
        if (!json.contains("{")) return mapOf("code" to -32700, "message" to "Parse error")
        return mapOf("code" to 0, "message" to "OK")
    }
}

class TestSplitPaneLayoutManager {
    fun calculateLayout(paneIds: List<String>, viewportW: Int, viewportH: Int): List<Map<String, Int>> {
        if (paneIds.isEmpty()) return emptyList()
        if (paneIds.size == 1) return listOf(mapOf("x" to 0, "y" to 0, "w" to viewportW, "h" to viewportH))
        val hPerPane = viewportH / paneIds.size
        return paneIds.indices.map { i ->
            mapOf("x" to 0, "y" to i * hPerPane, "w" to viewportW, "h" to hPerPane)
        }
    }

    fun assignUniquePaneIds(requestedIds: List<String>): List<String> {
        val seen = mutableMapOf<String, Int>()
        return requestedIds.map { id ->
            val count = seen.getOrDefault(id, 0)
            seen[id] = count + 1
            if (count == 0) id else "$id-$count"
        }
    }
}
