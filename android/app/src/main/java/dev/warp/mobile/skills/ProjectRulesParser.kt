package dev.warp.mobile.skills

import java.io.File

class ProjectRulesParser {
    fun parse(workspaceRoot: File): List<Rule> {
        val rulesFile = File(workspaceRoot, ".warprules")
        if (!rulesFile.exists() || !rulesFile.isFile) {
            return emptyList()
        }

        val rules = mutableListOf<Rule>()
        var currentName = ""
        var currentDescription = ""
        val currentPatterns = mutableListOf<String>()

        rulesFile.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("Rule:")) {
                if (currentName.isNotEmpty()) {
                    rules.add(Rule(currentName, currentDescription, currentPatterns.toList()))
                    currentPatterns.clear()
                }
                currentName = trimmed.removePrefix("Rule:").trim()
            } else if (trimmed.startsWith("Description:")) {
                currentDescription = trimmed.removePrefix("Description:").trim()
            } else if (trimmed.startsWith("- ")) {
                currentPatterns.add(trimmed.removePrefix("- ").trim())
            }
        }

        if (currentName.isNotEmpty()) {
            rules.add(Rule(currentName, currentDescription, currentPatterns.toList()))
        }

        return rules
    }
}
