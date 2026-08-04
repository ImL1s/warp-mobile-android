//! AgentSessionRegistry for thread-safe session storage and multi-turn state management.

use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};

use crate::session::{AgentSession, TurnState};

static GLOBAL_REGISTRY: OnceLock<AgentSessionRegistry> = OnceLock::new();

/// Global session registry accessor.
pub fn global_registry() -> &'static AgentSessionRegistry {
    GLOBAL_REGISTRY.get_or_init(AgentSessionRegistry::new)
}

/// Thread-safe registry maintaining active `AgentSession` instances.
#[derive(Default)]
pub struct AgentSessionRegistry {
    sessions: Arc<Mutex<HashMap<String, AgentSession>>>,
}

impl AgentSessionRegistry {
    pub fn new() -> Self {
        Self {
            sessions: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    /// Creates or updates a session with model and system prompt.
    pub fn create_or_get_session(&self, id: String, model: String, system_prompt: String) -> AgentSession {
        let mut guard = self.sessions.lock().unwrap_or_else(|e| e.into_inner());
        guard
            .entry(id.clone())
            .or_insert_with(|| AgentSession::new(id, model, system_prompt))
            .clone()
    }

    /// Gets a snapshot of an active session by ID.
    pub fn get_session(&self, id: &str) -> Option<AgentSession> {
        let guard = self.sessions.lock().unwrap_or_else(|e| e.into_inner());
        guard.get(id).cloned()
    }

    /// Mutates an active session with a closure.
    pub fn update_session<F, R>(&self, id: &str, f: F) -> Option<R>
    where
        F: FnOnce(&mut AgentSession) -> R,
    {
        let mut guard = self.sessions.lock().unwrap_or_else(|e| e.into_inner());
        guard.get_mut(id).map(f)
    }

    /// Sets session turn state.
    pub fn set_state(&self, id: &str, state: TurnState) {
        self.update_session(id, |sess| {
            sess.state = state;
        });
    }

    /// Removes a session from registry.
    pub fn remove_session(&self, id: &str) -> Option<AgentSession> {
        let mut guard = self.sessions.lock().unwrap_or_else(|e| e.into_inner());
        guard.remove(id)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_registry_create_and_update() {
        let reg = AgentSessionRegistry::new();
        let sess = reg.create_or_get_session("s1".into(), "model1".into(), "sys1".into());
        assert_eq!(sess.id, "s1");

        reg.set_state("s1", TurnState::Streaming);
        let updated = reg.get_session("s1").unwrap();
        assert_eq!(updated.state, TurnState::Streaming);
    }
}
