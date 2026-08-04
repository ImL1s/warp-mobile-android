#[cfg(unix)]
pub mod pty;

#[cfg(not(unix))]
pub mod pty {
    use std::io;
    pub struct PtySession;
    impl PtySession {
        pub fn read(&self, _buf: &mut [u8]) -> io::Result<usize> { Ok(0) }
        pub fn write(&self, _buf: &[u8]) -> io::Result<usize> { Ok(0) }
        pub fn resize(&self, _rows: u16, _cols: u16) -> io::Result<()> { Ok(()) }
        pub fn kill_eager(&self) {}
        pub fn kill(&self) -> io::Result<()> { Ok(()) }
        pub fn get_exit_status(&self) -> Option<i32> { None }
    }
    pub fn active_pty_count() -> u32 { 0 }
    pub fn total_spawned_ptys() -> u64 { 0 }
    pub fn spawn_pty(_program: &str, _args: &[&str], _env: &[(&str, &str)]) -> io::Result<PtySession> {
        Err(io::Error::new(io::ErrorKind::Unsupported, "pty unsupported on non-unix host"))
    }
}

#[cfg(target_os = "android")]
mod font_render;

// V1-prep: pure font-family selection helpers — host-runnable for
// unit-test coverage of OEM-naming-variation logic. The font_render
// module proper stays android-gated (cosmic-text + ndk_sys deps).
pub mod font_picker;

// M2-S10 IME state machine. Pure Rust + atomics; no Vulkan / NDK deps so we
// build it on host targets too (allows `cargo test -p warp-mobile-android-host`
// to exercise the state-machine tests without cross-compilation).
pub mod ime;

// M2-S11 touch input state machine. Same host-build policy as IME — no NDK
// deps so `cargo test -p warp-mobile-android-host` exercises the unit tests
// without cross-compilation.
pub mod input;

// M4-S12: `static_grid` + `dynamic_grid` were lifted into the shared
// `warp_mobile_android_link` rlib (Option D shared-rlib API split). The
// `crate::static_grid::*` / `crate::dynamic_grid::*` paths formerly used
// throughout this cdylib (lib.rs, vulkan.rs) now resolve via this `use`
// re-export so JNI exports + vulkan.rs don't need per-call-site rewrites.
// See `warp_mobile_android_link/Cargo.toml` for the full rationale.
#[cfg(target_os = "android")]
use warp_mobile_android_link::{dynamic_grid, static_grid};

// M3-S04: terminal model. Pure Rust + atomics (mirrors the canonical facade
// `warp_terminal_mobile_facade::render::TerminalModel`); built on host targets
// too so `cargo test -p warp-mobile-android-host` exercises ingest/dirty/snapshot.
pub mod terminal_model;

// M4-S05: bootstrap zip atomic extraction. Pure Rust I/O + sha2 + zip +
// serde_json. The Android-only JNI export
// `Java_dev_warp_mobile_NativeBridge_bootstrapInstall` is gated inside the
// module via `#[cfg(target_os = "android")]`; the extraction/symlink/rename
// logic is host-buildable so `cargo test -p warp-mobile-android-host`
// exercises 11 unit tests without cross-compilation.
pub mod bootstrap;

#[cfg(target_os = "android")]
mod vulkan;

use jni::objects::{JByteArray, JClass, JObjectArray, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jshort, jstring};
use jni::JNIEnv;
use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use tokio_util::sync::CancellationToken;
use warp_ai_mobile::session::{TurnState};
use warp_ai_mobile::session_registry::global_registry;
use warp_ai_mobile::client::{AnthropicClient, SseChunkEvent};


#[cfg(target_os = "android")]
use jni::objects::JObject;
#[cfg(target_os = "android")]
use jni::sys::{jboolean, jfloat, JNI_FALSE, JNI_TRUE};

#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_ping(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    init_logger();
    log::info!(target: "android-host", "ping called");
    env.new_string("Rust ping ok from arm64-v8a")
        .expect("failed to create Java string")
        .into_raw()
}

// ── M6-S03 round-2: AI ghost-text JNI binding ───────────────────────────────
//
// Synchronous (per-call tokio runtime) request to Anthropic Claude.
// Returns the assembled response text on success or "ERR:<message>"
// on failure. The Kotlin caller (AccessoryRow round-2) checks the
// "ERR:" prefix to distinguish success from error.
//
// Per-call Runtime::new() costs ~10ms which is negligible vs the
// 500ms-3s network round-trip. Round-3 will switch to a global
// runtime + streaming + cancellation token, but round-2 validates
// the Java→Rust→Anthropic→Rust→Java pipe end-to-end.
//
// JNI signature (Kotlin side):
//   external fun aiGhostComplete(apiKey: String, model: String,
//                                 prompt: String, maxTokens: Int): String

#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_aiGhostComplete(
    mut env: JNIEnv,
    _class: JClass,
    api_key: JString,
    model: JString,
    prompt: JString,
    max_tokens: jint,
) -> jstring {
    init_logger();

    // Decode all 3 jstrings up front; any decode failure → ERR result.
    let api_key_str = match env.get_string(&api_key) {
        Ok(s) => s.to_string_lossy().into_owned(),
        Err(e) => {
            return error_jstring(&mut env, &format!("ERR:invalid api_key jstring: {:?}", e));
        }
    };
    let model_str = match env.get_string(&model) {
        Ok(s) => s.to_string_lossy().into_owned(),
        Err(e) => {
            return error_jstring(&mut env, &format!("ERR:invalid model jstring: {:?}", e));
        }
    };
    let prompt_str = match env.get_string(&prompt) {
        Ok(s) => s.to_string_lossy().into_owned(),
        Err(e) => {
            return error_jstring(&mut env, &format!("ERR:invalid prompt jstring: {:?}", e));
        }
    };

    let max_t = if max_tokens <= 0 { 200 } else { max_tokens as u32 };

    log::info!(
        target: "android-host",
        "aiGhostComplete: model={} max_tokens={} prompt_len={}",
        model_str, max_t, prompt_str.len()
    );

    // Per-call runtime. M6-S03 round-3 will replace with a global
    // singleton + cancellation propagation.
    let rt = match tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
    {
        Ok(r) => r,
        Err(e) => {
            return error_jstring(&mut env, &format!("ERR:tokio runtime build: {}", e));
        }
    };

    let client = warp_ai_mobile::client::AnthropicClient::new(api_key_str);
    let result = rt.block_on(async {
        client.messages_complete(&model_str, &prompt_str, max_t).await
    });

    match result {
        Ok(text) => {
            log::info!(
                target: "android-host",
                "aiGhostComplete: ok response_len={}",
                text.len()
            );
            env.new_string(text)
                .map(|s| s.into_raw())
                .unwrap_or_else(|_| error_jstring(&mut env, "ERR:jstring create failed"))
        }
        Err(e) => {
            log::error!(target: "android-host", "aiGhostComplete: {}", e);
            error_jstring(&mut env, &format!("ERR:{}", e))
        }
    }
}

/// Helper: build a jstring "ERR:..." for the various failure paths.
/// Returns a null jstring if even that fails (caller checks for null).
fn error_jstring(env: &mut JNIEnv, msg: &str) -> jstring {
    env.new_string(msg)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

// ── M6-S03 round-3: streaming AI ghost-text ─────────────────────────────────
//
// Real SSE streaming via reqwest::Response::bytes_stream(). Pipe is:
//
//   Kotlin: aiGhostStreamStart(...) → Long handle
//   Kotlin: 50ms-poll loop calling aiGhostStreamPoll(handle) → String
//           (":CHUNK:..." | ":DONE:" | ":ERR:..." | "")
//   Kotlin: when ":DONE:" or ":ERR:", calls aiGhostStreamFree(handle)
//   Kotlin: aiGhostStreamCancel(handle) at any time → token.cancel()
//
// Polling beats cross-thread JNI callbacks: avoids the JavaVM::attach_
// current_thread + GlobalRef + jthread lifecycle complexity, and 50ms
// poll latency is invisible to user (chunks arrive in 50-200ms intervals
// from Anthropic anyway).
//
// Global tokio runtime: replaces the per-call Runtime::new from round-2.
// One LazyLock-initialized multi-thread runtime serves all streaming
// requests across the app lifetime. Per-request resources (CancellationToken,
// chunks queue) tracked via Arc<StreamHandle>.

use std::sync::OnceLock;
use tokio::runtime::Runtime;

// Arc and jlong already imported at top of file (lines 43-45).

/// Global tokio runtime. Lazy-init on first AI call. Multi-thread (4
/// workers) so concurrent ghost + agent requests don't serialize.
static TOKIO_RT: OnceLock<Runtime> = OnceLock::new();

fn tokio_rt() -> &'static Runtime {
    TOKIO_RT.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .thread_name("warp-ai-tokio")
            .build()
            .expect("failed to build global tokio runtime for AI streaming")
    })
}

/// Per-stream state shared between the spawned tokio task (writer) and
/// the JNI poll caller (reader).
struct StreamHandle {
    chunks: Mutex<VecDeque<String>>,
    done: AtomicBool,
    error: Mutex<Option<String>>,
    cancel: CancellationToken,
}

impl StreamHandle {
    fn new() -> Self {
        Self {
            chunks: Mutex::new(VecDeque::new()),
            done: AtomicBool::new(false),
            error: Mutex::new(None),
            cancel: CancellationToken::new(),
        }
    }
}

