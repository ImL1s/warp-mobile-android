package dev.warp.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.warp.mobile.editor.CommandHistoryManager
import dev.warp.mobile.editor.CommandHistoryNavigator
import dev.warp.mobile.editor.GhostCompletionEngine
import dev.warp.mobile.editor.GhostTextVisualTransformation
import dev.warp.mobile.editor.SlashCommandRegistry

@Composable
fun PromptComposer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSubmit: (String) -> Unit,
    onOpenSearch: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 120.dp
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val historyNavigator = remember { CommandHistoryNavigator() }
    var isExpanded by remember { mutableStateOf(false) }
    var isHistorySheetVisible by remember { mutableStateOf(false) }

    val text = value.text
    val isSlashMode = text.startsWith("/")

    var slashSelectedIndex by remember(text) { mutableStateOf(0) }
    val filteredSlashCommands = remember(text) {
        if (isSlashMode) SlashCommandRegistry.filterCommands(text) else emptyList()
    }

    var dismissedGhostSuggestion by remember { mutableStateOf<String?>(null) }

    // Ghost suggestion suffix calculation — suppressed during active CJK composition
    val rawGhostSuggestion = remember(text, value.composition) {
        if (!isSlashMode && value.composition == null) GhostCompletionEngine.getGhostSuggestion(text) else null
    }
    val activeGhostSuggestion = if (rawGhostSuggestion != dismissedGhostSuggestion) rawGhostSuggestion else null
    val ghostSuffix = remember(text, activeGhostSuggestion, value.composition) {
        if (value.composition == null) GhostCompletionEngine.getGhostSuffix(text, activeGhostSuggestion) else ""
    }

    val submitAction = {
        val cmd = value.text
        if (cmd.isNotBlank()) {
            if (cmd.trim() == "/search") {
                onOpenSearch?.invoke()
                onValueChange(TextFieldValue(""))
            } else {
                CommandHistoryManager.addCommand(context, cmd)
                historyNavigator.reset()
                dismissedGhostSuggestion = null
                onSubmit(cmd)
                onValueChange(TextFieldValue(""))
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Slash Command Palette Popover
        if (isSlashMode && filteredSlashCommands.isNotEmpty()) {
            SlashCommandPalette(
                items = filteredSlashCommands,
                selectedIndex = slashSelectedIndex,
                onSelectCommand = { selectedItem ->
                    if (selectedItem.command == "/search") {
                        onOpenSearch?.invoke()
                        onValueChange(TextFieldValue(""))
                    } else {
                        onValueChange(TextFieldValue(selectedItem.command, TextRange(selectedItem.command.length)))
                    }
                }
            )
        }

        // Main Prompt Editor Box
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = { newValue ->
                            if (newValue.text != value.text) {
                                dismissedGhostSuggestion = null
                            }
                            onValueChange(newValue)
                        },
                        visualTransformation = GhostTextVisualTransformation(ghostSuffix),
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp
                        ),
                        keyboardOptions = KeyboardOptions(
                            imeAction = if (!isExpanded) ImeAction.Send else ImeAction.Default
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = { submitAction() }
                        ),
                        decorationBox = { inner ->
                            if (value.text.isEmpty()) {
                                Text(
                                    "Type a command (ls, git status, /help…)",
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
                            .weight(1f)
                            .heightIn(min = 36.dp, max = maxHeight)
                            .verticalScroll(scrollState)
                            .semantics {
                                contentDescription = "Command prompt composer"
                                if (ghostSuffix.isNotEmpty()) {
                                    stateDescription = "Suggestion available: $ghostSuffix. Press Tab or swipe right to accept."
                                }
                                customActions = listOfNotNull(
                                    if (activeGhostSuggestion != null && ghostSuffix.isNotEmpty()) {
                                        CustomAccessibilityAction("Accept ghost suggestion") {
                                            onValueChange(TextFieldValue(activeGhostSuggestion, TextRange(activeGhostSuggestion.length)))
                                            true
                                        }
                                    } else null,
                                    CustomAccessibilityAction("Toggle expander") {
                                        isExpanded = !isExpanded
                                        true
                                    },
                                    CustomAccessibilityAction("Command history") {
                                        isHistorySheetVisible = true
                                        true
                                    }
                                )
                            }
                            .onPreviewKeyEvent { keyEvent ->
                                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                                val isComposing = value.composition != null
                                val isCtrl = keyEvent.isCtrlPressed
                                val isShift = keyEvent.isShiftPressed
                                val isMeta = keyEvent.isMetaPressed

                                // Physical hardware paste shortcuts: Shift+Insert, Ctrl+Shift+V, Cmd+V
                                if ((isShift && keyEvent.key == Key.Insert) ||
                                    (isCtrl && isShift && keyEvent.key == Key.V) ||
                                    (isMeta && keyEvent.key == Key.V)
                                ) {
                                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                    val clip = cm?.primaryClip
                                    val textToPaste = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context).toString() else ""
                                    if (textToPaste.isNotEmpty()) {
                                        val start = value.selection.start.coerceAtLeast(0)
                                        val end = value.selection.end.coerceAtLeast(0)
                                        val minSel = minOf(start, end)
                                        val maxSel = maxOf(start, end)
                                        val newText = value.text.substring(0, minSel) + textToPaste + value.text.substring(maxSel)
                                        val newCursor = minSel + textToPaste.length
                                        onValueChange(TextFieldValue(newText, TextRange(newCursor)))
                                        return@onPreviewKeyEvent true
                                    }
                                }

                                when (keyEvent.key) {
                                    Key.Tab -> {
                                        if (isSlashMode && filteredSlashCommands.isNotEmpty()) {
                                            val selected = filteredSlashCommands.getOrNull(slashSelectedIndex)
                                            if (selected != null) {
                                                onValueChange(TextFieldValue(selected.command, TextRange(selected.command.length)))
                                                return@onPreviewKeyEvent true
                                            }
                                        }
                                        if (activeGhostSuggestion != null && ghostSuffix.isNotEmpty()) {
                                            onValueChange(TextFieldValue(activeGhostSuggestion, TextRange(activeGhostSuggestion.length)))
                                            return@onPreviewKeyEvent true
                                        }
                                        false
                                    }
                                    Key.DirectionRight -> {
                                        if (activeGhostSuggestion != null && ghostSuffix.isNotEmpty() && value.selection.end == value.text.length) {
                                            onValueChange(TextFieldValue(activeGhostSuggestion, TextRange(activeGhostSuggestion.length)))
                                            return@onPreviewKeyEvent true
                                        }
                                        false
                                    }
                                    Key.Escape -> {
                                        if (activeGhostSuggestion != null) {
                                            dismissedGhostSuggestion = activeGhostSuggestion
                                            return@onPreviewKeyEvent true
                                        }
                                        false
                                    }
                                    Key.DirectionUp -> {
                                        if (isSlashMode && filteredSlashCommands.isNotEmpty()) {
                                            slashSelectedIndex = (slashSelectedIndex - 1 + filteredSlashCommands.size) % filteredSlashCommands.size
                                            return@onPreviewKeyEvent true
                                        } else {
                                            val prevCmd = historyNavigator.navigateUp(value.text)
                                            if (prevCmd != value.text) {
                                                onValueChange(TextFieldValue(text = prevCmd, selection = TextRange(prevCmd.length)))
                                                return@onPreviewKeyEvent true
                                            }
                                        }
                                        false
                                    }
                                    Key.DirectionDown -> {
                                        if (isSlashMode && filteredSlashCommands.isNotEmpty()) {
                                            slashSelectedIndex = (slashSelectedIndex + 1) % filteredSlashCommands.size
                                            return@onPreviewKeyEvent true
                                        } else {
                                            val nextCmd = historyNavigator.navigateDown()
                                            onValueChange(TextFieldValue(text = nextCmd, selection = TextRange(nextCmd.length)))
                                            return@onPreviewKeyEvent true
                                        }
                                    }
                                    Key.Enter -> {
                                        if (isComposing) {
                                            // IME candidate composing active: do not submit command
                                            return@onPreviewKeyEvent false
                                        }
                                        if (isSlashMode && filteredSlashCommands.isNotEmpty()) {
                                            val selected = filteredSlashCommands.getOrNull(slashSelectedIndex)
                                            if (selected != null) {
                                                onValueChange(TextFieldValue(selected.command, TextRange(selected.command.length)))
                                                return@onPreviewKeyEvent true
                                            }
                                        }
                                        if (keyEvent.isShiftPressed) {
                                            val newText = value.text + "\n"
                                            onValueChange(TextFieldValue(newText, TextRange(newText.length)))
                                            isExpanded = true
                                            true
                                        } else if (isExpanded) {
                                            if (keyEvent.isCtrlPressed) {
                                                submitAction()
                                                true
                                            } else {
                                                val newText = value.text + "\n"
                                                onValueChange(TextFieldValue(newText, TextRange(newText.length)))
                                                true
                                            }
                                        } else {
                                            submitAction()
                                            true
                                        }
                                    }
                                    else -> false
                                }
                            }
                    )

                    // Expander Toggle Button
                    IconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                            contentDescription = if (isExpanded) "Collapse composer" else "Expand composer",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { /* Working directory picker */ }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = "Working directory",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = { isHistorySheetVisible = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Filled.History,
                            contentDescription = "Command History",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.height(28.dp)
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

                    IconButton(onClick = { /* Voice input */ }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = "Voice input",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = submitAction,
                        enabled = value.text.isNotBlank(),
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Filled.Send,
                            contentDescription = "Send prompt",
                            tint = if (value.text.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }

    // Command History Modal Sheet
    CommandHistorySheet(
        isVisible = isHistorySheetVisible,
        historyItems = CommandHistoryManager.getHistory(),
        onSelectCommand = { cmd ->
            onValueChange(TextFieldValue(cmd, TextRange(cmd.length)))
        },
        onExecuteCommand = { cmd ->
            onValueChange(TextFieldValue(cmd, TextRange(cmd.length)))
            submitAction()
        },
        onDeleteItem = { item ->
            CommandHistoryManager.deleteItem(context, item)
        },
        onClearAll = {
            CommandHistoryManager.clearHistory(context)
        },
        onDismiss = { isHistorySheetVisible = false }
    )
}
