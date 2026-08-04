package dev.warp.mobile.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class BlockSearchState {
    var isSearchActive by mutableStateOf(false)
    var query by mutableStateOf("")
    var isRegex by mutableStateOf(false)
    var filterOnlyMatches by mutableStateOf(false)

    var matches by mutableStateOf<List<BlockSearchMatch>>(emptyList())
        private set

    var matchedBlockIds by mutableStateOf<Set<String>>(emptySet())
        private set

    var currentMatchIndex by mutableStateOf(-1)
        private set

    val currentMatch: BlockSearchMatch?
        get() = matches.getOrNull(currentMatchIndex)

    val matchCounterText: String
        get() = if (matches.isNotEmpty() && currentMatchIndex >= 0) {
            "Match ${currentMatchIndex + 1} of ${matches.size}"
        } else if (query.isNotBlank()) {
            "0 of 0"
        } else {
            ""
        }

    fun updateSearchResult(result: SearchResult) {
        matches = result.matches
        matchedBlockIds = result.matchedBlockIds
        currentMatchIndex = if (result.matches.isNotEmpty()) 0 else -1
    }

    fun nextMatch() {
        if (matches.isEmpty()) {
            currentMatchIndex = -1
            return
        }
        currentMatchIndex = (currentMatchIndex + 1) % matches.size
    }

    fun previousMatch() {
        if (matches.isEmpty()) {
            currentMatchIndex = -1
            return
        }
        currentMatchIndex = if (currentMatchIndex <= 0) matches.size - 1 else currentMatchIndex - 1
    }

    fun reset() {
        query = ""
        isRegex = false
        filterOnlyMatches = false
        matches = emptyList()
        matchedBlockIds = emptySet()
        currentMatchIndex = -1
    }
}