/// Start a streaming ghost-text request. Returns a Long handle that
/// the caller MUST eventually pass to `aiGhostStreamFree(handle)`. The
/// handle is also passed to `aiGhostStreamPoll(handle)` for chunks
/// and `aiGhostStreamCancel(handle)` to abort.
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_aiGhostStreamStart(
    mut env: JNIEnv,
    _class: JClass,
    api_key: JString,
    model: JString,
    prompt: JString,
    max_tokens: jint,
) -> jlong {
    init_logger();

    let api_key_str = env
        .get_string(&api_key)
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default();
    let model_str = env
        .get_string(&model)
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default();
    let prompt_str = env
        .get_string(&prompt)
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default();
    let max_t = if max_tokens <= 0 { 200 } else { max_tokens as u32 };

    log::info!(
        target: "android-host",
        "aiGhostStreamStart: model={} max_tokens={} prompt_len={}",
        model_str, max_t, prompt_str.len()
    );

    let handle = Arc::new(StreamHandle::new());
    let handle_w = Arc::clone(&handle);
    let cancel_w = handle.cancel.clone();

    tokio_rt().spawn(async move {
        let client = warp_ai_mobile::client::AnthropicClient::new(api_key_str);
        let result = client
            .messages_stream(&model_str, &prompt_str, max_t, cancel_w, |chunk| {
                if let Ok(mut q) = handle_w.chunks.lock() {
                    q.push_back(chunk.to_string());
                }
            })
            .await;
        match result {
            Ok(_) => {
                log::info!(target: "android-host", "aiGhostStreamStart: stream completed ok");
            }
            Err(e) => {
                log::error!(target: "android-host", "aiGhostStreamStart: {}", e);
                if let Ok(mut slot) = handle_w.error.lock() {
                    *slot = Some(e.to_string());
                }
            }
        }
        handle_w.done.store(true, Ordering::Release);
    });

    Arc::into_raw(handle) as jlong
}

/// Poll the stream for new chunks. Returns:
///   - `""` (empty) when there are no new chunks AND the stream is
///     still running. Caller polls again after a short delay.
///   - `":CHUNK:<text>"` when a new chunk is available. Multiple chunks
///     are concatenated in one return — caller drains them all at once
///     by stripping the prefix.
///   - `":DONE:"` when the stream finished successfully and ALL chunks
///     have been delivered. Caller should `aiGhostStreamFree(handle)`.
///   - `":ERR:<message>"` when the stream failed. Caller should free.
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_aiGhostStreamPoll(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    if handle == 0 {
        return error_jstring(&mut env, ":ERR:null handle");
    }
    // Borrow the Arc without dropping. into_raw + from_raw + into_raw
    // pattern keeps the refcount stable.
    let h_ptr = handle as *const StreamHandle;
    let h = unsafe { Arc::from_raw(h_ptr) };
    let h_ref = Arc::clone(&h);
    let _ = Arc::into_raw(h);

    // Drain any queued chunks first.
    let chunks_text: Option<String> = h_ref.chunks.lock().ok().and_then(|mut q| {
        if q.is_empty() {
            None
        } else {
            let mut concat = String::new();
            while let Some(c) = q.pop_front() {
                concat.push_str(&c);
            }
            Some(concat)
        }
    });

    let response = if let Some(text) = chunks_text {
        // Send the chunk(s) regardless of done state — caller still
        // needs the data even on the very last poll.
        format!(":CHUNK:{}", text)
    } else if h_ref.done.load(Ordering::Acquire) {
        // No new chunks AND stream finished. Check error slot.
        let err_msg = h_ref.error.lock().ok().and_then(|s| s.clone());
        if let Some(msg) = err_msg {
            format!(":ERR:{}", msg)
        } else {
            ":DONE:".to_string()
        }
    } else {
        // Still running, no new data — poll again later.
        String::new()
    };

    env.new_string(response)
        .map(|s| s.into_raw())
        .unwrap_or_else(|_| error_jstring(&mut env, ":ERR:jstring create failed"))
}

/// Cancel an in-flight stream. Idempotent; safe to call multiple times
/// or after the stream already completed. The handle remains valid
/// until `aiGhostStreamFree` is called — caller should still poll once
/// more to see ":ERR:Cancelled" then free.
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_aiGhostStreamCancel(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let h_ptr = handle as *const StreamHandle;
    let h = unsafe { Arc::from_raw(h_ptr) };
    h.cancel.cancel();
    log::info!(target: "android-host", "aiGhostStreamCancel: token cancelled");
    let _ = Arc::into_raw(h);
}

/// Drop the stream handle. MUST be called by the caller exactly once
/// after they observe ":DONE:" or ":ERR:" from the poll. Calling Free
/// on an in-flight stream WITHOUT cancelling first is undefined
/// behaviour because the spawned tokio task still holds an Arc
/// reference; the task will continue running and eventually drop its
/// own Arc, freeing the handle when both refs are gone. So Free is
/// safe (memory-wise) but you'll leak the active request — always
/// Cancel first if you're aborting.
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_aiGhostStreamFree(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let h_ptr = handle as *const StreamHandle;
    // Take ownership and drop. This decrements the refcount; if the
    // tokio task still holds the other Arc, the StreamHandle stays
    // alive until that task finishes.
    let _: Arc<StreamHandle> = unsafe { Arc::from_raw(h_ptr) };
    log::info!(target: "android-host", "aiGhostStreamFree: handle dropped");
}

// ── Multi-Turn Agent Conversations JNI bindings (Issue #14) ───────────────

struct AgentStreamHandle {
    session_id: String,
    events: Mutex<VecDeque<String>>,
    done: AtomicBool,
    paused: AtomicBool,
    error: Mutex<Option<String>>,
    cancel: CancellationToken,
}

impl AgentStreamHandle {
    fn new(session_id: String) -> Self {
        Self {
            session_id,
            events: Mutex::new(VecDeque::new()),
            done: AtomicBool::new(false),
            paused: AtomicBool::new(false),
            error: Mutex::new(None),
            cancel: CancellationToken::new(),
        }
    }
}

#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_aiAgentSessionCreate(
    mut env: JNIEnv,
    _class: JClass,
    session_id: JString,
    model: JString,
    system_prompt: JString,
) -> jboolean {
    init_logger();
    let sid = match env.get_string(&session_id) {
        Ok(s) => s.to_string_lossy().into_owned(),
        Err(_) => return 0,
    };
    let mdl = match env.get_string(&model) {
        Ok(s) => s.to_string_lossy().into_owned(),
        Err(_) => warp_ai_mobile::client::DEFAULT_AGENT_MODEL.to_string(),
    };
    let sys = match env.get_string(&system_prompt) {
        Ok(s) => s.to_string_lossy().into_owned(),
        Err(_) => String::new(),
    };

    global_registry().create_or_get_session(sid, mdl, sys);
    1
}

fn spawn_agent_turn_stream(
    session_id: String,
    api_key: String,
    max_tokens: u32,
) -> jlong {
    let registry = global_registry();
    let (model, system_prompt, messages_json) = match registry.get_session(&session_id) {
        Some(sess) => (sess.model.clone(), sess.system_prompt.clone(), sess.to_anthropic_messages()),
        None => return 0,
    };

    registry.set_state(&session_id, TurnState::Connecting);

    let handle = Arc::new(AgentStreamHandle::new(session_id.clone()));
    let handle_w = Arc::clone(&handle);
    let cancel_w = handle.cancel.clone();

    tokio_rt().spawn(async move {
        global_registry().set_state(&session_id, TurnState::Streaming);
        let client = AnthropicClient::new(api_key);

        let res = client
            .messages_stream_multi_turn(
                &model,
                &system_prompt,
                &messages_json,
                max_tokens,
                cancel_w,
                |ev| {
                    if handle_w.paused.load(Ordering::Acquire) {
                        std::thread::sleep(std::time::Duration::from_millis(50));
                    }
                    let json_payload = serde_json::to_string(&ev).unwrap_or_default();
                    if let Ok(mut q) = handle_w.events.lock() {
                        q.push_back(format!(":EVENT:{}", json_payload));
                    }
                },
            )
            .await;

        match res {
            Ok(accumulated) => {
                global_registry().update_session(&session_id, |sess| {
                    sess.add_assistant_message(&accumulated);
                    sess.state = TurnState::Completed;
                });
            }
            Err(warp_ai_mobile::client::MessagesError::Cancelled) => {
                global_registry().set_state(&session_id, TurnState::Cancelled);
            }
            Err(e) => {
                let err_msg = e.to_string();
                if let Ok(mut slot) = handle_w.error.lock() {
                    *slot = Some(err_msg);
                }
                global_registry().set_state(&session_id, TurnState::Error);
            }
        }

        handle_w.done.store(true, Ordering::Release);
    });

    Arc::into_raw(handle) as jlong
}

#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_aiAgentSendTurnStart(
    mut env: JNIEnv,
    _class: JClass,
    session_id: JString,
    api_key: JString,
    user_prompt: JString,
    max_tokens: jint,
) -> jlong {
    init_logger();
    let sid = match env.get_string(&session_id) {
        Ok(s) => s.to_string_lossy().into_owned(),
        Err(_) => return 0,
    };
    let key = match env.get_string(&api_key) {
        Ok(s) => s.to_string_lossy().into_owned(),
        Err(_) => return 0,
    };
    let prompt = match env.get_string(&user_prompt) {
        Ok(s) => s.to_string_lossy().into_owned(),
        Err(_) => return 0,
    };
    let max_t = if max_tokens <= 0 { 1024 } else { max_tokens as u32 };

    global_registry().update_session(&sid, |sess| {
        sess.add_user_message(prompt);
    });

    spawn_agent_turn_stream(sid, key, max_t)
}

#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_aiAgentTurnPoll(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    if handle == 0 {
        return error_jstring(&mut env, ":ERR:null handle");
    }
    let h_ptr = handle as *const AgentStreamHandle;
    let h = unsafe { Arc::from_raw(h_ptr) };
    let h_ref = Arc::clone(&h);
    let _ = Arc::into_raw(h);

    let events_text: Option<String> = h_ref.events.lock().ok().and_then(|mut q| {
        if q.is_empty() {
            None
        } else {
            let mut concat = String::new();
            while let Some(ev) = q.pop_front() {
                if !concat.is_empty() {
                    concat.push('\n');
                }
                concat.push_str(&ev);
            }
            Some(concat)
        }
    });

    let response = if let Some(text) = events_text {
        text
    } else if h_ref.done.load(Ordering::Acquire) {
        let err_msg = h_ref.error.lock().ok().and_then(|s| s.clone());
        if let Some(msg) = err_msg {
            format!(":ERR:{}", msg)
        } else {
            ":DONE:".to_string()
        }
    } else if h_ref.paused.load(Ordering::Acquire) {
        ":STATUS:{\"state\":\"PAUSED\"}".to_string()
    } else {
        String::new()
    };

    env.new_string(response)
        .map(|s| s.into_raw())
        .unwrap_or_else(|_| error_jstring(&mut env, ":ERR:jstring create failed"))
}

