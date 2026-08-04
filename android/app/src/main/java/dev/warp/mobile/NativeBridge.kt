package dev.warp.mobile

import android.content.res.AssetManager
import android.view.Surface

object NativeBridge {
    init {
        try {
            System.loadLibrary("warp_mobile_android_host")
        } catch (e: Throwable) {
            // Ignored on host JVM desktop test environments where native .so/.dll is absent
        }
    }

    external fun ping(): String

    // ── M6-S03 round-2: AI ghost-text (synchronous round-trip) ───────────────
    //
    // Calls Anthropic Claude /v1/messages via the warp_ai_mobile Rust crate
    // (reqwest + rustls + tokio). BLOCKING — caller MUST dispatch on a
    // background thread (Dispatchers.IO). On error, return value starts
    // with "ERR:" so caller can distinguish failure from a normal completion
    // that happens to start with quotes/punctuation.
    //
    // Round-3 will add a streaming + cancel-on-keystroke variant that
    // posts events through a callback object across the JNI boundary.
    external fun aiGhostComplete(
        apiKey: String,
        model: String,
        prompt: String,
        maxTokens: Int
    ): String?

    // ── M6-S03 round-3: AI ghost-text STREAMING ──────────────────────────────
    //
    // Real SSE streaming via Anthropic /v1/messages (stream=true). The
    // Rust task runs on a global multi-thread tokio runtime; chunks
    // accumulate in a VecDeque inside StreamHandle. Kotlin polls every
    // ~50ms via Handler.postDelayed.
    //
    // Lifecycle:
    //   1. start = aiGhostStreamStart(...) → Long handle
    //   2. loop: poll = aiGhostStreamPoll(start)
    //        - "" → still running; sleep 50ms; poll again
    //        - ":CHUNK:<text>" → render incremental text; sleep + poll
    //        - ":DONE:" → stream finished cleanly; break loop
    //        - ":ERR:<msg>" → error; break loop
    //   3. cancel anytime: aiGhostStreamCancel(handle); next poll yields ":ERR:Cancelled"
    //   4. ALWAYS finally: aiGhostStreamFree(handle) to drop the Arc
    //
    // The kotlin caller is responsible for the poll loop + free; the
    // Rust side does NOT auto-free on done because the JVM thread still
    // needs to retrieve the last chunks via poll. Forgetting to free =
    // small memory leak (~200 bytes per orphaned handle); use a
    // try/finally block.
    external fun aiGhostStreamStart(
        apiKey: String,
        model: String,
        prompt: String,
        maxTokens: Int
    ): Long

    external fun aiGhostStreamPoll(handle: Long): String?
    external fun aiGhostStreamCancel(handle: Long)
    external fun aiGhostStreamFree(handle: Long)

    // ── Multi-Turn Agent Conversations (Issue #14) ───────────────────────────
    external fun aiAgentSessionCreate(
        sessionId: String,
        model: String,
        systemPrompt: String
    ): Boolean

    external fun aiAgentSendTurnStart(
        sessionId: String,
        apiKey: String,
        userPrompt: String,
        maxTokens: Int
    ): Long

    external fun aiAgentTurnPoll(handle: Long): String?
    external fun aiAgentTurnControl(handle: Long, action: Int): Boolean
    external fun aiAgentTurnFree(handle: Long)

    external fun aiAgentRetryTurn(
        sessionId: String,
        apiKey: String,
        maxTokens: Int
    ): Long

    external fun aiAgentEditPrompt(
        sessionId: String,
        apiKey: String,
        turnIndex: Int,
        newPrompt: String,
        maxTokens: Int
    ): Long


    // ── Bootstrap atomic extraction (M4-S05) ─────────────────────────────────
    //
    // First-launch installer: reads bootstrap-aarch64.zip + version.json from
    // APK assets/warp/bootstrap/ via AAssetManager, sha256-verifies the zip
    // against the metadata, extracts to /data/data/dev.warp.mobile/files/usr.tmp,
    // applies SYMLINKS.txt, atomically renames usr.tmp → usr, and writes the
    // .bootstrap-version.json sha-pin marker. Idempotent: subsequent launches
    // short-circuit on sha-pin match.
    //
    // Returns an integer status code (0 = success; non-zero = error per
    // bootstrap.rs::InstallStatus).
    //
    //     0 — Success (or already installed)
    //     1 — InvalidAssetManager
    //     2 — AssetNotFound
    //     3 — Sha256Mismatch
    //     4 — IoError
    //     5 — MalformedSymlinks
    //     6 — AtomicRenameFailed
    external fun bootstrapInstall(assetManager: AssetManager, dataDir: String): Int

