//! Process-wide multi-session registry for the Android host.
//!
//! The pinned companion facade (`0f704db`) does not ship
//! `session_registry::{SessionManager, SessionHandle}`. This module provides
//! the API surface already consumed by `terminal_model.rs` and the session
//! JNI bridge in `lib.rs`, owning one `TerminalModel` per tab session.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

use warp_terminal_mobile_facade::render::TerminalModel;

static GLOBAL: OnceLock<SessionManager> = OnceLock::new();

/// Errors returned by [`SessionManager`] mutating APIs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SessionError {
    AlreadyExists(String),
    NotFound(String),
    InvalidState(String),
    Json(String),
}

impl std::fmt::Display for SessionError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SessionError::AlreadyExists(id) => write!(f, "session already exists: {id}"),
            SessionError::NotFound(id) => write!(f, "session not found: {id}"),
            SessionError::InvalidState(msg) => write!(f, "invalid session state: {msg}"),
            SessionError::Json(msg) => write!(f, "session json error: {msg}"),
        }
    }
}

impl std::error::Error for SessionError {}

/// Handle to a single terminal tab session.
#[derive(Clone)]
pub struct SessionHandle {
    id: String,
    title: String,
    cwd: String,
    program: String,
    env: HashMap<String, String>,
    created_at_ms: u64,
    model: Arc<TerminalModel>,
    /// Best-effort DECSET/DECRST 1049/1047/47 tracker (facade has no public getter).
    alt_screen: Arc<AtomicBool>,
}

impl SessionHandle {
    pub fn id(&self) -> &str {
        &self.id
    }

    pub fn model(&self) -> &Arc<TerminalModel> {
        &self.model
    }

    pub fn is_alt_screen(&self) -> bool {
        self.alt_screen.load(Ordering::SeqCst)
    }

    /// Update alt-screen flag by scanning raw PTY bytes for DEC private modes.
    pub fn track_alt_screen_from_bytes(&self, bytes: &[u8]) {
        update_alt_screen_flag(&self.alt_screen, bytes);
    }
}

struct Inner {
    sessions: HashMap<String, SessionHandle>,
    active_id: Option<String>,
}

/// Process-wide (or test-local) multi-session manager.
pub struct SessionManager {
    inner: Mutex<Inner>,
}

