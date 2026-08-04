package dev.warp.mobile.search

import dev.warp.mobile.SessionTab
import dev.warp.mobile.WarpBlockState
import dev.warp.mobile.WarpTimelineBlock
import dev.warp.mobile.editor.HistoryItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

object UnifiedSearchEngine {

    suspend fun search(
        query: String,
        selectedDomain: SearchDomain = SearchDomain.ALL,
        tabs: List<SessionTab> = emptyList(),
        activeSessionId: String? = null,
        blocks: List<WarpBlockState> = emptyList(),
        timelineBlocks: List<WarpTimelineBlock> = emptyList(),
        history: List<HistoryItem> = emptyList(),
        cwdPath: String? = null,
        maxDepth: Int = 5,
        maxFileMatches: Int = 100,
        defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ): List<UnifiedSearchResultItem> = withContext(defaultDispatcher) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) return@withContext emptyList()

        coroutineScope {
            val sessionsDeferred = async(defaultDispatcher) {
                if (selectedDomain == SearchDomain.ALL || selectedDomain == SearchDomain.SESSIONS) {
                    searchSessions(trimmedQuery, tabs, activeSessionId)
                } else emptyList()
            }

            val blocksDeferred = async(defaultDispatcher) {
                if (selectedDomain == SearchDomain.ALL || selectedDomain == SearchDomain.BLOCKS) {
                    searchBlocks(trimmedQuery, blocks)
                } else emptyList()
            }

            val historyDeferred = async(defaultDispatcher) {
                if (selectedDomain == SearchDomain.ALL || selectedDomain == SearchDomain.HISTORY) {
                    searchHistory(trimmedQuery, history)
                } else emptyList()
            }

            val aiDeferred = async(defaultDispatcher) {
                if (selectedDomain == SearchDomain.ALL || selectedDomain == SearchDomain.AI) {
                    searchAiConversations(trimmedQuery, timelineBlocks)
                } else emptyList()
            }

            val filesDeferred = async(ioDispatcher) {
                if (selectedDomain == SearchDomain.ALL || selectedDomain == SearchDomain.FILES) {
                    searchFiles(trimmedQuery, cwdPath, maxDepth, maxFileMatches)
                } else emptyList()
            }

            val sessionsResults = sessionsDeferred.await()
            val blocksResults = blocksDeferred.await()
            val historyResults = historyDeferred.await()
            val aiResults = aiDeferred.await()
            val filesResults = filesDeferred.await()

            val combined = mutableListOf<UnifiedSearchResultItem>()
            combined.addAll(sessionsResults)
            combined.addAll(blocksResults)
            combined.addAll(historyResults)
            combined.addAll(aiResults)
            combined.addAll(filesResults)

            combined.sortedWith(
                compareByDescending<UnifiedSearchResultItem> { it.score }
                    .thenByDescending { it.timestamp }
            )
        }
    }

    suspend fun searchWithCounts(
        query: String,
        selectedDomain: SearchDomain = SearchDomain.ALL,
        tabs: List<SessionTab> = emptyList(),
        activeSessionId: String? = null,
        blocks: List<WarpBlockState> = emptyList(),
        timelineBlocks: List<WarpTimelineBlock> = emptyList(),
        history: List<HistoryItem> = emptyList(),
        cwdPath: String? = null,
        maxDepth: Int = 5,
        maxFileMatches: Int = 100,
        defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ): Pair<List<UnifiedSearchResultItem>, Map<SearchDomain, Int>> = withContext(defaultDispatcher) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            return@withContext emptyList<UnifiedSearchResultItem>() to SearchDomain.entries.associateWith { 0 }
        }

        coroutineScope {
            val sessionsDeferred = async(defaultDispatcher) {
                searchSessions(trimmedQuery, tabs, activeSessionId)
            }

            val blocksDeferred = async(defaultDispatcher) {
                searchBlocks(trimmedQuery, blocks)
            }

            val historyDeferred = async(defaultDispatcher) {
                searchHistory(trimmedQuery, history)
            }

            val aiDeferred = async(defaultDispatcher) {
                searchAiConversations(trimmedQuery, timelineBlocks)
            }

            val filesDeferred = async(ioDispatcher) {
                searchFiles(trimmedQuery, cwdPath, maxDepth, maxFileMatches)
            }

            val sessionsResults = sessionsDeferred.await()
            val blocksResults = blocksDeferred.await()
            val historyResults = historyDeferred.await()
            val aiResults = aiDeferred.await()
            val filesResults = filesDeferred.await()

            val totalCount = sessionsResults.size + blocksResults.size + historyResults.size + aiResults.size + filesResults.size
            val counts = mapOf(
                SearchDomain.ALL to totalCount,
                SearchDomain.SESSIONS to sessionsResults.size,
                SearchDomain.BLOCKS to blocksResults.size,
                SearchDomain.HISTORY to historyResults.size,
                SearchDomain.AI to aiResults.size,
                SearchDomain.FILES to filesResults.size
            )

            val filteredResults = when (selectedDomain) {
                SearchDomain.ALL -> {
                    val combined = mutableListOf<UnifiedSearchResultItem>()
                    combined.addAll(sessionsResults)
                    combined.addAll(blocksResults)
                    combined.addAll(historyResults)
                    combined.addAll(aiResults)
                    combined.addAll(filesResults)
                    combined
                }
                SearchDomain.SESSIONS -> sessionsResults
                SearchDomain.BLOCKS -> blocksResults
                SearchDomain.HISTORY -> historyResults
                SearchDomain.AI -> aiResults
                SearchDomain.FILES -> filesResults
            }

            val sorted = filteredResults.sortedWith(
                compareByDescending<UnifiedSearchResultItem> { it.score }
                    .thenByDescending { it.timestamp }
            )

            sorted to counts
        }
    }

    fun calculateDomainCounts(
        query: String,
        tabs: List<SessionTab> = emptyList(),
        activeSessionId: String? = null,
        blocks: List<WarpBlockState> = emptyList(),
        timelineBlocks: List<WarpTimelineBlock> = emptyList(),
        history: List<HistoryItem> = emptyList(),
        cwdPath: String? = null,
        maxDepth: Int = 5,
        maxFileMatches: Int = 100
    ): Map<SearchDomain, Int> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return SearchDomain.entries.associateWith { 0 }
        }

        val sessionsCount = searchSessions(trimmed, tabs, activeSessionId).size
        val blocksCount = searchBlocks(trimmed, blocks).size
        val historyCount = searchHistory(trimmed, history).size
        val aiCount = searchAiConversations(trimmed, timelineBlocks).size
        val filesCount = searchFiles(trimmed, cwdPath, maxDepth, maxFileMatches).size
        val totalCount = sessionsCount + blocksCount + historyCount + aiCount + filesCount

        return mapOf(
            SearchDomain.ALL to totalCount,
            SearchDomain.SESSIONS to sessionsCount,
            SearchDomain.BLOCKS to blocksCount,
            SearchDomain.HISTORY to historyCount,
            SearchDomain.AI to aiCount,
            SearchDomain.FILES to filesCount
        )
    }

    internal fun searchSessions(
        query: String,
        tabs: List<SessionTab>,
        activeSessionId: String?
    ): List<UnifiedSearchResultItem.SessionResult> {
        val results = mutableListOf<UnifiedSearchResultItem.SessionResult>()
        for (tab in tabs) {
            val envMatch = tab.env.any { (k, v) ->
                "$k=$v".contains(query, ignoreCase = true)
            }
            val titleMatch = tab.title.contains(query, ignoreCase = true)
            val cwdMatch = tab.cwd.contains(query, ignoreCase = true)
            val programMatch = tab.program.contains(query, ignoreCase = true)
            val idMatch = tab.id.contains(query, ignoreCase = true)

            if (titleMatch || cwdMatch || programMatch || idMatch || envMatch) {
                val matchRange = findMatchRange(tab.title, query) ?: findMatchRange(tab.cwd, query)
                var score = calculateBaseScore(tab.title, query)
                if (tab.id == activeSessionId) score += 10.0f

                results.add(
                    UnifiedSearchResultItem.SessionResult(
                        id = "session-${tab.id}",
                        sessionId = tab.id,
                        sessionTitle = tab.title,
                        cwd = tab.cwd,
                        program = tab.program,
                        processState = tab.processState,
                        matchRange = matchRange,
                        timestamp = tab.createdAtMs,
                        score = score
                    )
                )
            }
        }
        return results
    }

    internal fun searchBlocks(
        query: String,
        blocks: List<WarpBlockState>
    ): List<UnifiedSearchResultItem.BlockResult> {
        val results = mutableListOf<UnifiedSearchResultItem.BlockResult>()
        for (block in blocks) {
            val cleanCmd = BlockSearchEngine.stripAnsiCodes(block.command)
            val cleanOut = BlockSearchEngine.stripAnsiCodes(block.output)

            val cmdMatch = cleanCmd.contains(query, ignoreCase = true)
            val outMatch = cleanOut.contains(query, ignoreCase = true)

            if (cmdMatch || outMatch) {
                val matchText = if (cmdMatch) cleanCmd else cleanOut
                val matchRange = findMatchRange(matchText, query)
                val snippet = if (outMatch) {
                    val idx = cleanOut.indexOf(query, ignoreCase = true)
                    val start = (idx - 20).coerceAtLeast(0)
                    val end = (idx + query.length + 60).coerceAtMost(cleanOut.length)
                    cleanOut.substring(start, end).trim()
                } else {
                    cleanOut.take(120)
                }

                var score = if (cmdMatch) calculateBaseScore(cleanCmd, query) else calculateBaseScore(cleanOut, query) * 0.7f
                if (block.exitCode != null && block.exitCode != 0) score += 5.0f

                results.add(
                    UnifiedSearchResultItem.BlockResult(
                        id = "block-${block.id}",
                        blockId = block.id,
                        command = block.command,
                        outputSnippet = snippet,
                        exitCode = block.exitCode,
                        isCommandMatch = cmdMatch,
                        matchRange = matchRange,
                        timestamp = block.timestamp,
                        score = score
                    )
                )
            }
        }
        return results
    }

    internal fun searchHistory(
        query: String,
        history: List<HistoryItem>
    ): List<UnifiedSearchResultItem.HistoryResult> {
        val results = mutableListOf<UnifiedSearchResultItem.HistoryResult>()
        for (item in history) {
            if (item.command.contains(query, ignoreCase = true)) {
                val matchRange = findMatchRange(item.command, query)
                val score = calculateBaseScore(item.command, query)

                results.add(
                    UnifiedSearchResultItem.HistoryResult(
                        id = "hist-${item.id}",
                        historyId = item.id,
                        command = item.command,
                        matchRange = matchRange,
                        timestamp = item.timestampMs,
                        score = score
                    )
                )
            }
        }
        return results
    }

    internal fun searchAiConversations(
        query: String,
        timelineBlocks: List<WarpTimelineBlock>
    ): List<UnifiedSearchResultItem.AiResult> {
        val results = mutableListOf<UnifiedSearchResultItem.AiResult>()
        for (block in timelineBlocks) {
            when (block) {
                is WarpTimelineBlock.UserPromptBlock -> {
                    if (block.prompt.contains(query, ignoreCase = true)) {
                        results.add(
                            UnifiedSearchResultItem.AiResult(
                                id = "ai-prompt-${block.id}",
                                blockId = block.id,
                                sessionId = block.sessionId,
                                turnType = "User Prompt",
                                model = null,
                                snippet = block.prompt,
                                promptOrResponse = block.prompt,
                                isUserPrompt = true,
                                matchRange = findMatchRange(block.prompt, query),
                                timestamp = block.timestamp,
                                score = calculateBaseScore(block.prompt, query)
                            )
                        )
                    }
                }
                is WarpTimelineBlock.AssistantResponseBlock -> {
                    val contentMatch = block.content.contains(query, ignoreCase = true)
                    val modelMatch = block.model.contains(query, ignoreCase = true)
                    if (contentMatch || modelMatch) {
                        results.add(
                            UnifiedSearchResultItem.AiResult(
                                id = "ai-resp-${block.id}",
                                blockId = block.id,
                                sessionId = block.sessionId,
                                turnType = "Assistant Response",
                                model = block.model,
                                snippet = block.content.take(120),
                                promptOrResponse = block.content.take(80),
                                isUserPrompt = false,
                                matchRange = findMatchRange(block.content, query),
                                timestamp = block.timestamp,
                                score = calculateBaseScore(block.content, query)
                            )
                        )
                    }
                }
                is WarpTimelineBlock.ReasoningCardBlock -> {
                    if (block.thinkingText.contains(query, ignoreCase = true)) {
                        results.add(
                            UnifiedSearchResultItem.AiResult(
                                id = "ai-think-${block.id}",
                                blockId = block.id,
                                sessionId = block.sessionId,
                                turnType = "Reasoning Card",
                                model = null,
                                snippet = block.thinkingText.take(120),
                                promptOrResponse = block.thinkingText.take(80),
                                isUserPrompt = false,
                                matchRange = findMatchRange(block.thinkingText, query),
                                timestamp = block.timestamp,
                                score = calculateBaseScore(block.thinkingText, query) * 0.8f
                            )
                        )
                    }
                }
                is WarpTimelineBlock.ToolInvocationBlock -> {
                    val toolMatch = block.toolName.contains(query, ignoreCase = true)
                    val inputMatch = block.inputJson.contains(query, ignoreCase = true)
                    val outputMatch = block.output?.contains(query, ignoreCase = true) == true

                    if (toolMatch || inputMatch || outputMatch) {
                        val displayStr = "Tool: ${block.toolName} ${block.inputJson}"
                        results.add(
                            UnifiedSearchResultItem.AiResult(
                                id = "ai-tool-${block.id}",
                                blockId = block.id,
                                sessionId = null,
                                turnType = "Tool Invocation",
                                model = null,
                                snippet = block.output?.take(120) ?: block.inputJson.take(120),
                                promptOrResponse = displayStr.take(80),
                                isUserPrompt = false,
                                matchRange = findMatchRange(displayStr, query),
                                timestamp = block.timestamp,
                                score = calculateBaseScore(block.toolName, query) * 0.85f
                            )
                        )
                    }
                }
                else -> {}
            }
        }
        return results
    }

    internal fun searchFiles(
        query: String,
        cwdPath: String?,
        maxDepth: Int = 5,
        maxMatches: Int = 100
    ): List<UnifiedSearchResultItem.FileResult> {
        if (cwdPath.isNullByBlank()) return emptyList()

        val rootDir = try {
            val path = if (cwdPath == "~") System.getProperty("user.home") ?: "." else cwdPath
            File(path)
        } catch (e: Throwable) {
            return emptyList()
        }

        if (!rootDir.exists() || !rootDir.isDirectory) return emptyList()

        val results = mutableListOf<UnifiedSearchResultItem.FileResult>()

        fun walkDir(dir: File, currentDepth: Int) {
            if (currentDepth > maxDepth || results.size >= maxMatches) return
            val children = dir.listFiles() ?: return

            for (file in children) {
                if (results.size >= maxMatches) break
                val name = file.name

                // Skip hidden files/dirs and standard build dirs
                if (name.startsWith(".") || name == "build" || name == "node_modules") {
                    continue
                }

                val relativePath = try {
                    rootDir.toPath().relativize(file.toPath()).toString()
                } catch (e: Throwable) {
                    file.name
                }

                val nameMatch = name.contains(query, ignoreCase = true)
                val pathMatch = relativePath.contains(query, ignoreCase = true)

                if (nameMatch || pathMatch) {
                    val matchRange = findMatchRange(name, query) ?: findMatchRange(relativePath, query)
                    val score = calculateBaseScore(name, query)

                    results.add(
                        UnifiedSearchResultItem.FileResult(
                            id = "file-${file.absolutePath.hashCode()}",
                            filePath = file.absolutePath,
                            fileName = name,
                            relativePath = relativePath,
                            isDirectory = file.isDirectory,
                            sizeBytes = if (file.isFile) file.length() else 0L,
                            matchRange = matchRange,
                            timestamp = file.lastModified(),
                            score = score
                        )
                    )
                }

                if (file.isDirectory) {
                    walkDir(file, currentDepth + 1)
                }
            }
        }

        walkDir(rootDir, 0)
        return results
    }

    private fun findMatchRange(text: String, query: String): IntRange? {
        if (query.isEmpty()) return null
        val idx = text.indexOf(query, ignoreCase = true)
        return if (idx != -1) idx until (idx + query.length) else null
    }

    private fun calculateBaseScore(text: String, query: String): Float {
        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()
        return when {
            lowerText == lowerQuery -> 100.0f
            lowerText.startsWith(lowerQuery) -> 80.0f
            lowerText.contains(lowerQuery) -> 50.0f
            else -> 10.0f
        }
    }

    private fun String?.isNullByBlank(): Boolean = this.isNullOrBlank() || this == "~"
}
