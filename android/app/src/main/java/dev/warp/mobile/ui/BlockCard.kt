package dev.warp.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.warp.mobile.BlockShareManager
import dev.warp.mobile.WarpBlockState
import dev.warp.mobile.search.BlockSearchEngine
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription



@Composable
fun BlockCard(
    block: WarpBlockState,
    modifier: Modifier = Modifier,
    onCopyBlock: (WarpBlockState) -> Unit = {},
    onCopyCommand: ((WarpBlockState) -> Unit)? = null,
    onCopyOutput: ((WarpBlockState) -> Unit)? = null,
    onRerunBlock: (WarpBlockState) -> Unit = {},
    onExplainBlock: (WarpBlockState) -> Unit = {},
    onShareBlock: ((WarpBlockState) -> Unit)? = null,
    searchQuery: String = "",
    isRegexSearch: Boolean = false,
    activeMatchRange: IntRange? = null,
    forceExpanded: Boolean = false
) {
    val context = LocalContext.current
    var isExpanded by remember(forceExpanded, activeMatchRange) { mutableStateOf(forceExpanded || activeMatchRange != null) }

    val highlightedCommand = remember(block.command, searchQuery, isRegexSearch, activeMatchRange) {
        if (searchQuery.isNotBlank()) {
            BlockSearchEngine.highlightSearchMatches(
                annotated = parseAnsiToAnnotatedString(block.command.ifBlank { "(empty command)" }),
                query = searchQuery,
                isRegex = isRegexSearch,
                activeMatchRange = activeMatchRange
            )
        } else {
            parseAnsiToAnnotatedString(block.command.ifBlank { "(empty command)" })
        }
    }

    val exitStatusDesc = when {
        block.isRunning -> "Running"
        block.exitCode == 0 -> "Completed successfully with exit code 0"
        else -> "Failed with exit code ${block.exitCode ?: 1}"
    }
    val durationDesc = block.durationMs?.let { "Duration: ${it}ms" } ?: ""
    val fullDescription = "Command: ${block.command.ifBlank { "empty" }}. Status: $exitStatusDesc. $durationDesc".trim()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = fullDescription
                stateDescription = exitStatusDesc
                customActions = listOf(
                    CustomAccessibilityAction("Copy output") {
                        val contentToCopy = "$ ${block.command}\n${block.output}"
                        BlockShareManager.copyToClipboardWithSensitiveFlag(
                            context = context,
                            label = "warp-block",
                            text = contentToCopy,
                            isSensitive = true
                        )
                        if (onCopyOutput != null) onCopyOutput(block) else onCopyBlock(block)
                        true
                    },
                    CustomAccessibilityAction("Re-run command") {
                        onRerunBlock(block)
                        true
                    },
                    CustomAccessibilityAction("Explain with AI") {
                        onExplainBlock(block)
                        true
                    },
                    CustomAccessibilityAction("Share block") {
                        if (onShareBlock != null) onShareBlock(block) else BlockShareManager.shareBlock(context, block)
                        true
                    }
                )
            },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header Row: prompt symbol $ , command text, status badge, duration badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "$ ",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp
                    )
                )
                Text(
                    text = highlightedCommand,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp
                    ),
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Duration badge
                block.durationMs?.let { duration ->
                    val durationText = if (duration < 1000) {
                        "${duration}ms"
                    } else {
                        String.format("%.1fs", duration / 1000.0)
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Text(
                            text = durationText,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                // Exit Code Status Badge
                if (block.isRunning) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(
                                color = Color(0xFFFFF3E0),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFFE65100)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Running",
                            style = TextStyle(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE65100)
                            )
                        )
                    }
                } else if (block.exitCode == 0) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFFE8F5E9)
                    ) {
                        Text(
                            text = "✓ 0",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2E7D32)
                            ),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFFFFEBEE)
                    ) {
                        Text(
                            text = "✗ ${block.exitCode ?: 1}",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFC62828)
                            ),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Output View: monospaced font output stream with ANSI color rendering & touch selection support
            if (block.output.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                val lines = remember(block.output) { block.output.lines() }
                val isLongOutput = lines.size > 150

                val displayText = remember(block.output, isExpanded, isLongOutput) {
                    if (isLongOutput && !isExpanded) {
                        val top = lines.take(50).joinToString("\n")
                        val bottom = lines.takeLast(100).joinToString("\n")
                        "$top\n... [${lines.size - 150} lines hidden] ...\n$bottom"
                    } else {
                        block.output
                    }
                }

                val annotatedOutput = remember(displayText, searchQuery, isRegexSearch, activeMatchRange) {
                    val rawAnnotated = parseAnsiToAnnotatedString(displayText)
                    if (searchQuery.isNotBlank()) {
                        BlockSearchEngine.highlightSearchMatches(
                            annotated = rawAnnotated,
                            query = searchQuery,
                            isRegex = isRegexSearch,
                            activeMatchRange = activeMatchRange
                        )
                    } else {
                        rawAnnotated
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF1E1E1E),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        SelectionContainer {
                            Text(
                                text = annotatedOutput,
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = Color(0xFFD4D4D4)
                                )
                            )
                        }

                        if (isLongOutput) {
                            Spacer(modifier = Modifier.height(4.dp))
                            TextButton(
                                onClick = { isExpanded = !isExpanded },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text(
                                    text = if (isExpanded) "Collapse output" else "Show full output (${lines.size} lines)",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            // Action Triggers Row: Copy, Re-run, Explain, Share buttons
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Copy Button
                OutlinedButton(
                    onClick = {
                        val contentToCopy = "$ ${block.command}\n${block.output}"
                        BlockShareManager.copyToClipboardWithSensitiveFlag(
                            context = context,
                            label = "warp-block",
                            text = contentToCopy,
                            isSensitive = true
                        )
                        if (onCopyOutput != null) {
                            onCopyOutput(block)
                        } else {
                            onCopyBlock(block)
                        }
                    },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy", fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Re-run Button
                OutlinedButton(
                    onClick = { onRerunBlock(block) },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Re-run",
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Re-run", fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Explain Button
                Button(
                    onClick = { onExplainBlock(block) },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "Explain",
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Explain", fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Share Button
                OutlinedButton(
                    onClick = {
                        if (onShareBlock != null) {
                            onShareBlock(block)
                        } else {
                            BlockShareManager.shareBlock(context, block)
                        }
                    },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share",
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Share", fontSize = 12.sp)
                }
            }
        }
    }
}
