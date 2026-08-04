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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.warp.mobile.WarpBlockState
import dev.warp.mobile.ai.CommandApprovalDialog
import dev.warp.mobile.ai.CommandApprovalManager
import dev.warp.mobile.search.UnifiedSearchProvider
import kotlinx.coroutines.launch

/**
 * M7 (iteration 20 — Warp UX scaffold): the Compose chrome around the
 * existing warpui Vulkan terminal pane. Mirrors the layout of Warp Desktop:
 * left navigation drawer with tabs list, top bar with search field, content
 * slot for the active tab's terminal grid (passed in via the `content`
 * lambda — typically an AndroidView wrapping the SurfaceView/WarpInputView/
 * AccessoryRow tree from MainActivity), bottom prompt box for agent +
 * `/<command>` palette + model picker.
 */

data class WarpTab(
    val id: String,
    val title: String,
    val cwd: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarpScaffold(
    tabs: List<WarpTab>,
    activeTabId: String,
    blocks: List<WarpBlockState> = emptyList(),
    isRawMode: Boolean = false,
    layoutType: WarpLayoutType = WarpLayoutType.COMPACT_SINGLE_PANE,
    onTabSelected: (String) -> Unit,
    onNewTab: () -> Unit,
    onSettings: () -> Unit,
    onPromptSubmit: (String) -> Unit,
    onRerunBlock: ((WarpBlockState) -> Unit)? = null,
    onExplainBlock: ((WarpBlockState) -> Unit)? = null,
    onShareBlock: ((WarpBlockState) -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var searchText by remember { mutableStateOf("") }
    var promptTextFieldValue by remember { mutableStateOf(TextFieldValue("")) }

    val searchProvider = remember { UnifiedSearchProvider(coroutineScope = scope) }
    val searchState by searchProvider.state.collectAsState()

    val pendingApproval by CommandApprovalManager.pendingApproval.collectAsState()
    val context = LocalContext.current

    pendingApproval?.let { pending ->
        CommandApprovalDialog(
            command = pending.command,
            riskLevel = pending.riskLevel,
            onApprove = {
                CommandApprovalManager.submitDecision(context, approved = true)
            },
            onReject = {
                CommandApprovalManager.submitDecision(context, approved = false)
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp)
                ) {
                    // Drawer header — search + new-tab button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp),
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Search,
                                    contentDescription = "Search tabs",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                BasicTextField(
                                    value = searchText,
                                    onValueChange = { searchText = it },
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 14.sp
                                    ),
                                    decorationBox = { inner ->
                                        if (searchText.isEmpty()) {
                                            Text(
                                                "Search tabs…",
                                                style = TextStyle(
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    fontSize = 14.sp
                                                )
                                            )
                                        }
                                        inner()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = onNewTab) {
                            Icon(Icons.Filled.Add, contentDescription = "New tab")
                        }
                    }
                    // Tabs list
                    val filtered = if (searchText.isBlank()) tabs
                        else tabs.filter { it.title.contains(searchText, ignoreCase = true) }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(filtered, key = { it.id }) { tab ->
                            NavigationDrawerItem(
                                label = {
                                    Column {
                                        Text(
                                            tab.title,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            tab.cwd,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                },
                                selected = tab.id == activeTabId,
                                onClick = {
                                    onTabSelected(tab.id)
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        // Top search field — mirrors Warp Desktop's "Search
                        // sessions, agents, files…" hero.
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .clickable { searchProvider.setOverlayVisible(true) },
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Search,
                                    contentDescription = "Search",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Search sessions, agents, files…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Open drawer")
                        }
                    },
                    actions = {
                        IconButton(onClick = { searchProvider.setOverlayVisible(true) }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search")
                        }
                        IconButton(onClick = onNewTab) {
                            Icon(Icons.Filled.Add, contentDescription = "New tab")
                        }
                        IconButton(onClick = onSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            },
            bottomBar = {
                PromptComposer(
                    value = promptTextFieldValue,
                    onValueChange = { promptTextFieldValue = it },
                    onSubmit = { promptText ->
                        if (promptText.trim() == "/search") {
                            searchProvider.setOverlayVisible(true)
                        } else {
                            onPromptSubmit(promptText)
                        }
                    },
                    onOpenSearch = {
                        searchProvider.setOverlayVisible(true)
                    }
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize()) {
                content(padding)
                if (!isRawMode) {
                    BlockTimeline(
                        blocks = blocks,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        onRerunBlock = { onRerunBlock?.invoke(it) },
                        onExplainBlock = { onExplainBlock?.invoke(it) },
                        onCopyBlock = { block ->
                            if (onShareBlock != null) onShareBlock.invoke(block)
                        }
                    )
                }

                if (searchState.isOverlayVisible) {
                    UnifiedSearchOverlay(
                        searchState = searchState,
                        onQueryChanged = { searchProvider.onQueryChanged(it) },
                        onDomainSelected = { searchProvider.onDomainSelected(it) },
                        onDismiss = { searchProvider.setOverlayVisible(false) },
                        onSelectNext = { searchProvider.selectNextResult() },
                        onSelectPrevious = { searchProvider.selectPreviousResult() },
                        onSessionSelected = { sessionId ->
                            onTabSelected(sessionId)
                            searchProvider.setOverlayVisible(false)
                        },
                        onBlockSelected = { sessionId, _ ->
                            if (sessionId != null) onTabSelected(sessionId)
                            searchProvider.setOverlayVisible(false)
                        },
                        onHistorySelected = { cmd ->
                            promptTextFieldValue = TextFieldValue(cmd, TextRange(cmd.length))
                            searchProvider.setOverlayVisible(false)
                        },
                        onAiBlockSelected = { sessionId, _ ->
                            if (sessionId != null) onTabSelected(sessionId)
                            searchProvider.setOverlayVisible(false)
                        },
                        onFileSelected = { _ ->
                            searchProvider.setOverlayVisible(false)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun WarpPromptComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    // V1-prep iteration 25 (2026-05-02): MainActivity uses
    // WindowCompat.setDecorFitsSystemWindows(window, false) for edge-to-edge,
    // so adjustResize does NOT shrink the window when the IME opens; the IME
    // overlays on top instead. Without imePadding() the prompt composer would
    // sit at the bottom of the full-height window and be hidden behind the
    // IME — exactly the "鍵盤輸入沒反應" bug the user hit. imePadding() on
    // the outer Surface lifts the composer above the IME.
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                // V1-prep iteration 27 (2026-05-02): singleLine = true so
                // pressing the IME Enter key triggers actionSend (→ onSend →
                // onSubmit) instead of inserting a literal newline. With
                // singleLine = false the IME treats Enter as multi-line
                // newline insertion regardless of imeAction = Send hint, so
                // the user types, presses Enter, and nothing happens (their
                // "按下 enter 沒反應" complaint). Multi-line paste still
                // works via the AccessoryRow Paste button which writes raw
                // bytes including embedded newlines.
                singleLine = true,
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSubmit() }),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        // V1-prep iteration 42 (2026-05-03): placeholder
                        // matches what Send actually does. Pre-iter-42 this
                        // said "Warp anything e.g. Deploy my React app..."
                        // implying AI-prompt semantics, but the BYOK AI
                        // client (M9-S03) is not yet wired so onSubmit
                        // writes the raw text to the PTY. Calling it a
                        // "command" matches the shell-first default.
                        Text(
                            "Type a command (ls, git status, …)",
                            style = TextStyle(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    inner()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 36.dp)
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Bottom-left: cwd indicator + font-size icon (mirrors Warp Desktop)
                IconButton(onClick = { /* M9-S02 — cwd picker */ }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = "Working directory",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                // Bottom-right: model picker chip placeholder (M9-S03 will wire
                // it to the BYOK Anthropic client) + mic + send.
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .height(28.dp)
                        .clickable { /* M9-S03 — model picker */ }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "auto (cost-efficient)",
                            style = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 12.sp
                            )
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = { /* v2+ voice input */ }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = "Voice input",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onSubmit,
                    enabled = value.isNotBlank(),
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = "Send prompt",
                        tint = if (value.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