    // ── PTY (M1) ─────────────────────────────────────────────────────────────

    external fun activePtyCount(): Int
    external fun totalSpawnedPtys(): Long
    external fun ptyGetExitStatus(ptr: Long): Int
    external fun ptySpawn(program: String, args: Array<String>, envFlat: Array<String>): Long
    external fun ptyAcquire(ptr: Long): Long
    external fun ptyRelease(ptr: Long)
    external fun ptyRead(ptr: Long, maxBytes: Int): ByteArray?
    external fun ptyWrite(ptr: Long, data: ByteArray): Int
    external fun ptyResize(ptr: Long, rows: Short, cols: Short): Int
    external fun ptyKill(ptr: Long)

    // ── Multi-Session Tabs Manager (Task 2 & 3 / #8, #9) ──────────────────────
    external fun createSession(sessionId: String, envJson: String): Boolean
    external fun switchSession(sessionId: String): Boolean
    external fun closeSession(sessionId: String): Boolean
    external fun activeSessionId(): String
    external fun saveSessionState(): String
    external fun restoreSessionState(json: String): Boolean

    // ── Vulkan render (M2-S04) ───────────────────────────────────────────────
    //
    // Drives the AndroidSwapchain in `crates/android-host/src/vulkan.rs` (which
    // mirrors warp-src/crates/warpui/src/platform/android/vulkan.rs per
    // M2-S04 AC#1). Surface lifecycle is tied to SurfaceHolder.Callback events
    // in MainActivity; render frames are pushed from a Choreographer callback.

    /**
     * Initializes Vulkan on the given Android Surface. Wraps
     * `ANativeWindow_fromSurface` + the M0-spike-validated swapchain creation
     * path. Returns true on success.
     */
    external fun renderAttachSurface(surface: Surface): Boolean

    /** Tears down the swapchain. Idempotent. */
    external fun renderDetachSurface()

    /**
     * Submits a single clear-color frame. RGBA components are floats in
     * [0.0, 1.0]. Returns true on successful vkQueuePresentKHR; false on
     * VK_ERROR_OUT_OF_DATE_KHR (caller may retry next vsync after the
     * swapchain has been recreated internally).
     */
    external fun renderClearFrame(r: Float, g: Float, b: Float, a: Float): Boolean

    /** Cumulative frame count since the last attach. */
    external fun renderFramesPresented(): Long

    /**
     * M2-S05: capture a single frame as PNG at `path`.
     *
     * Renders one clear-color frame (RGBA in [0.0, 1.0]), copies the swapchain
     * image to a host-coherent staging buffer via `vkCmdCopyImageToBuffer`,
     * swizzles BGRA→RGBA if needed, encodes a PNG, and writes it to disk.
     *
     * Returns `true` on success. The Rust side logs `capture_ok frame=<n>
     * ts=<ms> dims=<W>x<H> bytes=<n> mean_rgb=<r>,<g>,<b>` which the device
     * driver greps for.
     *
     * Synchronous — blocks until `vkQueueWaitIdle` completes.
     */
    external fun renderCaptureFrame(
        path: String,
        r: Float,
        g: Float,
        b: Float,
        a: Float
    ): Boolean

    /**
     * M2-S07: capture a single frame as PNG at `path`, with shaped text
     * composited on top.
     *
     * Renders one clear-color frame (RGBA in [0.0, 1.0]), reads it back via
     * the M2-S05 readback pipeline, then:
     *  - Discovers system fonts via `ASystemFontIterator` (NDK API 29+)
     *    or `/system/fonts/` directory scan as fallback.
     *  - Loads them into a `cosmic_text::FontSystem`.
     *  - Shapes `text` (e.g. `"Hello, 世界"`) — Latin from Roboto/Sans-Serif,
     *    CJK fallback from Noto Sans CJK.
     *  - Rasterizes each glyph via swash and alpha-blends the resulting
     *    bitmap onto the captured RGBA buffer at `(baselineX, baselineY)`
     *    in white.
     *  - Encodes the modified buffer as PNG.
     *
     * Returns `true` on success. The Rust side logs `capture_ok` (M2-S05
     * schema) AND `font_render_ok via=… fonts_loaded=… glyphs_total=…
     * composed_pixels=…` which the device driver greps for. The driver
     * additionally checks the resulting PNG for non-magenta pixels in the
     * expected glyph band.
     *
     * Synchronous — blocks until the PNG is fully flushed to disk.
     */
    external fun renderCaptureFrameWithText(
        path: String,
        r: Float,
        g: Float,
        b: Float,
        a: Float,
        text: String,
        fontSizePx: Float,
        baselineX: Float,
        baselineY: Float
    ): Boolean