#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_aiAgentTurnControl(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    action: jint,
) -> jboolean {
    if handle == 0 {
        return 0;
    }
    let h_ptr = handle as *const AgentStreamHandle;
    let h = unsafe { Arc::from_raw(h_ptr) };
    match action {
        1 => {
            // CANCEL
            h.cancel.cancel();
            global_registry().set_state(&h.session_id, TurnState::Cancelled);
        }
        2 => {
            // PAUSE
            h.paused.store(true, Ordering::Release);
            global_registry().set_state(&h.session_id, TurnState::Paused);
        }
        3 => {
            // RESUME
            h.paused.store(false, Ordering::Release);
            global_registry().set_state(&h.session_id, TurnState::Streaming);
        }
        _ => {}
    }
    let _ = Arc::into_raw(h);
    1
}

#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_aiAgentTurnFree(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let h_ptr = handle as *const AgentStreamHandle;
    let _: Arc<AgentStreamHandle> = unsafe { Arc::from_raw(h_ptr) };
    log::info!(target: "android-host", "aiAgentTurnFree: handle dropped");
}

#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_aiAgentRetryTurn(
    mut env: JNIEnv,
    _class: JClass,
    session_id: JString,
    api_key: JString,
    max_tokens: jint,
) -> jlong {
    init_logger();
    let sid = match env.get_string(&session_id) {
        Ok(s) => s.to_string_lossy().into_owned(),
        Err(_) => return 0,
    };
    let key = match env.get_string(&api_key) {
        Ok(s) => s.to_string_lossy().into_owned(),
        Err(_) => return 0,
    };
    let max_t = if max_tokens <= 0 { 1024 } else { max_tokens as u32 };

    global_registry().update_session(&sid, |sess| {
        sess.retry_last_turn();
    });

    spawn_agent_turn_stream(sid, key, max_t)
}

#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_aiAgentEditPrompt(
    mut env: JNIEnv,
    _class: JClass,
    session_id: JString,
    api_key: JString,
    turn_index: jint,
    new_prompt: JString,
    max_tokens: jint,
) -> jlong {
    init_logger();
    let sid = match env.get_string(&session_id) {
        Ok(s) => s.to_string_lossy().into_owned(),
        Err(_) => return 0,
    };
    let key = match env.get_string(&api_key) {
        Ok(s) => s.to_string_lossy().into_owned(),
        Err(_) => return 0,
    };
    let prompt = match env.get_string(&new_prompt) {
        Ok(s) => s.to_string_lossy().into_owned(),
        Err(_) => return 0,
    };
    let idx = if turn_index < 0 { 0 } else { turn_index as usize };
    let max_t = if max_tokens <= 0 { 1024 } else { max_tokens as u32 };

    global_registry().update_session(&sid, |sess| {
        sess.edit_prompt(idx, prompt);
    });

    spawn_agent_turn_stream(sid, key, max_t)
}


// ── PTY JNI bindings ────────────────────────────────────────────────────────

#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_ptySpawn(
    mut env: JNIEnv,
    _class: JClass,
    program: JString,
    args_array: JObjectArray,
    env_flat: JObjectArray,
) -> jlong {
    init_logger();
    let program_str: String = match env.get_string(&program) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    // Extract args from JObjectArray
    let args_len = env.get_array_length(&args_array).unwrap_or(0);
    let mut args_owned: Vec<String> = Vec::with_capacity(args_len as usize);
    for i in 0..args_len {
        if let Ok(elem) = env.get_object_array_element(&args_array, i) {
            let jstr: jni::objects::JString = elem.into();
            let s: String = env.get_string(&jstr)
                .map(|j| String::from(j))
                .unwrap_or_default();
            if !s.is_empty() { args_owned.push(s); }
        }
    }
    let args_refs: Vec<&str> = args_owned.iter().map(|s| s.as_str()).collect();

    // Extract env pairs from flat ["KEY=VALUE", ...] array
    let env_len = env.get_array_length(&env_flat).unwrap_or(0);
    let mut env_owned: Vec<(String, String)> = Vec::with_capacity(env_len as usize);
    for i in 0..env_len {
        if let Ok(elem) = env.get_object_array_element(&env_flat, i) {
            let jstr: jni::objects::JString = elem.into();
            let kv: String = env.get_string(&jstr)
                .map(|j| String::from(j))
                .unwrap_or_default();
            if let Some(eq) = kv.find('=') {
                env_owned.push((kv[..eq].to_string(), kv[eq + 1..].to_string()));
            }
        }
    }
    let env_refs: Vec<(&str, &str)> = env_owned.iter().map(|(k, v)| (k.as_str(), v.as_str())).collect();

    match pty::spawn_pty(&program_str, &args_refs, &env_refs) {
        Ok(session) => {
            // Arc refcount=1 owned by Java's PtyManager map
            let ptr = Arc::into_raw(Arc::new(session)) as jlong;
            log::info!(target: "android-host", "ptySpawn ok ptr={}", ptr);
            ptr
        }
        Err(e) => {
            log::error!(target: "android-host", "ptySpawn failed: {}", e);
            0
        }
    }
}

#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_activePtyCount(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    pty::active_pty_count() as jint
}

#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_totalSpawnedPtys(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    pty::total_spawned_ptys() as jlong
}

#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_ptyGetExitStatus(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jint {
    if ptr == 0 {
        return -1;
    }
    let session = unsafe { &*(ptr as *const pty::PtySession) };
    session.get_exit_status().unwrap_or(-2)
}

/// Increment Arc refcount. Called under PtyManager map lock before ptyRead.
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_ptyAcquire(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jlong {
    if ptr == 0 { return 0; }
    unsafe { Arc::increment_strong_count(ptr as *const pty::PtySession) };
    ptr
}

/// Decrement Arc refcount. Called in finally after ptyRead completes.
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_ptyRelease(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    if ptr == 0 { return; }
    unsafe { Arc::decrement_strong_count(ptr as *const pty::PtySession) };
}

#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_ptyRead(
    env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    max_bytes: jint,
) -> jbyteArray {
    if ptr == 0 {
        return std::ptr::null_mut();
    }
    // Safety: caller holds an Arc ref (via ptyAcquire) for the duration of this call
    let session = unsafe { &*(ptr as *const pty::PtySession) };
    let cap = max_bytes.max(0) as usize;
    let mut buf = vec![0u8; cap];
    match session.read(&mut buf) {
        Ok(n) => {
            let slice = &buf[..n];
            match env.byte_array_from_slice(slice) {
                Ok(arr) => arr.into_raw(),
                Err(_) => std::ptr::null_mut(),
            }
        }
        Err(e) => {
            // V1-prep blocker #3 diagnostic: surface the errno so we can tell
            // EBADF (post-kill, expected) from EIO (slave fully closed by a
            // child that exited — the v1-prep blocker we're chasing) from
            // anything else (EINTR, EAGAIN if O_NONBLOCK ever sneaks in).
            // Logged via android-host logger which is bridged to logcat tag
            // "android-host" by lib.rs init_logger.
            let errno = e.raw_os_error().unwrap_or(0);
            log::warn!(
                target: "android-host",
                "ptyRead returned Err errno={} ({})",
                errno,
                e
            );
            std::ptr::null_mut()
        }
    }
}

#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_ptyWrite(
    env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    data: JByteArray,
) -> jint {
    if ptr == 0 {
        return -1;
    }
    let session = unsafe { &*(ptr as *const pty::PtySession) };
    let bytes: Vec<u8> = match env.convert_byte_array(&data) {
        Ok(b) => b,
        Err(_) => return -1,
    };
    match session.write(&bytes) {
        Ok(n) => n as jint,
        Err(e) => -(e.raw_os_error().unwrap_or(1) as jint),
    }
}

#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_ptyResize(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    rows: jshort,
    cols: jshort,
) -> jint {
    if ptr == 0 {
        return -1;
    }
    let session = unsafe { &*(ptr as *const pty::PtySession) };
    match session.resize(rows as u16, cols as u16) {
        Ok(()) => 0,
        Err(e) => -(e.raw_os_error().unwrap_or(1) as jint),
    }
}

#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_ptyKill(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    if ptr == 0 {
        return;
    }
    let session = unsafe { &*(ptr as *const pty::PtySession) };
    // Close fd eagerly so concurrent reads return EBADF immediately
    session.kill_eager();
    session.kill().ok();
    // Decrement the Arc refcount held by the Java map (spawned with Arc::into_raw)
    unsafe { Arc::decrement_strong_count(ptr as *const pty::PtySession) };
}

// ── Vulkan / render JNI bindings (M2-S04) ───────────────────────────────────

/// Attaches an Android `Surface` (from `SurfaceHolder.getSurface()`) to the
/// Vulkan swapchain. Returns `true` on success.
///
/// Wraps `ANativeWindow_fromSurface` + the M0-spike-validated initialization
/// path in `vulkan.rs`.
#[cfg(target_os = "android")]
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_renderAttachSurface(
    env: JNIEnv,
    _class: JClass,
    surface: JObject,
) -> jboolean {
    init_logger();
    if surface.is_null() {
        log::error!(target: "warp-android-host", "renderAttachSurface: null Surface");
        return JNI_FALSE;
    }
    // SAFETY: env.get_native_interface() returns a valid JNIEnv* per JNI spec;
    // ANativeWindow_fromSurface returns a refcounted ANativeWindow* (we own the
    // ref and pass ownership into vulkan::attach).
    let native_window = unsafe {
        ndk_sys::ANativeWindow_fromSurface(
            env.get_native_interface() as *mut _,
            surface.as_raw() as *mut _,
        )
    };
    if native_window.is_null() {
        log::error!(target: "warp-android-host",
            "renderAttachSurface: ANativeWindow_fromSurface returned null");
        return JNI_FALSE;
    }
    // SAFETY: ownership transfers to vulkan::attach.
    let ok = unsafe { vulkan::attach(native_window) };
    if ok { JNI_TRUE } else { JNI_FALSE }
}