impl SessionManager {
    pub fn global() -> &'static SessionManager {
        GLOBAL.get_or_init(SessionManager::new)
    }

    pub fn new() -> SessionManager {
        SessionManager {
            inner: Mutex::new(Inner {
                sessions: HashMap::new(),
                active_id: None,
            }),
        }
    }

    pub fn clear(&self) {
        let mut guard = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        guard.sessions.clear();
        guard.active_id = None;
    }

    pub fn create_session(
        &self,
        id: &str,
        title: Option<&str>,
        cwd: Option<&str>,
        env: HashMap<String, String>,
        rows: usize,
        cols: usize,
    ) -> Result<SessionHandle, SessionError> {
        let mut guard = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        if guard.sessions.contains_key(id) {
            if guard.active_id.is_none() {
                guard.active_id = Some(id.to_string());
            }
            return Ok(guard.sessions.get(id).expect("contains_key").clone());
        }

        let created_at_ms = now_ms();
        let handle = SessionHandle {
            id: id.to_string(),
            title: title.unwrap_or(id).to_string(),
            cwd: cwd.unwrap_or("~").to_string(),
            program: "/system/bin/sh".to_string(),
            env,
            created_at_ms,
            model: Arc::new(TerminalModel::new(rows, cols)),
            alt_screen: Arc::new(AtomicBool::new(false)),
        };

        if guard.active_id.is_none() {
            guard.active_id = Some(id.to_string());
        }
        guard.sessions.insert(id.to_string(), handle.clone());
        Ok(handle)
    }

    pub fn switch_session(&self, id: &str) -> Result<(), SessionError> {
        let mut guard = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        if !guard.sessions.contains_key(id) {
            return Err(SessionError::NotFound(id.to_string()));
        }
        guard.active_id = Some(id.to_string());
        Ok(())
    }

    pub fn close_session(&self, id: &str) -> Result<(), SessionError> {
        let mut guard = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        if guard.sessions.remove(id).is_none() {
            return Err(SessionError::NotFound(id.to_string()));
        }
        if guard.active_id.as_deref() == Some(id) {
            guard.active_id = guard.sessions.keys().next().cloned();
        }
        Ok(())
    }

    pub fn active_session(&self) -> Option<SessionHandle> {
        let guard = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        let id = guard.active_id.as_ref()?;
        guard.sessions.get(id).cloned()
    }

    pub fn active_session_id(&self) -> Option<String> {
        let guard = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        guard.active_id.clone()
    }

    pub fn ingest_pty_bytes_for_session(
        &self,
        cmd_id: &str,
        bytes: &[u8],
    ) -> Result<usize, SessionError> {
        let handle = {
            let guard = self.inner.lock().unwrap_or_else(|e| e.into_inner());
            guard
                .sessions
                .get(cmd_id)
                .cloned()
                .ok_or_else(|| SessionError::NotFound(cmd_id.to_string()))?
        };
        handle.track_alt_screen_from_bytes(bytes);
        Ok(handle.model().ingest_pty_bytes(bytes))
    }

    pub fn export_session_state_json(&self) -> Result<String, SessionError> {
        let guard = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        let mut sessions: Vec<ExportedSession> = guard
            .sessions
            .values()
            .map(|s| ExportedSession {
                id: s.id.clone(),
                title: s.title.clone(),
                cwd: s.cwd.clone(),
                program: s.program.clone(),
                created_at_ms: s.created_at_ms,
                env: s.env.clone(),
                alt_screen: s.is_alt_screen(),
                snapshot_text: s.model().snapshot_text(),
                scroll_offset: s.model().scroll_offset(),
                blocks_json: s.model().blocks_dump_json(),
            })
            .collect();
        // Stable order for reproducibility.
        sessions.sort_by(|a, b| a.id.cmp(&b.id));

        let state = ExportedState {
            version: 1,
            saved_at_ms: now_ms(),
            active_session_id: guard.active_id.clone(),
            sessions,
        };
        serde_json::to_string(&state).map_err(|e| SessionError::Json(e.to_string()))
    }

    pub fn restore_session_state_json(&self, json: &str) -> Result<(), SessionError> {
        let parsed: ExportedState =
            serde_json::from_str(json).map_err(|e| SessionError::Json(e.to_string()))?;

        if parsed.sessions.is_empty() {
            return Err(SessionError::InvalidState(
                "empty sessions in restore JSON".into(),
            ));
        }

        self.clear();

        for sess in &parsed.sessions {
            let handle = self.create_session(
                &sess.id,
                Some(&sess.title),
                Some(&sess.cwd),
                sess.env.clone(),
                warp_terminal_mobile_facade::DEFAULT_ROWS,
                warp_terminal_mobile_facade::DEFAULT_COLS,
            )?;
            // Restore best-effort alt-screen flag; scrollback/block rebuild is
            // out of scope for RC1 (Kotlin re-spawns PTYs after restore).
            handle
                .alt_screen
                .store(sess.alt_screen, Ordering::SeqCst);
            let mut guard = self.inner.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(stored) = guard.sessions.get_mut(&sess.id) {
                stored.program = sess.program.clone();
                stored.created_at_ms = sess.created_at_ms;
                stored.title = sess.title.clone();
                stored.cwd = sess.cwd.clone();
            }
        }

        if let Some(active) = parsed
            .active_session_id
            .as_deref()
            .filter(|id| parsed.sessions.iter().any(|s| s.id == *id))
        {
            self.switch_session(active)?;
        } else if let Some(last) = parsed.sessions.last() {
            self.switch_session(&last.id)?;
        }

        Ok(())
    }
}

impl Default for SessionManager {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug, Serialize, Deserialize)]
struct ExportedState {
    version: u32,
    saved_at_ms: u64,
    #[serde(default, alias = "activeSessionId")]
    active_session_id: Option<String>,
    #[serde(default, alias = "tabs")]
    sessions: Vec<ExportedSession>,
}