    // ── Static glyph grid (M2-S08) ──────────────────────────────────────────
    //
    // Drives the GPU pipeline in `crates/android-host/src/static_grid.rs`
    // which mirrors `warp-src/crates/warpui/src/platform/android/static_grid.rs`.
    // The pipeline pre-rasterizes glyphs into a 1024×1024 R8 atlas, builds a
    // per-instance vertex buffer (one entry per glyph in the grid), and
    // draws all `rows × cols × glyphs_per_string` instances in a single
    // `vkCmdDraw` call per frame. Targets 60fps p95<16.6ms on Galaxy S24
    // Ultra (Adreno 750) for the M2a Acceptance #1 gate.

    /**
     * Initializes the static glyph grid. Pre-rasterizes glyphs into the GPU
     * atlas + builds the per-instance vertex buffer + creates the pipeline.
     * Idempotent — calling again replaces any prior grid.
     *
     * Synchronous; call from a non-rendering thread or before starting the
     * Choreographer loop.
     *
     * Returns `true` on success; logs `static_grid_init_ok dt_ms=… text=…
     * rows=… cols=… atlas_glyphs=… instances=…` which the test driver greps.
     */
    external fun renderInitStaticGrid(
        text: String,
        fontSizePx: Float,
        rows: Int,
        cols: Int,
        cellWPx: Float,
        cellHPx: Float
    ): Boolean

    /**
     * Submits one grid frame: clear → draw all instances → present. Returns
     * `true` on successful `vkQueuePresentKHR`. If no grid is initialized,
     * falls back to a clear-color frame.
     *
     * The Rust side logs `present_ok frame=N ts=M` per successful present,
     * the same schema as `renderClearFrame`, so the existing
     * `tools/scripts/test-render-scene.sh` parser is reusable.
     */
    external fun renderDrawGridFrame(r: Float, g: Float, b: Float, a: Float): Boolean

    /** True iff a static grid has been successfully attached. */
    external fun renderStaticGridAttached(): Boolean

    /**
     * Returns "atlas_glyphs=N,glyphs_per_frame=N,rows=N,cols=N,text=…" if a
     * grid is attached, empty string otherwise. Used by the M2-S08 driver to
     * round-trip diagnostic info into the result JSON.
     */
    external fun renderStaticGridStats(): String

    // ── M3-S08: per-cell dynamic grid (terminal_mode renderer) ──────────────
    //
    // Mirrors `renderDrawGridFrame` / `renderStaticGridAttached` /
    // `renderStaticGridStats` but for the dynamic grid pipeline that
    // `terminalTakeDirtyAndPushFrame` initializes from the per-cell terminal
    // model (see `crates/android-host/src/dynamic_grid.rs`).
    //
    // The Choreographer frame callback (MainActivity.frameCallback) calls
    // `renderDrawDynamicGridFrame` on the no-dirty-bit path so the surface
    // keeps presenting the last per-cell snapshot instead of going to
    // clear-color between dirty pushes.

    /**
     * Submit one dynamic-grid present (no re-init). Returns true on
     * successful `vkQueuePresentKHR`; false on no-grid-attached or transient
     * Vulkan failure (caller falls back to clear).
     */
    external fun renderDrawDynamicGridFrame(r: Float, g: Float, b: Float, a: Float): Boolean

    /** True iff a dynamic grid has been initialized. */
    external fun renderDynamicGridAttached(): Boolean

    /**
     * Returns "atlas_glyphs=N,glyph_quads=N,bg_quads=N,rows=N,cols=N" if a
     * dynamic grid is attached, empty string otherwise. Used by the M3-S08
     * driver to round-trip diagnostic info into the result JSON.
     */
    external fun renderDynamicGridStats(): String

