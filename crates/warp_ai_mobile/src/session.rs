//! AgentSession and Message models for multi-turn conversation context retention.

use serde::{Deserialize, Serialize};

/// Role of a message in Anthropic API conversation context.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum MessageRole {
    User,
    Assistant,
}

impl MessageRole {
    pub fn as_str(&self) -> &'static str {
        match self {
            MessageRole::User => "user",
            MessageRole::Assistant => "assistant",
        }
    }
}

/// Structured message in a multi-turn conversation.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Message {
    pub role: String,
    pub content: String,
    pub timestamp: u64,
}

impl Message {
    pub fn user(content: impl Into<String>) -> Self {
        Self {
            role: "user".to_string(),
            content: content.into(),
            timestamp: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs(),
        }
    }

    pub fn assistant(content: impl Into<String>) -> Self {
        Self {
            role: "assistant".to_string(),
            content: content.into(),
            timestamp: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs(),
        }
    }
}

/// Turn lifecycle state machine states.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TurnState {
    Idle,
    Connecting,
    Streaming,
    Paused,
    Completed,
    Cancelled,
    Error,
}

impl TurnState {
    pub fn as_str(&self) -> &'static str {
        match self {
            TurnState::Idle => "IDLE",
            TurnState::Connecting => "CONNECTING",
            TurnState::Streaming => "STREAMING",
            TurnState::Paused => "PAUSED",
            TurnState::Completed => "COMPLETED",
            TurnState::Cancelled => "CANCELLED",
            TurnState::Error => "ERROR",
        }
    }
}

/// An active multi-turn agent conversation session.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AgentSession {
    pub id: String,
    pub model: String,
    pub system_prompt: String,
    pub history: Vec<Message>,
    pub state: TurnState,
    pub created_at: u64,
}

impl AgentSession {
    pub fn new(id: String, model: String, system_prompt: String) -> Self {
        let created_at = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();
        Self {
            id,
            model,
            system_prompt,
            history: Vec::new(),
            state: TurnState::Idle,
            created_at,
        }
    }

    /// Add a user turn prompt to the session history.
    pub fn add_user_message(&mut self, content: impl Into<String>) {
        self.history.push(Message::user(content));
    }

    /// Add or update the latest assistant turn response.
    pub fn add_assistant_message(&mut self, content: impl Into<String>) {
        self.history.push(Message::assistant(content));
    }

    /// Retries the last turn by popping the last assistant message if present,
    /// leaving the previous user prompt intact as the active turn.
    pub fn retry_last_turn(&mut self) -> bool {
        if let Some(last) = self.history.last() {
            if last.role == "assistant" {
                self.history.pop();
                return true;
            }
        }
        false
    }

    /// Truncates conversation history at `turn_index` and replaces the user prompt at `turn_index`.
    pub fn edit_prompt(&mut self, turn_index: usize, new_prompt: String) -> bool {
        if turn_index < self.history.len() {
            self.history.truncate(turn_index);
            self.add_user_message(new_prompt);
            true
        } else {
            false
        }
    }

    /// Converts conversation history into Anthropic API JSON `messages` array format.
    pub fn to_anthropic_messages(&self) -> Vec<serde_json::Value> {
        self.history
            .iter()
            .map(|msg| {
                serde_json::json!({
                    "role": msg.role,
                    "content": msg.content
                })
            })
            .collect()
    }

