package dev.warp.mobile.search

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import dev.warp.mobile.WarpBlockState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockSearchEngineTest {

    @Test
    fun testEmptyQuery_returnsNoMatches() = runBlocking {
        val blocks = listOf(
            WarpBlockState(id = "b1", command = "ls -la", output = "file1.txt\nfile2.txt")
        )
        val result = BlockSearchEngine.search(blocks, "")
        assertTrue(result.matches.isEmpty())
        assertTrue(result.matchedBlockIds.isEmpty())
    }

    @Test
    fun testPlainSearch_matchesCommandAndOutput() = runBlocking {
        val blocks = listOf(
            WarpBlockState(id = "b1", command = "git status", output = "On branch main\nnothing to commit"),
            WarpBlockState(id = "b2", command = "cat error.log", output = "ERROR: connection failed\nINFO: retrying")
        )

        val result = BlockSearchEngine.search(blocks, "error")
        assertEquals(2, result.matches.size)
        assertEquals(1, result.matchedBlockIds.size)
        assertTrue(result.matchedBlockIds.contains("b2"))

        val cmdMatch = result.matches[0]
        assertEquals("b2", cmdMatch.blockId)
        assertTrue(cmdMatch.isCommandMatch)
        assertEquals(4 until 9, cmdMatch.matchRange)

        val outMatch = result.matches[1]
        assertEquals("b2", outMatch.blockId)
        assertFalse(outMatch.isCommandMatch)
        assertEquals(0 until 5, outMatch.matchRange)
    }

    @Test
    fun testCaseSensitivity() = runBlocking {
        val blocks = listOf(
            WarpBlockState(id = "b1", command = "echo Hello", output = "HELLO WORLD")
        )

        val caseInsensitive = BlockSearchEngine.search(blocks, "hello", ignoreCase = true)
        assertEquals(2, caseInsensitive.matches.size)

        val caseSensitive = BlockSearchEngine.search(blocks, "hello", ignoreCase = false)
        assertEquals(0, caseSensitive.matches.size)
    }

    @Test
    fun testRegexSearch() = runBlocking {
        val blocks = listOf(
            WarpBlockState(id = "b1", command = "ping 192.168.1.1", output = "64 bytes from 192.168.1.1: icmp_seq=1 ttl=64 time=10ms")
        )

        val result = BlockSearchEngine.search(blocks, """\d+\.\d+\.\d+\.\d+""", isRegex = true)
        assertEquals(2, result.matches.size)
    }

    @Test
    fun testInvalidRegex_handlesGracefully() = runBlocking {
        val blocks = listOf(
            WarpBlockState(id = "b1", command = "ls", output = "test")
        )

        val result = BlockSearchEngine.search(blocks, "[invalid regex", isRegex = true)
        assertTrue(result.matches.isEmpty())
    }

    @Test
    fun testMatchIndexWrapping_inBlockSearchState() = runBlocking {
        val blocks = listOf(
            WarpBlockState(id = "b1", command = "foo", output = "bar foo baz")
        )

        val searchState = BlockSearchState()
        searchState.query = "foo"
        val result = BlockSearchEngine.search(blocks, "foo")
        searchState.updateSearchResult(result)

        assertEquals(2, searchState.matches.size)
        assertEquals(0, searchState.currentMatchIndex)

        // Next match -> index 1
        searchState.nextMatch()
        assertEquals(1, searchState.currentMatchIndex)

        // Next match again -> wraps around to 0
        searchState.nextMatch()
        assertEquals(0, searchState.currentMatchIndex)

        // Previous match -> wraps around to last index (1)
        searchState.previousMatch()
        assertEquals(1, searchState.currentMatchIndex)
    }

    @Test
    fun testHighlightSearchMatches() {
        val rawText = AnnotatedString("Build succeeded in 1.5s")
        val highlighted = BlockSearchEngine.highlightSearchMatches(
            annotated = rawText,
            query = "succeeded"
        )

        assertEquals("Build succeeded in 1.5s", highlighted.text)
        val styles = highlighted.spanStyles
        assertTrue(styles.isNotEmpty())
        val matchStyle = styles.find { it.item.background == Color(0xFFFFD54F) }
        assertNotNull(matchStyle)
        assertEquals(6, matchStyle!!.start)
        assertEquals(15, matchStyle.end)
    }

    @Test
    fun testLargeHistorySearch_performance() = runBlocking {
        // Construct 10,000 blocks simulating heavy terminal history
        val largeBlocks = List(10_000) { i ->
            WarpBlockState(
                id = "block_$i",
                command = "command_$i arg1 arg2",
                output = "Output line 1 for $i\nOutput line 2 with target_keyword at index $i\nDone $i",
                exitCode = 0,
                durationMs = 15L
            )
        }

        val startTime = System.currentTimeMillis()
        val result = BlockSearchEngine.search(largeBlocks, "target_keyword")
        val elapsedTime = System.currentTimeMillis() - startTime

        assertEquals(10_000, result.matches.size)
        assertEquals(10_000, result.matchedBlockIds.size)
        assertTrue("Search took $elapsedTime ms, expected < 1000ms", elapsedTime < 1000)
    }

    @Test
    fun testStripAnsiCodes_removesAnsiControlSequences() {
        val ansiText = "\u001b[31mRed Text\u001b[0m with \u001b[1mBold\u001b[0m"
        val cleanText = BlockSearchEngine.stripAnsiCodes(ansiText)
        assertEquals("Red Text with Bold", cleanText)
    }

    @Test
    fun testMaxMatchesCap_limitsMatchListSize() = runBlocking {
        val blocks = List(500) { i ->
            WarpBlockState(
                id = "b_$i",
                command = "echo item",
                output = "item 1\nitem 2\nitem 3\nitem 4\nitem 5"
            )
        }

        // Each block has 6 "item" matches (1 cmd + 5 output). 500 blocks = 3000 matches.
        // Cap maxMatches at 100
        val cappedResult = BlockSearchEngine.search(blocks, "item", maxMatches = 100)
        assertEquals(100, cappedResult.matches.size)
    }
}