    // ── IME composing-text state machine (M2-S10) ───────────────────────────
    //
    // Drives the state machine in `crates/android-host/src/ime.rs` (which
    // mirrors `warp-src/crates/warpui/src/platform/android/ime.rs`). Called
    // from `WarpInputView.WarpInputConnection` overrides on the View's UI
    // thread.
    //
    // All four entry points are thread-safe (Mutex inside the Rust singleton).
    // Logcat tag: `WarpIme` (Rust target). The M2-S10 driver greps these.

    /**
     * `InputConnection.commitText(text, newCursorPosition)` — finalize text
     * into the buffer. If a composing region is active, the region is replaced
     * atomically (Pinyin candidate-pick path); otherwise the text is committed
     * as a Latin keystroke.
     */
    external fun imeCommitText(text: String, newCursorPosition: Int)

    /**
     * `InputConnection.setComposingText(text, newCursorPosition)` — update
     * the in-progress composing region. Empty text clears the region.
     */
    external fun imeSetComposingText(text: String, newCursorPosition: Int)

    /**
     * `InputConnection.finishComposingText()` — clear composing region. If
     * the region is non-empty, emits a `composing_finish` event. If empty
     * (Gboard known issue: spurious calls between setComposingText and
     * commitText), emits an `empty_finish` marker without double-committing.
     */
    external fun imeFinishComposingText()

    /**
     * Returns IME state machine counters as a comma-separated string:
     * "commit_calls=N,set_composing_calls=N,finish_calls=N,events=N,
     *  latin=N,composing_update=N,composing_commit=N,composing_finish=N,
     *  empty_finish=N,is_composing=B,composing_text=S"
     */
    external fun imeStats(): String

    /** Reset IME state (clear counters + composing region). Driver uses
     *  this between sub-tests. */
    external fun imeReset()

    // ── Touch input + gesture mapping (M2-S11) ──────────────────────────────
    //
    // Drives the state machine in `crates/android-host/src/input.rs` (which
    // mirrors `warp-src/crates/warpui/src/platform/android/input.rs`). Called
    // from `WarpInputView.onTouchEvent` + `GestureDetector.SimpleOnGestureListener`
    // on the View's UI thread.
    //
    // Raw down/up arrive from onTouchEvent ACTION_DOWN / ACTION_UP directly,
    // while tap / long_press / scroll arrive from GestureDetector callbacks.
    // VelocityTracker computes instantaneous velocity on the Java side and
    // forwards it alongside the scroll distance.
    //
    // Logcat tag: `WarpInput` (Rust). The M2-S11 driver greps these.

    /** Raw ACTION_DOWN: finger first touches screen. */
    external fun inputTouchDown(x: Float, y: Float)

    /** Raw ACTION_UP: finger lifts from screen. */
    external fun inputTouchUp(x: Float, y: Float)

    /**
     * Raw ACTION_CANCEL: gesture cancelled by the system (e.g. a parent View
     * intercepted the event stream, or the window lost focus). Emits
     * [InputEvent::TouchCancel] to close the open down sequence so Rust state
     * does not believe the finger is still down.
     */
    external fun inputTouchCancel(x: Float, y: Float)

    /**
     * GestureDetector `onSingleTapConfirmed`: confirmed single tap (fires
     * ~300 ms after ACTION_UP, after double-tap window expires).
     */
    external fun inputTap(x: Float, y: Float)

    /**
     * GestureDetector `onLongPress`: sustained press ≥ long-press timeout.
     * Equivalent to right-click / context-menu trigger.
     */
    external fun inputLongPress(x: Float, y: Float)

    /**
     * GestureDetector `onScroll` + VelocityTracker: drag scroll event.
     *
     * @param x  Current finger X position (px).
     * @param y  Current finger Y position (px).
     * @param dx Distance scrolled on X axis since last scroll event (px).
     * @param dy Distance scrolled on Y axis since last scroll event (px).
     * @param vx Instantaneous X velocity from VelocityTracker (px/s).
     *           Positive vx = finger moves rightward; negative = leftward.
     * @param vy Instantaneous Y velocity from VelocityTracker (px/s).
     *           Positive vy = finger moves DOWNWARD; negative = upward.
     *           (VelocityTracker uses Android screen coordinates: Y axis grows
     *           downward, so a downward swipe yields vy > 0.)
     *           Terminal scroll convention TBD M3 (likely INVERTED).
     */
    external fun inputScroll(x: Float, y: Float, dx: Float, dy: Float, vx: Float, vy: Float)

