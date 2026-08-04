package dev.warp.mobile.search

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import dev.warp.mobile.WarpBlockState
import dev.warp.mobile.ui.parseAnsiToAnnotatedString
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test


/**
 * Adversarial stress and edge-case test suite for BlockSearchEngine.
 * Stress-tests regex special characters, high-volume block timelines (50,000 blocks),
 * zero-width regexes, empty blocks, Unicode/CJK, and ANSI escape code interactions.
 */
class BlockSearchEngineAdversarialTest {

    @Test
    fun test50000Blocks_stressAndPerformance() = runBlocking {
        val count = 50_000
        val largeBlocks = List(count) { i ->
            WarpBlockState(
                id = "block_$i",
                command = "command_$i --opt=$i",
                output = "Log entry line 1 for block $i\nERROR: failed at step $i\nDone",
                exitCode = if (i % 5 == 0) 1 else 0,
                durationMs = (i % 100).toLong()
            )
        }

        val runtime = Runtime.getRuntime()
        runtime.gc()
        val memBefore = runtime.totalMemory() - runtime.freeMemory()

        val startTime = System.currentTimeMillis()
        val result = BlockSearchEngine.search(largeBlocks, "ERROR")
        val elapsedTime = System.currentTimeMillis() - startTime

        val memAfter = runtime.totalMemory() - runtime.freeMemory()
        val memUsedMb = (memAfter - memBefore) / (1024 * 1024)

        println("50,000 blocks search took: ${elapsedTime}ms, approx memory delta: ${memUsedMb}MB")

        assertEquals(10_000, result.matches.size)
        assertEquals(10_000, result.matchedBlockIds.size)
        assertTrue("Search over 50,000 blocks took $elapsedTime ms, expected < 2000ms", elapsedTime < 2000)
    }

    @Test
    fun test50000Blocks_highMatchDensityMemoryAllocation() = runBlocking {
        // Construct 50,000 blocks where each output contains 5 matches (total 250,000 matches)
        val count = 50_000
        val denseBlocks = List(count) { i ->
            WarpBlockState(
                id = "dense_$i",
                command = "test $i",
                output = "MATCH line 1\nMATCH line 2\nMATCH line 3\nMATCH line 4\nMATCH line 5"
            )
        }

        val startTime = System.currentTimeMillis()
        val result = BlockSearchEngine.search(denseBlocks, "MATCH")
        val elapsedTime = System.currentTimeMillis() - startTime

        assertEquals(10_000, result.matches.size)
        assertTrue("High density search over 50,000 blocks capped at maxMatches took $elapsedTime ms, expected < 3000ms", elapsedTime < 3000)
    }

    @Test
    fun testRegexSpecialCharacters_literalMode() = runBlocking {
        val blocks = listOf(
            WarpBlockState(id = "b1", command = "grep '.*+?()[]{}|^$\\' file.txt", output = "Special chars: .*+?()[]{}|^$\\ in output")
        )

        val specialChars = ".*+?()[]{}|^$\\"

        val result = BlockSearchEngine.search(blocks, specialChars, isRegex = false)

        assertEquals(2, result.matches.size)
        assertTrue(result.matchedBlockIds.contains("b1"))
        assertTrue(result.matches[0].isCommandMatch)
        assertFalse(result.matches[1].isCommandMatch)
    }

    @Test
    fun testRegexMode_invalidSyntaxResilience() = runBlocking {
        val blocks = listOf(
            WarpBlockState(id = "b1", command = "echo test", output = "sample text")
        )

        val invalidRegexes = listOf(
            "(",
            "[",
            "*",
            "?",
            "+",
            "\\",
            "(abc",
            "[a-z",
            "*+?",
            "(?P<"
        )

        for (invalid in invalidRegexes) {
            val result = BlockSearchEngine.search(blocks, invalid, isRegex = true)
            assertTrue("Expected empty matches for invalid regex: $invalid", result.matches.isEmpty())
            assertTrue(result.matchedBlockIds.isEmpty())
        }
    }

    @Test
    fun testRegexMode_zeroWidthMatchesDoNotLoopInfinitely() = runBlocking {
        val blocks = listOf(
            WarpBlockState(id = "b1", command = "echo hi", output = "abc")
        )

        val zeroWidthRegexes = listOf(
            "^",
            "$",
            ".*",
            "a*",
            "b?",
            "(?=a)"
        )

        for (regex in zeroWidthRegexes) {
            val result = BlockSearchEngine.search(blocks, regex, isRegex = true)
            assertNotNull(result)
        }
    }

