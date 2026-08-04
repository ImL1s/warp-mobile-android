package dev.warp.mobile.search

import dev.warp.mobile.ProcessState
import dev.warp.mobile.SessionTab
import dev.warp.mobile.WarpBlockState
import dev.warp.mobile.WarpTimelineBlock
import dev.warp.mobile.editor.HistoryItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UnifiedSearchEmpiricalStressTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testEmptyAndWhitespaceQueriesReturnEmptyAcrossAllDomains() = runBlocking {
        val emptyQueries = listOf("", "   ", "\t", "\n", " \t \n ")
        for (q in emptyQueries) {
            val results = UnifiedSearchEngine.search(q)
            assertTrue("Query '$q' should yield empty results", results.isEmpty())

            val domainCounts = UnifiedSearchEngine.calculateDomainCounts(q)
            for ((domain, count) in domainCounts) {
                assertEquals("Domain $domain count for '$q' should be 0", 0, count)
            }
        }
    }

    @Test
    fun testSpecialRegexCharactersAreMatchedLiterally() = runBlocking {
        val specialQueries = listOf(
            ".*", "[a-z]", "(foo|bar)", "\\d+", "?", "$", "^", "{1,3}", "%", "\\", "\"", "'", "<script>", "null"
        )

        val blocks = listOf(
            WarpBlockState(id = "b1", command = "echo .* [a-z]", output = "(foo|bar) \\d+ ? $ ^ {1,3} % \\ \" ' <script> null")
        )

        for (q in specialQueries) {
            val results = UnifiedSearchEngine.search(
                query = q,
                selectedDomain = SearchDomain.BLOCKS,
                blocks = blocks
            )
            assertFalse("Special query '$q' should safely match without crashing", results.isEmpty())
            val match = results.first() as UnifiedSearchResultItem.BlockResult
            assertNotNull(match.matchRange)
        }
    }

    @Test
    fun testExtremelyLongQueryStringsDoNotCrash() = runBlocking {
        val longQuery = "a".repeat(10_000)
        val extremelyLongQuery = "x".repeat(100_000)

        val blocks = listOf(
            WarpBlockState(id = "b1", command = "ls -la", output = "total 0\n" + "a".repeat(20_000))
        )

        // 10k long query match
        val res1 = UnifiedSearchEngine.search(
            query = longQuery,
            selectedDomain = SearchDomain.BLOCKS,
            blocks = blocks
        )
        assertEquals(1, res1.size)

        // 100k long query no match
        val res2 = UnifiedSearchEngine.search(
            query = extremelyLongQuery,
            selectedDomain = SearchDomain.BLOCKS,
            blocks = blocks
        )
        assertTrue(res2.isEmpty())
    }

    @Test
    fun testDeepDirectoryStructuresAndMaxDepthExclusion() = runBlocking {
        val rootDir = tempFolder.newFolder("deep_workspace")
        var currentDir = rootDir

        // Create 10 nested directory levels
        for (depth in 1..10) {
            currentDir = File(currentDir, "level_$depth").apply { mkdirs() }
            File(currentDir, "target_file_$depth.txt").apply { writeText("Level $depth content") }
        }

        // Search with maxDepth = 5
        val resultsDepth5 = UnifiedSearchEngine.search(
            query = "target_file",
            selectedDomain = SearchDomain.FILES,
            cwdPath = rootDir.absolutePath,
            maxDepth = 5
        )

        // Should find levels 1 through 5, but NOT 6 through 10
        assertTrue("Results size (${resultsDepth5.size}) should be 5", resultsDepth5.size == 5)
        for (item in resultsDepth5) {
            val fileRes = item as UnifiedSearchResultItem.FileResult
            val level = fileRes.fileName.removePrefix("target_file_").removeSuffix(".txt").toInt()
            assertTrue("Level $level should be <= 5", level <= 5)
        }
    }

    @Test
    fun testMalformedAnsiEscapeSequencesInTerminalOutput() = runBlocking {
        val malformedAnsiOutputs = listOf(
            "\u001B[38;2;255; incomplete CSI",
            "\u001B]8;;http://foo unterminated OSC",
            "\u001B[99999m crazy code",
            "\u001B[31mRed \u001B[32mGreen \u001B[0mReset",
            "\u001B[1;2;3;4;5;6;7;8;9m multi-sgr \u001B[m",
            "\u0000\u0001\u0002 null and control bytes \u001B["
        )

        val blocks = malformedAnsiOutputs.mapIndexed { idx, out ->
            WarpBlockState(id = "ansi-$idx", command = "cmd-$idx", output = out)
        }

        val results = UnifiedSearchEngine.search(
            query = "CSI",
            selectedDomain = SearchDomain.BLOCKS,
            blocks = blocks
        )

        assertEquals(1, results.size)
        val match = results.first() as UnifiedSearchResultItem.BlockResult
        assertEquals("ansi-0", match.blockId)
    }

    @Test
    fun testHighScalePerformanceAcross5Domains() = runBlocking {
        val tabs = (1..500).map { i ->
            SessionTab(id = "s-$i", title = "Session $i", cwd = "/path/to/session_$i", program = "bash")
        }
        val blocks = (1..2000).map { i ->
            WarpBlockState(id = "b-$i", command = "git commit -m \"feature $i\"", output = "Output for block $i")
        }
        val history = (1..5000).map { i ->
            HistoryItem(id = "h-$i", command = "history command $i", timestampMs = i.toLong())
        }
        val timelineBlocks = (1..1000).map { i ->
            WarpTimelineBlock.UserPromptBlock(id = "ai-$i", sessionId = "s-1", prompt = "AI prompt $i", turnIndex = i)
        }

        val startTime = System.currentTimeMillis()
        val results = UnifiedSearchEngine.search(
            query = "feature 1500",
            selectedDomain = SearchDomain.ALL,
            tabs = tabs,
            blocks = blocks,
            history = history,
            timelineBlocks = timelineBlocks
        )
        val durationMs = System.currentTimeMillis() - startTime

        assertEquals(1, results.size)
        assertTrue("Search over 8,500 items completed in $durationMs ms", durationMs < 2000)
    }
}
