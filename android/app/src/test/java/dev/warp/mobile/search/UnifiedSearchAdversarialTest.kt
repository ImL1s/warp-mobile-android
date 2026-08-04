package dev.warp.mobile.search

import dev.warp.mobile.SessionTab
import dev.warp.mobile.WarpBlockState
import dev.warp.mobile.WarpTimelineBlock
import dev.warp.mobile.editor.HistoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class UnifiedSearchAdversarialTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @org.junit.Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @org.junit.After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // =========================================================================
    // CATEGORY 1: ANSI Code Stripping & Terminal Output Corruption Stress Tests
    // =========================================================================

    @Test
    fun testAnsiStrippingWithComplexAndCorruptedSequences() {
        val testCases = listOf(
            // SGR Truecolor & 256 color
            "\u001B[38;2;255;128;64mTrueColorText\u001B[0m" to "TrueColorText",
            "\u001B[38:2::255:128:64mColonRGBText\u001B[0m" to "ColonRGBText",
            "\u001B[38;5;196m256ColorText\u001B[0m" to "256ColorText",
            
            // OSC Hyperlinks
            "\u001B]8;;https://warp.dev\u0007Warp Website\u001B]8;;\u0007" to "Warp Website",
            "\u001B]8;;https://warp.dev\u001B\\Warp Website 7Bit\u001B]8;;\u001B\\" to "Warp Website 7Bit",
            
            // Corrupted / Malformed ANSI
            "\u001B[9999999999999999999999999999999mHugeNum\u001B[m" to "HugeNum",
            "\u001B[;::;;;mMalformedParams\u001B[0m" to "MalformedParams",
            "\u001B[38;2;UnterminatedCSI" to "UnterminatedCSI",
            "\u001B]8;;UnclosedOSC" to "", // Unclosed OSC body consumes remaining string up to length
            "\u001B\u001B\u001B[[[[[[MultipleEscapes" to "[[[MultipleEscapes",
            "TrailingEscape\u001B" to "TrailingEscape\u001B",
            
            // Text with null bytes and control chars
            "Control\u0000Bytes\u0007And\u0008Chars" to "Control\u0000Bytes\u0007And\u0008Chars"
        )

        for ((input, expectedSubstring) in testCases) {
            val stripped = BlockSearchEngine.stripAnsiCodes(input)
            assertNotNull(stripped)
            assertTrue("Expected '$stripped' to contain '$expectedSubstring'", stripped.contains(expectedSubstring))
        }
    }

    @Test
    fun testAnsiStrippingLargeBlockPerformance() = runBlocking {
        // Build a massive 1MB string with alternating text and complex ANSI codes
        val sb = StringBuilder()
        repeat(20_000) { i ->
            sb.append("\u001B[38;2;${i % 256};100;200mLine $i: \u001B[1m\u001B[4mCommand execution result $i\u001B[0m\n")
        }
        val largeOutput = sb.toString()
        assertTrue(largeOutput.length > 500_000)

        val startTime = System.currentTimeMillis()
        val stripped = BlockSearchEngine.stripAnsiCodes(largeOutput)
        val duration = System.currentTimeMillis() - startTime

        assertNotNull(stripped)
        assertFalse(stripped.contains("\u001B"))
        assertTrue(stripped.contains("Line 19999: Command execution result 19999"))
        assertTrue("ANSI stripping on ~1MB text should take < 1000ms, took ${duration}ms", duration < 1000L)
    }

    @Test
    fun testBlockSearchWithCorruptedOutputDoesNotCrash() = runBlocking {
        val corruptedBlocks = listOf(
            WarpBlockState(
                id = "b-corrupt-1",
                command = "\u001B[38;2;99999999999999999999mCorruptedCmd",
                output = "\u001B]8;;https://warp.dev\u0007Output\u001B[31mError\u001B[0m",
                exitCode = -1,
                timestamp = System.currentTimeMillis()
            ),
            WarpBlockState(
                id = "b-corrupt-2",
                command = "cat /dev/urandom",
                output = (0..1000).map { (it % 256).toChar() }.joinToString(""),
                exitCode = 137,
                timestamp = System.currentTimeMillis()
            )
        )

        val results = UnifiedSearchEngine.search(
            query = "Error",
            selectedDomain = SearchDomain.BLOCKS,
            blocks = corruptedBlocks
        )

        assertEquals(1, results.size)
        val match = results.first() as UnifiedSearchResultItem.BlockResult
        assertEquals("b-corrupt-1", match.blockId)
    }

    // =========================================================================
    // CATEGORY 2: Rapid Query Burst & Coroutine Cancellation (`flatMapLatest`) Stress Tests
    // =========================================================================

    @Test
    fun testRapidQueryBurstCancellation() = testScope.runTest {
        val historyItems = (1..100).map { i ->
            HistoryItem(id = "h-$i", command = "command_item_$i", timestampMs = 1000L + i)
        }

        val provider = UnifiedSearchProvider(
            sessionManagerSupplier = { null },
            historySupplier = { historyItems },
            coroutineScope = testScope,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        // Rapidly fire 50 query updates within < 1ms
        repeat(50) { i ->
            provider.onQueryChanged("command_item_$i")
        }

        // Before 300ms debounce, isSearching should be true for the latest query
        assertTrue(provider.state.value.isSearching)
        assertEquals("command_item_49", provider.state.value.query)

        // Advance 350ms past debounce
        advanceTimeBy(350L)

        // Verify only the final query results are reflected
        assertFalse(provider.state.value.isSearching)
        assertEquals("command_item_49", provider.state.value.query)
        assertEquals(1, provider.state.value.results.size)
        val res = provider.state.value.results.first() as UnifiedSearchResultItem.HistoryResult
        assertEquals("command_item_49", res.command)
        provider.close()
    }

    @Test
    fun testRapidDomainSwitchingDuringActiveQuery() = testScope.runTest {
        val historyItems = listOf(HistoryItem(id = "h1", command = "gradle build", timestampMs = 1000L))
        val tabs = listOf(SessionTab(id = "s1", title = "gradle session"))

        val provider = UnifiedSearchProvider(
            sessionManagerSupplier = { null },
            historySupplier = { historyItems },
            coroutineScope = testScope,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        )

        provider.onQueryChanged("gradle")
        advanceTimeBy(100L) // mid-debounce

        // Rapidly switch domains
        provider.onDomainSelected(SearchDomain.SESSIONS)
        provider.onDomainSelected(SearchDomain.HISTORY)
        provider.onDomainSelected(SearchDomain.FILES)
        provider.onDomainSelected(SearchDomain.ALL)

        advanceTimeBy(350L)

        assertEquals(SearchDomain.ALL, provider.state.value.selectedDomain)
        assertFalse(provider.state.value.isSearching)
        assertTrue(provider.state.value.results.isNotEmpty())
        provider.close()
    }

    // =========================================================================
    // CATEGORY 3: Deep File Tree Search & Exception Safety Stress Tests
    // =========================================================================

    @Test
    fun testFileSearchExceedingMaxDepth() = runBlocking {
        val rootDir = tempFolder.newFolder("deep_workspace")
        var current = rootDir
        // Create 10 levels deep directory structure
        for (depth in 1..10) {
            current = File(current, "level_$depth").apply { mkdirs() }
            File(current, "target_file_at_depth_$depth.txt").apply { writeText("content") }
        }

        // Search with maxDepth = 5
        val resultsMax5 = UnifiedSearchEngine.search(
            query = "target_file",
            selectedDomain = SearchDomain.FILES,
            cwdPath = rootDir.absolutePath,
            maxDepth = 5
        )

        // Only files up to depth 5 should be returned
        assertTrue("Results size (${resultsMax5.size}) should be <= 5", resultsMax5.size <= 5)
        for (result in resultsMax5) {
            val fileRes = result as UnifiedSearchResultItem.FileResult
            val depthName = fileRes.fileName.removePrefix("target_file_at_depth_").removeSuffix(".txt")
            val depthVal = depthName.toIntOrNull()
            assertNotNull(depthVal)
            assertTrue("Depth ($depthVal) must be <= 5", depthVal!! <= 5)
        }
    }

    @Test
    fun testFileSearchInvalidAndRestrictedCwd() = runBlocking {
        // Test null, empty, blank, home tilde, and non-existent directories
        val invalidPaths = listOf(
            null,
            "",
            "   ",
            "~",
            "/non_existent_directory_warp_test_12345",
            "D:\\NonExistentPathWarp9999999"
        )

        for (path in invalidPaths) {
            val results = UnifiedSearchEngine.search(
                query = "search_test",
                selectedDomain = SearchDomain.FILES,
                cwdPath = path
            )
            assertTrue("Path '$path' should return empty list without throwing", results.isEmpty())
        }
    }

    @Test
    fun testFileSearchWithSymlinkLoopDoesNotStackOverflow() = runBlocking {
        val rootDir = tempFolder.newFolder("symlink_workspace")
        val subDir = File(rootDir, "subdir").apply { mkdirs() }
        File(subDir, "TargetFile.kt").apply { writeText("// test") }

        // Attempt to create a symlink pointing to parent (if supported by OS)
        val symlinkFile = File(subDir, "link_to_parent")
        try {
            java.nio.file.Files.createSymbolicLink(symlinkFile.toPath(), rootDir.toPath())
        } catch (e: Throwable) {
            // Symlink creation might fail on Windows depending on permissions; skip if unsupported
        }

        val results = UnifiedSearchEngine.search(
            query = "TargetFile",
            selectedDomain = SearchDomain.FILES,
            cwdPath = rootDir.absolutePath,
            maxDepth = 5
        )

        assertNotNull(results)
        assertTrue(results.isNotEmpty())
    }
}
