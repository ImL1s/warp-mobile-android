package dev.warp.mobile.ai

enum class RiskLevel {
    LOW,
    HIGH;

    fun asString(): String = when (this) {
        LOW -> "LOW"
        HIGH -> "HIGH"
    }
}

object CommandRiskEvaluator {
    private val DANGEROUS_PATTERNS = listOf(
        "rm -rf",
        "rm -r ",
        "rm -f /",
        "rm -rf /",
        "shred ",
        "wipefs",
        "dd if=",
        "mkfs",
        "fdisk",
        "parted",
        "mkswap",
        "sudo ",
        "su -",
        "doas ",
        "chmod 777",
        "chmod -R 777",
        "chown -R",
        "reboot",
        "shutdown",
        "poweroff",
        "init 0",
        "curl | sh",
        "curl | bash",
        "curl | zsh",
        "wget | sh",
        "wget | bash",
        "wget | zsh",
        "git push --force",
        "git push -f",
        "git reset --hard",
        "apt purge",
        "drop database",
        "drop table",
        ":(){ :|:& };:"
    )

    fun evaluate(command: String): RiskLevel {
        val cleanCmd = command.trim()
        if (cleanCmd.isEmpty()) return RiskLevel.LOW

        val normalizedCmd = cleanCmd.replace(Regex("\\s+"), " ")
        val cmdLower = normalizedCmd.lowercase()

        for (pattern in DANGEROUS_PATTERNS) {
            if (cmdLower.contains(pattern.lowercase())) {
                return RiskLevel.HIGH
            }
        }

        if ((cmdLower.contains("curl") || cmdLower.contains("wget")) &&
            (cmdLower.contains("| sh") || cmdLower.contains("| bash") || cmdLower.contains("| zsh") ||
             cmdLower.contains("|sh") || cmdLower.contains("|bash") || cmdLower.contains("|zsh"))
        ) {
            return RiskLevel.HIGH
        }

        return RiskLevel.LOW
    }
}
