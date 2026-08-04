package dev.warp.mobile.search

import dev.warp.mobile.SessionManager
import dev.warp.mobile.editor.CommandHistoryManager
import dev.warp.mobile.editor.HistoryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class UnifiedSearchProvider(
    private val sessionManagerSupplier: () -> SessionManager? = {
        try { SessionManager.getInstance() } catch (e: Throwable) { null }
    },
    private val historySupplier: () -> List<HistoryItem> = {
        try { CommandHistoryManager.getHistory() } catch (e: Throwable) { emptyList() }
    },
    private val coroutineScope: CoroutineScope,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val _state = MutableStateFlow(UnifiedSearchState())
    val state: StateFlow<UnifiedSearchState> = _state.asStateFlow()

    private val _queryFlow = MutableStateFlow("")
    private val _domainFlow = MutableStateFlow(SearchDomain.ALL)

    private var searchJob: Job? = null

    init {
        searchJob = coroutineScope.launch {
            combine(_queryFlow, _domainFlow) { query, domain -> query to domain }
                .debounce(300L)
                .distinctUntilChanged()
                .flatMapLatest { (query, domain) ->
                    flow {
                        val trimmed = query.trim()
                        if (trimmed.isEmpty()) {
                            emit(emptyList<UnifiedSearchResultItem>() to SearchDomain.entries.associateWith { 0 })
                        } else {
                            _state.update { it.copy(isSearching = true) }
                            try {
                                val sessionManager = sessionManagerSupplier()
                                val appState = sessionManager?.appState?.value
                                val tabs = appState?.tabs ?: emptyList()
                                val activeSessionId = appState?.activeSessionId
                                val blocks = appState?.effectiveBlocks ?: emptyList()
                                val timelineBlocks = appState?.timelineBlocks ?: emptyList()
                                val history = historySupplier()
                                val cwdPath = appState?.activeTab?.cwd

                                val (results, domainCounts) = UnifiedSearchEngine.searchWithCounts(
                                    query = trimmed,
                                    selectedDomain = domain,
                                    tabs = tabs,
                                    activeSessionId = activeSessionId,
                                    blocks = blocks,
                                    timelineBlocks = timelineBlocks,
                                    history = history,
                                    cwdPath = cwdPath,
                                    defaultDispatcher = defaultDispatcher,
                                    ioDispatcher = ioDispatcher
                                )

                                emit(results to domainCounts)
                            } finally {
                                _state.update { it.copy(isSearching = false) }
                            }
                        }
                    }.onCompletion {
                        _state.update { it.copy(isSearching = false) }
                    }
                }
                .collect { (results, domainCounts) ->
                    _state.update { current ->
                        current.copy(
                            isSearching = false,
                            results = results,
                            domainCounts = domainCounts,
                            selectedIndex = if (results.isNotEmpty()) 0 else -1
                        )
                    }
                }
        }
    }

    fun onQueryChanged(newQuery: String) {
        _queryFlow.value = newQuery
        _state.update {
            it.copy(
                query = newQuery,
                isSearching = newQuery.trim().isNotBlank()
            )
        }
    }

    fun onDomainSelected(domain: SearchDomain) {
        _domainFlow.value = domain
        _state.update { current ->
            current.copy(
                selectedDomain = domain,
                isSearching = current.query.trim().isNotBlank()
            )
        }
    }

    fun setOverlayVisible(visible: Boolean) {
        _state.update { current ->
            if (!visible) {
                _queryFlow.value = ""
                _domainFlow.value = SearchDomain.ALL
                current.copy(
                    isOverlayVisible = false,
                    query = "",
                    selectedDomain = SearchDomain.ALL,
                    results = emptyList(),
                    isSearching = false,
                    selectedIndex = 0
                )
            } else {
                current.copy(isOverlayVisible = true)
            }
        }
    }

    fun selectNextResult() {
        _state.update { current ->
            if (current.results.isEmpty()) current
            else current.copy(selectedIndex = (current.selectedIndex + 1) % current.results.size)
        }
    }

    fun selectPreviousResult() {
        _state.update { current ->
            if (current.results.isEmpty()) current
            else {
                val newIdx = if (current.selectedIndex <= 0) current.results.size - 1 else current.selectedIndex - 1
                current.copy(selectedIndex = newIdx)
            }
        }
    }

    fun close() {
        searchJob?.cancel()
        searchJob = null
    }

    fun cancel() {
        close()
    }
}