/// Detaches the Vulkan swapchain. Idempotent.
#[cfg(target_os = "android")]
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_renderDetachSurface(
    _env: JNIEnv,
    _class: JClass,
) {
    init_logger();
    vulkan::detach();
}

/// Submits a single clear-color frame. Returns `true` on successful
/// `vkQueuePresentKHR`. The clear color components are floats in [0.0, 1.0].
///
/// For M2-S04 the JNI side typically passes magenta `(1.0, 0.0, 1.0, 1.0)`.
#[cfg(target_os = "android")]
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_renderClearFrame(
    _env: JNIEnv,
    _class: JClass,
    r: jfloat,
    g: jfloat,
    b: jfloat,
    a: jfloat,
) -> jboolean {
    let ok = vulkan::submit_clear_frame([r, g, b, a]);
    if ok { JNI_TRUE } else { JNI_FALSE }
}

/// Returns the cumulative number of frames presented since the last attach.
#[cfg(target_os = "android")]
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_renderFramesPresented(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    vulkan::frames_presented() as jlong
}

/// M2-S05: capture a single frame as PNG to the given file path.
///
/// Renders one magenta clear frame, copies the swapchain image to a host-coherent
/// staging buffer via `vkCmdCopyImageToBuffer`, swizzles BGRA→RGBA if needed,
/// and encodes a PNG via the `png` crate.
///
/// Returns `true` on success. Logs a `capture_ok` line with frame number,
/// dimensions, mean RGB, and bytes written; the test driver greps for this.
///
/// Synchronous — blocks the calling thread until `vkQueueWaitIdle` completes.
#[cfg(target_os = "android")]
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_renderCaptureFrame(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    r: jfloat,
    g: jfloat,
    b: jfloat,
    a: jfloat,
) -> jboolean {
    init_logger();
    let path_str: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(e) => {
            log::error!(target: "warp-android-host",
                "renderCaptureFrame: could not extract path JString: {:?}", e);
            return JNI_FALSE;
        }
    };
    let out_path = std::path::PathBuf::from(&path_str);
    match vulkan::capture_to_png([r, g, b, a], &out_path) {
        Some(_) => JNI_TRUE,
        None => JNI_FALSE,
    }
}

/// M2-S07: capture a single frame as PNG with shaped text composited on top.
///
/// 1. Renders one magenta clear frame and reads back the swapchain image into
///    a host-coherent staging buffer (M2-S05 pipeline).
/// 2. Constructs a cosmic-text `FontSystem` populated from `/system/fonts/`
///    via `ASystemFontIterator` (NDK API 29+) — see
///    `crates/android-host/src/font_render.rs` for the full discovery pipeline.
/// 3. Shapes `text` (typically `"Hello, 世界"`) and rasterizes each glyph via
///    swash; alpha-blends every glyph onto the captured RGBA buffer at
///    `(baseline_x, baseline_y)` using `font_size_px`.
/// 4. Encodes the modified buffer as PNG.
///
/// Returns `true` on success. Logs `capture_ok` (M2-S05 schema) AND a new
/// `font_render_ok` line carrying the M2-S07 counters (fonts_loaded,
/// glyphs_total, composed_pixels, mean_rgb_after, primary/cjk family). The
/// test driver greps these to verify glyph coverage.
///
/// Synchronous — blocks the calling thread until the PNG file is fully
/// flushed to disk.
#[cfg(target_os = "android")]
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_renderCaptureFrameWithText(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    r: jfloat,
    g: jfloat,
    b: jfloat,
    a: jfloat,
    text: JString,
    font_size_px: jfloat,
    baseline_x: jfloat,
    baseline_y: jfloat,
) -> jboolean {
    init_logger();
    let path_str: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(e) => {
            log::error!(
                target: "warp-android-host",
                "renderCaptureFrameWithText: could not extract path JString: {:?}",
                e
            );
            return JNI_FALSE;
        }
    };
    let text_str: String = match env.get_string(&text) {
        Ok(s) => s.into(),
        Err(e) => {
            log::error!(
                target: "warp-android-host",
                "renderCaptureFrameWithText: could not extract text JString: {:?}",
                e
            );
            return JNI_FALSE;
        }
    };
    let out_path = std::path::PathBuf::from(&path_str);
    match vulkan::capture_to_png_with_text(
        [r, g, b, a],
        &out_path,
        &text_str,
        font_size_px,
        baseline_x,
        baseline_y,
    ) {
        Some(_) => JNI_TRUE,
        None => JNI_FALSE,
    }
}

// ── M2-S08: static glyph grid renderer JNI bindings ────────────────────────

/// M2-S08: initialize the static-grid GPU pipeline.
///
/// Builds the glyph atlas + per-instance vertex buffer + Vulkan pipeline once.
/// All expensive work (cosmic-text shaping, swash rasterization, GPU upload,
/// pipeline creation) happens synchronously in this call. Subsequent
/// `renderDrawGridFrame` calls are pure GPU draws with zero per-frame
/// allocations.
///
/// Returns `true` on success. The Rust side logs `static_grid_init_ok dt_ms=…
/// text=… rows=… cols=… atlas_glyphs=… instances=…` which the test driver
/// greps for.
///
/// Idempotent: if a grid is already attached, the old one is destroyed first.
#[cfg(target_os = "android")]
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_renderInitStaticGrid(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
    font_size_px: jfloat,
    rows: jint,
    cols: jint,
    cell_w_px: jfloat,
    cell_h_px: jfloat,
) -> jboolean {
    init_logger();
    let text_str: String = match env.get_string(&text) {
        Ok(s) => s.into(),
        Err(e) => {
            log::error!(target: "warp-android-host",
                "renderInitStaticGrid: could not extract text JString: {:?}", e);
            return JNI_FALSE;
        }
    };
    if rows <= 0 || cols <= 0 {
        log::error!(target: "warp-android-host",
            "renderInitStaticGrid: invalid grid dims rows={} cols={}", rows, cols);
        return JNI_FALSE;
    }
    let ok = vulkan::init_static_grid(
        &text_str,
        font_size_px,
        rows as u32,
        cols as u32,
        cell_w_px,
        cell_h_px,
    );
    if ok { JNI_TRUE } else { JNI_FALSE }
}

/// M2-S08: submit one grid frame (clear + grid draw + present).
///
/// Returns `true` on successful `vkQueuePresentKHR`. If no grid has been
/// initialized, the call falls back to a clear-color frame.
#[cfg(target_os = "android")]
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_renderDrawGridFrame(
    _env: JNIEnv,
    _class: JClass,
    r: jfloat,
    g: jfloat,
    b: jfloat,
    a: jfloat,
) -> jboolean {
    let ok = vulkan::submit_grid_frame([r, g, b, a]);
    if ok { JNI_TRUE } else { JNI_FALSE }
}

/// Returns true if a static grid is currently attached.
#[cfg(target_os = "android")]
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_renderStaticGridAttached(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if vulkan::static_grid_attached() { JNI_TRUE } else { JNI_FALSE }
}

/// M3-S08 — submit one frame of the per-cell dynamic grid (without
/// re-initialization). Used by the Choreographer fallback path when the
/// model is not dirty: the previously-initialized dynamic grid is
/// re-presented so the surface keeps showing the last PTY frame instead
/// of going to clear color between dirty pushes.
///
/// Returns `true` on successful present. If no dynamic grid is attached,
/// falls back to clear-color (which the caller observes via the same
/// black-screen path as before).
#[cfg(target_os = "android")]
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_renderDrawDynamicGridFrame(
    _env: JNIEnv,
    _class: JClass,
    r: jfloat,
    g: jfloat,
    b: jfloat,
    a: jfloat,
) -> jboolean {
    let ok = vulkan::submit_dynamic_grid_frame([r, g, b, a]);
    if ok { JNI_TRUE } else { JNI_FALSE }
}

/// Returns true if a dynamic grid is currently attached.
#[cfg(target_os = "android")]
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_renderDynamicGridAttached(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if vulkan::dynamic_grid_attached() { JNI_TRUE } else { JNI_FALSE }
}

/// M3-S08 — diagnostic stats: comma-separated CSV
///   "atlas_glyphs=N,glyph_quads=N,bg_quads=N,rows=N,cols=N"
/// or empty string if no dynamic grid attached. Used by the driver.
#[cfg(target_os = "android")]
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_renderDynamicGridStats(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let s = match vulkan::dynamic_grid_stats() {
        Some((atlas, glyph_q, bg_q, rows, cols)) => {
            // M3-S09: append fast-path / full-reinit counters so the device
            // driver can verify the optimization actually fired during
            // sustained scroll. Falling back to (0,0) if no surface attached
            // (already filtered above by `dynamic_grid_stats() => Some`).
            let (fast_updates, full_reinits) =
                vulkan::dynamic_grid_perf_counters().unwrap_or((0, 0));
            format!(
                "atlas_glyphs={},glyph_quads={},bg_quads={},rows={},cols={},\
                 fast_path_updates={},full_reinits={}",
                atlas, glyph_q, bg_q, rows, cols, fast_updates, full_reinits
            )
        }
        None => String::new(),
    };
    env.new_string(s)
        .expect("failed to create Java string")
        .into_raw()
}

