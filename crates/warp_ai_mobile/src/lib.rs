//! M6 — Warp AI Mobile (Anthropic Claude integration).
//!
//! Async streaming client for Claude APIs (Haiku ghost-text, Sonnet
//! agent). The synchronous Java client at android/.../AnthropicClient.kt
//! shipped in M6-S02 for one-shot Test Connection — this Rust crate
//! adds the streaming layer needed for ghost-text (cancel-on-keystroke)
//! + agent (progressive output rendering).
//!
//! Module map (planned, populated across M6-S03..M6-S05):
//!   - `client`        : POST /v1/messages with stream=true (SSE)
//!   - `ghost`         : debounced ghost-text completion + cancel token
//!   - `agent`         : multi-step agent task with system prompt
//!   - `connectivity`  : online/offline state for offline graceful degrade
//!
//! Round-1 (this commit) ships the foundation: Cargo deps locked,
//! lib.rs module declarations, a stub `client::AnthropicClient`
//! struct + minimal smoke test that constructs one without making
//! network calls. Real network logic lands in M6-S03 round-2 with
//! device-side latency benchmarks.
//!
//! Why a separate crate (not inside crates/android-host):
//!   - Async/Tokio is heavy (~1 MB to .so) — keep it isolated from the
//!     synchronous PTY/render path so future architecture refactors
//!     can drop it cleanly if AI features become opt-in compiled-out.
//!   - Independent testing: `cargo test -p warp_ai_mobile` exercises
//!     the Anthropic API client without spinning up the full
//!     android-host JNI surface.
//!   - Clean cross-workspace edge: warp_ai_mobile depends on no warp
//!     upstream crates, so cherry-pick velocity for warp_terminal /
//!     warpui upstream stays unaffected.

pub mod approval;
pub mod audit;
pub mod client;
pub mod profile;
pub mod provider;
pub mod session;
pub mod session_registry;



#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn client_constructs_without_network() {
        // Smoke test: just instantiate the client struct. No HTTP
        // call. Validates the Cargo.toml dep tree compiles + the
        // builder pattern works.
        let _ = client::AnthropicClient::new("sk-ant-test".to_string());
    }
}