    /**
     * Returns input state machine counters as a comma-separated string:
     * "touch_down=N,touch_up=N,tap=N,long_press=N,scroll=N,events=N,
     *  last_down_x=F,last_down_y=F,last_up_x=F,last_up_y=F,
     *  last_scroll_vx=F,last_scroll_vy=F"
     */
    external fun inputStats(): String

    /** Reset input state (clear counters + event queue). Driver uses between sub-tests. */
    external fun inputReset()

    // ── WindowInsets render area (M2-S12) ───────────────────────────────────
    //
    // Called from `ViewCompat.setOnApplyWindowInsetsListener` in MainActivity
    // whenever the system reports new inset values (IME up/down, system bars
    // hide/show, rotation). For M2-S12 the Rust side logs and stores the
    // effective viewport so M3 grid rendering can avoid overlapping the IME
    // panel or status bar.
    //
    // Units: physical pixels (same as the Surface / ANativeWindow dimensions).

    /**
     * Inform the Rust render path of the current effective render insets.
     *
     * @param top    Status-bar inset (px) — reserved for system bars at top.
     * @param left   Left system-bar inset (px) — usually 0 in portrait.
     * @param right  Right system-bar inset (px) — usually 0 in portrait.
     * @param bottom IME-panel height (px) when keyboard is visible; or
     *               navigation-bar height when in non-fullscreen mode.
     *               Callers pass `max(ime.bottom, sysBars.bottom)` so the
     *               effective bottom is always the larger of the two (e.g.
     *               in full-screen mode with IME up, there is no nav bar
     *               but IME still encroaches from the bottom).
     */
    external fun setRenderInsets(top: Int, left: Int, right: Int, bottom: Int)

    // ── Terminal model + push_frame (M3-S04) ────────────────────────────────
    //
    // Bridges PTY bytes (M1 backend) → facade-shaped TerminalModel → Vulkan
    // static-grid pipeline (M2 renderer). The model lives in Rust in
    // `crates/android-host/src/terminal_model.rs` (mirror of
    // `warp-src/crates/warp_terminal_mobile_facade/src/render.rs`). The Java
    // side is split into two distinct call sites:
    //
    //   1. `WarpTerminalService.startReadLoop` invokes `terminalInputBytes`
    //      from a coroutine on Dispatchers.IO each time the PTY emits a
    //      chunk. This sets the model dirty bit (atomic).
    //   2. `MainActivity.frameCallback` (Choreographer doFrame, UI thread)
    //      invokes `terminalTakeDirtyAndPushFrame` once per vsync. If the
    //      bit is set, the call snapshots the grid + drives a Vulkan
    //      init_static_grid + submit_grid_frame; otherwise it returns 0
    //      and the existing clear-frame path runs.
    //
    // Logcat tag: `WarpTerminalModel` (Rust). Test drivers grep these:
    //   * `terminalInputBytes cmd_id=… bytes=… ingested=…`
    //   * `terminal_push_frame ok=… text_len=… rows=… cols=…`
    //   * `terminal_resize rows=… cols=…`

    /**
     * M3-S04: forward a PTY chunk to the Rust terminal model. Sets the
     * model's dirty bit so the next Choreographer doFrame call picks it up.
     *
     * @param cmdId  Session identifier (forwarded for logging only — M3-S04
     *               baseline routes ALL chunks into a single global model).
     * @param bytes  Raw PTY output bytes (UTF-8 best-effort decoded inside
     *               Rust; control bytes \r \n \t \b are honored, ESC is
     *               dropped pending M3-S05 ANSI parser).
     * @return Number of bytes ingested (always equals bytes.size on success).
     *         Returns -1 on conversion failure (rare; only on JVM heap
     *         pressure during JByteArray copy).
     */
    external fun terminalInputBytes(cmdId: String, bytes: ByteArray): Int