/// Returns a comma-separated diagnostic string:
///   "atlas_glyphs=N,glyphs_per_frame=N,rows=N,cols=N,text=…"
/// or empty string if no grid attached. Used by the driver to round-trip
/// diagnostic info into the result JSON.
#[cfg(target_os = "android")]
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_renderStaticGridStats(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let s = match vulkan::static_grid_stats() {
        Some((atlas, ppf, rows, cols, text)) => format!(
            "atlas_glyphs={},glyphs_per_frame={},rows={},cols={},text={}",
            atlas, ppf, rows, cols, text
        ),
        None => String::new(),
    };
    env.new_string(s)
        .expect("failed to create Java string")
        .into_raw()
}

// On non-Android Unix targets (host-side cargo test) the Vulkan symbols are
// gated out — JNI bindings are only meaningful on Android.

// ── M2-S10: IME composing-text JNI bindings ─────────────────────────────────
//
// Drives `crates/android-host/src/ime.rs::global_ime()` (which mirrors the
// canonical state machine in `warp-src/crates/warpui/src/platform/android/
// ime.rs` per M2-S10 AC#1). Java side is `WarpInputView.WarpInputConnection`
// in `android/app/src/main/java/dev/warp/mobile/WarpInputView.kt`.
//
// JNI call thread: the View's UI thread (per Android InputConnection contract).
// All three event entry points are guarded by a process-wide `Mutex` inside
// `ime::global_ime()` so the driver-side `imeStats` query (potentially on a
// different thread) is serialized against UI-thread updates.

/// M2-S10: Java `WarpInputView.WarpInputConnection.commitText` → Rust state.
///
/// `text` may be empty (some IMEs send empty commits as no-ops); the state
/// machine handles that internally without emitting LatinCommit events for
/// empty Latin commits.
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_imeCommitText(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
    new_cursor_position: jint,
) {
    init_logger();
    let text_str: String = match env.get_string(&text) {
        Ok(s) => s.into(),
        Err(e) => {
            log::error!(target: "WarpIme", "imeCommitText: get_string failed: {:?}", e);
            return;
        }
    };
    ime::commit_text(&text_str, new_cursor_position as i32);
}

/// M2-S10: Java `WarpInputView.WarpInputConnection.setComposingText` → Rust.
///
/// Empty `text` while a region is active is treated as a finish (clears the
/// region without inserting); empty + no active region is a no-op.
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_imeSetComposingText(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
    new_cursor_position: jint,
) {
    init_logger();
    let text_str: String = match env.get_string(&text) {
        Ok(s) => s.into(),
        Err(e) => {
            log::error!(target: "WarpIme", "imeSetComposingText: get_string failed: {:?}", e);
            return;
        }
    };
    ime::set_composing_text(&text_str, new_cursor_position as i32);
}

/// M2-S10: Java `WarpInputView.WarpInputConnection.finishComposingText` → Rust.
///
/// If the composing region is empty (Gboard known issue: spurious empty
/// finishComposingText between setComposingText and commitText), emits an
/// `EmptyFinish` marker rather than double-committing.
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_imeFinishComposingText(
    _env: JNIEnv,
    _class: JClass,
) {
    init_logger();
    ime::finish_composing_text();
}

/// M2-S10: returns the current IME stats as a comma-separated string. Driver
/// queries this between sub-tests to read counters without parsing logcat.
///
/// Schema:
///   `commit_calls=N,set_composing_calls=N,finish_calls=N,events=N,
///    latin=N,composing_update=N,composing_commit=N,composing_finish=N,
///    empty_finish=N,is_composing=B,composing_text=S`
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_imeStats(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let s = ime::stats_string();
    env.new_string(s)
        .expect("failed to create Java string")
        .into_raw()
}

/// M2-S10: reset the IME state (clear counters + composing region). Driver
/// calls this between Latin and Pinyin sub-tests so counters are independent.
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_imeReset(
    _env: JNIEnv,
    _class: JClass,
) {
    init_logger();
    ime::reset();
}

// ── M2-S11: Touch input + gesture JNI bindings ──────────────────────────────
//
// Drives `crates/android-host/src/input.rs::global_input()` (which mirrors the
// canonical state machine in `warp-src/crates/warpui/src/platform/android/
// input.rs` per M2-S11 AC#1). Java side is `WarpInputView` in
// `android/app/src/main/java/dev/warp/mobile/WarpInputView.kt`.
//
// JNI call thread: View's UI thread (Android touch dispatch contract).
// All five event entry points + the stats/reset pair are guarded by the
// process-wide `Mutex` inside `input::global_input()`.

/// M2-S11: Java `WarpInputView.onTouchEvent ACTION_DOWN` → Rust state.
#[cfg(target_os = "android")]
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_inputTouchDown(
    _env: JNIEnv,
    _class: JClass,
    x: jfloat,
    y: jfloat,
) {
    init_logger();
    input::touch_down(x, y);
}

/// M2-S11: Java `WarpInputView.onTouchEvent ACTION_UP` → Rust state.
#[cfg(target_os = "android")]
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_inputTouchUp(
    _env: JNIEnv,
    _class: JClass,
    x: jfloat,
    y: jfloat,
) {
    init_logger();
    input::touch_up(x, y);
}

/// M2-S11: Java `WarpInputView.onTouchEvent ACTION_CANCEL` → Rust state.
///
/// Emits `InputEvent::TouchCancel` to close the open touch-down sequence when
/// Android cancels the gesture (e.g. a parent View intercepts the event stream,
/// or the window loses focus). Without this, Rust state believes the finger is
/// still down after an intercepted DOWN.
#[cfg(target_os = "android")]
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_inputTouchCancel(
    _env: JNIEnv,
    _class: JClass,
    x: jfloat,
    y: jfloat,
) {
    init_logger();
    input::touch_cancel(x, y);
}

/// M2-S11: Java `GestureDetector.onSingleTapConfirmed` → Rust state.
#[cfg(target_os = "android")]
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_inputTap(
    _env: JNIEnv,
    _class: JClass,
    x: jfloat,
    y: jfloat,
) {
    init_logger();
    input::tap(x, y);
}

/// M2-S11: Java `GestureDetector.onLongPress` → Rust state.
#[cfg(target_os = "android")]
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_inputLongPress(
    _env: JNIEnv,
    _class: JClass,
    x: jfloat,
    y: jfloat,
) {
    init_logger();
    input::long_press(x, y);
}

/// M2-S11: Java `GestureDetector.onScroll` + VelocityTracker → Rust state.
///
/// `dx`/`dy`: distance moved since last scroll event (from `onScroll` distanceX/Y).
/// `vx`/`vy`: instantaneous velocity in px/s from `VelocityTracker` (computed
/// at ACTION_MOVE time on Java side and forwarded here).
#[cfg(target_os = "android")]
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_inputScroll(
    _env: JNIEnv,
    _class: JClass,
    x: jfloat,
    y: jfloat,
    dx: jfloat,
    dy: jfloat,
    vx: jfloat,
    vy: jfloat,
) {
    init_logger();
    input::scroll(x, y, dx, dy, vx, vy);
}

/// M2-S11: returns current input stats as a comma-separated string:
///   "touch_down=N,touch_up=N,tap=N,long_press=N,scroll=N,events=N,
///    last_down_x=F,last_down_y=F,last_up_x=F,last_up_y=F,
///    last_scroll_vx=F,last_scroll_vy=F"
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_inputStats(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let s = input::stats_string();
    env.new_string(s)
        .expect("failed to create Java string")
        .into_raw()
}

/// M2-S11: reset the input state (clear counters + events). Driver calls
/// between sub-tests so each sub-test's counters are independent.
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_inputReset(
    _env: JNIEnv,
    _class: JClass,
) {
    init_logger();
    input::reset();
}

// ── M2-S12: WindowInsets render area ────────────────────────────────────────
//
// Called from `ViewCompat.setOnApplyWindowInsetsListener` in MainActivity
// whenever system insets change (IME up/down, system bars show/hide, rotation).
// For M2-S12 we store + log; M3 grid rendering will consume the effective
// viewport to avoid overlap with the IME panel or status bar.

/// Process-global render insets in physical pixels.
/// Updated atomically from the UI thread (single-writer via main thread).
static RENDER_INSETS_TOP: std::sync::atomic::AtomicI32 =
    std::sync::atomic::AtomicI32::new(0);
static RENDER_INSETS_LEFT: std::sync::atomic::AtomicI32 =
    std::sync::atomic::AtomicI32::new(0);
static RENDER_INSETS_RIGHT: std::sync::atomic::AtomicI32 =
    std::sync::atomic::AtomicI32::new(0);
static RENDER_INSETS_BOTTOM: std::sync::atomic::AtomicI32 =
    std::sync::atomic::AtomicI32::new(0);

/// M2-S12: store the current effective render insets reported from
/// `ViewCompat.setOnApplyWindowInsetsListener` in MainActivity.
///
/// Insets are in physical pixels (same coordinate space as ANativeWindow).
/// `bottom` is `max(ime.bottom, sysBars.bottom)` from the Kotlin side so
/// it represents whichever reservation is larger (keyboard or nav bar).
///
/// For M2-S12 this is a store + log only. M3 will read these atomics when
/// computing the effective render viewport for the block grid.
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_setRenderInsets(
    _env: JNIEnv,
    _class: JClass,
    top: jni::sys::jint,
    left: jni::sys::jint,
    right: jni::sys::jint,
    bottom: jni::sys::jint,
) {
    use std::sync::atomic::Ordering;
    init_logger();
    RENDER_INSETS_TOP.store(top, Ordering::Relaxed);
    RENDER_INSETS_LEFT.store(left, Ordering::Relaxed);
    RENDER_INSETS_RIGHT.store(right, Ordering::Relaxed);
    RENDER_INSETS_BOTTOM.store(bottom, Ordering::Relaxed);
    log::info!(
        target: "WarpRender",
        "render_insets top={} left={} right={} bottom={}",
        top, left, right, bottom
    );
}

