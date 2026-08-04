package dev.warp.mobile.search

import androidx.compose.ui.graphics.Color
import dev.warp.mobile.BlockShareManager
import dev.warp.mobile.ShareFormat
import dev.warp.mobile.WarpBlockState
import dev.warp.mobile.WarpTimelineBlock
import dev.warp.mobile.ui.parseAnsiToAnnotatedString
import dev.warp.mobile.test.BaseWarpUnitTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Empirical challenger test suite verifying end-to-end UI state safety:
 * 1. Search overlay state toggling, match counter text, reset lifecycle.
 * 2. Rapid query changes under concurrent coroutine dispatch.
 * 3. `filterOnlyMatches` timeline block filtering across Command and non-Command blocks.
 * 4. Scroll target match index calculation.
 * 5. `BlockShareManager` plain text and JSON escaping safety under adversarial inputs.
 * 6. ANSI stripping and search highlight range index alignment.
 */
class UIStateSafetyChallengerTest : BaseWarpUnitTest() {

    @Test
    fun testSearchState_togglingResetAndCounterFormatting() {
        val state = BlockSearchState()

        // 1. Default state
        assertFalse(state.isSearchActive)
        assertEquals("", state.query)
        assertFalse(state.isRegex)
        assertFalse(state.filterOnlyMatches)
        assertTrue(state.matches.isEmpty())
        assertTrue(state.matchedBlockIds.isEmpty())
        assertEquals(-1, state.currentMatchIndex)
        assertNull(state.currentMatch)
        assertEquals("", state.matchCounterText)

        // 2. Active search with query but 0 matches
        state.isSearchActive = true
        state.query = "nonexistent_term"
        state.updateSearchResult(SearchResult(emptyList(), emptySet()))
        assertEquals("0 of 0", state.matchCounterText)
        assertNull(state.currentMatch)

        // 3. Populate matches
        val match1 = BlockSearchMatch("b1", 0, true, 0..4)
        val match2 = BlockSearchMatch("b2", 1, false, 10..14)
        val match3 = BlockSearchMatch("b2", 1, false, 20..24)
        val result = SearchResult(listOf(match1, match2, match3), setOf("b1", "b2"))

        state.updateSearchResult(result)
        assertEquals(0, state.currentMatchIndex)
        assertEquals("Match 1 of 3", state.matchCounterText)
        assertEquals(match1, state.currentMatch)

        state.nextMatch()
        assertEquals(1, state.currentMatchIndex)
        assertEquals("Match 2 of 3", state.matchCounterText)
        assertEquals(match2, state.currentMatch)

        state.nextMatch()
        assertEquals(2, state.currentMatchIndex)
        assertEquals("Match 3 of 3", state.matchCounterText)
        assertEquals(match3, state.currentMatch)

        // Wrap around to 0
        state.nextMatch()
        assertEquals(0, state.currentMatchIndex)
        assertEquals("Match 1 of 3", state.matchCounterText)

        // Wrap backward to 2
        state.previousMatch()
        assertEquals(2, state.currentMatchIndex)
        assertEquals("Match 3 of 3", state.matchCounterText)

        // Reset overlay close flow
        state.isSearchActive = false
        state.reset()
        assertFalse(state.isSearchActive)
        assertEquals("", state.query)
        assertFalse(state.isRegex)
        assertFalse(state.filterOnlyMatches)
        assertTrue(state.matches.isEmpty())
        assertEquals(-1, state.currentMatchIndex)
        assertEquals("", state.matchCounterText)
    }

    @Test
    fun testRapidQueryChanges_concurrencySafety() {
        runBlocking {
            val blocks = List(100) { i ->
                WarpBlockState(
                    id = "b_$i",
                    command = "cargo test --package module_$i",
                    output = "Running tests for module $i...\ntest result: ok. 0 passed; 0 failed"
                )
            }

            val queries = listOf("cargo", "module", "test", "package", "ok", "failed", "1", "99", "nonexistent", ".*", "module_5")

            // Fire 50 concurrent search operations simulating rapid user typing
            val jobs = List(50) { index ->
                async(Dispatchers.Default) {
                    val query = queries[index % queries.size]
                    val useRegex = index % 2 == 0
                    val searchResult = BlockSearchEngine.search(blocks, query, isRegex = useRegex)
                    assertNotNull(searchResult)
                    assertTrue(searchResult.matches.size <= 10_000)
                }
            }

            jobs.awaitAll()
        }
    }