    @Test
    fun testEmptyBlocksAndBlankQueries() = runBlocking {
        val emptyBlocks = listOf(
            WarpBlockState(id = "b1", command = "", output = ""),
            WarpBlockState(id = "b2", command = "   ", output = "   ")
        )

        val result1 = BlockSearchEngine.search(emptyBlocks, "test")
        assertTrue(result1.matches.isEmpty())

        val result2 = BlockSearchEngine.search(emptyBlocks, "")
        assertTrue(result2.matches.isEmpty())

        val result3 = BlockSearchEngine.search(emptyBlocks, "   ")
        assertTrue(result3.matches.isEmpty())
    }

    @Test
    fun testUnicodeAndEmojiSearch() = runBlocking {
        val blocks = listOf(
            WarpBlockState(id = "b1", command = "echo 🚀 測試 🚀", output = "Result: 🎉 成功 ✓")
        )

        val resultEmoji = BlockSearchEngine.search(blocks, "🚀")
        assertEquals(2, resultEmoji.matches.size)

        val resultCjk = BlockSearchEngine.search(blocks, "測試")
        assertEquals(1, resultCjk.matches.size)

        val resultSuccess = BlockSearchEngine.search(blocks, "成功")
        assertEquals(1, resultSuccess.matches.size)
    }

    @Test
    fun testAnsiEscapeCodes_interactionAndActiveMatchHighlightDefect() = runBlocking {
        // Block output contains ANSI escape sequence: \u001b[31mError message\u001b[0m
        // Length of raw output: 5 (\u001b[31m) + 13 ("Error message") + 4 (\u001b[0m) = 22 chars.
        val rawAnsiOutput = "\u001b[31mError message\u001b[0m"
        val block = WarpBlockState(id = "ansi_b1", command = "ls", output = rawAnsiOutput)

        // 1. Search for "Error"
        val result = BlockSearchEngine.search(listOf(block), "Error")
        assertEquals(1, result.matches.size)
        val match = result.matches[0]

        // Cleaned text in AnnotatedString is "Error message" (length 13, "Error" is at index 0 until 5)
        assertEquals(0 until 5, match.matchRange)

        // 2. Parse ANSI to AnnotatedString (strips ANSI codes)
        val annotated = parseAnsiToAnnotatedString(block.output)
        assertEquals("Error message", annotated.text)

        // 3. Highlight matches with activeMatchRange set to the matchRange returned by search()
        val highlighted = BlockSearchEngine.highlightSearchMatches(
            annotated = annotated,
            query = "Error",
            isRegex = false,
            activeMatchRange = match.matchRange,
            highlightStyle = SpanStyle(background = Color(0xFFFFD54F), color = Color.Black),
            activeHighlightStyle = SpanStyle(background = Color(0xFFFF8F00), color = Color.White)
        )

        val activeStyle = highlighted.spanStyles.find { it.item.background == Color(0xFFFF8F00) }
        val normalStyle = highlighted.spanStyles.find { it.item.background == Color(0xFFFFD54F) }

        println("ANSI Test - Normal style found: ${normalStyle != null}, Active style found: ${activeStyle != null}")
        assertNotNull("Active highlight style (orange) SHOULD BE PRESENT because search matchRange aligns with stripped ANSI text", activeStyle)
        assertEquals(0, activeStyle!!.start)
        assertEquals(5, activeStyle.end)
    }

    @Test
    fun testAnsiEscapeCodes_falsePositiveSearchMatch() = runBlocking {
        // Block output contains ANSI escape sequence for red text: \u001b[31mHello\u001b[0m
        val block = WarpBlockState(id = "ansi_b2", command = "cat test", output = "\u001b[31mHello\u001b[0m")

        // Search for "31m" (part of the ANSI escape code sequence)
        val result = BlockSearchEngine.search(listOf(block), "31m")

        // Verified fix: search() strips ANSI control sequences prior to string searching, eliminating phantom matches on hidden ANSI bytes
        assertEquals(0, result.matches.size)
        assertTrue(result.matchedBlockIds.isEmpty())
    }
}
