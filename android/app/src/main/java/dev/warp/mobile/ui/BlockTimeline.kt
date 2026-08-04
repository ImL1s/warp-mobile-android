package dev.warp.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.warp.mobile.AgentTurnStatus
import dev.warp.mobile.ToolStatus
import dev.warp.mobile.WarpBlockState
import dev.warp.mobile.WarpTimelineBlock

@Composable
fun BlockTimeline(
    timelineBlocks: List<WarpTimelineBlock>,
    modifier: Modifier = Modifier,
    searchState: dev.warp.mobile.search.BlockSearchState? = null,
    onCopyBlock: (WarpBlockState) -> Unit = {},
    onRerunBlock: (WarpBlockState) -> Unit = {},
    onExplainBlock: (WarpBlockState) -> Unit = {},
    onCancelTurn: (WarpTimelineBlock.AssistantResponseBlock) -> Unit = {},
    onPauseResumeTurn: (WarpTimelineBlock.AssistantResponseBlock) -> Unit = {},
    onRetryTurn: (WarpTimelineBlock.AssistantResponseBlock) -> Unit = {},
    onEditPrompt: (WarpTimelineBlock.UserPromptBlock, String) -> Unit = { _, _ -> }
) {
    val listState = rememberLazyListState()

    val filteredBlocks = remember(
        timelineBlocks,
        searchState?.filterOnlyMatches,
        searchState?.matchedBlockIds,
        searchState?.isSearchActive,
        searchState?.query
    ) {
        if (searchState != null && searchState.isSearchActive && searchState.filterOnlyMatches && searchState.query.isNotBlank()) {
            timelineBlocks.filter { block ->
                when (block) {
                    is WarpTimelineBlock.CommandBlock -> searchState.matchedBlockIds.contains(block.state.id)
                    else -> true
                }
            }
        } else {
            timelineBlocks
        }
    }

    val currentMatch = searchState?.currentMatch
    LaunchedEffect(currentMatch) {
        if (currentMatch != null) {
            val targetIndex = filteredBlocks.indexOfFirst { block ->
                block is WarpTimelineBlock.CommandBlock && block.state.id == currentMatch.blockId
            }
            if (targetIndex != -1) {
                listState.animateScrollToItem(targetIndex)
            }
        }
    }

    LaunchedEffect(timelineBlocks.size, (timelineBlocks.lastOrNull() as? WarpTimelineBlock.AssistantResponseBlock)?.content?.length) {
        if (timelineBlocks.isNotEmpty() && (searchState == null || !searchState.isSearchActive || searchState.query.isBlank())) {
            listState.animateScrollToItem(timelineBlocks.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = filteredBlocks,
            key = { it.id }
        ) { block ->
            when (block) {
                is WarpTimelineBlock.CommandBlock -> {
                    val isSearchActive = searchState != null && searchState.isSearchActive && searchState.query.isNotBlank()
                    val query = if (isSearchActive) searchState?.query?.orEmpty() ?: "" else ""
                    val isRegex = searchState?.isRegex == true
                    val match = currentMatch
                    val isCurrentMatchBlock = match != null && match.blockId == block.state.id
                    val activeRange = if (isCurrentMatchBlock) match?.matchRange else null

                    BlockCard(
                        block = block.state,
                        searchQuery = query,
                        isRegexSearch = isRegex,
                        activeMatchRange = activeRange,
                        forceExpanded = activeRange != null,
                        onCopyBlock = onCopyBlock,
                        onRerunBlock = onRerunBlock,
                        onExplainBlock = onExplainBlock
                    )
                }
                is WarpTimelineBlock.UserPromptBlock -> {
                    UserPromptCard(
                        block = block,
                        onEditPrompt = onEditPrompt
                    )
                }
                is WarpTimelineBlock.ReasoningCardBlock -> {
                    ReasoningCard(block = block)
                }
                is WarpTimelineBlock.ToolInvocationBlock -> {
                    ToolInvocationCard(block = block)
                }
                is WarpTimelineBlock.AssistantResponseBlock -> {
                    AssistantResponseCard(
                        block = block,
                        onCancelTurn = onCancelTurn,
                        onPauseResumeTurn = onPauseResumeTurn,
                        onRetryTurn = onRetryTurn
                    )
                }
            }
        }
    }
}

@Composable
fun BlockTimeline(
    blocks: List<WarpBlockState>,
    modifier: Modifier = Modifier,
    searchState: dev.warp.mobile.search.BlockSearchState? = null,
    onCopyBlock: (WarpBlockState) -> Unit = {},
    onRerunBlock: (WarpBlockState) -> Unit = {},
    onExplainBlock: (WarpBlockState) -> Unit = {}
) {
    val items = remember(blocks) { blocks.map { WarpTimelineBlock.CommandBlock(it) } }
    BlockTimeline(
        timelineBlocks = items,
        modifier = modifier,
        searchState = searchState,
        onCopyBlock = onCopyBlock,
        onRerunBlock = onRerunBlock,
        onExplainBlock = onExplainBlock
    )
}

@Composable
fun UserPromptCard(
    block: WarpTimelineBlock.UserPromptBlock,
    modifier: Modifier = Modifier,
    onEditPrompt: (WarpTimelineBlock.UserPromptBlock, String) -> Unit = { _, _ -> }
) {
    var isEditing by remember { mutableStateOf(false) }
    var editedText by remember(block.prompt) { mutableStateOf(block.prompt) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E293B)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "User",
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "User Prompt",
                    style = TextStyle(
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Color(0xFF38BDF8)
                    ),
                    modifier = Modifier.weight(1f)
                )
                if (!isEditing) {
                    OutlinedButton(
                        onClick = { isEditing = true },
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit",
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Edit", fontSize = 11.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (isEditing) {
                OutlinedTextField(
                    value = editedText,
                    onValueChange = { editedText = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 13.sp, color = Color.White)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(onClick = {
                        isEditing = false
                        editedText = block.prompt
                    }) {
                        Text("Cancel", fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Button(onClick = {
                        isEditing = false
                        onEditPrompt(block, editedText)
                    }) {
                        Text("Submit", fontSize = 12.sp)
                    }
                }
            } else {
                Text(
                    text = block.prompt,
                    style = TextStyle(
                        fontFamily = FontFamily.Default,
                        fontSize = 13.sp,
                        color = Color(0xFFF1F5F9)
                    )
                )
            }
        }
    }
}

@Composable
fun ReasoningCard(
    block: WarpTimelineBlock.ReasoningCardBlock,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(true) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E1B4B)
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
            ) {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = "Reasoning",
                    tint = Color(0xFFA855F7),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (block.isStreaming) "Thinking..." else "Reasoning Process",
                    style = TextStyle(
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                        color = Color(0xFFA855F7)
                    ),
                    modifier = Modifier.weight(1f)
                )
                if (block.isStreaming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFFA855F7)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Toggle",
                    tint = Color(0xFFA855F7),
                    modifier = Modifier.size(16.dp)
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF0F172A),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = block.thinkingText,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = Color(0xFFCBD5E1)
                            ),
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ToolInvocationCard(
    block: WarpTimelineBlock.ToolInvocationBlock,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF0F172A)
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = "Tool",
                    tint = Color(0xFFF59E0B),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Tool: ${block.toolName}",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Color(0xFFF59E0B)
                    ),
                    modifier = Modifier.weight(1f)
                )
                StatusBadge(status = block.status)
            }

            Spacer(modifier = Modifier.height(6.dp))

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFF1E293B),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = block.inputJson,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFFE2E8F0)
                    ),
                    modifier = Modifier.padding(8.dp)
                )
            }

            block.output?.let { output ->
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF020617),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = output,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        ),
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun StatusBadge(status: ToolStatus) {
    val (bg, fg, label) = when (status) {
        ToolStatus.PENDING_APPROVAL -> Triple(Color(0xFFFEF3C7), Color(0xFFD97706), "Pending Approval")
        ToolStatus.APPROVED -> Triple(Color(0xFFDCFCE7), Color(0xFF16A34A), "Approved")
        ToolStatus.REJECTED -> Triple(Color(0xFFFEE2E2), Color(0xFFDC2626), "Rejected")
        ToolStatus.EXECUTING -> Triple(Color(0xFFE0F2FE), Color(0xFF0284C7), "Executing")
        ToolStatus.COMPLETED -> Triple(Color(0xFFDCFCE7), Color(0xFF16A34A), "Completed")
        ToolStatus.FAILED -> Triple(Color(0xFFFEE2E2), Color(0xFFDC2626), "Failed")
    }

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = bg
    ) {
        Text(
            text = label,
            style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = fg),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun AssistantResponseCard(
    block: WarpTimelineBlock.AssistantResponseBlock,
    modifier: Modifier = Modifier,
    onCancelTurn: (WarpTimelineBlock.AssistantResponseBlock) -> Unit = {},
    onPauseResumeTurn: (WarpTimelineBlock.AssistantResponseBlock) -> Unit = {},
    onRetryTurn: (WarpTimelineBlock.AssistantResponseBlock) -> Unit = {}
) {
    val clipboardManager = LocalClipboardManager.current

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF0F172A)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header Row: Assistant Icon, Model Badge, Status Badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = "Agent",
                    tint = Color(0xFF10B981),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = block.model,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Color(0xFF10B981)
                    ),
                    modifier = Modifier.weight(1f)
                )
                AgentStatusBadge(status = block.status)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Content Area
            if (block.content.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF1E293B),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = block.content,
                        style = TextStyle(
                            fontFamily = FontFamily.Default,
                            fontSize = 13.sp,
                            color = Color(0xFFF8FAFC)
                        ),
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            block.errorMessage?.let { error ->
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF450A0A),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Error: $error",
                        style = TextStyle(fontSize = 11.sp, color = Color(0xFFFCA5A5)),
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Turn Control Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (block.status == AgentTurnStatus.STREAMING || block.status == AgentTurnStatus.CONNECTING) {
                    // Pause/Resume Button
                    OutlinedButton(
                        onClick = { onPauseResumeTurn(block) },
                        modifier = Modifier.height(30.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Pause,
                            contentDescription = "Pause",
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Pause", fontSize = 11.sp)
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Cancel Button
                    Button(
                        onClick = { onCancelTurn(block) },
                        modifier = Modifier.height(30.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cancel,
                            contentDescription = "Cancel",
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Cancel", fontSize = 11.sp)
                    }
                } else if (block.status == AgentTurnStatus.PAUSED) {
                    // Resume Button
                    Button(
                        onClick = { onPauseResumeTurn(block) },
                        modifier = Modifier.height(30.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Resume",
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Resume", fontSize = 11.sp)
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Cancel Button
                    OutlinedButton(
                        onClick = { onCancelTurn(block) },
                        modifier = Modifier.height(30.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cancel,
                            contentDescription = "Cancel",
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Cancel", fontSize = 11.sp)
                    }
                } else {
                    // Retry Button
                    OutlinedButton(
                        onClick = { onRetryTurn(block) },
                        modifier = Modifier.height(30.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Retry",
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Retry", fontSize = 11.sp)
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Copy Button
                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(block.content))
                        },
                        modifier = Modifier.height(30.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun AgentStatusBadge(status: AgentTurnStatus) {
    val (bg, fg, label) = when (status) {
        AgentTurnStatus.IDLE -> Triple(Color(0xFF334155), Color(0xFF94A3B8), "Idle")
        AgentTurnStatus.CONNECTING -> Triple(Color(0xFFFEF3C7), Color(0xFFD97706), "Connecting")
        AgentTurnStatus.STREAMING -> Triple(Color(0xFFDCFCE7), Color(0xFF16A34A), "Streaming")
        AgentTurnStatus.PAUSED -> Triple(Color(0xFFFEF3C7), Color(0xFFD97706), "Paused")
        AgentTurnStatus.COMPLETED -> Triple(Color(0xFFDCFCE7), Color(0xFF16A34A), "Completed")
        AgentTurnStatus.CANCELLED -> Triple(Color(0xFFF1F5F9), Color(0xFF64748B), "Cancelled")
        AgentTurnStatus.ERROR -> Triple(Color(0xFFFEE2E2), Color(0xFFDC2626), "Error")
    }

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = bg
    ) {
        Text(
            text = label,
            style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = fg),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