    /// Enforces a maximum history token / turn length window cap.
    pub fn truncate_history_if_needed(&mut self, max_messages: usize) {
        if self.history.len() > max_messages {
            let drain_count = self.history.len() - max_messages;
            self.history.drain(0..drain_count);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_agent_session_creation_and_history() {
        let mut session = AgentSession::new(
            "sess_1".to_string(),
            "claude-sonnet-4-6".to_string(),
            "You are a helpful CLI assistant.".to_string(),
        );

        assert_eq!(session.id, "sess_1");
        assert_eq!(session.state, TurnState::Idle);
        assert!(session.history.is_empty());

        session.add_user_message("How do I list files?");
        session.add_assistant_message("Use `ls -la`.");

        assert_eq!(session.history.len(), 2);
        assert_eq!(session.history[0].role, "user");
        assert_eq!(session.history[1].role, "assistant");

        let messages_json = session.to_anthropic_messages();
        assert_eq!(messages_json.len(), 2);
        assert_eq!(messages_json[0]["role"], "user");
        assert_eq!(messages_json[0]["content"], "How do I list files?");
    }

    #[test]
    fn test_retry_last_turn() {
        let mut session = AgentSession::new("s".into(), "m".into(), "sys".into());
        session.add_user_message("Prompt 1");
        session.add_assistant_message("Response 1 (incomplete)");

        assert_eq!(session.history.len(), 2);
        let retried = session.retry_last_turn();
        assert!(retried);
        assert_eq!(session.history.len(), 1);
        assert_eq!(session.history[0].role, "user");
    }

    #[test]
    fn test_edit_prompt_truncates_history() {
        let mut session = AgentSession::new("s".into(), "m".into(), "sys".into());
        session.add_user_message("Turn 0 user");
        session.add_assistant_message("Turn 0 assistant");
        session.add_user_message("Turn 1 user");
        session.add_assistant_message("Turn 1 assistant");

        assert_eq!(session.history.len(), 4);

        // Edit turn 2 (Turn 1 user)
        let edited = session.edit_prompt(2, "Turn 1 edited user prompt".into());
        assert!(edited);
        assert_eq!(session.history.len(), 3);
        assert_eq!(session.history[2].role, "user");
        assert_eq!(session.history[2].content, "Turn 1 edited user prompt");
    }

    #[test]
    fn test_truncate_history_window() {
        let mut session = AgentSession::new("s".into(), "m".into(), "sys".into());
        for i in 0..10 {
            session.add_user_message(format!("User {}", i));
            session.add_assistant_message(format!("Assistant {}", i));
        }

        assert_eq!(session.history.len(), 20);
        session.truncate_history_if_needed(6);
        assert_eq!(session.history.len(), 6);
        assert_eq!(session.history[0].content, "User 7");
    }

    // ── Adversarial Stress Tests (Challenger 1 Empirical Verification) ────────

    #[test]
    fn stress_retry_last_turn_edge_cases() {
        let mut session = AgentSession::new("s1".into(), "model".into(), "sys".into());

        // Case 1: Empty history
        assert!(!session.retry_last_turn());
        assert!(session.history.is_empty());

        // Case 2: History ends with user message
        session.add_user_message("User turn 1");
        assert!(!session.retry_last_turn());
        assert_eq!(session.history.len(), 1);

        // Case 3: History ends with assistant message
        session.add_assistant_message("Assistant turn 1");
        assert_eq!(session.history.len(), 2);
        assert!(session.retry_last_turn());
        assert_eq!(session.history.len(), 1);
        assert_eq!(session.history[0].role, "user");

        // Case 4: Repeated retry on user-ended history returns false
        assert!(!session.retry_last_turn());
        assert_eq!(session.history.len(), 1);
    }

    #[test]
    fn stress_edit_prompt_out_of_bounds_and_truncation() {
        let mut session = AgentSession::new("s2".into(), "model".into(), "sys".into());

        // Case 1: Edit on empty history -> out of bounds
        assert!(!session.edit_prompt(0, "New Prompt".into()));
        assert!(session.history.is_empty());

        // Add 3 turns (6 messages)
        for i in 0..3 {
            session.add_user_message(format!("User {}", i));
            session.add_assistant_message(format!("Assistant {}", i));
        }
        assert_eq!(session.history.len(), 6);

        // Case 2: Out of bounds turn_index (e.g. 100)
        assert!(!session.edit_prompt(100, "Out of bounds".into()));
        assert_eq!(session.history.len(), 6);

        // Case 3: Truncate at turn_index = 2 (which is User 1, 3rd message at index 2)
        assert!(session.edit_prompt(2, "Edited User 1".into()));
        assert_eq!(session.history.len(), 3);
        assert_eq!(session.history[0].content, "User 0");
        assert_eq!(session.history[1].content, "Assistant 0");
        assert_eq!(session.history[2].content, "Edited User 1");
        assert_eq!(session.history[2].role, "user");

        // Verify Anthropic JSON conversion maintains clean array shape
        let json_msgs = session.to_anthropic_messages();
        assert_eq!(json_msgs.len(), 3);
        assert_eq!(json_msgs[2]["content"], "Edited User 1");
        assert_eq!(json_msgs[2]["role"], "user");
    }
}

