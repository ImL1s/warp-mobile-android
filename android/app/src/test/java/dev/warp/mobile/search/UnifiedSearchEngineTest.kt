package dev.warp.mobile.search

import dev.warp.mobile.ProcessState
import dev.warp.mobile.SessionTab
import dev.warp.mobile.WarpBlockState
import dev.warp.mobile.WarpTimelineBlock
import dev.warp.mobile.editor.HistoryItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UnifiedSearchEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testEmptyAndBlankQueryReturnsEmptyList() = runBlocking {
        val resultsEmpty = UnifiedSearchEngine.search("")
        val resultsBlank = UnifiedSearchEngine.search("   ")

        assertTrue(resultsEmpty.isEmpty())
        assertTrue(resultsBlank.isEmpty())
    }

    @Test
    fun testSessionsDomainSearch() = runBlocking {
        val tabs = listOf(
            SessionTab(
                id = "session-1",
                title = "Backend Server",
                cwd = "/var/www/project",
                program = "/bin/bash",
                env = mapOf("NODE_ENV" to "production")
            ),
            SessionTab(
                id = "session-2",
                title = "Android Build",
                cwd = "/home/user/warp",
                program = "/bin/zsh"
            )
        )

        // 1. Search title match
        val resultsTitle = UnifiedSearchEngine.search(
            query = "Backend",
            selectedDomain = SearchDomain.SESSIONS,
            tabs = tabs
        )
        assertEquals(1, resultsTitle.size)
        assertTrue(resultsTitle.first() is UnifiedSearchResultItem.SessionResult)
        val sessionResult = resultsTitle.first() as UnifiedSearchResultItem.SessionResult
        assertEquals("Backend Server", sessionResult.sessionTitle)
        assertEquals("session-1", sessionResult.sessionId)

        // 2. Search env match
        val resultsEnv = UnifiedSearchEngine.search(
            query = "production",
            selectedDomain = SearchDomain.SESSIONS,
            tabs = tabs
        )
        assertEquals(1, resultsEnv.size)

        // 3. Search CWD match
        val resultsCwd = UnifiedSearchEngine.search(
            query = "warp",
            selectedDomain = SearchDomain.SESSIONS,
            tabs = tabs
        )
        assertEquals(1, resultsCwd.size)
        assertEquals("session-2", (resultsCwd.first() as UnifiedSearchResultItem.SessionResult).sessionId)
    }

    @Test
    fun testBlocksDomainSearchAndAnsiStripping() = runBlocking {
        val blocks = listOf(
            WarpBlockState(
                id = "block-1",
                command = "git status",
                output = "\u001B[32mOn branch main\u001B[0m\nYour branch is up to date.",
                exitCode = 0,
                timestamp = 1000L
            ),
            WarpBlockState(
                id = "block-2",
                command = "cargo build",
                output = "\u001B[31merror: could not compile `warp_host`\u001B[0m",
                exitCode = 101,
                timestamp = 2000L
            )
        )

        // Search ANSI stripped output
        val resultsAnsi = UnifiedSearchEngine.search(
            query = "could not compile",
            selectedDomain = SearchDomain.BLOCKS,
            blocks = blocks
        )

        assertEquals(1, resultsAnsi.size)
        val blockResult = resultsAnsi.first() as UnifiedSearchResultItem.BlockResult
        assertEquals("block-2", blockResult.blockId)
        assertEquals(101, blockResult.exitCode)
        assertTrue(blockResult.outputSnippet.contains("could not compile"))

        // Search command
        val resultsCmd = UnifiedSearchEngine.search(
            query = "git",
            selectedDomain = SearchDomain.BLOCKS,
            blocks = blocks
        )
        assertEquals(1, resultsCmd.size)
        assertEquals("block-1", (resultsCmd.first() as UnifiedSearchResultItem.BlockResult).blockId)
    }

    @Test
    fun testHistoryDomainSearch() = runBlocking {
        val history = listOf(
            HistoryItem(id = "h1", command = "docker ps -a", timestampMs = 5000L),
            HistoryItem(id = "h2", command = "./gradlew testDebugUnitTest", timestampMs = 6000L)
        )

        val results = UnifiedSearchEngine.search(
            query = "docker",
            selectedDomain = SearchDomain.HISTORY,
            history = history
        )

        assertEquals(1, results.size)
        val historyResult = results.first() as UnifiedSearchResultItem.HistoryResult
        assertEquals("h1", historyResult.historyId)
        assertEquals("docker ps -a", historyResult.command)
    }

    @Test
    fun testAiConversationsDomainSearch() = runBlocking {
        val timelineBlocks = listOf(
            WarpTimelineBlock.UserPromptBlock(
                id = "ai-1",
                sessionId = "s1",
                prompt = "How do I setup Vulkan renderer?",
                turnIndex = 1
            ),
            WarpTimelineBlock.AssistantResponseBlock(
                id = "ai-2",
                sessionId = "s1",
                turnIndex = 1,
                model = "claude-sonnet-4-6",
                content = "You can initialize Vulkan via VkInstanceCreateInfo and VulkanSwapchain."
            ),
            WarpTimelineBlock.ReasoningCardBlock(
                id = "ai-3",
                sessionId = "s1",
                thinkingText = "Analyzing Vulkan memory allocations and surface format choices."
            ),
            WarpTimelineBlock.ToolInvocationBlock(
                id = "ai-4",
                toolId = "t1",
                toolName = "execute_command",
                inputJson = "{\"command\":\"vulkaninfo\"}",
                output = "Vulkan Instance Version: 1.3.268"
            )
        )

        val resultsPrompt = UnifiedSearchEngine.search(
            query = "Vulkan renderer",
            selectedDomain = SearchDomain.AI,
            timelineBlocks = timelineBlocks
        )
        assertEquals(1, resultsPrompt.size)
        assertTrue((resultsPrompt.first() as UnifiedSearchResultItem.AiResult).isUserPrompt)

        val resultsTool = UnifiedSearchEngine.search(
            query = "vulkaninfo",
            selectedDomain = SearchDomain.AI,
            timelineBlocks = timelineBlocks
        )
        assertEquals(1, resultsTool.size)
        assertEquals("ai-4", (resultsTool.first() as UnifiedSearchResultItem.AiResult).blockId)
    }

    @Test
    fun testFilesDomainSearchAndIgnoredDirs() = runBlocking {
        val rootDir = tempFolder.newFolder("workspace")

        // Create standard files
        val srcDir = File(rootDir, "src").apply { mkdirs() }
        val targetFile = File(srcDir, "MainActivity.kt").apply { writeText("// Main Activity") }

        // Create ignored directory
        val buildDir = File(rootDir, "build").apply { mkdirs() }
        File(buildDir, "IgnoredMainActivity.kt").apply { writeText("// Ignored") }

        val results = UnifiedSearchEngine.search(
            query = "MainActivity",
            selectedDomain = SearchDomain.FILES,
            cwdPath = rootDir.absolutePath
        )

        assertEquals(1, results.size)
        val fileResult = results.first() as UnifiedSearchResultItem.FileResult
        assertEquals("MainActivity.kt", fileResult.fileName)
        assertEquals(targetFile.absolutePath, fileResult.filePath)
    }

    @Test
    fun testDomainFilteringAndCounts() = runBlocking {
        val tabs = listOf(SessionTab(id = "s1", title = "SearchTerm Session"))
        val history = listOf(HistoryItem(id = "h1", command = "SearchTerm Command"))

        val domainCounts = UnifiedSearchEngine.calculateDomainCounts(
            query = "SearchTerm",
            tabs = tabs,
            history = history
        )

        assertEquals(2, domainCounts[SearchDomain.ALL])
        assertEquals(1, domainCounts[SearchDomain.SESSIONS])
        assertEquals(1, domainCounts[SearchDomain.HISTORY])
        assertEquals(0, domainCounts[SearchDomain.BLOCKS])
        assertEquals(0, domainCounts[SearchDomain.AI])
        assertEquals(0, domainCounts[SearchDomain.FILES])
    }

    @Test
    fun testSearchWithCountsSinglePass() = runBlocking {
        val tabs = listOf(SessionTab(id = "s1", title = "SearchTerm Session"))
        val history = listOf(HistoryItem(id = "h1", command = "SearchTerm Command"))

        val (results, domainCounts) = UnifiedSearchEngine.searchWithCounts(
            query = "SearchTerm",
            selectedDomain = SearchDomain.ALL,
            tabs = tabs,
            history = history
        )

        assertEquals(2, results.size)
        assertEquals(2, domainCounts[SearchDomain.ALL])
        assertEquals(1, domainCounts[SearchDomain.SESSIONS])
        assertEquals(1, domainCounts[SearchDomain.HISTORY])
        assertEquals(0, domainCounts[SearchDomain.BLOCKS])
        assertEquals(0, domainCounts[SearchDomain.AI])
        assertEquals(0, domainCounts[SearchDomain.FILES])
    }
}