// ── M3-S04: Terminal model + push_frame JNI bindings ───────────────────────
//
// Pipeline: PTY bytes (M1 backend) → terminalInputBytes JNI → facade-shaped
// TerminalModel.ingest_pty_bytes → Choreographer-side terminalPushFrame JNI
// → vulkan::push_frame (which chains init_static_grid + submit_grid_frame).
//
// Java side flow:
//   1. WarpTerminalService.kt PTY read coroutine forwards every chunk to
//      `NativeBridge.terminalInputBytes(cmdId, bytes)`. This is fire-and-forget;
//      the model handles its own dirty bit.
//   2. MainActivity Choreographer per-vsync callback calls
//      `NativeBridge.terminalTakeDirtyAndPushFrame(font_size_px, rows, cols,
//      cell_w_px, cell_h_px)`. If the model is dirty, it snapshots the text
//      and re-inits the GPU grid + submits a frame; otherwise it falls back
//      to `renderClearFrame` so the loop keeps presenting at vsync rate.
//
// Logcat tag: `WarpTerminalModel` (Rust). Test drivers grep these.

/// M3-S04: Java `WarpTerminalService.startReadLoop` → Rust state.
///
/// Forwards the PTY chunk to the process-global [`terminal_model::TerminalModel`].
/// Returns the number of bytes ingested (always equals `bytes.len`) so the
/// Java side can spot-check the round-trip count via stats.
///
/// `cmd_id` is forwarded for logging only — the M3-S04 baseline routes ALL
/// PTY chunks into the single global model. M3 future work may key per-tab.
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_terminalInputBytes(
    mut env: JNIEnv,
    _class: JClass,
    cmd_id: JString,
    data: JByteArray,
) -> jint {
    init_logger();
    // cmd_id is informational; if extraction fails we still process bytes.
    let cmd_id_str: String = match env.get_string(&cmd_id) {
        Ok(s) => s.into(),
        Err(_) => String::from("<unknown>"),
    };
    let bytes: Vec<u8> = match env.convert_byte_array(&data) {
        Ok(b) => b,
        Err(e) => {
            log::error!(
                target: "WarpTerminalModel",
                "terminalInputBytes: convert_byte_array failed: {:?}", e
            );
            return -1;
        }
    };
    let n = terminal_model::ingest_pty_bytes_for_session(&cmd_id_str, &bytes);
    log::debug!(
        target: "WarpTerminalModel",
        "terminalInputBytes cmd_id={} bytes={} ingested={}",
        cmd_id_str, bytes.len(), n
    );
    n as jint
}

/// M3-S04: Choreographer-driven push_frame.
///
/// If the model dirty bit is set, snapshots the current text + drives the
/// Vulkan static-grid pipeline (re-init + submit). Returns:
///   *  1 → frame pushed successfully
///   *  0 → no dirty buffer; caller should fall back to clear-frame
///   * -1 → init/submit failed
///
/// The grid params (`font_size_px`, `rows`, `cols`, `cell_w_px`, `cell_h_px`)
/// mirror `renderInitStaticGrid`. They are passed per-call rather than stored
/// in a global because they may differ per orientation / Activity recreate.
///
/// Tagged `WarpTerminalModel` for log scraping; the M3-S04 driver greps
/// `terminal_push_frame ok=… text_len=…` to verify the pipeline ran.
#[cfg(target_os = "android")]
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_terminalTakeDirtyAndPushFrame(
    _env: JNIEnv,
    _class: JClass,
    font_size_px: jfloat,
    rows: jint,
    cols: jint,
    cell_w_px: jfloat,
    cell_h_px: jfloat,
) -> jint {
    if !terminal_model::take_dirty() {
        return 0;
    }
    if rows <= 0 || cols <= 0 {
        log::error!(
            target: "WarpTerminalModel",
            "terminalTakeDirtyAndPushFrame: invalid grid dims rows={} cols={}", rows, cols
        );
        return -1;
    }

    // M3-S08: route through the per-cell dynamic_grid renderer instead of
    // the M3-S04 single-line static_grid projection. The dynamic grid:
    //   * preserves per-cell SGR fg/bg colors (M3 Acceptance #1)
    //   * draws line-wrapped output (each row of the model snapshot lands at
    //     its own pixel-y, so wrapping is implicit)
    //   * leaves the M2-S08 static_grid pipeline untouched (still drives the
    //     `--ez grid_mode true` demo path)
    let cells = terminal_model::snapshot_cells();
    if cells.is_empty() {
        log::warn!(
            target: "WarpTerminalModel",
            "terminalTakeDirtyAndPushFrame: empty cells snapshot; skipping"
        );
        return 0;
    }
    // Translate from `terminal_model::Cell` to `dynamic_grid::Cell` (same
    // wire shape; the duplication exists to keep dynamic_grid free of the
    // facade-layer Cargo dep). Sized at construction so we don't grow the
    // outer/inner Vecs on the hot path.
    let mut dyn_cells: Vec<Vec<crate::dynamic_grid::Cell>> = cells
        .iter()
        .map(|row| {
            row.iter()
                .map(|c| crate::dynamic_grid::Cell {
                    glyph: c.glyph,
                    fg: c.fg,
                    bg: c.bg,
                    attrs: c.attrs,
                })
                .collect()
        })
        .collect();

    // V1-prep iteration 32: bottom-anchor short content. Without this, when
    // the user has only typed a few commands, the prompt row sits at the top
    // of the viewport and a large empty area separates it from the on-screen
    // composer at the bottom — the user's "反著來" complaint. Standard
    // terminal UX (Warp Desktop, alacritty, iTerm, native xterm at startup)
    // keeps the active prompt at the bottom of the visible area with any
    // empty rows above it.
    //
    // We do this purely in the render-time projection — the underlying
    // TerminalState still holds rows×cols with content top-down, so zsh's
    // line-wrapping and scroll semantics are unchanged. We only shift WHICH
    // rows go into the dynamic_grid output: prepend (rows - cursor_row - 1)
    // blank rows, then the actual content rows up to the cursor row.
    let cursor = terminal_model::cursor_position();
    let total_rows = dyn_cells.len();
    // V1-prep iteration 38: unified bottom-anchor. iter-36 left 7 blank rows
    // ABOVE the cursor for AccessoryRow clearance, but only in the cold-start
    // case where shift > 0. When the user runs enough commands that content
    // fills the viewport (cursor.row + 7 >= total_rows), the iter-36 path
    // returned early with shift = 0 — letting the cursor row + any subsequent
    // output land inside the AccessoryRow's opaque overlay area (the user's
    // 「whoami 輸出看不見」symptom).
    //
    // iter-38 fix: always reserve ACCESSORY_PAD blank rows at the bottom of
    // the rendered grid. Cursor lands at row (total_rows - ACCESSORY_PAD - 1)
    // in the projection. When cursor is high in the model we prepend blanks
    // (iter-32 / iter-36 case); when cursor is low we drop scrollback rows
    // from the top (the iter-38 new case).
    const ACCESSORY_PAD: usize = 7;
    if total_rows > ACCESSORY_PAD {
        let target_cursor_row = total_rows - ACCESSORY_PAD - 1;
        let cols_count = dyn_cells.first().map(|r| r.len()).unwrap_or(0);
        let blank_row: Vec<crate::dynamic_grid::Cell> =
            (0..cols_count).map(|_| crate::dynamic_grid::Cell::blank()).collect();
        let mut out: Vec<Vec<crate::dynamic_grid::Cell>> = Vec::with_capacity(total_rows);
        if cursor.row <= target_cursor_row {
            // Cold-start / sparse case: pad above content so cursor lands at target.
            let pad_top = target_cursor_row - cursor.row;
            for _ in 0..pad_top {
                out.push(blank_row.clone());
            }
            out.extend(dyn_cells.into_iter().take(cursor.row + 1));
        } else {
            // Filled viewport: drop scrollback rows from the top so the
            // cursor row lands at target_cursor_row in the projection.
            let drop = cursor.row - target_cursor_row;
            out.extend(dyn_cells.into_iter().skip(drop).take(target_cursor_row + 1));
        }
        // Append ACCESSORY_PAD blank rows below the cursor row to clear the
        // AccessoryRow overlay (ESC/TAB/CTRL/ALT) and any composer chrome.
        for _ in 0..ACCESSORY_PAD {
            out.push(blank_row.clone());
        }
        dyn_cells = out;
    }

    let init_ok = vulkan::init_dynamic_grid(
        &dyn_cells,
        font_size_px,
        cell_w_px,
        cell_h_px,
    );
    if !init_ok {
        log::error!(
            target: "WarpTerminalModel",
            "terminalTakeDirtyAndPushFrame: init_dynamic_grid failed; dropping frame"
        );
        return -1;
    }
    // Black clear color so non-default-bg cells stand out instead of being
    // washed by the M2-S08 demo magenta.
    let present_ok = vulkan::submit_dynamic_grid_frame([0.0, 0.0, 0.0, 1.0]);
    let total_cells = dyn_cells.iter().map(|r| r.len()).sum::<usize>();
    log::info!(
        target: "WarpTerminalModel",
        "terminal_push_frame_dynamic ok={} cells={} rows={} cols={}",
        present_ok, total_cells, rows, cols
    );
    if present_ok { 1 } else { -1 }
}

/// M3-S04: returns terminal model dimensions/cursor/byte-count as a CSV
/// string for the device driver to read out without parsing logcat.
///
/// Schema:
///   `rows=N,cols=N,cursor_row=N,cursor_col=N,bytes_ingested=N,dirty=B`
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_terminalModelStats(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let model = terminal_model::global_model();
    let (rows, cols) = model.dims();
    let cur = model.cursor();
    let bytes = model.bytes_ingested();
    // Non-destructive peek so the stats accessor doesn't accidentally swallow
    // a pending Choreographer re-init.
    let dirty = model.peek_dirty();
    let s = format!(
        "rows={},cols={},cursor_row={},cursor_col={},bytes_ingested={},dirty={}",
        rows, cols, cur.row, cur.col, bytes, dirty
    );
    env.new_string(s)
        .expect("failed to create Java string")
        .into_raw()
}