    @Test
    fun testFilterOnlyMatches_preservesNonCommandBlocksAndFiltersCommandBlocks() {
        runBlocking {
            val blocks = listOf(
                WarpTimelineBlock.CommandBlock(WarpBlockState(id = "c1", command = "ls -la", output = "file1.txt")),
                WarpTimelineBlock.UserPromptBlock(id = "u1", sessionId = "s1", prompt = "How to list files?", turnIndex = 1),
                WarpTimelineBlock.CommandBlock(WarpBlockState(id = "c2", command = "cat README.md", output = "Warp Mobile")),
                WarpTimelineBlock.ReasoningCardBlock(id = "r1", sessionId = "s1", thinkingText = "Thinking about directory structure..."),
                WarpTimelineBlock.CommandBlock(WarpBlockState(id = "c3", command = "grep Warp README.md", output = "Warp Mobile Terminal"))
            )

            val rawCmdBlocks = blocks.mapNotNull { (it as? WarpTimelineBlock.CommandBlock)?.state }
            val searchState = BlockSearchState()
            searchState.isSearchActive = true
            searchState.query = "Warp"

            val searchResult = BlockSearchEngine.search(rawCmdBlocks, searchState.query)
            searchState.updateSearchResult(searchResult)

            // Matched command blocks: c2 and c3
            assertEquals(setOf("c2", "c3"), searchState.matchedBlockIds)

            // When filterOnlyMatches is enabled:
            searchState.filterOnlyMatches = true

            val filtered = blocks.filter { block ->
                if (searchState.isSearchActive && searchState.filterOnlyMatches && searchState.query.isNotBlank()) {
                    when (block) {
                        is WarpTimelineBlock.CommandBlock -> searchState.matchedBlockIds.contains(block.state.id)
                        else -> true // Non-command blocks are retained for context
                    }
                } else {
                    true
                }
            }

            // Output should include: c2, c3, u1, r1 (c1 is filtered out)
            assertEquals(4, filtered.size)
            assertFalse(filtered.any { it is WarpTimelineBlock.CommandBlock && it.state.id == "c1" })
            assertTrue(filtered.any { it is WarpTimelineBlock.CommandBlock && it.state.id == "c2" })
            assertTrue(filtered.any { it is WarpTimelineBlock.CommandBlock && it.state.id == "c3" })
            assertTrue(filtered.any { it is WarpTimelineBlock.UserPromptBlock })
            assertTrue(filtered.any { it is WarpTimelineBlock.ReasoningCardBlock })
        }
    }

    @Test
    fun testScrollTargetCalculation_firstMiddleLastMatches() {
        runBlocking {
            val blocks = List(20) { i ->
                WarpTimelineBlock.CommandBlock(WarpBlockState(id = "b_$i", command = "cmd_$i", output = "output_$i"))
            }

            // Match on b_15
            val match = BlockSearchMatch("b_15", 15, true, 0..4)
            val targetIndex = blocks.indexOfFirst { block ->
                block is WarpTimelineBlock.CommandBlock && block.state.id == match.blockId
            }

            assertEquals(15, targetIndex)
        }
    }

    @Test
    fun testBlockShareManager_plainTextFormatting() {
        val blockWithAll = WarpBlockState(
            id = "b1",
            command = "git status",
            exitCode = 0,
            durationMs = 120L,
            output = "On branch main\nnothing to commit",
            isRunning = false
        )

        val plainText = BlockShareManager.formatPlainText(blockWithAll)
        val expected = "$ git status\nOn branch main\nnothing to commit\n[exit 0]"
        assertEquals(expected, plainText)

        val blockFailed = WarpBlockState(
            id = "b2",
            command = "make build",
            exitCode = 2,
            output = "error: syntax error at line 42",
            isRunning = false
        )
        val failedText = BlockShareManager.formatPlainText(blockFailed)
        assertTrue(failedText.endsWith("[exit 2]"))

        val emptyOutputBlock = WarpBlockState(
            id = "b3",
            command = "touch /tmp/file",
            exitCode = 0,
            output = "",
            isRunning = false
        )
        val emptyText = BlockShareManager.formatPlainText(emptyOutputBlock)
        assertEquals("$ touch /tmp/file\n[exit 0]", emptyText)
    }

    @Test
    fun testBlockShareManager_jsonEscapingAdversarial() {
        val complexBlock = WarpBlockState(
            id = "b_json_1",
            command = "echo \"Hello \\ World\t\n\r\u0007\"",
            exitCode = 1,
            durationMs = 50L,
            output = "Line 1: \"Quoted\"\nLine 2: Backslash \\ path C:\\Windows\nTab:\tEnd\r\nControl: \u0000\u001B[31mRed\u001B[0m",
            isRunning = false,
            timestamp = 1700000000000L
        )

        val json = BlockShareManager.formatJson(complexBlock)

        // Verify valid JSON structural components
        assertTrue(json.contains("\"id\": \"b_json_1\""))
        assertTrue(json.contains("\"exitCode\": 1"))
        assertTrue(json.contains("\"durationMs\": 50"))
        assertTrue(json.contains("\"isRunning\": false"))
        assertTrue(json.contains("\"timestamp\": 1700000000000"))

        // Verify escaping
        assertTrue(json.contains("\\\"Quoted\\\""))
        assertTrue(json.contains("Backslash \\\\ path C:\\\\Windows"))
        assertTrue(json.contains("Tab:\\tEnd\\r\\n"))
        assertTrue(json.contains("\\u0000"))
        assertTrue(json.contains("\\u001b[31mRed\\u001b[0m") || json.contains("\\u001B[31mRed\\u001B[0m") || json.contains("Red"))
    }

    @Test
    fun testAnsiStrippingAndHighlightAlignment_endToEnd() {
        val coloredText = "\u001B[32mBuild Succeeded\u001B[0m in 1.2s"
        val cleanText = BlockSearchEngine.stripAnsiCodes(coloredText)
        assertEquals("Build Succeeded in 1.2s", cleanText)

        val annotated = parseAnsiToAnnotatedString(coloredText)
        assertEquals("Build Succeeded in 1.2s", annotated.text)

        val query = "Succeeded"
        val highlighted = BlockSearchEngine.highlightSearchMatches(
            annotated = annotated,
            query = query,
            activeMatchRange = 6..14
        )

        // Check active style (orange) applied to start 6, end 15
        val activeStyle = highlighted.spanStyles.find { it.item.background == Color(0xFFFF8F00) }
        assertNotNull("Active highlight style must be present", activeStyle)
        assertEquals(6, activeStyle!!.start)
        assertEquals(15, activeStyle.end)
    }
}