    /**
     * M3-S04: Choreographer-driven push_frame.
     *
     * If the model dirty bit is set, snapshots the current grid text, calls
     * renderInitStaticGrid (replacing the previous grid), and submits a
     * single Vulkan frame. Returns:
     *   *  1 → frame pushed successfully
     *   *  0 → no dirty buffer; caller falls back to renderClearFrame
     *   * -1 → init/submit failed
     *
     * `fontSizePx`, `rows`, `cols`, `cellWPx`, `cellHPx` mirror the
     * `renderInitStaticGrid` parameters. The Choreographer side reads
     * current grid params from MainActivity state (set at launch via
     * --ef grid_font_size_px / --ei grid_rows etc.).
     */
    external fun terminalTakeDirtyAndPushFrame(
        fontSizePx: Float,
        rows: Int,
        cols: Int,
        cellWPx: Float,
        cellHPx: Float
    ): Int

    /**
     * M3-S04: returns terminal model state as a CSV string for the device
     * driver to round-trip into result JSON without parsing logcat.
     *
     * Schema:
     *   "rows=N,cols=N,cursor_row=N,cursor_col=N,bytes_ingested=N,dirty=B"
     */
    external fun terminalModelStats(): String

    /**
     * Task 2 (#12): returns true if the terminal model is in alternate screen mode.
     */
    external fun terminalIsAltScreen(): Boolean

    /**
     * M3-S04: reshape the terminal model. Called when the SurfaceView
     * dimensions change (rotation, IME show/hide). Existing in-bounds cells
     * are preserved; out-of-bounds cells are dropped. Cursor is clamped.
     */
    external fun terminalResize(rows: Int, cols: Int)

    /**
     * M3-S05: returns SGR + DCS parser counters as a CSV string. The
     * AC#7 device driver reads this after writing colored bytes to the PTY
     * to assert the streaming ANSI/DCS state machine actually parsed them.
     *
     * Schema:
     *   "sgr_apply_count=N,dcs_hook_count=N,dcs_error_count=N,
     *    cur_fg=0xRRGGBBAA,cur_bg=0xRRGGBBAA,cur_attrs=0xNN"
     */
    external fun terminalSgrSummary(): String

    /**
     * M3-S07: returns the current `Vec<Block>` as a JSON array.
     *
     * Schema (each array entry):
     *   {
     *     "id": "session-{n}-{i}",
     *     "start_time_unix_ms": <u64>,
     *     "command": "<string>",
     *     "exit_code": <i32 | null>,
     *     "end_time_unix_ms": <u64 | null>
     *   }
     *
     * Consumed by `tools/scripts/test-block-model.sh` to gate M3 Acceptance
     * #3 (block model start_time + command + exit_code populated correctly).
     */
    external fun terminalBlocksDump(): String

    // ── Scrollback + viewport offset (M3-S09) ───────────────────────────────
    //
    // Drives the scrollback ring + viewport offset on the Rust side. Java
    // calls `terminalSetScrollOffset` from the M2-S11 GestureDetector
    // `onScroll` callback (drag scroll) and from a Choreographer-driven fling
    // decay timer (`onFling` momentum). Rust clamps the request to
    // `scrollback.len()` and sets the dirty flag so the next vsync re-inits
    // the GPU grid with the new viewport.
    //
    // Logcat tag: `WarpTerminalModel` (Rust target). The M3-S09 driver greps
    // `terminal_set_scroll_offset offset=…`.

    /**
     * M3-S09: set viewport scroll offset (rows back into history).
     * 0 = live tail; >0 = scrolled up. Negative values are clamped to 0;
     * over-scroll is clamped to the actual scrollback length on the Rust side.
     *
     * M3-S09 round-2: returns the **actual clamped offset** Rust applied,
     * after Rust caps the request to `scrollback.len()`. Callers that
     * accumulate scroll deltas (drag/fling in [WarpInputView]) MUST
     * assign this return value back into their local `currentScrollOffsetRows`
     * accumulator — otherwise an over-scroll request lets the local state
     * drift above the Rust state and the user has to scroll back the
     * overflow before the viewport visibly moves (codex round-1 finding #1).
     */
    external fun terminalSetScrollOffset(offsetRows: Int): Int

    /**
     * M3-S09: returns scrollback state as a CSV string for the device driver:
     *   "scrollback_len=N,scrollback_max=N,scroll_offset=N"
     */
    external fun terminalScrollbackInfo(): String
}
