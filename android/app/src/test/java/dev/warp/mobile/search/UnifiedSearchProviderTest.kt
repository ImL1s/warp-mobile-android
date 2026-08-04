package dev.warp.mobile.search

import dev.warp.mobile.SessionTab
import dev.warp.mobile.WarpAppState
import dev.warp.mobile.editor.HistoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UnifiedSearchProviderTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val providers = mutableListOf<UnifiedSearchProvider>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        providers.forEach { it.close() }
        providers.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun testReactiveQueryDebounceAndResults() = testScope.runTest {
        val historyItems = listOf(
            HistoryItem(id = "h1", command = "git clone repo", timestampMs = 1000L),
            HistoryItem(id = "h2", command = "gradlew assembleDebug", timestampMs = 2000L)
        )

        val provider = UnifiedSearchProvider(
            sessionManagerSupplier = { null },
            historySupplier = { historyItems },
            coroutineScope = testScope,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        ).also { providers.add(it) }

        assertEquals("", provider.state.value.query)
        assertTrue(provider.state.value.results.isEmpty())

        // Set query
        provider.onQueryChanged("git")
        assertTrue(provider.state.value.isSearching)

        // Advance past 300ms debounce
        advanceTimeBy(350L)

        assertFalse(provider.state.value.isSearching)
        assertEquals("git", provider.state.value.query)
        assertEquals(1, provider.state.value.results.size)
        val result = provider.state.value.results.first() as UnifiedSearchResultItem.HistoryResult
        assertEquals("git clone repo", result.command)
        provider.close()
    }

    @Test
    fun testDomainChipFiltering() = testScope.runTest {
        val historyItems = listOf(
            HistoryItem(id = "h1", command = "test command", timestampMs = 1000L)
        )

        val provider = UnifiedSearchProvider(
            sessionManagerSupplier = { null },
            historySupplier = { historyItems },
            coroutineScope = testScope,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        ).also { providers.add(it) }

        provider.onQueryChanged("test")
        advanceTimeBy(350L)

        assertEquals(SearchDomain.ALL, provider.state.value.selectedDomain)
        assertEquals(1, provider.state.value.results.size)

        // Switch to FILES domain (should yield 0 results)
        provider.onDomainSelected(SearchDomain.FILES)
        advanceTimeBy(350L)

        assertEquals(SearchDomain.FILES, provider.state.value.selectedDomain)
        assertTrue(provider.state.value.results.isEmpty())

        // Switch to HISTORY domain (should yield 1 result)
        provider.onDomainSelected(SearchDomain.HISTORY)
        advanceTimeBy(350L)

        assertEquals(SearchDomain.HISTORY, provider.state.value.selectedDomain)
        assertEquals(1, provider.state.value.results.size)
        provider.close()
    }

    @Test
    fun testOverlayVisibilityAndReset() = testScope.runTest {
        val provider = UnifiedSearchProvider(
            sessionManagerSupplier = { null },
            historySupplier = { emptyList() },
            coroutineScope = testScope,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        ).also { providers.add(it) }

        assertFalse(provider.state.value.isOverlayVisible)

        provider.setOverlayVisible(true)
        assertTrue(provider.state.value.isOverlayVisible)

        provider.onQueryChanged("some query")
        assertEquals("some query", provider.state.value.query)

        // Dismissing overlay resets query and results
        provider.setOverlayVisible(false)
        assertFalse(provider.state.value.isOverlayVisible)
        assertEquals("", provider.state.value.query)
        assertTrue(provider.state.value.results.isEmpty())
        provider.close()
    }

    @Test
    fun testResultSelectionNavigation() = testScope.runTest {
        val historyItems = listOf(
            HistoryItem(id = "h1", command = "cmd 1", timestampMs = 1000L),
            HistoryItem(id = "h2", command = "cmd 2", timestampMs = 2000L),
            HistoryItem(id = "h3", command = "cmd 3", timestampMs = 3000L)
        )

        val provider = UnifiedSearchProvider(
            sessionManagerSupplier = { null },
            historySupplier = { historyItems },
            coroutineScope = testScope,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        ).also { providers.add(it) }

        provider.onQueryChanged("cmd")
        advanceTimeBy(350L)

        assertEquals(3, provider.state.value.results.size)
        assertEquals(0, provider.state.value.selectedIndex)

        provider.selectNextResult()
        assertEquals(1, provider.state.value.selectedIndex)

        provider.selectNextResult()
        assertEquals(2, provider.state.value.selectedIndex)

        provider.selectNextResult() // Wrap around
        assertEquals(0, provider.state.value.selectedIndex)

        provider.selectPreviousResult() // Wrap back to end
        assertEquals(2, provider.state.value.selectedIndex)
        provider.close()
    }

    @Test
    fun testFastQueryUpdatesCancellation() = testScope.runTest {
        val historyItems = listOf(
            HistoryItem(id = "h1", command = "git status", timestampMs = 1000L),
            HistoryItem(id = "h2", command = "gradlew test", timestampMs = 2000L)
        )

        val provider = UnifiedSearchProvider(
            sessionManagerSupplier = { null },
            historySupplier = { historyItems },
            coroutineScope = testScope,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        ).also { providers.add(it) }

        // Rapidly change queries within debounce window (every 100ms)
        provider.onQueryChanged("g")
        advanceTimeBy(100L)
        assertTrue(provider.state.value.isSearching)

        provider.onQueryChanged("gi")
        advanceTimeBy(100L)
        assertTrue(provider.state.value.isSearching)

        provider.onQueryChanged("git")
        advanceTimeBy(100L)
        assertTrue(provider.state.value.isSearching)

        provider.onQueryChanged("gradlew")
        // Now advance full debounce period (350ms)
        advanceTimeBy(350L)

        assertFalse(provider.state.value.isSearching)
        assertEquals("gradlew", provider.state.value.query)
        assertEquals(1, provider.state.value.results.size)
        val result = provider.state.value.results.first() as UnifiedSearchResultItem.HistoryResult
        assertEquals("gradlew test", result.command)
        provider.close()
    }

    @Test
    fun testAllDomainsChipFiltering() = testScope.runTest {
        val provider = UnifiedSearchProvider(
            sessionManagerSupplier = { null },
            historySupplier = { emptyList() },
            coroutineScope = testScope,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        ).also { providers.add(it) }

        for (domain in SearchDomain.entries) {
            provider.onDomainSelected(domain)
            assertEquals(domain, provider.state.value.selectedDomain)
        }
        provider.close()
    }

    @Test
    fun testClearQueryResetsState() = testScope.runTest {
        val historyItems = listOf(
            HistoryItem(id = "h1", command = "cargo check", timestampMs = 1000L)
        )

        val provider = UnifiedSearchProvider(
            sessionManagerSupplier = { null },
            historySupplier = { historyItems },
            coroutineScope = testScope,
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher
        ).also { providers.add(it) }

        provider.onQueryChanged("cargo")
        advanceTimeBy(350L)
        assertEquals(1, provider.state.value.results.size)

        // Clear query
        provider.onQueryChanged("")
        advanceTimeBy(350L)
        assertEquals("", provider.state.value.query)
        assertFalse(provider.state.value.isSearching)
        assertTrue(provider.state.value.results.isEmpty())
        provider.close()
    }
}