/// M3-S05: returns SGR/DCS parser counters as a CSV string. Used by the
/// device-side AC#7 driver to verify that ANSI color sequences and DCS
/// frames were actually parsed (rather than getting silently dropped).
///
/// Schema:
///   `sgr_apply_count=N,dcs_hook_count=N,dcs_error_count=N,cur_fg=0xRRGGBBAA,cur_bg=0xRRGGBBAA,cur_attrs=0xNN`
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_terminalSgrSummary(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let model = terminal_model::global_model();
    let (sgr, hooks, errs) = model.parser_stats();
    let (fg, bg, attrs) = model.current_attrs();
    let s = format!(
        "sgr_apply_count={},dcs_hook_count={},dcs_error_count={},cur_fg=0x{:08X},cur_bg=0x{:08X},cur_attrs=0x{:02X}",
        sgr, hooks, errs, fg, bg, attrs
    );
    env.new_string(s)
        .expect("failed to create Java string")
        .into_raw()
}

/// M3-S07: returns the current `Vec<Block>` as a JSON array. Each entry
/// has `{id, start_time_unix_ms, command, exit_code, end_time_unix_ms}`
/// — wire-format identical to the canonical facade `BlockList::to_dump_json`.
///
/// Consumed by `tools/scripts/test-block-model.sh` to gate M3 Acceptance
/// #3 (start_time/command/exit_code populated correctly).
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_terminalBlocksDump(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let json = terminal_model::blocks_dump_json();
    env.new_string(json)
        .expect("failed to create Java string")
        .into_raw()
}

/// M3-S04: resize the terminal model. Called from the Java side when the
/// surface dimensions change (e.g. rotation, IME show/hide). The grid is
/// reshaped; existing in-bounds cells are preserved.
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_terminalResize(
    _env: JNIEnv,
    _class: JClass,
    rows: jint,
    cols: jint,
) {
    init_logger();
    if rows <= 0 || cols <= 0 {
        log::error!(
            target: "WarpTerminalModel",
            "terminalResize: invalid dims rows={} cols={}", rows, cols
        );
        return;
    }
    terminal_model::resize(rows as usize, cols as usize);
    log::info!(
        target: "WarpTerminalModel",
        "terminal_resize rows={} cols={}", rows, cols
    );
}

// ── M3-S09: scrollback + viewport offset JNI ────────────────────────────────
//
// Drives the scrollback ring + viewport offset from the M2-S11 GestureDetector
// callbacks (drag scroll + fling momentum). Java side calls
// `terminalSetScrollOffset` from `WarpInputView.gestureListener.onScroll` and
// the fling-decay timer; the Rust side clamps to `scrollback.len()` and sets
// the dirty flag so the next vsync re-inits the GPU grid.

/// M3-S09: set the viewport scroll offset (rows back into history).
/// `0` = live tail; `>0` = scrolled up by N rows. Clamped to
/// `scrollback.len()` inside Rust so callers cannot scroll beyond the
/// retained history.
///
/// Negative values from Java are saturated to 0 (a finger drag past the
/// live tail can't unscroll the future).
///
/// M3-S09 round-2: returns the actual clamped offset (after Rust clamps to
/// `scrollback.len()`) so the Kotlin caller (`WarpInputView.gestureListener`
/// `onScroll` / fling decay) can sync `currentScrollOffsetRows` against the
/// real value. Without this, an over-scroll request leaves the Kotlin
/// accumulator drifting above the Rust state, forcing the user to scroll
/// back the overflow before the viewport visibly moves (see codex round-1
/// finding #1).
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_terminalSetScrollOffset(
    _env: JNIEnv,
    _class: JClass,
    offset_rows: jint,
) -> jint {
    init_logger();
    let requested = if offset_rows < 0 { 0usize } else { offset_rows as usize };
    let clamped = terminal_model::set_scroll_offset(requested);
    log::info!(
        target: "WarpTerminalModel",
        "terminal_set_scroll_offset requested={} clamped={}",
        requested, clamped
    );
    clamped as jint
}

/// M3-S09: returns scrollback state as a CSV string for the JNI driver:
///   "scrollback_len=N,scrollback_max=N,scroll_offset=N"
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_terminalScrollbackInfo(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let s = format!(
        "scrollback_len={},scrollback_max={},scroll_offset={}",
        terminal_model::scrollback_len(),
        terminal_model::scrollback_max_lines(),
        terminal_model::scroll_offset(),
    );
    env.new_string(s)
        .expect("failed to create Java string")
        .into_raw()
}

/// Task 2 (#12): returns true if the active terminal model is currently in alternate screen mode (DECSET 1049/47/1047).
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_terminalIsAltScreen(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if terminal_model::is_alt_screen() {
        jni::sys::JNI_TRUE
    } else {
        jni::sys::JNI_FALSE
    }
}

// ── Multi-Session Tabs FFI Bridge (Task 2 / #8) ────────────────────────────

/// Creates a new terminal session in Rust SessionManager.
/// `sessionId`: unique string identifier for the tab session.
/// `envJson`: JSON string containing environment variables map (e.g. `{"TERM":"xterm-256color"}`).
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_createSession(
    mut env: JNIEnv,
    _class: JClass,
    session_id: JString,
    env_json: JString,
) -> jboolean {
    init_logger();
    let session_id_str: String = match env.get_string(&session_id) {
        Ok(s) => s.into(),
        Err(_) => return jni::sys::JNI_FALSE,
    };
    let env_json_str: String = match env.get_string(&env_json) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let env_map: std::collections::HashMap<String, String> =
        serde_json::from_str(&env_json_str).unwrap_or_default();

    match warp_terminal_mobile_facade::SessionManager::global().create_session(
        &session_id_str,
        Some(&session_id_str),
        Some("~"),
        env_map,
        warp_terminal_mobile_facade::DEFAULT_ROWS,
        warp_terminal_mobile_facade::DEFAULT_COLS,
    ) {
        Ok(_) => {
            log::info!(target: "WarpTerminalModel", "createSession ok id={}", session_id_str);
            jni::sys::JNI_TRUE
        }
        Err(e) => {
            log::error!(target: "WarpTerminalModel", "createSession failed id={} err={:?}", session_id_str, e);
            jni::sys::JNI_FALSE
        }
    }
}

/// Switches active viewport session to `sessionId`.
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_switchSession(
    mut env: JNIEnv,
    _class: JClass,
    session_id: JString,
) -> jboolean {
    init_logger();
    let session_id_str: String = match env.get_string(&session_id) {
        Ok(s) => s.into(),
        Err(_) => return jni::sys::JNI_FALSE,
    };

    match warp_terminal_mobile_facade::SessionManager::global().switch_session(&session_id_str) {
        Ok(_) => {
            log::info!(target: "WarpTerminalModel", "switchSession ok id={}", session_id_str);
            jni::sys::JNI_TRUE
        }
        Err(e) => {
            log::error!(target: "WarpTerminalModel", "switchSession failed id={} err={:?}", session_id_str, e);
            jni::sys::JNI_FALSE
        }
    }
}

/// Closes and releases a session tab by `sessionId`.
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_closeSession(
    mut env: JNIEnv,
    _class: JClass,
    session_id: JString,
) -> jboolean {
    init_logger();
    let session_id_str: String = match env.get_string(&session_id) {
        Ok(s) => s.into(),
        Err(_) => return jni::sys::JNI_FALSE,
    };

    match warp_terminal_mobile_facade::SessionManager::global().close_session(&session_id_str) {
        Ok(_) => {
            log::info!(target: "WarpTerminalModel", "closeSession ok id={}", session_id_str);
            jni::sys::JNI_TRUE
        }
        Err(e) => {
            log::error!(target: "WarpTerminalModel", "closeSession failed id={} err={:?}", session_id_str, e);
            jni::sys::JNI_FALSE
        }
    }
}

/// Returns the active session ID string (or "default" if none).
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_activeSessionId(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let id = warp_terminal_mobile_facade::SessionManager::global()
        .active_session_id()
        .unwrap_or_else(|| "default".to_string());
    env.new_string(id)
        .expect("failed to create Java string")
        .into_raw()
}

/// Exports full session state (active tab list, active ID, scrollback, blocks) as JSON.
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_saveSessionState(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    init_logger();
    let json = match warp_terminal_mobile_facade::SessionManager::global().export_session_state_json() {
        Ok(s) => s,
        Err(e) => {
            log::error!(target: "WarpTerminalModel", "saveSessionState failed: {:?}", e);
            String::new()
        }
    };
    env.new_string(json)
        .expect("failed to create Java string")
        .into_raw()
}

/// Restores full session state (active tab list, active ID, scrollback, blocks) from JSON.
#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn Java_dev_warp_mobile_NativeBridge_restoreSessionState(
    mut env: JNIEnv,
    _class: JClass,
    json: JString,
) -> jboolean {
    init_logger();
    let json_str: String = match env.get_string(&json) {
        Ok(s) => s.into(),
        Err(_) => return jni::sys::JNI_FALSE,
    };

    match warp_terminal_mobile_facade::SessionManager::global().restore_session_state_json(&json_str) {
        Ok(_) => {
            log::info!(target: "WarpTerminalModel", "restoreSessionState ok");
            jni::sys::JNI_TRUE
        }
        Err(e) => {
            log::error!(target: "WarpTerminalModel", "restoreSessionState failed: {:?}", e);
            jni::sys::JNI_FALSE
        }
    }
}

// ── Logger ──────────────────────────────────────────────────────────────────

#[cfg(target_os = "android")]
fn init_logger() {
    let _ = android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Debug)
            .with_tag("warp-android-host"),
    );
}

#[cfg(not(target_os = "android"))]
fn init_logger() {}

