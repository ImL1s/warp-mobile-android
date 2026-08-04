package dev.warp.mobile.test

import java.util.UUID

data class TestSessionHandle(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "session-test-1",
    val workingDir: String = "/home/user",
    val isActive: Boolean = true,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

data class TestBlockCardState(
    val blockId: String = "block-${UUID.randomUUID().toString().take(8)}",
    val command: String = "ls -la",
    val exitCode: Int? = 0,
    val outputText: String = "total 12\ndrwxr-xr-x 2 user user 4096 Aug 3 15:00 .\n",
    val isRunning: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
)

data class TestTerminalModelState(
    val cols: Int = 80,
    val rows: Int = 24,
    val cursorX: Int = 0,
    val cursorY: Int = 0,
    val bufferLines: List<String> = listOf("welcome to warp-mobile"),
    val selectedText: String? = null
)

data class TestPtyProcess(
    val pid: Int = 1234,
    val cmdId: String = "cmd-001",
    val masterFd: Int = 10,
    val isAlive: Boolean = true,
    val ttyPath: String = "/dev/pts/1"
)

data class TestAnthropicMessageStreamChunk(
    val index: Int = 0,
    val deltaText: String = "",
    val type: String = "text_delta",
    val isDone: Boolean = false
)

data class TestSshCredential(
    val host: String = "192.168.1.100",
    val port: Int = 22,
    val username: String = "warp_user",
    val authType: String = "KEY", // "PASSWORD" or "KEY"
    val privateKeyPath: String? = "/home/user/.ssh/id_ed25519",
    val passphrase: String? = null
)

data class TestProjectRule(
    val pattern: String = "rm -rf *",
    val action: String = "DENY"
)

data class TestMcpToolConfig(
    val name: String = "fs_read",
    val endpoint: String = "stdio://mcp-server",
    val permissions: List<String> = listOf("READ")
)

data class TestSplitPaneConfig(
    val orientation: String = "HORIZONTAL",
    val paneRatio: Float = 0.5f
)

data class TestSearchQueryResult(
    val domain: String = "SESSIONS",
    val snippet: String = "main terminal",
    val id: String = UUID.randomUUID().toString()
)

data class TestAccessibilityNode(
    val contentDescription: String = "Terminal Output",
    val role: String = "textbox",
    val isImportantForAccessibility: Boolean = true
)

data class TestLedgerEntry(
    val id: String,
    val timestampMs: Long,
    val type: String,
    val checksum: String
)

data class TestLedgerReconciliationResult(
    val isSynchronized: Boolean,
    val missingLocalIds: List<String>,
    val mismatchedChecksumIds: List<String>
)

data class TestTabInfo(val id: String, val name: String, val workingDir: String)

fun reconcileLedger(
    local: List<TestLedgerEntry>,
    remote: List<TestLedgerEntry>
): TestLedgerReconciliationResult {
    val localMap = local.associateBy { it.id }
    val remoteMap = remote.associateBy { it.id }

    val missingLocal = remoteMap.keys.filter { !localMap.containsKey(it) }
    val mismatched = localMap.entries.filter { (id, entry) ->
        val rem = remoteMap[id]
        rem != null && rem.checksum != entry.checksum
    }.map { it.key }

    val isSync = missingLocal.isEmpty() && mismatched.isEmpty() && local.size == remote.size
    return TestLedgerReconciliationResult(isSync, missingLocal, mismatched)
}

fun verifySourcePin(sha: String, validList: List<String>): Boolean =
    validList.contains(sha)

class TestWxStageManager {
    private val writable = mutableSetOf<String>()
    private val executable = mutableSetOf<String>()

    fun beginStaging(dir: String) {
        writable.add(dir)
        executable.remove(dir)
    }

    fun finalizeStaging(tmpDir: String, finalDir: String) {
        writable.remove(tmpDir)
        writable.remove(finalDir)
        executable.add(finalDir)
    }

    fun isWritable(dir: String): Boolean = writable.contains(dir)
    fun isExecutable(dir: String): Boolean = executable.contains(dir)
}

class TestCanonicalFacade(val maxCapacity: Int = 10) {
    var isInitialized: Boolean = false
        private set
    var activeSessionId: String? = null
        private set

    fun initializeSession(id: String, dir: String): Boolean {
        if (isInitialized) return false
        isInitialized = true
        activeSessionId = id
        return true
    }

    fun tearDown() {
        isInitialized = false
        activeSessionId = null
    }
}

class TestWarpAppState(val maxTabs: Int = 5) {
    val tabs = mutableListOf<TestTabInfo>()
    var activeTabId: String? = null
        private set

    fun createTab(name: String, dir: String): String {
        if (tabs.size >= maxTabs) throw IllegalStateException("Max tab limit reached")
        val id = "tab-${UUID.randomUUID().toString().take(6)}"
        val tab = TestTabInfo(id, name, dir)
        tabs.add(tab)
        activeTabId = id
        return id
    }

    fun selectTab(id: String) {
        if (tabs.any { it.id == id }) {
            activeTabId = id
        }
    }

    fun closeTab(id: String) {
        tabs.removeAll { it.id == id }
        if (activeTabId == id) {
            activeTabId = tabs.lastOrNull()?.id
        }
    }
}

data class TestSessionSnapshot(
    val sessionId: String,
    val workingDir: String,
    val activeTabName: String,
    val scrollOffset: Int,
    val bufferSnippet: String
) {
    fun toJson(): String =
        "{\"sessionId\":\"$sessionId\",\"workingDir\":\"$workingDir\",\"activeTabName\":\"$activeTabName\",\"scrollOffset\":$scrollOffset,\"bufferSnippet\":\"$bufferSnippet\"}"

    companion object {
        fun fromJson(json: String): TestSessionSnapshot? {
            return try {
                val getVal = { key: String ->
                    val pattern = "\"$key\":\"([^\"]+)\"".toRegex()
                    pattern.find(json)?.groupValues?.get(1)
                }
                val getIntVal = { key: String ->
                    val pattern = "\"$key\":(\\d+)".toRegex()
                    pattern.find(json)?.groupValues?.get(1)?.toIntOrNull()
                }
                TestSessionSnapshot(
                    sessionId = getVal("sessionId") ?: return null,
                    workingDir = getVal("workingDir") ?: "/",
                    activeTabName = getVal("activeTabName") ?: "default",
                    scrollOffset = getIntVal("scrollOffset") ?: 0,
                    bufferSnippet = getVal("bufferSnippet") ?: ""
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

class TestFgsPtyServiceManager {
    var isFgsActive: Boolean = false
        private set
    private val activePtys = mutableMapOf<String, Int>()

    val activePtyCount: Int get() = activePtys.size

    fun registerPtyProcess(cmdId: String, pid: Int): TestPtyProcess {
        activePtys[cmdId] = pid
        isFgsActive = true
        return WarpTestFixtures.createPtyProcess(pid = pid, cmdId = cmdId)
    }

    fun terminatePtyProcess(cmdId: String) {
        activePtys.remove(cmdId)
    }

    fun stopForegroundService() {
        activePtys.clear()
        isFgsActive = false
    }
}

data class TestArtifactEntry(val fileName: String, val sha256: String) {
    val name: String get() = fileName
}

data class TestReleaseManifest(val versionName: String, val buildNumber: Int, val artifacts: List<TestArtifactEntry>)

class TestReleasePipelineValidator(private val manifest: TestReleaseManifest? = null) {
    fun verifyArtifactChecksum(fileName: String, actualSha256: String): Boolean {
        if (manifest == null) return false
        val expected = manifest.artifacts.find { it.fileName == fileName || it.name == fileName }?.sha256 ?: return false
        return expected.equals(actualSha256, ignoreCase = true)
    }

    fun verifyArtifactManifest(manifestMap: Map<String, String>, files: Map<String, String>): Boolean {
        if (manifestMap.isEmpty()) return false
        return manifestMap.all { (name, sha) ->
            files.containsKey(name) && files[name]?.lowercase() == sha.lowercase()
        }
    }

    fun verifySemVer(versionName: String): Boolean {
        val regex = Regex("^\\d+\\.\\d+\\.\\d+(-[a-zA-Z0-9.]+)?$")
        return regex.matches(versionName)
    }

    fun verifyBuildNumber(buildNumber: Int): Boolean = buildNumber > 0
}

class TestPyramidConfiguration {
    fun getMinTargetForTier(tier: Int): Int {
        return when (tier) {
            1 -> 125
            2 -> 125
            3 -> 25
            4 -> 15
            else -> 0
        }
    }
    val totalMinTarget: Int get() = 125 + 125 + 25 + 15
}

data class TestPaneConfig(val id: String, val cwd: String, val command: String)
data class TestLaunchConfig(val profileName: String, val panes: List<TestPaneConfig>)
data class TestPaneRect(val x: Int, val y: Int, val w: Int, val h: Int)

class TestSplitPaneGridManager {
    var activePaneId: String = ""
        private set
    private val panes = mutableListOf<TestPaneConfig>()

    val paneCount: Int get() = panes.size

    fun loadLaunchConfig(config: TestLaunchConfig) {
        panes.clear()
        panes.addAll(config.panes)
        activePaneId = panes.firstOrNull()?.id ?: ""
    }

    fun setActivePane(id: String) {
        if (panes.any { it.id == id }) {
            activePaneId = id
        }
    }

    fun calculateLayout(widthPx: Int, heightPx: Int): List<TestPaneRect> {
        if (panes.size == 4) {
            val halfW = widthPx / 2
            val halfH = heightPx / 2
            return listOf(
                TestPaneRect(0, 0, halfW, halfH),
                TestPaneRect(halfW, 0, halfW, halfH),
                TestPaneRect(0, halfH, halfW, halfH),
                TestPaneRect(halfW, halfH, halfW, halfH)
            )
        }
        if (panes.isEmpty()) return emptyList()
        val hPerPane = if (panes.size > 0) heightPx / panes.size else 0
        return panes.indices.map { i -> TestPaneRect(0, i * hPerPane, widthPx, hPerPane) }
    }
}

object TestSecretScrubber {
    private val API_KEY_REGEX = Regex("sk-ant-[A-Za-z0-9_-]{4,}")

    fun scrubApiKey(input: String): String =
        input.replace(API_KEY_REGEX, "sk-ant-***REDACTED***")

    fun scrubSecrets(input: String): String = scrubApiKey(input)

    fun sanitizeEnvMap(env: Map<String, String>): Map<String, String> {
        return env.mapValues { (k, v) ->
            if (k.contains("SECRET") || k.contains("KEY") || k.contains("TOKEN") || v.startsWith("sk-ant-")) {
                "[REDACTED]"
            } else {
                v
            }
        }
    }
}

data class TestMcpExecutionResult(
    val isSuccess: Boolean,
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = 0
)


object WarpTestFixtures {
    fun createSessionHandle(
        id: String = UUID.randomUUID().toString(),
        name: String = "test-session",
        workingDir: String = "/workspace",
        isActive: Boolean = true
    ): TestSessionHandle = TestSessionHandle(id, name, workingDir, isActive)

    fun createBlockCardState(
        blockId: String = "block-1",
        command: String = "echo 'hello warp'",
        exitCode: Int? = 0,
        outputText: String = "hello warp\n",
        isRunning: Boolean = false
    ): TestBlockCardState = TestBlockCardState(blockId, command, exitCode, outputText, isRunning)

    fun createTerminalModel(
        cols: Int = 80,
        rows: Int = 24,
        lines: List<String> = listOf("$ echo hello", "hello")
    ): TestTerminalModelState = TestTerminalModelState(cols, rows, 0, 1, lines, null)

    fun createPtyProcess(
        pid: Int = 4200,
        cmdId: String = "cmd-101",
        masterFd: Int = 12
    ): TestPtyProcess = TestPtyProcess(pid, cmdId, masterFd, true, "/dev/pts/2")

    fun createAnthropicStream(
        responseChunks: List<String> = listOf("Hello", " from", " Warp", " AI!")
    ): List<TestAnthropicMessageStreamChunk> {
        val result = mutableListOf<TestAnthropicMessageStreamChunk>()
        responseChunks.forEachIndexed { idx, chunk ->
            result.add(TestAnthropicMessageStreamChunk(idx, chunk, "text_delta", false))
        }
        result.add(TestAnthropicMessageStreamChunk(responseChunks.size, "", "message_stop", true))
        return result
    }

    fun createSshCredential(
        host: String = "dev.warp.local",
        port: Int = 22,
        username: String = "warpdev"
    ): TestSshCredential = TestSshCredential(host, port, username, "KEY", "~/.ssh/id_rsa")

    fun createProjectRule(
        pattern: String = "rm -rf *",
        action: String = "DENY"
    ): TestProjectRule = TestProjectRule(pattern, action)

    fun createMcpToolConfig(
        name: String = "fs_read",
        endpoint: String = "stdio://mcp-server",
        permissions: List<String> = listOf("READ")
    ): TestMcpToolConfig = TestMcpToolConfig(name, endpoint, permissions)

    fun createSplitPaneConfig(
        orientation: String = "HORIZONTAL",
        paneRatio: Float = 0.5f
    ): TestSplitPaneConfig = TestSplitPaneConfig(orientation, paneRatio)

    fun createSearchQueryResult(
        domain: String = "SESSIONS",
        snippet: String = "main terminal",
        id: String = UUID.randomUUID().toString()
    ): TestSearchQueryResult = TestSearchQueryResult(domain, snippet, id)

    fun createAccessibilityNode(
        contentDescription: String = "Terminal Output",
        role: String = "textbox"
    ): TestAccessibilityNode = TestAccessibilityNode(contentDescription, role)
}

