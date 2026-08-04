package dev.warp.mobile.editor

data class SlashCommandItem(
    val command: String,
    val title: String,
    val description: String,
    val iconName: String = ""
)

object SlashCommandRegistry {
    val ALL_COMMANDS = listOf(
        SlashCommandItem("/ai", "AI Assistant", "Start multi-turn AI agent prompt"),
        SlashCommandItem("/clear", "Clear Screen", "Clear active terminal scrollback"),
        SlashCommandItem("/history", "Command History", "Browse and search command execution history"),
        SlashCommandItem("/search", "Unified Search", "Search sessions, blocks, history, AI, files"),
        SlashCommandItem("/ssh", "SSH Session", "Connect to remote host via SSH"),
        SlashCommandItem("/settings", "Settings", "Open BYOK & app configuration"),
        SlashCommandItem("/split", "Split Pane", "Create split terminal viewport"),
        SlashCommandItem("/help", "Help & Shortcuts", "View command palette reference")
    )

    fun getCommands(): List<SlashCommandItem> = ALL_COMMANDS

    fun filterCommands(input: String): List<SlashCommandItem> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ALL_COMMANDS
        if (trimmed == "/" || trimmed == "//") return ALL_COMMANDS

        val isDirectControlQuery = input.startsWith("/") && (input.endsWith("\n") || input.endsWith("\t") || input.endsWith("\r"))

        if (trimmed.startsWith("/")) {
            val rawAfterSlash = trimmed.substring(1)
            val afterSlash = rawAfterSlash.trim().lowercase()
            if (afterSlash.isEmpty() || afterSlash == "/") return ALL_COMMANDS

            val hasSpaceAfterSlash = rawAfterSlash.contains(" ")

            val exactMatch = ALL_COMMANDS.filter { it.command.substring(1).lowercase() == afterSlash || it.command.lowercase() == "/$afterSlash" }
            if (exactMatch.isNotEmpty() && !isDirectControlQuery && !hasSpaceAfterSlash) {
                return exactMatch
            }

            if (!isDirectControlQuery && !hasSpaceAfterSlash) {
                val cmdOrTitleMatches = ALL_COMMANDS.filter {
                    it.command.lowercase().contains(afterSlash) ||
                    it.command.substring(1).lowercase().startsWith(afterSlash) ||
                    it.title.lowercase().contains(afterSlash)
                }
                if (cmdOrTitleMatches.isNotEmpty()) return cmdOrTitleMatches
            }

            return ALL_COMMANDS.filter {
                it.command.lowercase().contains(afterSlash) ||
                it.command.substring(1).lowercase().startsWith(afterSlash) ||
                it.title.lowercase().contains(afterSlash) ||
                it.description.lowercase().contains(afterSlash)
            }
        } else {
            val q = trimmed.lowercase()
            val cmdOrTitleMatches = ALL_COMMANDS.filter {
                it.command.lowercase().contains(q) ||
                it.command.substring(1).lowercase().startsWith(q) ||
                it.title.lowercase().contains(q)
            }
            if (cmdOrTitleMatches.isNotEmpty()) return cmdOrTitleMatches
            return ALL_COMMANDS.filter { it.description.lowercase().contains(q) }
        }
    }
}
