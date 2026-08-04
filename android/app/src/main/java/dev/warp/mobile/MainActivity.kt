package dev.warp.mobile

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

/**
 * MainActivity hosts a SurfaceView that backs the Vulkan swapchain (M2-S04).
 *
 * Lifecycle:
 *   - onCreate: starts the FGS (PTY backend, M1 — unchanged), creates a
 *     SurfaceView and registers as its SurfaceHolder.Callback.
 *   - surfaceCreated: passes the Surface to NativeBridge.renderAttachSurface
 *     which drives ANativeWindow_fromSurface + Vulkan init.
 *   - surfaceChanged: re-attaches with the new dimensions (Vulkan recreates
 *     the swapchain internally on next acquire if needed).
 *   - surfaceDestroyed: tears down Vulkan via renderDetachSurface.
 *
 * Render loop: Choreographer.postFrameCallback drives renderClearFrame at
 * vsync (60Hz on most flagships, 120Hz on S24 Ultra). The frame counter is
 * exported via renderFramesPresented for the test driver.
 *
 * Tag for logcat scraping: "WarpRender" (Kotlin) + "WarpVulkan" (Rust).
 */
class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private var renderActive = false
    // M2-S09: track currently-attached surface dimensions. surfaceChanged is
    // *always* called by Android once after surfaceCreated with the initial
    // dimensions, then again only on actual size/format changes. If we
    // re-attach blindly on every surfaceChanged we double-init the Vulkan
    // pipeline (attach + init_static_grid run twice → ~200ms wasted per
    // rotation). Skip the second redundant call by tracking last-attached
    // dims and only re-attaching when they change.
    private var attachedWidth = -1
    private var attachedHeight = -1
    // M2-S08: when true, doFrame calls renderDrawGridFrame instead of
    // renderClearFrame. Toggled by intent extras at launch (`--ez grid_mode
    // true`); a `START_STATIC_GRID` BroadcastReceiver was scoped originally
    // but never landed because the launch-extras path covered every M2/M3
    // driver use-case (M3-S11 housekeeping nit fix 2026-05-01).
    @Volatile
    private var gridMode = false
    @Volatile
    private var gridText: String = "Hello, World"
    @Volatile
    private var gridFontSizePx: Float = 32.0f
    @Volatile
    private var gridRows: Int = 20
    @Volatile
    private var gridCols: Int = 50
    @Volatile
    private var gridCellWPx: Float = 200.0f
    @Volatile
    private var gridCellHPx: Float = 60.0f

    // M3-S04: when true, doFrame consumes the dirty bit on the Rust
    // TerminalModel and pushes a frame whenever PTY output changed. Falls
    // back to renderClearFrame when no dirty buffer (so vsync keeps ticking
    // for swapchain health). Toggled at launch via --ez terminal_mode true.
    @Volatile
    private var terminalMode = false

    // M2-S10: input focus target for IME attachment. SurfaceView cannot
    // receive `onCreateInputConnection`, so we overlay a 1x1 transparent
    // focusable View on top.
    private var warpInputView: WarpInputView? = null
    // M5-S02: keyboard accessory row (Esc/Tab/Ctrl/Alt/arrows + symbols).
    // Visibility + bottom-margin maintained by the WindowInsets listener.
    private var accessoryRow: AccessoryRow? = null
    // V1-prep iteration 25 (2026-05-02): track whether we're in the Compose
    // path so the legacy WindowInsets listener can skip Compose-specific
    // adjustments (Scaffold + imePadding already handle IME inset for the
    // AndroidView and the bottomBar; doing it again here double-counts).
    private var composePath: Boolean = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!renderActive) return
            // M3-S04 — Choreographer-driven push_frame.
            //
            // Mode precedence (most-specific first):
            //   1. terminalMode → consume the Rust TerminalModel dirty bit;
            //      if dirty, push a frame from the snapshot text. Otherwise
            //      drop through to a clear-frame so vsync keeps the
            //      swapchain healthy (per Choreographer.FrameCallback
            //      contract: <https://developer.android.com/reference/android/view/Choreographer.FrameCallback>).
            //   2. gridMode → static grid (M2-S08 baseline; unchanged).
            //   3. neither → clear-only (M2-S04 baseline).
            //
            // The terminalMode path falls back to gridMode if dirty=0 AND
            // a static grid was previously initialized, so the user always
            // sees text not just a magenta wash between PTY chunks.
            val ok = try {
                when {
                    terminalMode -> {
                        val pushResult = NativeBridge.terminalTakeDirtyAndPushFrame(
                            gridFontSizePx, gridRows, gridCols, gridCellWPx, gridCellHPx
                        )
                        when (pushResult) {
                            // M3-S08: dirty bit set; the JNI re-initialized the
                            // dynamic_grid + presented one frame.
                            1 -> true
                            // No-dirty fallback: re-present the last dynamic_grid
                            // snapshot so the user keeps seeing the per-cell text
                            // instead of a clear-color frame between PTY chunks.
                            // Black clear matches the bg of the in-flight cells.
                            0 -> NativeBridge.renderDrawDynamicGridFrame(0.0f, 0.0f, 0.0f, 1.0f)
                            else -> false
                        }
                    }
                    gridMode -> NativeBridge.renderDrawGridFrame(1.0f, 0.0f, 1.0f, 1.0f)
                    else -> NativeBridge.renderClearFrame(1.0f, 0.0f, 1.0f, 1.0f)
                }
            } catch (t: Throwable) {
                false
            }
            if (!ok) {
                // VK_ERROR_OUT_OF_DATE_KHR or transient: skip + retry next vsync.
                Log.d(TAG, "render frame returned false @ ${SystemClock.uptimeMillis()}")
            }
            // Schedule next frame (Choreographer is one-shot).
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * M2-S08: initialize + start the static-grid render path.
     *
     * Called from `onCreate` when launched with `--ez grid_mode true`
     * (+ grid params). The grid init is idempotent on the Rust side, so
     * repeated calls are safe; a `START_STATIC_GRID` broadcast was scoped
     * but never implemented because the launch-extras driver path covered
     * every M2/M3 use-case (M3-S11 housekeeping nit fix 2026-05-01).
     *
     * Logs `static_grid_started rows=… cols=… text=…` for the driver to grep.
     */
    @Synchronized
    fun startStaticGrid(
        text: String,
        fontSizePx: Float,
        rows: Int,
        cols: Int,
        cellWPx: Float,
        cellHPx: Float
    ) {
        gridText = text
        gridFontSizePx = fontSizePx
        gridRows = rows
        gridCols = cols
        gridCellWPx = cellWPx
        gridCellHPx = cellHPx
        if (!renderActive) {
            Log.w(TAG, "startStaticGrid: renderActive=false — surface not yet attached; will retry on surfaceCreated")
            gridMode = true
            return
        }
        val initOk = try {
            NativeBridge.renderInitStaticGrid(
                text, fontSizePx, rows, cols, cellWPx, cellHPx
            )
        } catch (t: Throwable) {
            Log.w(TAG, "renderInitStaticGrid JNI fallback: ${t.message}")
            false
        }
        Log.i(
            TAG,
            "renderInitStaticGrid ok=$initOk text=\"$text\" rows=$rows cols=$cols " +
                "cell=${cellWPx}x${cellHPx}px font_size_px=$fontSizePx"
        )
        if (initOk) {
            gridMode = true
            val stats = try {
                NativeBridge.renderStaticGridStats()
            } catch (t: Throwable) {
                "stats-unavailable"
            }
            Log.i(TAG, "static_grid_started $stats")
        }
    }

    // M2-S05: the CAPTURE_FRAME broadcast is handled by the manifest-registered
    // [CaptureFrameReceiver] — runtime-registered receivers don't reliably
    // match `am broadcast` from `shell` UID on Android 14+. The receiver
    // calls into [NativeBridge.renderCaptureFrame] directly, which serializes
    // against the Choreographer per-vsync `renderClearFrame` calls via the
    // swapchain mutex inside the Rust crate.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // M4-S05: kick off bootstrap atomic extraction on a background thread.
        // First launch extracts ~50 MB to /data/data/dev.warp.mobile/files/usr;
        // subsequent launches short-circuit on sha-pin match (~instant).
        //
        // Codex round-1 finding 2: first launch must show install progress
        // (per `.omc/prd.json` M4-S05 AC#5). We probe the sha-pin file to
        // detect first-launch vs subsequent: pin file absent = first launch
        // = show indeterminate Toast; pin file present = no UI (sha-pin
        // fast path is ~10ms and shouldn't flash UI).
        val pinFile = File("${applicationInfo.dataDir}/files/.bootstrap-version.json")
        val isFirstLaunch = !pinFile.exists()
        if (isFirstLaunch) {
            Toast.makeText(this, "Installing Termux runtime…", Toast.LENGTH_LONG).show()
        }
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        GlobalScope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val status = try { NativeBridge.bootstrapInstall(assets, applicationInfo.dataDir) } catch (t: Throwable) { -1 }
            val elapsed = System.currentTimeMillis() - t0
            Log.i(
                "warp.bootstrap",
                "M4-S05 bootstrapInstall: status=$status elapsedMs=$elapsed dataDir=${applicationInfo.dataDir}"
            )
            if (isFirstLaunch) {
                val msg = if (status == 0) {
                    "Termux runtime installed (${elapsed} ms)"
                } else {
                    "Termux runtime install failed: status=$status"
                }
                runOnUiThread {
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
        }

        // Keep the screen on while this Activity is in the foreground.
        // Same flag YouTube/Netflix/etc. use — survives Samsung One UI's
        // power-policy overrides that defeat `adb shell svc power stayon`
        // and `wm dismiss-keyguard`. Only effective while Activity is at
        // the top of the stack; system reclaims power management once the
        // user backgrounds us. Fixes M2-S05 round-2 manual unlock loop.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // M2-S12: edge-to-edge — app draws content under system bars.
        // We read insets via ViewCompat.setOnApplyWindowInsetsListener to
        // reserve the bottom region for the IME panel and top for the
        // status bar. Android 15+ enforces edge-to-edge for targetSdk 35+;
        // applying it explicitly here ensures consistent behavior across
        // API 31-36 (Plan Amendment 3 minSdk 31).
        // Ref: https://developer.android.com/develop/ui/views/layout/edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // POST_NOTIFICATIONS for FGS (M1 carry-over, unchanged).
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                1001
            )
        }

        // Start the FGS so PTY backend is reachable for M1+M2 integration.
        startForegroundService(Intent(this, WarpTerminalService::class.java))

        // M2-S10: composite layout = SurfaceView (rendering, full screen)
        // + WarpInputView overlay (1x1, transparent, focusable in touch mode)
        // for IME attachment. SurfaceView's surface is on a separate Z-layer
        // and the framework's IME-routing assumes the focused View also owns
        // the visible content, so we host an invisible focusable View on top.
        val frame = FrameLayout(this)
        val surfaceView = SurfaceView(this).apply {
            setZOrderMediaOverlay(true)
        }
        frame.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        warpInputView = WarpInputView(this).apply {
            // alpha=0: transparent so it doesn't obscure the SurfaceView render
            // output (alpha=0 on Android 5+ doesn't skip layout/measurement).
            // M2-S11: MATCH_PARENT so the View covers the full screen and
            // receives all touch events (WarpInputView.onTouchEvent). A 1x1
            // size would only capture taps in the top-left pixel; MATCH_PARENT
            // captures taps anywhere on screen and is still invisible.
            alpha = 0f
        }
        frame.addView(
            warpInputView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // M5-S02: AccessoryRow overlay. Lives at the BOTTOM of the FrameLayout
        // (gravity bottom). When IME is hidden, GONE. When IME shown, the
        // WindowInsets listener below reveals it + sets bottom margin =
        // ime.bottom so the row sits just above the IME panel. Touch
        // priority: AccessoryRow needs to receive button clicks BEFORE
        // warpInputView's MATCH_PARENT touch capture, so we add it AFTER
        // warpInputView (later children of FrameLayout sit higher in
        // Z-order and get touch dispatch first).
        accessoryRow = AccessoryRow(this).apply {
            visibility = View.GONE
        }
        warpInputView?.accessoryRow = accessoryRow
        frame.addView(
            accessoryRow,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )

        // M7 (iteration 20 — Warp UX scaffold): wrap the existing FrameLayout
        // (SurfaceView + WarpInputView + AccessoryRow) in a Jetpack Compose
        // ModalNavigationDrawer scaffold that gives us the Warp Desktop
        // shape — top bar with hamburger / search / new-tab / settings, left
        // drawer with tab list + search filter, bottom prompt-composer with
        // model picker. Compose hosts the FrameLayout via AndroidView so
        // every M0–M6 engine investment (Vulkan renderer, IME, gestures,
        // AccessoryRow) keeps working unchanged.
        //
        // For now there's exactly one tab — the launcher-default
        // "terminal_mode" cmd_id. M7-S06 will wire multi-tab; M9 will wire
        // the prompt composer to the BYOK agent client. This commit is the
        // structural shape only.
        if (intent.getBooleanExtra("legacy_layout", false)) {
            // Driver-mode escape hatch: device-test scripts that depend on
            // the SurfaceView living at the root of the content view (no
            // Compose top bar pushing it down) can opt out via
            // `--ez legacy_layout true`. Production users always go through
            // the new Compose path.
            setContentView(frame)
            composePath = false
        } else {
            composePath = true
            // Compose path: AccessoryRow's WindowInsets listener never
            // fires (frame is wrapped in AndroidView), so we override
            // visibility to VISIBLE here. Termux-style: modifier keys
            // always available regardless of IME state.
            accessoryRow?.visibility = View.VISIBLE
            val composeView = androidx.compose.ui.platform.ComposeView(this).apply {
                setContent {
                    dev.warp.mobile.ui.WarpAppTheme {
                        // V1-prep iteration 25 (2026-05-02): in Compose
                        // path, AccessoryRow stays inside `frame` (the
                        // AndroidView wrapped by Scaffold's content slot).
                        // Scaffold + imePadding on the bottomBar shrink
                        // the AndroidView so its bottom edge sits just
                        // above the prompt composer + IME; AccessoryRow
                        // gravity=BOTTOM places it at that edge naturally,
                        // so we DON'T touch its bottomMargin in Compose
                        // path (the legacy listener does that for the
                        // legacy_layout escape hatch only). Visibility is
                        // still IME-driven via the listener at MainActivity
                        // line ~454.
                        val appState by SessionManager.getInstance(this@MainActivity).appState.collectAsState()
                        val tabs = remember {
                            listOf(
                                dev.warp.mobile.ui.WarpTab(
                                    id = "terminal_mode",
                                    title = "New agent conversation",
                                    cwd = "~"
                                )
                            )
                        }
                        dev.warp.mobile.ui.WarpScaffold(
                            tabs = tabs,
                            activeTabId = "terminal_mode",
                            blocks = appState.blocks,
                            isRawMode = appState.isRawMode,
                            onTabSelected = { _ -> /* M7-S06 multi-tab wiring */ },
                            onNewTab = { /* M7-S06 multi-tab wiring */ },
                            onSettings = {
                                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                            },
                            onPromptSubmit = { promptText ->
                                val activeProfile = dev.warp.mobile.ai.ModelProfileRepository.getActiveProfile(this@MainActivity)
                                dev.warp.mobile.ai.CommandApprovalManager.requestApproval(
                                    context = this@MainActivity,
                                    command = promptText,
                                    model = activeProfile.id
                                ) { approved ->
                                    if (approved) {
                                        WarpInputView.writeBytesToActivePty(
                                            this@MainActivity,
                                            (promptText + "\n").toByteArray(Charsets.UTF_8)
                                        )
                                    }
                                }
                            },
                            onRerunBlock = { block ->
                                val activeId = appState.activeSessionId ?: "terminal_mode"
                                val payload = (block.command + "\r").toByteArray(Charsets.UTF_8)
                                val intent = Intent(WarpTerminalService.ACTION_WRITE).apply {
                                    component = ComponentName(packageName, "$packageName.PtyBroadcastReceiver")
                                    putExtra("cmd_id", activeId)
                                    putExtra("data", payload)
                                }
                                sendBroadcast(intent)
                            },
                            onExplainBlock = { block ->
                                SessionManager.getInstance(this@MainActivity).insertExplanationCard(block)
                            },
                            onShareBlock = { block ->
                                BlockShareManager.shareBlock(this@MainActivity, block)
                            }
                        ) { innerPadding ->
                            dev.warp.mobile.ui.TerminalCanvas(
                                modifier = androidx.compose.ui.Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding),
                                isRawMode = appState.isRawMode,
                                terminalMode = terminalMode,
                                gridFontSizePx = gridFontSizePx,
                                gridCellWPx = gridCellWPx,
                                gridCellHPx = gridCellHPx,
                                gridRows = gridRows,
                                gridCols = gridCols,
                                customView = frame
                            )
                        }
                    }
                }
            }
            setContentView(composeView)
        }
        if (!composePath) {
            surfaceView.holder.addCallback(this)
        }
        warpInputView!!.requestFocus()
        // M2-S10: publish the input view to companion object so the
        // ImeSimulationReceiver can route driver broadcasts through the
        // real WarpInputConnection.
        activeWarpInputView = warpInputView

        // M2-S12: WindowInsets listener. Listens on the root FrameLayout
        // so we receive *every* inset change — IME up/down, system bars
        // show/hide (including the fullscreen-toggle below), and rotation.
        //
        // Why root layout vs SurfaceView/WarpInputView: ViewCompat.setOn-
        // ApplyWindowInsetsListener on SurfaceView is unreliable — the
        // SurfaceView surface is on a separate Z-layer and the framework
        // may not propagate window insets to it. The root FrameLayout is a
        // normal View and always receives inset dispatches first per
        // WindowInsets traversal rules (parent → child).
        //
        // IME bottom vs system-bars bottom: we pass max(ime.bottom,
        // sysBars.bottom) so the Rust side always gets the effective bottom
        // reservation. In fullscreen mode sysBars.bottom=0 (nav bar hidden)
        // and ime.bottom reflects only the keyboard height; in non-fullscreen
        // normal mode sysBars.bottom is the nav-bar height and ime.bottom is
        // 0 when the keyboard is hidden — both collapse to the right value.
        //
        // Refs (M2-S12, 2026-04-30; M3-S11 nit fix 2026-05-01 — stale
        // /develop/ui/views/layout/insets/handle-ime-keyboard-visibility URL
        // replaced with the canonical /touch-and-input/keyboard-input/visibility
        // landing page that the Android team currently maintains; the old path
        // 302-redirects but produces a "page not found" inline doc on Android
        // Studio's hover preview):
        //   https://developer.android.com/reference/androidx/core/view/WindowInsetsCompat
        //   https://developer.android.com/develop/ui/views/layout/edge-to-edge
        //   https://developer.android.com/reference/androidx/core/view/ViewCompat#setOnApplyWindowInsetsListener(android.view.View,androidx.core.view.OnApplyWindowInsetsListener)
        //   https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/visibility
        ViewCompat.setOnApplyWindowInsetsListener(frame) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // bottom = max of IME height and nav-bar height (whichever is larger).
            val effectiveBottom = maxOf(ime.bottom, sysBars.bottom)
            Log.i(
                TAG,
                "window_insets ime.bottom=${ime.bottom} " +
                    "sysBars={top=${sysBars.top} l=${sysBars.left} r=${sysBars.right} b=${sysBars.bottom}} " +
                    "effectiveBottom=$effectiveBottom"
            )
            // V1-prep iteration 25 (2026-05-02): only setRenderInsets in
            // legacy_layout path. Compose path's Scaffold already shrinks
            // the AndroidView (and therefore the SurfaceView) for IME +
            // bottomBar, so doing it again here would double-count and
            // collapse the visible terminal area to ~0 px (the bug from
            // iteration-25 first attempt).
            if (!composePath) {
                try {
                    NativeBridge.setRenderInsets(sysBars.top, sysBars.left, sysBars.right, effectiveBottom)
                } catch (t: Throwable) {
                    Log.w(TAG, "setRenderInsets JNI fallback: ${t.message}")
                }
            }

            // M5-S02: accessory row visibility + position. When IME is up
            // (ime.bottom > 0), show the row just above the IME panel by
            // setting the bottom margin to ime.bottom. When IME is down,
            // hide the row entirely (no value in showing modifier keys
            // when there's no soft keyboard to modify).
            //
            // V1-prep iteration 25: in Compose path, the bottom margin is
            // ALREADY handled by Scaffold + imePadding on the bottomBar
            // (AccessoryRow gravity=BOTTOM in `frame` puts it at the bottom
            // of the AndroidView, which Compose has already shrunk to sit
            // above the IME). Setting bottomMargin = ime.bottom here would
            // push the row above its container by an extra ime.bottom px
            // → "row floats at top of screen" bug. So skip the margin
            // assignment in Compose path; only update visibility.
            accessoryRow?.let { row ->
                if (ime.bottom > 0) {
                    if (!composePath) {
                        val lp = row.layoutParams as FrameLayout.LayoutParams
                        if (lp.bottomMargin != ime.bottom) {
                            lp.bottomMargin = ime.bottom
                            row.layoutParams = lp
                        }
                    }
                    if (row.visibility != View.VISIBLE) {
                        row.visibility = View.VISIBLE
                    }
                } else {
                    if (row.visibility != View.GONE) {
                        row.visibility = View.GONE
                    }
                }
            }

            insets
        }

        // M2-S12: fullscreen mode — hide nav bar + status bar.
        // Triggered by --ez fullscreen true on launch intent. In fullscreen
        // mode BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE brings them back
        // temporarily on an inward swipe from the edge, then auto-hides.
        //
        // Refs (M2-S12, 2026-04-30):
        //   https://developer.android.com/reference/androidx/core/view/WindowInsetsControllerCompat
        //   https://developer.android.com/develop/ui/views/layout/immersive
        // V1-prep blocker #2 follow-up (2026-05-02): the renderer does not
        // currently honor `setRenderInsets.top` for the dynamic grid (it stores
        // the value but never reads it back), so row 0 of the terminal grid
        // would otherwise sit under the status bar. The pragmatic v1.0 fix is
        // to default to fullscreen on the plain launcher Intent — same pattern
        // Termux uses. The status bar is still summonable via swipe-down for
        // notifications. Driver-style intents that do NOT set fullscreen=true
        // explicitly stay non-fullscreen so device tests continue to interact
        // with system UI normally.
        // Detect "plain launcher tap" the same way the terminal_mode block
        // below does: no terminal_mode, no grid_mode, no driver-style extras.
        val launcherFullscreenDefault =
            !intent.getBooleanExtra("terminal_mode", false) &&
                !intent.getBooleanExtra("grid_mode", false) &&
                (intent.extras?.let { ex ->
                    ex.keySet().none { it.startsWith("grid_") || it == "terminal_cmd" }
                } ?: true)
        if (intent.getBooleanExtra("fullscreen", launcherFullscreenDefault)) {
            val controller = WindowInsetsControllerCompat(window, frame)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            Log.i(
                TAG,
                "fullscreen mode applied: systemBars hidden, transient-swipe behavior set " +
                    "(launcher_default=$launcherFullscreenDefault)"
            )
        }

        val ping = try { NativeBridge.ping() } catch (t: Throwable) { "JNI-absent" }
        Log.i(TAG, "MainActivity ready ping=$ping input_focus=${warpInputView!!.isFocused}")

        // M2-S08: optional grid mode via launch intent extras. Driver uses
        //   am start -n dev.warp.mobile/.MainActivity \
        //     --ez grid_mode true \
        //     --es grid_text "Hello, World" \
        //     --ef grid_font_size_px 32.0 \
        //     --ei grid_rows 20 --ei grid_cols 50 \
        //     --ef grid_cell_w_px 200.0 --ef grid_cell_h_px 60.0
        if (intent.getBooleanExtra("grid_mode", false)) {
            // Text precedence (CJK / space-resilient, mirrors M2-S07
            // CaptureFrameReceiver):
            //   1. `grid_text_b64` extra (base64-encoded UTF-8) — driver-friendly,
            //      avoids `am start --es` losing whitespace/multi-byte chars
            //      when relayed through adb shell.
            //   2. `grid_text` extra (plain string) — works for ASCII tests
            //      without spaces.
            //   3. Default "Hello, World".
            val textB64 = intent.getStringExtra("grid_text_b64")
            val textExtra = intent.getStringExtra("grid_text")
            val text = when {
                textB64 != null -> {
                    try {
                        String(android.util.Base64.decode(textB64, android.util.Base64.DEFAULT), Charsets.UTF_8)
                    } catch (e: Exception) {
                        Log.w(TAG, "grid_text_b64 decode failed (${e.message}); using default")
                        "Hello, World"
                    }
                }
                !textExtra.isNullOrBlank() -> textExtra
                else -> "Hello, World"
            }
            val fontSize = intent.getFloatExtra("grid_font_size_px", 32.0f)
            val rows = intent.getIntExtra("grid_rows", 20)
            val cols = intent.getIntExtra("grid_cols", 50)
            val cellW = intent.getFloatExtra("grid_cell_w_px", 200.0f)
            val cellH = intent.getFloatExtra("grid_cell_h_px", 60.0f)
            Log.i(TAG, "grid_mode requested at launch text=\"$text\" rows=$rows cols=$cols")
            // Mark gridMode so surfaceCreated will init once the surface arrives.
            gridText = text
            gridFontSizePx = fontSize
            gridRows = rows
            gridCols = cols
            gridCellWPx = cellW
            gridCellHPx = cellH
            gridMode = true
        }

        // M3-S04: terminal_mode at launch. Driver path uses
        //   am start -n dev.warp.mobile/.MainActivity \
        //     --ez terminal_mode true \
        //     --ef grid_font_size_px 32.0 \
        //     --ei grid_rows 24 --ei grid_cols 80 \
        //     --ef grid_cell_w_px 24.0 --ef grid_cell_h_px 40.0
        //
        // V1-prep blocker #1 fix (2026-05-02): the plain launcher Intent
        // (action=MAIN/category=LAUNCHER, no extras) ALSO triggers
        // terminal_mode now. Without this, tapping the app icon led to a
        // magenta clear-color surface with no terminal — useless for end
        // users. We treat any launch where neither grid_mode nor
        // terminal_mode is explicitly set AND no driver flag is present
        // as the launcher path → default-on terminal_mode + auto-spawn
        // the default shell.
        val explicitTerminalMode = intent.getBooleanExtra("terminal_mode", false)
        val launcherDefaultTerminal =
            !explicitTerminalMode &&
                !intent.getBooleanExtra("grid_mode", false) &&
                intent.extras?.let { ex ->
                    // No driver-style extras → plain launcher tap.
                    ex.keySet().none { it.startsWith("grid_") || it == "terminal_cmd" }
                } ?: true
        if (explicitTerminalMode || launcherDefaultTerminal) {
            terminalMode = true
            gridFontSizePx = intent.getFloatExtra("grid_font_size_px", 32.0f)
            // V1-prep blocker #2 fix (2026-05-02): cell defaults stay 24×40 px
            // (good for the 32 px font on flagship density) BUT rows/cols are
            // computed from the actual surface DisplayMetrics instead of the
            // hardcoded 80×24 VT100 grid that overflowed every portrait
            // device (1920×960 vs 1080×2340). M3-S08's renderer-side dynamic-
            // grid path already takes the rows/cols we send via
            // terminalResize, so this end-to-end produces a grid that fills
            // the visible region without clipping.
            gridCellWPx = intent.getFloatExtra("grid_cell_w_px", 24.0f)
            gridCellHPx = intent.getFloatExtra("grid_cell_h_px", 40.0f)
            val dm = resources.displayMetrics
            // Reserve ~2 cell-rows of vertical chrome (action bar + bottom
            // accessory row when IME is up). The renderer's setRenderInsets
            // will paint inside the inset-aware region, so we only need to
            // make sure the grid's logical row count is small enough that
            // bottom rows aren't clipped on common devices.
            val chromeRowsReserve = 4
            val derivedRows = maxOf(8, (dm.heightPixels / gridCellHPx).toInt() - chromeRowsReserve)
            val derivedCols = maxOf(20, (dm.widthPixels / gridCellWPx).toInt())
            gridRows = intent.getIntExtra("grid_rows", derivedRows)
            gridCols = intent.getIntExtra("grid_cols", derivedCols)
            warpInputView?.setCellHeightPx(gridCellHPx)
            warpInputView?.resetScroll()
            try {
                NativeBridge.terminalResize(gridRows, gridCols)
            } catch (t: Throwable) {
                Log.w(TAG, "terminalResize JNI fallback: ${t.message}")
            }
            Log.i(
                TAG,
                "terminal_mode rows=$gridRows cols=$gridCols " +
                    "font_size_px=$gridFontSizePx cell=${gridCellWPx}x${gridCellHPx}px " +
                    "screen=${dm.widthPixels}x${dm.heightPixels} " +
                    "source=${if (explicitTerminalMode) "explicit" else "launcher_default"}"
            )

            val terminalCmd = intent.getStringExtra("terminal_cmd")
            val cmdId = intent.getStringExtra("terminal_cmd_id") ?: "terminal_mode"
            // Launcher-default path: spawn whatever WarpTerminalService picks
            // by default ($PREFIX/bin/zsh if extracted, else /system/bin/sh).
            // Driver path: honor explicit terminal_cmd. When neither is
            // present, still spawn the default — the user just tapped the
            // icon and expects a working terminal.
            val spawnProgram = terminalCmd?.takeIf { it.isNotBlank() }
            val spawnIntent = Intent(WarpTerminalService.ACTION_SPAWN).apply {
                setPackage(packageName)
                putExtra("cmd_id", cmdId)
                if (spawnProgram != null) {
                    putExtra("program", spawnProgram)
                }
                // V1-prep iteration 27 (2026-05-02): pass initial winsize
                // so zsh starts with the correct rows/cols. The renderer's
                // dynamic_grid was already sized via NativeBridge.terminalResize
                // a few lines above; PTY winsize must match or zsh wraps
                // lines at the wrong column → "5"/"e" garbage glyphs at row
                // 0 + typing overwrites prior output (M3-S08 dynamic_grid
                // assumes the cell snapshot from TerminalModel matches
                // zsh's view of the terminal).
                putExtra("rows", gridRows)
                putExtra("cols", gridCols)
            }
            sendBroadcast(spawnIntent)
            Log.i(
                TAG,
                "terminal_mode auto-spawn cmd_id=$cmdId program=${spawnProgram ?: "<service-default>"}"
            )

            // V1-prep: launcher path wants the IME up by default so the user
            // can type immediately after tapping the icon. Driver path leaves
            // ime_mode handling to its own extra (preserved below).
            if (launcherDefaultTerminal) {
                warpInputView?.postDelayed({
                    warpInputView?.requestFocus()
                    val imm =
                        getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showSoftInput(warpInputView, InputMethodManager.SHOW_IMPLICIT)
                    Log.i(TAG, "launcher path: auto-show IME requested")
                }, 250)
            }

            val initialInput = intent.getStringExtra("terminal_initial_input")
            if (!initialInput.isNullOrEmpty()) {
                warpInputView?.postDelayed({
                    val writeIntent = Intent(WarpTerminalService.ACTION_WRITE).apply {
                        setPackage(packageName)
                        putExtra("cmd_id", cmdId)
                        putExtra("data", initialInput)
                    }
                    sendBroadcast(writeIntent)
                    Log.i(TAG, "terminal_mode auto-input cmd_id=$cmdId bytes=${initialInput.length}")
                }, 200)
            }
        }

        // M2-S10: optional auto-show IME on launch. Driver uses
        //   am start --ez ime_mode true
        // to request the soft keyboard popup so logcat captures
        // setComposingText/commitText events end-to-end.
        //
        // M3-S11 nit fix (2026-05-01): switched the primary path to
        // `WindowInsetsControllerCompat.show(Type.ime())` per Android 11+
        // (API 30) guidance — this is the future-proof, system-aware way
        // to surface the IME (also tracks Type.ime() insets correctly so
        // the listener registered above forwards `ime.bottom` to the Rust
        // renderer without a re-layout race). The legacy
        // `InputMethodManager.showSoftInput` call is kept as a fallback
        // for stricter OEMs (Samsung Knox blocks the controller path on
        // some debug builds — observed in M2-S12 sub-test 1) and for log
        // parity (driver still greps `showSoftInput shown=…`).
        //
        // Refs:
        //   https://developer.android.com/reference/androidx/core/view/WindowInsetsControllerCompat#show(int)
        //   https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/visibility
        if (intent.getBooleanExtra("ime_mode", false)) {
            // Wait for the View to be attached to the window before showing
            // the soft keyboard (otherwise InputMethodManager.showSoftInput
            // returns false silently). post() runs after layout pass.
            warpInputView?.post {
                warpInputView?.requestFocus()
                // Primary (API 30+ canonical): WindowInsetsControllerCompat.
                val controllerShown = try {
                    val controller = WindowInsetsControllerCompat(window, warpInputView!!)
                    controller.show(WindowInsetsCompat.Type.ime())
                    true
                } catch (t: Throwable) {
                    Log.w(TAG, "ime_mode: WindowInsetsControllerCompat.show(ime()) threw: ${t.message}")
                    false
                }
                // Fallback (legacy + Knox quirk): InputMethodManager.showSoftInput.
                val imm =
                    getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                // SHOW_IMPLICIT is preferred over deprecated SHOW_FORCED on
                // API 30+; we always have a focused View at this point.
                val shown = imm?.showSoftInput(warpInputView, InputMethodManager.SHOW_IMPLICIT) ?: false
                Log.i(
                    TAG,
                    "ime_mode requested: controllerShown=$controllerShown showSoftInput shown=$shown focus=${warpInputView?.isFocused} ime_visible_post_call=${imm?.isAcceptingText}"
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        renderActive = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        // M2-S10: clear input-view companion-object pointer; ImeSimulation-
        // Receiver will fall back to direct JNI if a stray broadcast arrives
        // post-destroy.
        if (activeWarpInputView === warpInputView) {
            activeWarpInputView = null
        }
    }

    // ── SurfaceHolder.Callback ───────────────────────────────────────────────

    override fun surfaceCreated(holder: SurfaceHolder) {
        val ts = SystemClock.uptimeMillis()
        Log.i(TAG, "surfaceCreated_ts=$ts")
        // Attach uses the Surface's current dimensions (read inside Rust via
        // ANativeWindow_getWidth/getHeight). We mark attachedWidth=-1 so the
        // first surfaceChanged is treated as a real change and updates our
        // local cache, but we skip the redundant re-attach since this
        // surfaceCreated call already did one.
        attachAndStartRender(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val ts = SystemClock.uptimeMillis()
        Log.i(TAG, "surfaceChanged_ts=$ts width=$width height=$height")
        // M2-S09: skip the redundant double-init. Android calls surfaceChanged
        // exactly once after surfaceCreated with the initial dims (always),
        // then again only on real size/format changes. Re-attaching here on
        // the first call duplicates what surfaceCreated→attachAndStartRender
        // just did and wastes ~80ms on grid_init.
        //
        // Strategy: if renderActive is true and we haven't recorded dims yet
        // (attachedWidth=-1 — fresh attach from surfaceCreated), this is the
        // spurious follow-up surfaceChanged. Just record dims and bail.
        // If dims match the recorded ones, also bail (idempotent).
        // Only re-attach when dims actually changed.
        if (renderActive && attachedWidth == -1 && attachedHeight == -1) {
            // First surfaceChanged after surfaceCreated already attached.
            attachedWidth = width
            attachedHeight = height
            return
        }
        if (renderActive && attachedWidth == width && attachedHeight == height) {
            // No-op: same dims, already attached.
            return
        }
        // Real change: re-attach with fresh dims.
        renderActive = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        attachAndStartRender(holder.surface, width, height)

        // V1-prep iteration 27 (2026-05-02): when the SurfaceView dimensions
        // change (typically: IME shows / hides → Compose Scaffold + imePadding
        // shrinks the AndroidView → SurfaceView resizes → surfaceChanged),
        // recompute the dynamic grid rows/cols and propagate to BOTH the
        // renderer (NativeBridge.terminalResize) AND the PTY (TIOCSWINSZ via
        // PTY_RESIZE broadcast). Without the PTY resize zsh keeps wrapping
        // lines at its old column count → mismatched line wrap with the
        // visible cells → leftover "5"/"e" garbage at row 0 and typing
        // appears to overwrite earlier text.
        if (terminalMode && gridCellWPx > 0 && gridCellHPx > 0) {
            val newRows = maxOf(8, (height / gridCellHPx).toInt())
            val newCols = maxOf(20, (width / gridCellWPx).toInt())
            if (newRows != gridRows || newCols != gridCols) {
                Log.i(
                    TAG,
                    "surfaceChanged → grid resize from " +
                        "${gridRows}x${gridCols} to ${newRows}x${newCols} " +
                        "(surface=${width}x${height} cell=${gridCellWPx}x${gridCellHPx})"
                )
                gridRows = newRows
                gridCols = newCols
                try {
                    NativeBridge.terminalResize(newRows, newCols)
                } catch (t: Throwable) {
                    Log.w(TAG, "terminalResize JNI fallback: ${t.message}")
                }
                // PTY winsize: tell zsh the terminal got resized.
                val resizeIntent = Intent(WarpTerminalService.ACTION_RESIZE).apply {
                    setPackage(packageName)
                    putExtra("cmd_id", "terminal_mode")
                    putExtra("rows", newRows)
                    putExtra("cols", newCols)
                }
                sendBroadcast(resizeIntent)
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val ts = SystemClock.uptimeMillis()
        Log.i(TAG, "surfaceDestroyed_ts=$ts")
        renderActive = false
        attachedWidth = -1
        attachedHeight = -1
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        try {
            NativeBridge.renderDetachSurface()
        } catch (t: Throwable) {
            Log.w(TAG, "renderDetachSurface JNI fallback: ${t.message}")
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun attachAndStartRender(surface: Surface, width: Int = -1, height: Int = -1) {
        val ok = try {
            NativeBridge.renderAttachSurface(surface)
        } catch (t: Throwable) {
            Log.w(TAG, "renderAttachSurface JNI fallback: ${t.message}")
            false
        }
        Log.i(TAG, "renderAttachSurface ok=$ok")
        if (ok) {
            // M2-S09: cache dims so the followup surfaceChanged with the same
            // dims becomes a no-op. width=-1/height=-1 means caller didn't
            // know the dims (surfaceCreated path); the followup surfaceChanged
            // will record the real dims on its first run.
            attachedWidth = width
            attachedHeight = height
            renderActive = true
            // M3-S08: terminal_mode now uses the per-cell dynamic_grid
            // pipeline driven by `terminalTakeDirtyAndPushFrame`. We do NOT
            // pre-init a static_grid here — the very first PTY chunk's
            // dirty-vsync triggers `init_dynamic_grid` from the real
            // TerminalModel cell snapshot. Until that lands, the
            // Choreographer fallback path renders a black clear (matching
            // the eventual cell bg).
            if (terminalMode) {
                Log.i(
                    TAG,
                    "terminal_mode ready (post-surfaceCreated) " +
                        "rows=$gridRows cols=$gridCols " +
                        "cell=${gridCellWPx}x${gridCellHPx}px font_size_px=$gridFontSizePx"
                )
            }
            // M2-S08: if grid mode was requested before surface was ready, do
            // the init now while we have a valid swapchain. The Rust side
            // builds the atlas + pipeline against the current render_pass.
            if (gridMode) {
                val initOk = try {
                    NativeBridge.renderInitStaticGrid(
                        gridText, gridFontSizePx, gridRows, gridCols, gridCellWPx, gridCellHPx
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "renderInitStaticGrid JNI fallback: ${t.message}")
                    false
                }
                Log.i(
                    TAG,
                    "renderInitStaticGrid (post-surfaceCreated) ok=$initOk " +
                        "text=\"$gridText\" rows=$gridRows cols=$gridCols " +
                        "cell=${gridCellWPx}x${gridCellHPx}px font_size_px=$gridFontSizePx"
                )
                if (initOk) {
                    val stats = try {
                        NativeBridge.renderStaticGridStats()
                    } catch (t: Throwable) {
                        "stats-unavailable"
                    }
                    Log.i(TAG, "static_grid_started $stats")
                } else {
                    // Disable grid mode; doFrame will fall back to clear so
                    // we still drive the loop and the driver can detect the
                    // failure via missing static_grid_started line.
                    gridMode = false
                }
            }
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE || event.keyCode == KeyEvent.KEYCODE_TAB) {
            val focused = currentFocus
            if (focused is WarpInputView) {
                return focused.dispatchKeyEvent(event)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        private const val TAG = "WarpRender"

        /**
         * M2-S10: the currently-foregrounded MainActivity's WarpInputView.
         * Set in `onCreate` (after the View is built) and cleared in
         * `onDestroy`. Read by [ImeSimulationReceiver] to forward driver
         * IME-event broadcasts through the real `WarpInputConnection` code
         * path. Volatile because reader and writer are on different threads
         * (broadcast receiver vs UI thread on cold start).
         */
        @Volatile
        var activeWarpInputView: WarpInputView? = null
            private set
    }
}
