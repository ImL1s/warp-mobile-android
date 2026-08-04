package dev.warp.mobile.search

import androidx.compose.ui.graphics.Color
import dev.warp.mobile.WarpBlockState
import dev.warp.mobile.WarpTimelineBlock
import dev.warp.mobile.ui.parseAnsiToAnnotatedString
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration test suite for BlockTimeline search features:
 * BlockSearchState management, search filtering, target match calculation,
 * and clean ANSI search range alignment.
 */
class BlockTimelineSearchIntegrationTest {

    @Test
    fun testBlockSearchState_lifecycleAndNavigation() = runBlocking {
        val state = BlockSearchState()
        assertFalse(state.isSearchActive)
        assertEquals("", state.query)
        assertEquals(-1, state.currentMatchIndex)
        assertNull(state.currentMatch)
        assertEquals("", state.matchCounterText)

        val blocks = listOf(
            WarpBlockState(id = "b1", command = "git status", output = "On branch main\nmodified: file.txt"),
            WarpBlockState(id = "b2", command = "cat file.txt", output = "Hello world\nAnother line")
        )

        state.isSearchActive = true
        state.query = "file"
        val result = BlockSearchEngine.search(blocks, state.query)
        state.updateSearchResult(result)

        assertEquals(2, state.matches.size)
        assertEquals(2, state.matchedBlockIds.size)
        assertEquals(0, state.currentMatchIndex)
        assertEquals("Match 1 of 2", state.matchCounterText)
        assertEquals("b1", state.currentMatch?.blockId)

        // Navigate next
        state.nextMatch()
        assertEquals(1, state.currentMatchIndex)
        assertEquals("Match 2 of 2", state.matchCounterText)
        assertEquals("b2", state.currentMatch?.blockId)

        // Navigate next again -> wrap to 0
        state.nextMatch()
        assertEquals(0, state.currentMatchIndex)
        assertEquals("b1", state.currentMatch?.blockId)

        // Navigate previous -> wrap to 1
        state.previousMatch()
        assertEquals(1, state.currentMatchIndex)
        assertEquals("b2", state.currentMatch?.blockId)

        // Reset
        state.reset()
        assertEquals("", state.query)
        assertFalse(state.isRegex)
        assertFalse(state.filterOnlyMatches)
        assertTrue(state.matches.isEmpty())
        assertTrue(state.matchedBlockIds.isEmpty())
        assertEquals(-1, state.currentMatchIndex)
    }

    @Test
    fun testFilterOnlyMatches_filtersTimelineBlocks() = runBlocking {
        val blocks = listOf(
            WarpBlockState(id = "b1", command = "ls -la", output = "file1.txt"),
            WarpBlockState(id = "b2", command = "echo hello", output = "world"),
            WarpBlockState(id = "b3", command = "grep hello log.txt", output = "hello test")
        )

        val timelineBlocks: List<WarpTimelineBlock> = blocks.map { WarpTimelineBlock.CommandBlock(it) }

        val searchState = BlockSearchState()
        searchState.isSearchActive = true
        searchState.query = "hello"
        val result = BlockSearchEngine.search(blocks, searchState.query)
        searchState.updateSearchResult(result)

        // Matched block IDs should be b2 and b3
        assertEquals(setOf("b2", "b3"), searchState.matchedBlockIds)

        searchState.filterOnlyMatches = true

        val filtered = timelineBlocks.filter { block ->
            when (block) {
                is WarpTimelineBlock.CommandBlock -> searchState.matchedBlockIds.contains(block.state.id)
                else -> true
            }
        }

        assertEquals(2, filtered.size)
        assertEquals("b2", (filtered[0] as WarpTimelineBlock.CommandBlock).state.id)
        assertEquals("b3", (filtered[1] as WarpTimelineBlock.CommandBlock).state.id)
    }

    @Test
    fun testTargetScrollIndex_locatesCurrentMatchBlock() = runBlocking {
        val blocks = List(10) { i ->
            WarpBlockState(id = "b_$i", command = "cmd_$i", output = "output_$i")
        }

        val timelineBlocks: List<WarpTimelineBlock> = blocks.map { WarpTimelineBlock.CommandBlock(it) }

        val searchState = BlockSearchState()
        searchState.isSearchActive = true
        searchState.query = "cmd_7"
        val result = BlockSearchEngine.search(blocks, searchState.query)
        searchState.updateSearchResult(result)

        val currentMatch = searchState.currentMatch
        assertNotNull(currentMatch)

        val targetIndex = timelineBlocks.indexOfFirst { block ->
            block is WarpTimelineBlock.CommandBlock && block.state.id == currentMatch?.blockId
        }

        assertEquals(7, targetIndex)
    }

    @Test
    fun testAnsiCleanText_rangeAlignmentForCardHighlighting() = runBlocking {
        val ansiOutput = "\u001b[32mSUCCESS: Operation completed\u001b[0m"
        val block = WarpBlockState(id = "ansi_1", command = "run_task.sh", output = ansiOutput)

        val result = BlockSearchEngine.search(listOf(block), "SUCCESS")
        assertEquals(1, result.matches.size)
        val match = result.matches[0]

        val cleanAnnotated = parseAnsiToAnnotatedString(block.output)
        assertEquals("SUCCESS: Operation completed", cleanAnnotated.text)

        // Match range in search result must align 1:1 with cleanAnnotated.text
        val matchedSubtext = cleanAnnotated.text.substring(match.matchRange.first, match.matchRange.last + 1)
        assertEquals("SUCCESS", matchedSubtext)

        val highlighted = BlockSearchEngine.highlightSearchMatches(
            annotated = cleanAnnotated,
            query = "SUCCESS",
            activeMatchRange = match.matchRange
        )

        // Active highlight style (orange background Color(0xFFFF8F00)) must be applied to 0 until 7
        val activeStyle = highlighted.spanStyles.find { it.item.background == Color(0xFFFF8F00) }
        assertNotNull("Active highlight style (orange) must be present", activeStyle)
        assertEquals(0, activeStyle!!.start)
        assertEquals(7, activeStyle.end)
    }
}
