package dev.warp.mobile.editor

import dev.warp.mobile.GhostSuggestController

object GhostCompletionEngine {
    val CLI_DICTIONARY = listOf(
        "git status",
        "git diff",
        "git log",
        "git commit -m \"\"",
        "git push",
        "git pull",
        "git checkout -b ",
        "docker ps",
        "docker run -it ",
        "docker compose up",
        "cargo build",
        "cargo test",
        "cargo run",
        "npm start",
        "npm test",
        "npm run build",
        "kubectl get pods",
        "kubectl logs ",
        "cd ..",
        "ls -la",
        "adb logcat",
        "python3 -m ",
        "ssh "
    )

    fun getGhostSuggestion(
        input: String,
        history: List<HistoryItem> = CommandHistoryManager.getHistory(),
        aiSuggestionSupplier: () -> String? = { GhostSuggestController.snapshot().suggestion }
    ): String? {
        if (input.isBlank()) return null

        // 1. Search recent command history (reverse chronological) for prefix match
        val historyMatch = history.reversed().firstOrNull {
            it.command.startsWith(input, ignoreCase = true) && it.command.length > input.length
        }?.command
        if (historyMatch != null) return historyMatch

        // 2. Search common CLI dictionary for prefix match
        val dictionaryMatch = CLI_DICTIONARY.firstOrNull {
            it.startsWith(input, ignoreCase = true) && it.length > input.length
        }
        if (dictionaryMatch != null) return dictionaryMatch

        // 3. Fallback to AI suggestion if available
        val aiSuggestion = aiSuggestionSupplier()
        if (aiSuggestion != null && aiSuggestion.startsWith(input, ignoreCase = true) && aiSuggestion.length > input.length) {
            return aiSuggestion
        }

        return null
    }

    fun getGhostSuffix(input: String, suggestion: String?): String {
        if (suggestion == null || input.isEmpty()) return ""
        return if (suggestion.startsWith(input, ignoreCase = true) && suggestion.length > input.length) {
            suggestion.substring(input.length)
        } else {
            ""
        }
    }
}
