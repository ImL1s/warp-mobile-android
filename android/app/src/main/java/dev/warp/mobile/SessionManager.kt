package dev.warp.mobile

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class SessionManager private constructor(private val context: Context? = null) {

    private val _appState = MutableStateFlow(WarpAppState())
    val appState: StateFlow<WarpAppState> = _appState.asStateFlow()

    private val _isRawMode = MutableStateFlow(false)
    val isRawMode: StateFlow<Boolean> = _isRawMode.asStateFlow()

    @Synchronized
    fun onToggleRawMode(raw: Boolean) {
        _appState.update { current ->
            val updated = current.onToggleRawMode(raw)
            _isRawMode.value = updated.isRawMode
            updated
        }
    }

    init {
        context?.let {
            if (!restoreSessionState()) {
                if (_appState.value.tabs.isEmpty()) {
                    createSession()
                }
            }
        }
    }

    @Synchronized
    fun createSession(
        title: String? = null,
        cwd: String = "~",
        program: String? = null,
        env: Map<String, String> = emptyMap()
    ): String {
        val sessionId = "session-${UUID.randomUUID().toString().take(8)}"
        val defaultTitle = title ?: "Terminal ${_appState.value.tabs.size + 1}"
        val prog = program ?: "/system/bin/sh"
        val newTab = SessionTab(
            id = sessionId,
            title = defaultTitle,
            cwd = cwd,
            program = prog,
            env = env,
            processState = ProcessState.INITIALIZING
        )

        _appState.update { current ->
            current.copy(
                tabs = current.tabs + newTab,
                activeSessionId = sessionId
            )
        }

        // Notify Rust native facade JNI bridge
        try {
            val envJson = JSONObject(env as Map<*, *>).toString()
            NativeBridge.createSession(sessionId, envJson)
            NativeBridge.switchSession(sessionId)
        } catch (e: Throwable) {
            // Unsat error or mock env in tests
        }

        // Start Foreground Service PTY session if context available
        context?.let { ctx ->
            try {
                val intent = Intent(ctx, WarpTerminalService::class.java).apply {
                    action = WarpTerminalService.ACTION_SPAWN
                    putExtra("cmd_id", sessionId)
                    putExtra("program", prog)
                    putExtra("cwd", cwd)
                }
                ctx.startForegroundService(intent)
            } catch (e: Throwable) {
                // In non-service test context
            }
        }

        saveSessionState()
        return sessionId
    }

    @Synchronized
    fun switchSession(sessionId: String): Boolean {
        if (_appState.value.tabs.none { it.id == sessionId }) return false
        _appState.update { current ->
            current.copy(activeSessionId = sessionId)
        }
        try {
            NativeBridge.switchSession(sessionId)
        } catch (e: Throwable) {
            // JNI error or test mock
        }
        saveSessionState()
        return true
    }

    @Synchronized
    fun closeSession(sessionId: String): Boolean {
        var found = false
        var newActiveId: String? = null

        _appState.update { current ->
            if (current.tabs.none { it.id == sessionId }) {
                found = false
                current
            } else {
                found = true
                val remainingTabs = current.tabs.filterNot { it.id == sessionId }
                val targetActiveId = if (current.activeSessionId == sessionId) {
                    remainingTabs.lastOrNull()?.id
                } else {
                    current.activeSessionId
                }
                newActiveId = targetActiveId
                current.copy(
                    tabs = remainingTabs,
                    activeSessionId = targetActiveId
                )
            }
        }

        if (!found) return false

        // Kill PTY process via Service if context available
        context?.let { ctx ->
            try {
                val killIntent = Intent(ctx, WarpTerminalService::class.java).apply {
                    action = WarpTerminalService.ACTION_KILL
                    putExtra("cmd_id", sessionId)
                }
                ctx.startService(killIntent)
            } catch (e: Throwable) {
                // Ignore in unit tests without context
            }
        }

        // Notify Rust native facade to release session and switch active session
        try {
            NativeBridge.closeSession(sessionId)
            newActiveId?.let { NativeBridge.switchSession(it) }
        } catch (e: Throwable) {
            // Ignore in unit tests
        }

        saveSessionState()
        return true
    }

    @Synchronized
    fun refreshBlocks(dumpJson: String? = null) {
        val json = dumpJson ?: try {
            NativeBridge.terminalBlocksDump()
        } catch (e: Throwable) {
            ""
        }
        _appState.update { current ->
            current.updateBlocksFromDump(json)
        }
    }

    @Synchronized
    fun updateProcessState(sessionId: String, state: ProcessState, exitCode: Int? = null) {
        val updatedState = _appState.updateAndGet { current ->
            current.copy(
                tabs = current.tabs.map { tab ->
                    if (tab.id == sessionId) tab.copy(processState = state, exitCode = exitCode)
                    else tab
                }
            )
        }
        if ((state == ProcessState.EXITED || state == ProcessState.ERROR) && sessionId == updatedState.activeSessionId) {
            onToggleRawMode(false)
        }
    }

    @Synchronized
    fun updateCwd(sessionId: String, newCwd: String) {
        _appState.update { current ->
            current.copy(
                tabs = current.tabs.map { tab ->
                    if (tab.id == sessionId) tab.copy(cwd = newCwd) else tab
                }
            )
        }
        saveSessionState()
    }

    @Synchronized
    fun saveSessionState(): Boolean {
        val ctx = context ?: return false
        val json = try {
            val nativeJson = NativeBridge.saveSessionState()
            if (nativeJson.isNotBlank() && nativeJson != "default") nativeJson else buildFallbackJsonState(_appState.value)
        } catch (e: Throwable) {
            buildFallbackJsonState(_appState.value)
        }
        return SessionPersistenceManager.saveSessionState(ctx, json)
    }

    @Synchronized
    fun restoreSessionState(): Boolean {
        val ctx = context ?: return false
        val json = SessionPersistenceManager.loadSessionState(ctx) ?: return false
        return try {
            val root = JSONObject(json)
            val activeId = if (root.has("active_session_id") && !root.isNull("active_session_id")) {
                root.getString("active_session_id")
            } else if (root.has("activeSessionId") && !root.isNull("activeSessionId")) {
                root.getString("activeSessionId")
            } else null

            val sessionsArray = if (root.has("sessions")) root.getJSONArray("sessions")
                                else if (root.has("tabs")) root.getJSONArray("tabs")
                                else null

            if (sessionsArray == null || sessionsArray.length() == 0) {
                throw IllegalArgumentException("Empty sessions in JSON")
            }

            // Attempt Rust native restoration
            try {
                NativeBridge.restoreSessionState(json)
            } catch (e: Throwable) {
                // Ignore JNI missing in test
            }

            val restoredTabs = mutableListOf<SessionTab>()
            val defaultHome = try { "${ctx.applicationInfo.dataDir}/files/home" } catch (e: Throwable) { "~" }

            for (i in 0 until sessionsArray.length()) {
                val tabObj = sessionsArray.getJSONObject(i)
                val id = tabObj.optString("id", "session-$i")
                val title = tabObj.optString("title", "Terminal ${i + 1}")
                val rawCwd = tabObj.optString("cwd", defaultHome)
                val resolvedCwd = if (rawCwd != "~" && File(rawCwd).isDirectory) rawCwd else defaultHome
                val program = tabObj.optString("program", "/system/bin/sh")
                val createdAtMs = tabObj.optLong("created_at_ms", tabObj.optLong("createdAtMs", System.currentTimeMillis()))

                val envMap = mutableMapOf<String, String>()
                if (tabObj.has("env") && !tabObj.isNull("env")) {
                    val envObj = tabObj.getJSONObject("env")
                    val keys = envObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        envMap[k] = envObj.getString(k)
                    }
                }

                val tab = SessionTab(
                    id = id,
                    title = title,
                    cwd = resolvedCwd,
                    program = program,
                    env = envMap,
                    createdAtMs = createdAtMs,
                    processState = ProcessState.INITIALIZING
                )
                restoredTabs.add(tab)
            }

            val targetActiveId = if (activeId != null && restoredTabs.any { it.id == activeId }) activeId else restoredTabs.lastOrNull()?.id

            _appState.update {
                WarpAppState(
                    tabs = restoredTabs,
                    activeSessionId = targetActiveId
                )
            }

            targetActiveId?.let {
                try {
                    NativeBridge.switchSession(it)
                } catch (e: Throwable) {}
            }

            // Re-attach PTY processes
            for (tab in restoredTabs) {
                try {
                    val intent = Intent(ctx, WarpTerminalService::class.java).apply {
                        action = WarpTerminalService.ACTION_SPAWN
                        putExtra("cmd_id", tab.id)
                        putExtra("program", tab.program)
                        putExtra("cwd", tab.cwd)
                    }
                    ctx.startForegroundService(intent)
                } catch (e: Throwable) {}
            }

            true
        } catch (e: Throwable) {
            SessionPersistenceManager.quarantineCorruptedFile(ctx)
            false
        }
    }

    private fun buildFallbackJsonState(state: WarpAppState): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("saved_at_ms", System.currentTimeMillis())
        if (state.activeSessionId != null) {
            root.put("active_session_id", state.activeSessionId)
        }
        val sessionsArray = JSONArray()
        for (tab in state.tabs) {
            val tabObj = JSONObject()
            tabObj.put("id", tab.id)
            tabObj.put("title", tab.title)
            tabObj.put("cwd", tab.cwd)
            tabObj.put("program", tab.program)
            tabObj.put("created_at_ms", tab.createdAtMs)
            val envObj = JSONObject()
            tab.env.forEach { (k, v) -> envObj.put(k, v) }
            tabObj.put("env", envObj)
            sessionsArray.put(tabObj)
        }
        root.put("sessions", sessionsArray)
        return root.toString()
    }

    @Synchronized
    fun setTimelineBlocks(blocks: List<WarpTimelineBlock>) {
        _appState.update { current ->
            current.copy(timelineBlocks = blocks)
        }
    }

    @Synchronized
    fun addTimelineBlock(block: WarpTimelineBlock) {
        _appState.update { current ->
            val list = if (current.timelineBlocks.isNotEmpty()) {
                current.timelineBlocks
            } else {
                current.blocks.map { WarpTimelineBlock.CommandBlock(it) }
            }
            current.copy(timelineBlocks = list + block)
        }
    }

    @Synchronized
    fun insertExplanationCard(
        targetCommandBlock: WarpBlockState,
        model: String = "claude-sonnet-4-6"
    ) {
        val activeSessionId = _appState.value.activeSessionId ?: "terminal_mode"
        val promptId = "prompt_${UUID.randomUUID().toString().take(8)}"
        val responseId = "resp_${UUID.randomUUID().toString().take(8)}"

        val promptBlock = WarpTimelineBlock.UserPromptBlock(
            id = promptId,
            sessionId = activeSessionId,
            prompt = "Explain command: ${targetCommandBlock.command}",
            turnIndex = 0
        )

        val explanationText = buildString {
            append("Explanation for `$ ${targetCommandBlock.command}`:\n\n")
            if (targetCommandBlock.exitCode != null) {
                append("• Exit Status: ${targetCommandBlock.exitCode}\n")
            }
            if (targetCommandBlock.durationMs != null) {
                append("• Execution Duration: ${targetCommandBlock.durationMs}ms\n")
            }
            if (targetCommandBlock.output.isNotBlank()) {
                append("\nOutput summary:\n")
                append(targetCommandBlock.output.lines().take(5).joinToString("\n"))
            } else {
                append("\nCommand produced no output.")
            }
        }

        val responseBlock = WarpTimelineBlock.AssistantResponseBlock(
            id = responseId,
            sessionId = activeSessionId,
            turnIndex = 0,
            model = model,
            content = explanationText,
            status = AgentTurnStatus.COMPLETED
        )

        _appState.update { current ->
            val list: MutableList<WarpTimelineBlock> = if (current.timelineBlocks.isNotEmpty()) {
                current.timelineBlocks.toMutableList()
            } else {
                current.blocks.map { WarpTimelineBlock.CommandBlock(it) as WarpTimelineBlock }.toMutableList()
            }
            val targetIdx = list.indexOfFirst { it is WarpTimelineBlock.CommandBlock && it.state.id == targetCommandBlock.id }
            if (targetIdx != -1) {
                list.add(targetIdx + 1, promptBlock)
                list.add(targetIdx + 2, responseBlock)
            } else {
                list.add(promptBlock)
                list.add(responseBlock)
            }
            current.copy(timelineBlocks = list)
        }
    }

    @Synchronized
    fun updateAssistantResponse(
        blockId: String,
        newContent: String,
        status: AgentTurnStatus,
        errorMessage: String? = null
    ) {
        _appState.update { current ->
            val updated = current.timelineBlocks.map { item ->
                if (item is WarpTimelineBlock.AssistantResponseBlock && item.id == blockId) {
                    item.copy(content = newContent, status = status, errorMessage = errorMessage)
                } else {
                    item
                }
            }
            current.copy(timelineBlocks = updated)
        }
    }

    fun resetForTesting() {
        _appState.value = WarpAppState()
        _isRawMode.value = false
        context?.let { SessionPersistenceManager.clearSessionState(it) }
        INSTANCE = null
    }


    companion object {
        @Volatile
        private var INSTANCE: SessionManager? = null

        fun getInstance(context: Context? = null): SessionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SessionManager(context?.applicationContext).also { INSTANCE = it }
            }
        }

        fun createForTesting(context: Context? = null): SessionManager {
            val instance = SessionManager(context)
            INSTANCE = instance
            return instance
        }
    }
}
