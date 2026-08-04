package dev.warp.mobile

import kotlinx.coroutines.flow.MutableStateFlow

data class WarpAppState(
    val tabs: List<SessionTab> = emptyList(),
    val activeSessionId: String? = null,
    val timelineBlocks: List<WarpTimelineBlock> = emptyList(),
    val blocks: List<WarpBlockState> = emptyList(),
    val isRawMode: Boolean = false
) {
    val activeTab: SessionTab?
        get() = tabs.find { it.id == activeSessionId }

    val tabCount: Int
        get() = tabs.size

    val effectiveBlocks: List<WarpBlockState>
        get() = if (blocks.isNotEmpty()) blocks else timelineBlocks.filterIsInstance<WarpTimelineBlock.CommandBlock>().map { it.state }

    fun onToggleRawMode(raw: Boolean): WarpAppState {
        return copy(isRawMode = raw)
    }

    fun updateBlocksFromDump(dumpJson: String): WarpAppState {
        val parsed = WarpBlockState.parseBlocksJson(dumpJson)
        val commandBlocks = parsed.map { WarpTimelineBlock.CommandBlock(it) }
        return copy(
            blocks = parsed,
            timelineBlocks = commandBlocks
        )
    }

    companion object {
        fun parseBlocksJson(dumpJson: String): List<WarpBlockState> {
            return WarpBlockState.parseBlocksJson(dumpJson)
        }

        fun createBlocksStateFlow(initialJson: String = ""): MutableStateFlow<List<WarpBlockState>> {
            return MutableStateFlow(parseBlocksJson(initialJson))
        }

        fun createRawModeStateFlow(initial: Boolean = false): MutableStateFlow<Boolean> {
            return MutableStateFlow(initial)
        }
    }
}
