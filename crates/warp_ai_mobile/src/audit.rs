//! CSV Usage Audit Logger Helper & RFC 4180 Escaping (Issue #15).

use serde::{Deserialize, Serialize};

/// Approval state of a tool command execution.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ApprovalState {
    Approved,
    Rejected,
    AutoAllowed,
}

impl ApprovalState {
    pub fn as_str(&self) -> &'static str {
        match self {
            ApprovalState::Approved => "APPROVED",
            ApprovalState::Rejected => "REJECTED",
            ApprovalState::AutoAllowed => "AUTO_ALLOWED",
        }
    }
}

/// A structured 7-column audit entry for warp-ai-usage.csv.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AuditEntry {
    pub timestamp: String,
    pub model: String,
    pub input_tokens: u32,
    pub output_tokens: u32,
    pub latency_ms: u64,
    pub command_string: String,
    pub approval_state: ApprovalState,
}

impl AuditEntry {
    /// Formats the audit entry as a 7-column RFC 4180 compliant CSV row.
    /// Schema: timestamp,model,input_tokens,output_tokens,latency_ms,command_string,approval_state
    pub fn to_csv_row(&self) -> String {
        let escaped_command = escape_rfc4180(&self.command_string);
        format!(
            "{},{},{},{},{},{},{}",
            self.timestamp,
            self.model,
            self.input_tokens,
            self.output_tokens,
            self.latency_ms,
            escaped_command,
            self.approval_state.as_str()
        )
    }

    /// Standard CSV header.
    pub fn csv_header() -> &'static str {
        "# timestamp,model,input_tokens,output_tokens,latency_ms,command_string,approval_state"
    }
}

/// RFC 4180 double-quote escaping helper.
pub fn escape_rfc4180(field: &str) -> String {
    let needs_quotes = field.contains(',') || field.contains('"') || field.contains('\n') || field.contains('\r');
    let escaped = field.replace('"', "\"\"");
    if needs_quotes {
        format!("\"{}\"", escaped)
    } else {
        escaped
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_escape_rfc4180_simple() {
        assert_eq!(escape_rfc4180("ls -la"), "ls -la");
    }

    #[test]
    fn test_escape_rfc4180_with_commas() {
        assert_eq!(escape_rfc4180("echo a,b,c"), "\"echo a,b,c\"");
    }

    #[test]
    fn test_escape_rfc4180_with_quotes() {
        assert_eq!(escape_rfc4180("echo \"hello\""), "\"echo \"\"hello\"\"\"");
    }

    #[test]
    fn test_escape_rfc4180_with_newlines() {
        assert_eq!(escape_rfc4180("line1\nline2"), "\"line1\nline2\"");
    }

    #[test]
    fn test_audit_entry_to_csv_row() {
        let entry = AuditEntry {
            timestamp: "2026-08-04T01:00:00Z".to_string(),
            model: "claude-3-5-sonnet".to_string(),
            input_tokens: 150,
            output_tokens: 300,
            latency_ms: 1200,
            command_string: "rm -rf /tmp/test,foo".to_string(),
            approval_state: ApprovalState::Approved,
        };

        let row = entry.to_csv_row();
        assert_eq!(
            row,
            "2026-08-04T01:00:00Z,claude-3-5-sonnet,150,300,1200,\"rm -rf /tmp/test,foo\",APPROVED"
        );
    }
}
