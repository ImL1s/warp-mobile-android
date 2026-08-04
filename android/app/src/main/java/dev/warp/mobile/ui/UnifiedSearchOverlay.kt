package dev.warp.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dev.warp.mobile.search.SearchDomain
import dev.warp.mobile.search.UnifiedSearchResultItem
import dev.warp.mobile.search.UnifiedSearchState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedSearchOverlay(
    searchState: UnifiedSearchState,
    onQueryChanged: (String) -> Unit,
    onDomainSelected: (SearchDomain) -> Unit,
    onDismiss: () -> Unit,
    onSelectNext: () -> Unit = {},
    onSelectPrevious: () -> Unit = {},
    onSessionSelected: (String) -> Unit = {},
    onBlockSelected: (sessionId: String?, blockId: String) -> Unit = { _, _ -> },
    onHistorySelected: (String) -> Unit = {},
    onAiBlockSelected: (sessionId: String?, blockId: String) -> Unit = { _, _ -> },
    onFileSelected: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (!searchState.isOverlayVisible) return

    BackHandler(enabled = searchState.isOverlayVisible) {
        onDismiss()
    }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(searchState.isOverlayVisible) {
        if (searchState.isOverlayVisible) {
            focusRequester.requestFocus()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onDismiss)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 24.dp)
                .align(Alignment.TopCenter)
                .clickable(enabled = false, onClick = {}),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Top Search Input Box
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(24.dp)
                        )
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    BasicTextField(
                        value = searchState.query,
                        onValueChange = onQueryChanged,
                        singleLine = true,
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 16.sp
                        ),
                        decorationBox = { inner ->
                            if (searchState.query.isEmpty()) {
                                Text(
                                    "Search sessions, blocks, history, AI, files…",
                                    style = TextStyle(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 15.sp
                                    )
                                )
                            }
                            inner()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent { keyEvent ->
                                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (keyEvent.key) {
                                    Key.DirectionDown -> {
                                        onSelectNext()
                                        true
                                    }
                                    Key.DirectionUp -> {
                                        onSelectPrevious()
                                        true
                                    }
                                    Key.Escape -> {
                                        onDismiss()
                                        true
                                    }
                                    Key.Enter -> {
                                        if (searchState.results.isNotEmpty()) {
                                            val idx = searchState.selectedIndex.coerceIn(0, searchState.results.lastIndex)
                                            val item = searchState.results[idx]
                                            onDismiss()
                                            when (item) {
                                                is UnifiedSearchResultItem.SessionResult -> onSessionSelected(item.sessionId)
                                                is UnifiedSearchResultItem.BlockResult -> onBlockSelected(item.sessionId, item.blockId)
                                                is UnifiedSearchResultItem.HistoryResult -> onHistorySelected(item.command)
                                                is UnifiedSearchResultItem.AiResult -> onAiBlockSelected(item.sessionId, item.blockId)
                                                is UnifiedSearchResultItem.FileResult -> onFileSelected(item.filePath)
                                            }
                                            true
                                        } else false
                                    }
                                    else -> false
                                }
                            }
                    )
                    if (searchState.query.isNotEmpty()) {
                        IconButton(
                            onClick = { onQueryChanged("") },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Filled.Clear,
                                contentDescription = "Clear search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close overlay",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 6 Domain Filter Chips
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(SearchDomain.entries) { domain ->
                        val isSelected = searchState.selectedDomain == domain
                        val count = searchState.domainCounts[domain]
                        val labelText = if (count != null && count > 0) "${domain.label} ($count)" else domain.label

                        FilterChip(
                            selected = isSelected,
                            onClick = { onDomainSelected(domain) },
                            label = {
                                Text(
                                    labelText,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier.semantics {
                                role = Role.Tab
                                selected = isSelected
                                contentDescription = labelText
                            }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Results Container
                if (searchState.isSearching) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                } else if (searchState.query.isNotBlank() && searchState.results.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No matching results found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(
                            items = searchState.results,
                            key = { _, item -> item.id }
                        ) { index, item ->
                            val isSelectedRow = index == searchState.selectedIndex
                            SearchResultRow(
                                item = item,
                                query = searchState.query,
                                isSelected = isSelectedRow,
                                onClick = {
                                    onDismiss()
                                    when (item) {
                                        is UnifiedSearchResultItem.SessionResult -> onSessionSelected(item.sessionId)
                                        is UnifiedSearchResultItem.BlockResult -> onBlockSelected(item.sessionId, item.blockId)
                                        is UnifiedSearchResultItem.HistoryResult -> onHistorySelected(item.command)
                                        is UnifiedSearchResultItem.AiResult -> onAiBlockSelected(item.sessionId, item.blockId)
                                        is UnifiedSearchResultItem.FileResult -> onFileSelected(item.filePath)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    item: UnifiedSearchResultItem,
    query: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val cardBg = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "${item.domain.label}: ${item.title}. ${item.subtitle}"
                customActions = listOf(
                    CustomAccessibilityAction("Select result") {
                        onClick()
                        true
                    }
                )
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Domain Badge Icon
            DomainBadge(domain = item.domain)

            Spacer(Modifier.width(12.dp))

            // Text Content with Yellow SpanStyle Highlighting
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = buildHighlightedText(item.title, query),
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (item.subtitle.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = buildHighlightedText(item.subtitle, query),
                        style = TextStyle(
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun DomainBadge(domain: SearchDomain) {
    val (icon, color) = when (domain) {
        SearchDomain.ALL -> Icons.Filled.Search to Color(0xFF757575)
        SearchDomain.SESSIONS -> Icons.Filled.Tab to Color(0xFF1E88E5)
        SearchDomain.BLOCKS -> Icons.Filled.Terminal to Color(0xFF43A047)
        SearchDomain.HISTORY -> Icons.Filled.History to Color(0xFFFB8C00)
        SearchDomain.AI -> Icons.Filled.SmartToy to Color(0xFF8E24AA)
        SearchDomain.FILES -> Icons.Filled.Folder to Color(0xFF00ACC1)
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.15f),
        modifier = Modifier.size(36.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = domain.label,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

internal fun buildHighlightedText(text: String, query: String): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        val yellowHighlightStyle = SpanStyle(
            color = Color(0xFFFFD54F),
            fontWeight = FontWeight.Bold
        )
        var idx = text.indexOf(query, ignoreCase = true)
        while (idx != -1) {
            val end = idx + query.length
            addStyle(yellowHighlightStyle, idx, end)
            idx = text.indexOf(query, idx + query.length.coerceAtLeast(1), ignoreCase = true)
        }
    }
}