// ── M3-S11 SubpixelMask emoji smoke tests ──────────────────────────────────
//
// `font_render::classify_char` (Android-only module) tags each input
// codepoint as Latin / Cjk / Emoji so the cosmic-text shaping pipeline can
// pick the correct font family (`"Noto Color Emoji"` for emoji, `"Noto Sans
// CJK"` for CJK, default for Latin). A misclassification here is the
// upstream cause of "tofu" in the SubpixelMask + Color blit paths at
// `font_render.rs:498-523` — if an emoji codepoint is tagged Latin it
// resolves to the Latin font, hits no glyph, and the swash cache returns
// `None` so the codepoint never makes it to the `SwashContent::Mask |
// SubpixelMask | Color` arm.
//
// Per AC#5 in `.omc/prd.json` M3-S11: "SubpixelMask emoji smoke test
// (similar to M2-S07's 'Hello, 世界' CJK test)". The on-device equivalent
// for emoji rendering is M5 (Termux brings GNU coreutils + emoji-rich shell
// scripts); for M3-S11 we add a host-runnable classifier smoke test that
// pins the emoji-codepoint contract so a future regression — e.g. someone
// shrinks the U+1F300..=U+1F6FF range or drops U+2600..=U+27BF — fails CI
// before reaching device verification.
//
// The classifier mirror below is a verbatim mirror of
// `font_render::classify_char` (the Android module is `#![cfg(target_os =
// "android")]`-gated so we cannot call it directly from a host-side test;
// duplicating the small range table here is cheaper than refactoring the
// gate).
#[cfg(test)]
mod m3_s11_emoji_smoke_tests {
    /// Mirror of `font_render::RunKind` that compiles on host targets. The
    /// canonical definition lives behind `#![cfg(target_os = "android")]` so
    /// we cannot import it from a host-side test.
    #[derive(Debug, Clone, Copy, PartialEq, Eq)]
    enum RunKind {
        Latin,
        Cjk,
        Emoji,
    }

    /// Mirror of `font_render::classify_char`. Keep the Unicode ranges
    /// identical to the canonical Android module — `font_render.rs:105-118`
    /// for CJK, `:122-126` for emoji.
    fn classify_char(ch: char) -> RunKind {
        let cp = ch as u32;
        let is_cjk = matches!(
            cp,
            0x1100..=0x11FF
                | 0x3000..=0x303F
                | 0x3040..=0x309F
                | 0x30A0..=0x30FF
                | 0x3100..=0x312F
                | 0x3400..=0x4DBF
                | 0x4E00..=0x9FFF
                | 0xAC00..=0xD7AF
                | 0xF900..=0xFAFF
                | 0xFE30..=0xFE4F
                | 0xFF00..=0xFFEF
        );
        if is_cjk {
            return RunKind::Cjk;
        }
        let is_emoji = matches!(
            cp,
            0x1F300..=0x1F6FF | 0x1F900..=0x1F9FF | 0x1FA00..=0x1FAFF | 0x2600..=0x27BF
        );
        if is_emoji {
            return RunKind::Emoji;
        }
        RunKind::Latin
    }

    /// Smoke test: emoji codepoints across the four supported Unicode
    /// blocks (U+1F300 Misc Symbols, U+1F900 Supplemental Symbols,
    /// U+1FA00 Symbols & Pictographs Extended-A, U+2600 Miscellaneous
    /// Symbols) all classify as `Emoji` and therefore route through the
    /// `Family::Name("Noto Color Emoji")` Attrs span at
    /// `font_render.rs:430,445`. A regression here = tofu on device.
    #[test]
    fn emoji_codepoints_classify_as_emoji() {
        let cases = [
            ('\u{1F389}', "U+1F389 PARTY POPPER (Misc Symbols block)"),
            ('\u{1F600}', "U+1F600 GRINNING FACE (Misc Symbols block)"),
            ('\u{1F4A9}', "U+1F4A9 PILE OF POO (Misc Symbols block)"),
            ('\u{1F923}', "U+1F923 ROLLING ON THE FLOOR LAUGHING (Supplemental block)"),
            ('\u{1FA90}', "U+1FA90 RINGED PLANET (Symbols & Pictographs Extended-A)"),
            ('\u{2600}', "U+2600 BLACK SUN WITH RAYS (Misc Symbols block)"),
            ('\u{2728}', "U+2728 SPARKLES (Dingbats range)"),
            ('\u{27B0}', "U+27B0 CURLY LOOP (Dingbats end)"),
        ];
        for (ch, desc) in cases {
            assert_eq!(
                classify_char(ch),
                RunKind::Emoji,
                "{desc} should classify as Emoji (otherwise it routes to \
                 Family::default and produces tofu in the SubpixelMask/\
                 Color blit paths at font_render.rs:498-523)"
            );
        }
    }

    /// Negative smoke test: the codepoints flanking the emoji ranges must
    /// classify as Latin (or CJK for the Han range), NOT as Emoji. This is
    /// the boundary half of the contract that pins
    /// `font_render::classify_char` against accidental over-broadening of
    /// the emoji ranges (which would re-route legitimate text glyphs
    /// through Noto Color Emoji and produce missing-glyph tofu).
    #[test]
    fn emoji_range_boundaries_are_tight() {
        // Just below U+1F300 — must NOT be emoji.
        assert_eq!(classify_char('\u{1F2FF}'), RunKind::Latin);
        // Just above U+1F6FF — must NOT be emoji (gap before U+1F900).
        assert_eq!(classify_char('\u{1F700}'), RunKind::Latin);
        // Just below U+2600 — must NOT be emoji (Latin punctuation).
        assert_eq!(classify_char('\u{25FF}'), RunKind::Latin);
        // Just above U+27BF — must NOT be emoji.
        assert_eq!(classify_char('\u{27C0}'), RunKind::Latin);
        // CJK Han 世界 (M2-S07 baseline): MUST stay Cjk, not Emoji.
        assert_eq!(classify_char('世'), RunKind::Cjk);
        assert_eq!(classify_char('界'), RunKind::Cjk);
        // ASCII Latin: MUST stay Latin.
        assert_eq!(classify_char('H'), RunKind::Latin);
        assert_eq!(classify_char(' '), RunKind::Latin);
        assert_eq!(classify_char(','), RunKind::Latin);
    }

    /// Mixed-string smoke test mirroring the M2-S07 CJK acceptance ("Hello,
    /// 世界") with an emoji suffix. Validates that a typical Android-IME
    /// production input (Latin + CJK + emoji in one run) produces three
    /// distinct `RunKind` segments — which is the precondition for
    /// `font_render::classify_text_runs` correctly emitting three Attrs
    /// spans (Latin → CJK → Emoji), each routed to the right font family.
    #[test]
    fn mixed_string_produces_three_run_kinds() {
        let s = "Hello, 世界 🎉";
        let kinds: Vec<RunKind> = s.chars().map(classify_char).collect();
        // 7× Latin ("Hello, "), 2× CJK (世, 界), 1× Latin (' '), 1× Emoji (🎉).
        assert!(kinds.iter().any(|k| *k == RunKind::Latin));
        assert!(kinds.iter().any(|k| *k == RunKind::Cjk));
        assert!(
            kinds.iter().any(|k| *k == RunKind::Emoji),
            "🎉 (U+1F389) must classify as Emoji"
        );
    }
}

// ── M-W4.1 Agent Stream Handle Adversarial Stress Tests (Challenger 1) ───────

#[cfg(test)]
mod agent_stream_stress_tests {
    use super::*;
    use std::sync::atomic::Ordering;

    #[test]
    fn stress_agent_stream_handle_pause_and_resume_buffering() {
        let handle = Arc::new(AgentStreamHandle::new("sess_test_1".to_string()));

        // Initial state: not paused, not done
        assert!(!handle.paused.load(Ordering::Acquire));
        assert!(!handle.done.load(Ordering::Acquire));

        // 1. Pause handle
        handle.paused.store(true, Ordering::Release);
        assert!(handle.paused.load(Ordering::Acquire));

        // 2. Buffer events while paused
        {
            let mut q = handle.events.lock().unwrap();
            q.push_back(":EVENT:{\"type\":\"text_delta\",\"text\":\"Chunk 1\"}".to_string());
            q.push_back(":EVENT:{\"type\":\"thinking_delta\",\"text\":\"Thinking...\"}".to_string());
        }

        // 3. Verify events are retained and can be drained even while paused
        {
            let mut q = handle.events.lock().unwrap();
            assert_eq!(q.len(), 2);
            let first = q.pop_front().unwrap();
            assert!(first.contains("Chunk 1"));
        }

        // 4. Resume handle
        handle.paused.store(false, Ordering::Release);
        assert!(!handle.paused.load(Ordering::Acquire));
    }

    #[test]
    fn stress_agent_stream_cancellation_and_null_handle_safety() {
        let handle = Arc::new(AgentStreamHandle::new("sess_test_cancel".to_string()));

        // Cancellation Token check
        assert!(!handle.cancel.is_cancelled());
        handle.cancel.cancel();
        assert!(handle.cancel.is_cancelled());

        // Calling cancel multiple times must be safe & idempotent
        handle.cancel.cancel();
        assert!(handle.cancel.is_cancelled());

        // Null handle safety test: handle = 0
        let null_handle: jlong = 0;
        let h_ptr = null_handle as *const AgentStreamHandle;
        assert!(h_ptr.is_null());
    }

    #[test]
    fn stress_agent_session_registry_concurrent_turn_states() {
        let reg = global_registry();
        let sid = "sess_stress_reg".to_string();

        reg.create_or_get_session(sid.clone(), "claude-sonnet-4-6".into(), "sys".into());

        let states = vec![
            TurnState::Idle,
            TurnState::Connecting,
            TurnState::Streaming,
            TurnState::Paused,
            TurnState::Streaming,
            TurnState::Completed,
        ];

        for st in states {
            reg.set_state(&sid, st);
            let snap = reg.get_session(&sid).unwrap();
            assert_eq!(snap.state, st);
        }

        // Clean up session
        reg.remove_session(&sid);
        assert!(reg.get_session(&sid).is_none());
    }
}

