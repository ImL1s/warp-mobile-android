package dev.warp.mobile

import androidx.compose.runtime.Immutable

/**
 * Sealed hierarchy of timeline block items rendered in Warp's unified BlockTimeline list.
 */
@Immutable
sealed class WarpTimelineBlock {
    abstract val id: String
    abstract val timestamp: Long

    data class CommandBlock(
        val state: WarpBlockState
    ) : WarpTimelineBlock() {
        override val id: String get() = state.id
        override val timestamp: Long get() = state.timestamp
    }

    data class UserPromptBlock(
        override val id: String,
        val sessionId: String,
        val prompt: String,
        val turnIndex: Int,
        override val timestamp: Long = System.currentTimeMillis()
    ) : WarpTimelineBlock()

    data class ReasoningCardBlock(
        override val id: String,
        val sessionId: String,
        val thinkingText: String,
        val isStreaming: Boolean = false,
        override val timestamp: Long = System.currentTimeMillis()
    ) : WarpTimelineBlock()

    data class ToolInvocationBlock(
        override val id: String,
        val toolId: String,
        val toolName: String,
        val inputJson: String,
        val output: String? = null,
        val status: ToolStatus = ToolStatus.COMPLETED,
        override val timestamp: Long = System.currentTimeMillis()
    ) : WarpTimelineBlock()

    data class AssistantResponseBlock(
        override val id: String,
        val sessionId: String,
        val turnIndex: Int,
        val model: String,
        val content: String,
        val status: AgentTurnStatus = AgentTurnStatus.STREAMING,
        val errorMessage: String? = null,
        override val timestamp: Long = System.currentTimeMillis()
    ) : WarpTimelineBlock()
}

enum class ToolStatus {
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    EXECUTING,
    COMPLETED,
    FAILED
}

enum class AgentTurnStatus {
    IDLE,
    CONNECTING,
    STREAMING,
    PAUSED,
    COMPLETED,
    CANCELLED,
    ERROR
}
