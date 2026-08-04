package dev.warp.mobile.skills

import java.io.File

class LocalSkillRegistry(private val skillsDir: File) {

    private val skills = mutableListOf<Skill>()

    init {
        loadSkills()
    }

    private fun loadSkills() {
        if (!skillsDir.exists() || !skillsDir.isDirectory) return

        skillsDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory) {
                val file = File(dir, "SKILL.md")
                if (file.exists() && file.isFile) {
                    var name = ""
                    var description = ""
                    val keywords = mutableListOf<String>()

                    file.readLines().forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("# Name:")) {
                            name = trimmed.removePrefix("# Name:").trim()
                        } else if (trimmed.startsWith("Description:")) {
                            description = trimmed.removePrefix("Description:").trim()
                        } else if (trimmed.startsWith("- Keyword:")) {
                            keywords.add(trimmed.removePrefix("- Keyword:").trim())
                        }
                    }

                    if (name.isNotEmpty()) {
                        skills.add(Skill(name, description, keywords))
                    }
                }
            }
        }
    }

    fun matchSkill(prompt: String): Skill? {
        if (prompt.isBlank()) return null
        val lowerPrompt = prompt.lowercase()
        return skills.firstOrNull { skill ->
            skill.triggerKeywords.any { keyword ->
                lowerPrompt.contains(keyword.lowercase())
            }
        }
    }
}
