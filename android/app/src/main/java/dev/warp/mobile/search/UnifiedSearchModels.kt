package dev.warp.mobile.search

import dev.warp.mobile.ProcessState

enum class SearchDomain(
    val label: String,
    val badgeLabel: String,
    val badgeColorHex: Long
) {
    ALL("All", "ALL", 0xFF757575),
    SESSIONS("Sessions", "SESSION", 0xFF1E88E5),
    BLOCKS("Blocks", "BLOCK", 0xFF43A047),
    HISTORY("History", "HISTORY", 0xFFFB8C00),
    AI("AI", "AI", 0xFF8E24AA),
    FILES("Files", "FILE", 0xFF00ACC1)
}

sealed class UnifiedSearchResultItem {
    abstract val id: String
    abstract val domain: SearchDomain
    abstract val title: String
    abstract val subtitle: String
    abstract val timestamp: Long
    abstract val matchRange: IntRange?
    abstract val score: Float

    data class SessionResult(
        override val id: String,
        val sessionId: String,
        val sessionTitle: String,
        val cwd: String,
        val program: String,
        val processState: ProcessState = ProcessState.INITIALIZING,
        override val matchRange: IntRange? = null,
        override val timestamp: Long = System.currentTimeMillis(),
        override val score: Float = 1.0f
    ) : UnifiedSearchResultItem() {
        override val domain: SearchDomain get() = SearchDomain.SESSIONS
        override val title: String get() = sessionTitle
        override val subtitle: String get() = "$program • $cwd"
    }

    data class BlockResult(
        override val id: String,
        val blockId: String,
        val sessionId: String? = null,
        val command: String,
        val outputSnippet: String,
        val exitCode: Int? = null,
        val isCommandMatch: Boolean = true,
        override val matchRange: IntRange? = null,
        override val timestamp: Long = System.currentTimeMillis(),
        override val score: Float = 1.0f
    ) : UnifiedSearchResultItem() {
        override val domain: SearchDomain get() = SearchDomain.BLOCKS
        override val title: String get() = command.ifBlank { "Output Block" }
        override val subtitle: String get() = if (exitCode != null) "Exit: $exitCode | $outputSnippet" else outputSnippet
    }

    data class HistoryResult(
        override val id: String,
        val historyId: String,
        val command: String,
        override val matchRange: IntRange? = null,
        override val timestamp: Long = System.currentTimeMillis(),
        override val score: Float = 1.0f
    ) : UnifiedSearchResultItem() {
        override val domain: SearchDomain get() = SearchDomain.HISTORY
        override val title: String get() = command
        override val subtitle: String get() = "Command History"
    }

    data class AiResult(
        override val id: String,
        val blockId: String,
        val sessionId: String? = null,
        val turnType: String,
        val model: String? = null,
        val snippet: String,
        val promptOrResponse: String,
        val isUserPrompt: Boolean,
        override val matchRange: IntRange? = null,
        override val timestamp: Long = System.currentTimeMillis(),
        override val score: Float = 1.0f
    ) : UnifiedSearchResultItem() {
        override val domain: SearchDomain get() = SearchDomain.AI
        override val title: String get() = if (isUserPrompt) "User: $promptOrResponse" else "AI (${model ?: "Agent"}): $promptOrResponse"
        override val subtitle: String get() = snippet
    }

    data class FileResult(
        override val id: String,
        val filePath: String,
        val fileName: String,
        val relativePath: String,
        val isDirectory: Boolean,
        val sizeBytes: Long = 0L,
        override val matchRange: IntRange? = null,
        override val timestamp: Long = System.currentTimeMillis(),
        override val score: Float = 1.0f
    ) : UnifiedSearchResultItem() {
        override val domain: SearchDomain get() = SearchDomain.FILES
        override val title: String get() = fileName
        override val subtitle: String get() = relativePath
    }
}

data class UnifiedSearchState(
    val query: String = "",
    val selectedDomain: SearchDomain = SearchDomain.ALL,
    val results: List<UnifiedSearchResultItem> = emptyList(),
    val isSearching: Boolean = false,
    val isOverlayVisible: Boolean = false,
    val domainCounts: Map<SearchDomain, Int> = emptyMap(),
    val selectedIndex: Int = 0
)