#[derive(Debug, Serialize, Deserialize)]
struct ExportedSession {
    id: String,
    #[serde(default)]
    title: String,
    #[serde(default = "default_cwd")]
    cwd: String,
    #[serde(default = "default_program")]
    program: String,
    #[serde(default, alias = "createdAtMs")]
    created_at_ms: u64,
    #[serde(default)]
    env: HashMap<String, String>,
    #[serde(default)]
    alt_screen: bool,
    #[serde(default)]
    snapshot_text: String,
    #[serde(default)]
    scroll_offset: usize,
    #[serde(default)]
    blocks_json: String,
}

fn default_cwd() -> String {
    "~".to_string()
}

fn default_program() -> String {
    "/system/bin/sh".to_string()
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// Scan for CSI private-mode DECSET/DECRST sequences affecting alt screen:
/// `ESC [ ? <params> h` / `ESC [ ? <params> l` where params include 1049, 1047, or 47.
fn update_alt_screen_flag(flag: &AtomicBool, bytes: &[u8]) {
    let mut i = 0;
    while i + 3 < bytes.len() {
        if bytes[i] == 0x1b && bytes[i + 1] == b'[' && bytes[i + 2] == b'?' {
            i += 3;
            let start = i;
            while i < bytes.len() && (bytes[i].is_ascii_digit() || bytes[i] == b';') {
                i += 1;
            }
            if i < bytes.len() && (bytes[i] == b'h' || bytes[i] == b'l') {
                let set = bytes[i] == b'h';
                if let Ok(params) = std::str::from_utf8(&bytes[start..i]) {
                    for p in params.split(';') {
                        if matches!(p, "1049" | "1047" | "47") {
                            flag.store(set, Ordering::SeqCst);
                        }
                    }
                }
                i += 1;
                continue;
            }
            continue;
        }
        i += 1;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use warp_terminal_mobile_facade::render::{DEFAULT_COLS, DEFAULT_ROWS};

    #[test]
    fn create_switch_close_and_ingest_routing() {
        let mgr = SessionManager::new();
        let s1 = mgr
            .create_session(
                "a",
                Some("A"),
                Some("/a"),
                HashMap::new(),
                DEFAULT_ROWS,
                DEFAULT_COLS,
            )
            .unwrap();
        let _s2 = mgr
            .create_session(
                "b",
                Some("B"),
                Some("/b"),
                HashMap::new(),
                DEFAULT_ROWS,
                DEFAULT_COLS,
            )
            .unwrap();
        assert_eq!(mgr.active_session_id(), Some("a".into()));

        mgr.ingest_pty_bytes_for_session("a", b"hello-a").unwrap();
        mgr.ingest_pty_bytes_for_session("b", b"hello-b").unwrap();
        assert!(s1.model().snapshot_text().contains("hello-a"));

        mgr.switch_session("b").unwrap();
        assert_eq!(mgr.active_session_id(), Some("b".into()));

        mgr.close_session("b").unwrap();
        assert_eq!(mgr.active_session_id(), Some("a".into()));
        assert!(mgr.ingest_pty_bytes_for_session("b", b"x").is_err());
    }

    #[test]
    fn alt_screen_tracks_decset_1049() {
        let mgr = SessionManager::new();
        let s = mgr
            .create_session("t", None, None, HashMap::new(), 2, 16)
            .unwrap();
        assert!(!s.is_alt_screen());
        mgr.ingest_pty_bytes_for_session("t", b"\x1b[?1049h")
            .unwrap();
        assert!(s.is_alt_screen());
        mgr.ingest_pty_bytes_for_session("t", b"\x1b[?1049l")
            .unwrap();
        assert!(!s.is_alt_screen());
    }

    #[test]
    fn export_restore_round_trip_preserves_ids() {
        let mgr = SessionManager::new();
        mgr.create_session("s1", Some("One"), Some("/1"), HashMap::new(), 2, 8)
            .unwrap();
        mgr.create_session("s2", Some("Two"), Some("/2"), HashMap::new(), 2, 8)
            .unwrap();
        mgr.switch_session("s2").unwrap();
        let json = mgr.export_session_state_json().unwrap();

        let mgr2 = SessionManager::new();
        mgr2.restore_session_state_json(&json).unwrap();
        assert_eq!(mgr2.active_session_id(), Some("s2".into()));
        assert!(mgr2.active_session().is_some());
        assert!(mgr2.switch_session("s1").is_ok());
    }
}
