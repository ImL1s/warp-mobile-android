package dev.warp.mobile

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.Spanned
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlin.math.abs

/**
 * M2-S10: a focusable, IME-attaching `View` that does NOT render anything
 * (the SurfaceView next to it owns the rendering). Its sole job is to attract
 * `onCreateInputConnection` calls from the system IME and route Java-side
 * `InputConnection` callbacks (`commitText`, `setComposingText`,
 * `finishComposingText`) into the Rust state machine in
 * `crates/android-host/src/ime.rs` via [NativeBridge].
 *
 * ## Why a separate View?
 *
 * `SurfaceView` does not play well with IME — its surface is on a separate
 * Z-layer (window manager hosted), and the framework's input-routing code
 * paths assume the focused View also owns the visible content. So we use a
 * composite layout: SurfaceView for render + invisible WarpInputView (size
 * 1x1, alpha 0) overlay, with `setFocusableInTouchMode(true)` so it captures
 * focus and the IME attaches to it.
 *
 * ## State machine
 *
 * The Rust side maintains the composing-text buffer (see `ime.rs` for the
 * full state machine). This Kotlin side is essentially a transparent shim:
 * every IC override forwards the args to JNI, then calls super (so that
 * `BaseInputConnection`'s own bookkeeping — relevant for delete/getTextBefore
 * etc. on subsequent IME queries — stays consistent).
 *
 * ## M2-S11 addition: onTouchEvent + GestureDetector
 *
 * WarpInputView is 1×1 px overlaying the SurfaceView; however, in the
 * FrameLayout composite layout it sits at the top of the Z-order and
 * `isClickable = true` means it receives touch events first (before
 * SurfaceView). We override `onTouchEvent` here to:
 *
 *   1. Emit raw `ACTION_DOWN` / `ACTION_UP` → `NativeBridge.inputTouchDown/Up`.
 *   2. Feed the event to a `GestureDetector` for tap/long-press detection.
 *   3. Feed the event to `VelocityTracker` so scroll events carry instantaneous
 *      velocity (positive vy = finger moves DOWNWARD in screen coordinates).
 *
 * **Why WarpInputView gets touch, not SurfaceView**: SurfaceView is on a
 * separate Z-layer (window-manager-hosted surface). Touch dispatch walks the
 * View hierarchy, not the Z-order of surfaces. WarpInputView is a regular View
 * at z-position 1 (added second to FrameLayout), so it sits above SurfaceView
 * for hit-testing purposes. `isClickable=true` ensures it doesn't pass through.
 *
 * **Focus + touch tension**: WarpInputView must have `isFocusableInTouchMode`
 * for IME (S10 requirement). Touch dispatch calls `requestFocus()` on the
 * target view when `isFocusableInTouchMode = true`, which is idempotent here
 * since it already has focus. No conflict.
 *
 * **GestureDetector timing**: `onSingleTapConfirmed` fires ~300 ms after
 * ACTION_UP (double-tap window). Raw `inputTouchDown`/`inputTouchUp` fire
 * immediately for latency-sensitive paths.
 *
 * **VelocityTracker**: initialized on ACTION_DOWN, fed on ACTION_MOVE,
 * velocity computed at ACTION_MOVE and forwarded with each `inputScroll` call.
 * Released on ACTION_UP / ACTION_CANCEL.
 *
 * ## Web docs consulted (M2-S11, 2026-04-30):
 * - <https://developer.android.com/reference/android/view/MotionEvent>
 * - <https://developer.android.com/reference/android/view/GestureDetector>
 * - <https://developer.android.com/reference/android/view/GestureDetector.SimpleOnGestureListener>
 * - <https://developer.android.com/reference/android/view/VelocityTracker>
 * - <https://developer.android.com/training/gestures/detector>
 * ## Web docs consulted (M2-S10, 2026-04-30):
 * - <https://developer.android.com/reference/android/view/inputmethod/InputConnection>
 * - <https://developer.android.com/reference/android/view/inputmethod/BaseInputConnection>
 * - <https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method>
 * - <https://infinum.com/blog/input-connection/>
 */
class WarpInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /**
     * The most recent `WarpInputConnection` returned from
     * `onCreateInputConnection`. Set on the UI thread; read by
     * [ImeSimulationReceiver] (also UI thread via `View.post`). May be null
     * if no IME is currently attached. Held as a strong reference for the
     * driver path; the framework normally drops the IC when the IME detaches
     * but we keep the last one alive for test-injection purposes.
     *
     * In production (M3+) this would be reset to null when the IME detaches
     * (overrride `onInputConnectionClosed` on API 24+). For M2-S10 driver
     * needs we let it linger.
     */
    private var lastInputConnection: WarpInputConnection? = null

    fun warpInputConnectionOrNull(): WarpInputConnection? = lastInputConnection

    // ── M3-S09: scroll offset state ─────────────────────────────────────
    //
    // The `currentScrollOffsetRows` is the number of rows the viewport has
    // been scrolled UP into the scrollback (mirrors the Rust-side
    // `TerminalModel::scroll_offset`). Updated on `onScroll` (drag scroll)
    // and via the fling decay scheduler. Cell height is reported by
    // MainActivity through [setCellHeightPx]; default tracks the M3-S08
    // `GRID_CELL_H_PX` baseline of 27px.
    private var currentScrollOffsetRows: Int = 0
    private var cellHeightPx: Float = 27f
    /// Pixel accumulator for sub-cell scroll motion. The GestureDetector
    /// emits `distanceY` in pixels; we accumulate small motions until they
    /// add up to ≥ 1 cell row before bumping the offset. This avoids
    /// flooding the JNI bridge with no-op offset calls.
    private var pixelAccumulator: Float = 0f
    /// Active fling decay scheduler — non-null while a fling is in flight.
    private var flingRunnable: Runnable? = null
    private val flingHandler = Handler(Looper.getMainLooper())

    /// Update the assumed cell height (px). MainActivity calls this after
    /// resolving the grid params from --ef grid_cell_h_px / display metrics
    /// so onScroll's pixel→rows conversion uses the right divisor.
    fun setCellHeightPx(px: Float) {
        if (px > 0.0f) {
            cellHeightPx = px
        }
    }

    /// Reset scroll state (live tail). Called on terminal_mode launch + when
    /// the surface is recreated (rotation, IME show/hide).
    ///
    /// M3-S09 round-2: terminalSetScrollOffset now returns the actual clamped
    /// offset (always 0 here since we requested 0); we ignore the return so
    /// the accumulator is locally reset to 0 above and stays consistent.
    fun resetScroll() {
        currentScrollOffsetRows = 0
        pixelAccumulator = 0f
        cancelFling()
        try {
            NativeBridge.terminalSetScrollOffset(0)
        } catch (t: Throwable) {
            Log.w(TAG, "terminalSetScrollOffset failed: ${t.message}")
        }
    }

    private fun cancelFling() {
        flingRunnable?.let { flingHandler.removeCallbacks(it) }
        flingRunnable = null
    }

    // M2-S11: GestureDetector for tap / long-press / scroll detection.
    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        /**
         * Fires ~300 ms after ACTION_UP once the double-tap window expires.
         * More reliable for "single tap intent" than raw ACTION_UP which could
         * be the start of a double-tap.
         */
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            Log.i(TAG, "gesture_tap x=${e.x} y=${e.y}")
            try {
                NativeBridge.inputTap(e.x, e.y)
            } catch (t: Throwable) {
                Log.w(TAG, "inputTap failed: ${t.message}")
            }
            return true
        }

        /**
         * Long-press: sustained contact ≥ ViewConfiguration.getLongPressTimeout
         * (~500 ms). Equivalent to right-click / context-menu trigger.
         */
        override fun onLongPress(e: MotionEvent) {
            Log.i(TAG, "gesture_long_press x=${e.x} y=${e.y}")
            try {
                NativeBridge.inputLongPress(e.x, e.y)
            } catch (t: Throwable) {
                Log.w(TAG, "inputLongPress failed: ${t.message}")
            }
        }

        /**
         * Scroll: drag gesture (finger moves while touching). `distanceX`/`Y`
         * are the pixels moved since the previous call (positive = scrolled
         * up / left). We forward these alongside the instantaneous velocity
         * from `velocityTracker` so the Rust side can drive deceleration.
         *
         * `e1` is the initial ACTION_DOWN; `e2` is the current ACTION_MOVE.
         */
        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            val vx: Float
            val vy: Float
            val vt = velocityTracker
            if (vt != null) {
                // NOTE: do NOT call vt.addMovement(e2) here — onTouchEvent fed
                // e2 to the tracker in its ACTION_MOVE branch BEFORE calling
                // gestureDetector.onTouchEvent, so the velocity is already current.
                // Adding it again with the same timestamp would skew the result.
                vt.computeCurrentVelocity(1000)
                // Units: pixels per second (screen coordinates).
                // Positive vx = finger moves rightward; positive vy = DOWNWARD.
                // (Y axis grows downward on Android — downward swipe yields vy > 0.)
                vx = vt.xVelocity
                vy = vt.yVelocity
            } else {
                vx = 0f
                vy = 0f
            }
            Log.d(TAG, "gesture_scroll x=${e2.x} y=${e2.y} dx=$distanceX dy=$distanceY vx=$vx vy=$vy")
            try {
                NativeBridge.inputScroll(e2.x, e2.y, distanceX, distanceY, vx, vy)
            } catch (t: Throwable) {
                Log.w(TAG, "inputScroll failed: ${t.message}")
            }

            // M3-S09: drag scroll → terminal viewport offset.
            //
            // GestureDetector convention: positive `distanceY` = finger moved
            // UP (oldY - newY > 0 because successive Y decreases as finger
            // travels toward top). When the user drags UP they are scrolling
            // INTO older history → scroll offset should INCREASE.
            //
            // Pixel-to-rows conversion uses `cellHeightPx` provided by
            // MainActivity. We accumulate sub-cell motion in
            // `pixelAccumulator` so a slow drag doesn't get rounded away.
            if (cellHeightPx > 0f) {
                // Cancel any in-flight fling — direct touch trumps inertia.
                cancelFling()

                // distanceY > 0 → finger up → older content → offset ++
                // distanceY < 0 → finger down → newer content → offset --
                pixelAccumulator += distanceY
                val rowsDelta = (pixelAccumulator / cellHeightPx).toInt()
                if (rowsDelta != 0) {
                    pixelAccumulator -= rowsDelta * cellHeightPx
                    val requested = (currentScrollOffsetRows + rowsDelta).coerceAtLeast(0)
                    // M3-S09 round-2: capture the **actual clamped offset**
                    // returned by Rust (Rust caps to `scrollback.len()`) and
                    // assign it back into `currentScrollOffsetRows`.
                    // Without this, an over-scroll past the scrollback cap
                    // (1000 lines) lets `currentScrollOffsetRows` drift to
                    // 1500+ while Rust sits at 1000; the user then has to
                    // scroll back the overflow before the viewport visibly
                    // moves. The unconditional call also drains the
                    // accumulator on no-op clamps so a sustained
                    // upward drag past the top doesn't keep producing
                    // "delta but Rust unchanged" cycles.
                    val clamped = try {
                        NativeBridge.terminalSetScrollOffset(requested)
                    } catch (t: Throwable) {
                        requested
                    }
                    currentScrollOffsetRows = clamped
                }
            }
            return true
        }

        /**
         * M3-S09: two-finger flick / fast swipe → momentum scroll.
         *
         * GestureDetector calls `onFling` on ACTION_UP if the trailing
         * velocity exceeds the system's fling threshold. We schedule a
         * decay-driven scroll: each frame (16ms) the velocity is multiplied
         * by 0.9 (matches Android scroller decel curve close enough for
         * terminal use; cf. iOS UIScrollView's similar-feel constant of
         * ~0.998 per ms ≈ 0.9 per 16ms). When velocity drops below
         * one cell-per-second the timer cancels.
         *
         * `velocityY` from GestureDetector: positive = finger moves DOWNWARD.
         * Finger moving DOWNWARD reveals NEWER content → offset DECREASES.
         * Finger moving UPWARD (negative velocityY) → older content → offset
         * INCREASES. We negate so that a negative (upward) fling produces a
         * positive accumulator contribution matching the onScroll convention.
         */
        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            Log.i(TAG, "gesture_fling vx=$velocityX vy=$velocityY")
            if (cellHeightPx <= 0f) {
                return false
            }
            cancelFling()

            // Negate velocityY: GestureDetector positive = finger DOWN = newer
            // content = decrease offset. We want the accumulator to grow for
            // an upward fling (negative velocityY after negation → positive).
            // Store negated so the decay loop uses the corrected sign.
            val velocityArr = floatArrayOf(-velocityY)
            val r = object : Runnable {
                override fun run() {
                    val v = velocityArr[0]
                    // Per-frame distance: v px/s × frame_duration_s.
                    // We use 16ms frame budget; matches Choreographer cadence.
                    val deltaPx = v * 0.016f
                    pixelAccumulator += deltaPx
                    val rowsDelta = (pixelAccumulator / cellHeightPx).toInt()
                    if (rowsDelta != 0) {
                        pixelAccumulator -= rowsDelta * cellHeightPx
                        val requested = (currentScrollOffsetRows + rowsDelta).coerceAtLeast(0)
                        // M3-S09 round-2: same as onScroll — capture the
                        // actual clamped offset from Rust to prevent
                        // top-boundary state drift. A fling past
                        // scrollback.len() would otherwise leave
                        // `currentScrollOffsetRows` ahead of Rust by the
                        // overshoot until the user scrolls back.
                        val clamped = try {
                            NativeBridge.terminalSetScrollOffset(requested)
                        } catch (t: Throwable) {
                            requested
                        }
                        currentScrollOffsetRows = clamped
                    }
                    // Decay velocity. Reference iOS UIScrollView momentum
                    // model + Android OverScroller default decel: ~0.9 per
                    // 16ms frame → ~95% of velocity decays in ~50 frames.
                    velocityArr[0] = v * 0.9f
                    if (abs(velocityArr[0]) > cellHeightPx) {
                        flingHandler.postDelayed(this, 16L)
                    } else {
                        flingRunnable = null
                    }
                }
            }
            flingRunnable = r
            flingHandler.postDelayed(r, 16L)
            return true
        }

        // Return true so GestureDetector tracks scroll state from the initial
        // ACTION_DOWN. Without this, onScroll is never called.
        override fun onDown(e: MotionEvent): Boolean = true
    }

    private val gestureDetector = GestureDetector(context, gestureListener)

    // M2-S11: VelocityTracker — initialized on ACTION_DOWN, released on ACTION_UP.
    // The Java VelocityTracker pool is small; always recycle.
    private var velocityTracker: VelocityTracker? = null

    init {
        // Critical for IME attachment:
        //   isFocusable = true        — System.requestFocus may target us.
        //   isFocusableInTouchMode    — touches in our area request focus.
        //   isClickable = true        — also needed on some OEM ROMs.
        // Background is null (no draw cost). The View has size 1x1 in the
        // composite layout (see MainActivity).
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
    }

    private var customAccessibilityProvider: WarpAccessibilityNodeProvider? = null

    fun setAccessibilityProvider(provider: WarpAccessibilityNodeProvider?) {
        customAccessibilityProvider = provider
    }

    override fun getAccessibilityNodeProvider(): android.view.accessibility.AccessibilityNodeProvider {
        var provider = customAccessibilityProvider
        if (provider == null) {
            provider = WarpAccessibilityNodeProvider(this)
            customAccessibilityProvider = provider
        }
        return provider
    }

    var accessoryRow: AccessoryRow? = null

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val result = HardwareKeyDecoder.decodeKeyEvent(event, isSelectionActive = false)
        return when (result) {
            is HardwareKeyDecoder.KeyDecodeResult.HandledBytes -> {
                if (result.bytes.size == 1 && result.bytes[0] == 0x1B.toByte()) {
                    dev.warp.mobile.clipboard.ChunkedPasteEngine.cancelPaste()
                }
                writeBytesToActivePty(context, result.bytes)
                true
            }
            is HardwareKeyDecoder.KeyDecodeResult.PerformPaste -> {
                accessoryRow?.startClipboardPaste() ?: performSystemPaste()
                true
            }
            is HardwareKeyDecoder.KeyDecodeResult.PerformCopy -> {
                copyVisibleToClipboard()
                true
            }
            is HardwareKeyDecoder.KeyDecodeResult.NotHandled -> {
                super.onKeyDown(keyCode, event)
            }
        }
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_ESCAPE || keyCode == android.view.KeyEvent.KEYCODE_TAB) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun performSystemPaste() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val clip = cm?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context).toString() else ""
        if (text.isNotEmpty()) {
            if (dev.warp.mobile.clipboard.PasteConfirmationDialog.shouldConfirm(text)) {
                dev.warp.mobile.clipboard.PasteConfirmationDialog.show(context, text) { payload ->
                    dev.warp.mobile.clipboard.ChunkedPasteEngine.streamPaste(context, payload, activePtyCmdId)
                }
            } else {
                dev.warp.mobile.clipboard.ChunkedPasteEngine.streamPaste(context, text, activePtyCmdId)
            }
        }
    }

    private fun copyVisibleToClipboard() {
        try {
            val dump = NativeBridge.terminalBlocksDump()
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Terminal Dump", dump)
            cm?.setPrimaryClip(clip)
        } catch (t: Throwable) {
            Log.w(TAG, "copyVisibleToClipboard failed: ${t.message}")
        }
    }

    /**
     * M2-S11: touch event handler.
     *
     * Routes raw ACTION_DOWN / ACTION_UP / ACTION_CANCEL to Rust JNI
     * immediately (low latency), and feeds every event through the
     * GestureDetector for higher-level gesture recognition (tap, long-press,
     * scroll).
     *
     * VelocityTracker is fed for ACTION_DOWN and ACTION_MOVE BEFORE the event
     * is forwarded to gestureDetector, so that any onScroll callback triggered
     * by gestureDetector.onTouchEvent sees up-to-date velocity immediately.
     *
     * We always return `true` so the View consumes the event and Android does
     * not propagate it further down the view tree.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                Log.i(TAG, "touch_down x=${event.x} y=${event.y}")
                // Acquire a fresh VelocityTracker and feed DOWN before
                // forwarding to gestureDetector so velocity state is current.
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker!!.addMovement(event)
                try {
                    NativeBridge.inputTouchDown(event.x, event.y)
                } catch (t: Throwable) {
                    Log.w(TAG, "inputTouchDown failed: ${t.message}")
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // Feed VelocityTracker BEFORE gestureDetector so that any
                // onScroll callback triggered inside gestureDetector.onTouchEvent
                // sees the current velocity when it calls vt.computeCurrentVelocity.
                velocityTracker?.addMovement(event)
            }
            MotionEvent.ACTION_UP -> {
                Log.i(TAG, "touch_up x=${event.x} y=${event.y}")
                try {
                    NativeBridge.inputTouchUp(event.x, event.y)
                } catch (t: Throwable) {
                    Log.w(TAG, "inputTouchUp failed: ${t.message}")
                }
                velocityTracker?.recycle()
                velocityTracker = null
            }
            MotionEvent.ACTION_CANCEL -> {
                // Emit TouchCancel so Rust state machine closes the open down
                // sequence — without this, Rust would believe the finger is
                // still down after a parent View intercepts the event stream.
                Log.d(TAG, "touch_cancel x=${event.x} y=${event.y}")
                try {
                    NativeBridge.inputTouchCancel(event.x, event.y)
                } catch (t: Throwable) {
                    Log.w(TAG, "inputTouchCancel failed: ${t.message}")
                }
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        // Forward to GestureDetector AFTER VelocityTracker updates above.
        // Returning true is required so GestureDetector receives subsequent
        // ACTION_MOVE / ACTION_UP events (if false on ACTION_DOWN, the detector
        // won't track scroll and long-press).
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    /**
     * Called by the framework when the IME attaches to this View (typically
     * after our View gains focus + the user taps to bring up the soft
     * keyboard). We populate `outAttrs` with a generic IME_ACTION_NONE +
     * TYPE_CLASS_TEXT so Gboard / Pinyin display normally and not in some
     * specialized mode (PIN, password, search, etc.).
     */
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT or
            EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        Log.i(TAG, "onCreateInputConnection inputType=0x${"%x".format(outAttrs.inputType)} imeOptions=0x${"%x".format(outAttrs.imeOptions)}")
        val ic = WarpInputConnection(this, /* fullEditor = */ false)
        lastInputConnection = ic
        return ic
    }

    /**
     * Custom `BaseInputConnection` subclass. `fullEditor=false` means we
     * don't keep a full Editable buffer (we don't have text selection,
     * cursor movement, etc. for a terminal); we only need the composing-
     * region machinery, which is in the Rust state machine.
     *
     * We still call `super.*` so that `BaseInputConnection`'s minimal
     * Editable bookkeeping stays consistent for any subsequent IME queries
     * like `getTextBeforeCursor` / `deleteSurroundingText` (M2-S11+; not
     * exercised in M2-S10).
     */
    class WarpInputConnection(
        private val view: View,
        fullEditor: Boolean
    ) : BaseInputConnection(view, fullEditor) {

        private var lastComposingText: String = ""
        private var pendingComposingText: String? = null
        private val lineContextBuffer = StringBuilder()

        @Volatile
        private var commitTextSynthExpiresMs: Long = 0L

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
            if (n <= 0) return ""
            val currentContext = lineContextBuffer.toString()
            if (currentContext.isNotEmpty()) {
                val takeCount = n.coerceAtMost(currentContext.length)
                val sub = currentContext.substring(currentContext.length - takeCount)
                return if (flags and GET_TEXT_WITH_STYLES != 0) android.text.SpannableString(sub) else sub
            }
            val superRes = super.getTextBeforeCursor(n, flags)
            return superRes ?: ""
        }

        private fun updateLineContext(text: String) {
            if (text.contains("\n")) {
                lineContextBuffer.clear()
                val lastLine = text.substringAfterLast("\n")
                lineContextBuffer.append(lastLine)
            } else {
                lineContextBuffer.append(text)
                if (lineContextBuffer.length > 512) {
                    lineContextBuffer.delete(0, lineContextBuffer.length - 512)
                }
            }
        }

        /**
         * Diff a previous composing region against a new value and emit
         * the equivalent edit to the active PTY. Used by both
         * `setComposingText` and `commitText` to keep the PTY-side state
         * synchronized with what the user sees in the IME.
         */
        private fun forwardComposingDiff(prev: String, next: String) {
            if (prev == next) return
            // Find longest common prefix.
            var i = 0
            val maxI = minOf(prev.length, next.length)
            while (i < maxI && prev[i] == next[i]) i++

            // Erase the part of prev past the common prefix using DEL (0x7F).
            // Uses Unicode code point counts to prevent over-deleting surrogate pairs/emojis.
            val textToErase = if (prev.length > i) prev.substring(i) else ""
            val codePointCount = if (textToErase.isNotEmpty()) textToErase.codePointCount(0, textToErase.length) else 0
            if (codePointCount > 0) {
                try {
                    val dels = ByteArray(codePointCount.coerceAtMost(256)) { 0x7F }
                    writeBytesToActivePty(view.context, dels)
                } catch (t: Throwable) {
                    Log.w(TAG, "PTY composing-erase forward failed: ${t.message}")
                }
            }
            // Type the new suffix.
            val toAdd = next.substring(i)
            if (toAdd.isNotEmpty()) {
                try {
                    writeBytesToActivePty(view.context, toAdd.toByteArray(Charsets.UTF_8))
                } catch (t: Throwable) {
                    Log.w(TAG, "PTY composing-add forward failed: ${t.message}")
                }
            }
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val s = text?.toString() ?: ""
            Log.i(
                TAG,
                "IC.commitText text=${quote(s)} cursorPos=$newCursorPosition len=${s.length}"
            )
            try {
                NativeBridge.imeCommitText(s, newCursorPosition)
            } catch (t: Throwable) {
                Log.w(TAG, "imeCommitText failed: ${t.message}")
            }
            val prevComposing = if (lastComposingText.isNotEmpty()) lastComposingText else (pendingComposingText ?: "")
            forwardComposingDiff(prevComposing, s)
            lastComposingText = ""
            pendingComposingText = null
            updateLineContext(s)

            try {
                GhostSuggestController.onTextCommitted(s)
            } catch (t: Throwable) {
                Log.w(TAG, "GhostSuggest forward failed: ${t.message}")
            }
            val result = super.commitText(if (text == null) "" else text, newCursorPosition)
            commitTextSynthExpiresMs = android.os.SystemClock.elapsedRealtime() + 50L
            return result
        }

        override fun setComposingText(
            text: CharSequence?,
            newCursorPosition: Int
        ): Boolean {
            val s = text?.toString() ?: ""
            Log.i(
                TAG,
                "IC.setComposingText text=${quote(s)} cursorPos=$newCursorPosition len=${s.length} spanned=${text is Spanned}"
            )
            try {
                NativeBridge.imeSetComposingText(s, newCursorPosition)
            } catch (t: Throwable) {
                Log.w(TAG, "imeSetComposingText failed: ${t.message}")
            }
            val prevComposing = if (lastComposingText.isNotEmpty()) lastComposingText else (pendingComposingText ?: "")
            forwardComposingDiff(prevComposing, s)
            lastComposingText = s
            pendingComposingText = null
            try {
                GhostSuggestController.onTextComposing(s)
            } catch (t: Throwable) {
                Log.w(TAG, "GhostSuggest compose forward failed: ${t.message}")
            }
            return super.setComposingText(if (text == null) "" else text, newCursorPosition)
        }

        override fun finishComposingText(): Boolean {
            Log.i(TAG, "IC.finishComposingText")
            try {
                NativeBridge.imeFinishComposingText()
            } catch (t: Throwable) {
                Log.w(TAG, "imeFinishComposingText failed: ${t.message}")
            }
            pendingComposingText = lastComposingText
            lastComposingText = ""
            return super.finishComposingText()
        }

        override fun sendKeyEvent(event: android.view.KeyEvent?): Boolean {
            // M6 carry-over #1 round-3 review MEDIUM: hardware keyboards
            // and some IMEs submit Enter via sendKeyEvent rather than
            // commitText("\n"). When that happens, the ghost-suggest
            // controller never sees the "\n" and its buffer goes stale.
            // Detect KEYCODE_ENTER on ACTION_DOWN and forward an explicit
            // "\n" commit to the controller (idempotent — multiple resets
            // just clear an already-empty buffer).
            //
            // V1-prep iteration 19 round-2: detect synthesized events.
            // BaseInputConnection.sendCurrentText() (called from
            // super.commitText) synthesizes KeyEvents via KeyCharacterMap
            // with deviceId == VIRTUAL_KEYBOARD (-1). These events follow
            // a commitText that ALREADY wrote the bytes to PTY, so writing
            // again here causes a double-write (e.g. Enter committed twice).
            // Skip PTY writes from synthesized events; commitText covers
            // them. Hardware keyboards (deviceId >= 0) still get full
            // sendKeyEvent byte mapping.
            // V1-prep iteration 35: only treat as synthesis if a recent
            // super.commitText call set a window that hasn't expired.
            // Real soft-keyboard Enter / Backspace via sendKeyEvent (Gboard
            // when actionNone, hardware-mapped soft keys) and adb-injected
            // keyevents fall outside the window and DO reach the PTY.
            val now = android.os.SystemClock.elapsedRealtime()
            val isSynthesizedFromCommit =
                event != null &&
                    event.deviceId == android.view.KeyCharacterMap.VIRTUAL_KEYBOARD &&
                    now < commitTextSynthExpiresMs
            if (event != null && event.action == android.view.KeyEvent.ACTION_DOWN && !isSynthesizedFromCommit) {
                // V1-prep iteration 19: forward the byte sequence for
                // common control keys to the active PTY. Without this,
                // Gboard's Enter / Backspace and any hardware keyboard
                // input never reach the spawned shell.
                val ptyBytes: ByteArray? = when (event.keyCode) {
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> byteArrayOf('\n'.code.toByte())
                    // ASCII DEL (0x7F) — what most line editors expect for
                    // Backspace; mksh / zsh / bash all map this to delete-
                    // previous-char in their default termios.
                    KeyEvent.KEYCODE_DEL -> byteArrayOf(0x7F)
                    // Forward-delete (rare on phones; mapped to ESC[3~).
                    KeyEvent.KEYCODE_FORWARD_DEL -> byteArrayOf(0x1B, '['.code.toByte(), '3'.code.toByte(), '~'.code.toByte())
                    KeyEvent.KEYCODE_TAB -> byteArrayOf('\t'.code.toByte())
                    KeyEvent.KEYCODE_ESCAPE -> byteArrayOf(0x1B)
                    KeyEvent.KEYCODE_DPAD_UP -> byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte())
                    KeyEvent.KEYCODE_DPAD_DOWN -> byteArrayOf(0x1B, '['.code.toByte(), 'B'.code.toByte())
                    KeyEvent.KEYCODE_DPAD_RIGHT -> byteArrayOf(0x1B, '['.code.toByte(), 'C'.code.toByte())
                    KeyEvent.KEYCODE_DPAD_LEFT -> byteArrayOf(0x1B, '['.code.toByte(), 'D'.code.toByte())
                    else -> {
                        // Ctrl+letter — used to send 0x01..0x1F control chars
                        // (Ctrl+C → SIGINT, Ctrl+D → EOF, Ctrl+L → clear).
                        // Plain printable chars from a hardware keyboard
                        // ALSO arrive here via sendKeyEvent, but we skip
                        // them: BaseInputConnection.commitText synthesizes
                        // exactly the same KeyEvents AFTER our commitText
                        // override has already written the bytes — handling
                        // them here would double-write every char. The
                        // trade-off: a true USB-keyboard-only "no IME"
                        // setup wouldn't get printable chars to the PTY
                        // (none exist on phones). v1.x scope.
                        val u = event.unicodeChar
                        if (u != 0 && (event.metaState and KeyEvent.META_CTRL_ON) != 0 && u in 0x40..0x7F) {
                            byteArrayOf((u and 0x1F).toByte())
                        } else {
                            null
                        }
                    }
                }
                if (ptyBytes != null && ptyBytes.isNotEmpty()) {
                    try {
                        writeBytesToActivePty(view.context, ptyBytes)
                    } catch (t: Throwable) {
                        Log.w(TAG, "PTY key forward failed (kc=${event.keyCode}): ${t.message}")
                    }
                }

                when (event.keyCode) {
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        try {
                            GhostSuggestController.onTextCommitted("\n")
                        } catch (t: Throwable) {
                            Log.w(TAG, "GhostSuggest enter-reset failed: ${t.message}")
                        }
                    }
                    KeyEvent.KEYCODE_DEL,
                    KeyEvent.KEYCODE_FORWARD_DEL -> {
                        // M6-CO1 v1-prep: hardware backspace shrinks the
                        // ghost buffer by 1 char.
                        try {
                            GhostSuggestController.onTextDeleted(1)
                        } catch (t: Throwable) {
                            Log.w(TAG, "GhostSuggest backspace shrink failed: ${t.message}")
                        }
                    }
                }
            }
            return super.sendKeyEvent(event)
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            // M6-CO1 v1-prep: IME-driven delete (Gboard, SwiftKey backspace)
            // routes through deleteSurroundingText, not sendKeyEvent. Forward
            // the before-cursor delete count so the ghost buffer shrinks
            // accordingly. afterLength is rare for shell input (would mean
            // the cursor isn't at end-of-line) — accepted but ignored for
            // round-1; round-2 could track cursor position to handle it.
            if (beforeLength > 0) {
                try {
                    GhostSuggestController.onTextDeleted(beforeLength)
                } catch (t: Throwable) {
                    Log.w(TAG, "GhostSuggest deleteSurroundingText shrink failed: ${t.message}")
                }
                // V1-prep iteration 19: emit one DEL (0x7F) per char to be
                // deleted so the shell's line editor erases the same
                // characters Gboard thinks it removed.
                try {
                    val dels = ByteArray(beforeLength.coerceAtMost(256)) { 0x7F }
                    writeBytesToActivePty(view.context, dels)
                } catch (t: Throwable) {
                    Log.w(TAG, "PTY deleteSurroundingText forward failed: ${t.message}")
                }
            }
            return super.deleteSurroundingText(beforeLength, afterLength)
        }

        private fun quote(s: String): String {
            // Compact one-line representation suitable for logcat. Truncates
            // overly long composing strings (Pinyin can sometimes have
            // candidate previews) but for "ni hao" / "你好" cases this is
            // never reached.
            val truncated = if (s.length > 64) s.substring(0, 64) + "…" else s
            return "\"" + truncated.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
    }

    companion object {
        private const val TAG = "WarpIme"

        // V1-prep iteration 19 (2026-05-02): the M2-S10 IME pipeline
        // (commitText → NativeBridge.imeCommitText → Rust ime::commit_text)
        // only updates a stats counter for the device test driver. It does
        // NOT forward typed bytes to the spawned PTY. Every prior "device-
        // verified" test bypassed this by sending `am broadcast PTY_WRITE
        // --es data ...` directly. Real users typing on Gboard saw nothing
        // appear in the terminal.
        //
        // Fix: every IME / key-event path in WarpInputConnection also
        // broadcasts ACTION_WRITE for the active PTY cmd_id (defaults to
        // "terminal_mode" — the launcher-default cmd_id from MainActivity;
        // driver scripts that use cmd_id="default" set this field
        // accordingly when they spawn).
        @Volatile
        @JvmStatic
        var activePtyCmdId: String = "terminal_mode"

        /**
         * Broadcast raw bytes to the active PTY cmd_id.
         *
         * Targets PtyBroadcastReceiver via explicit ComponentName so the
         * dynamic receiver inside WarpTerminalService doesn't ALSO match
         * the broadcast (which would double-write — same bug AccessoryRow
         * already fixed in M5-S02 round-1).
         */
        fun writeBytesToActivePty(context: Context, bytes: ByteArray) {
            if (bytes.isEmpty()) return
            val intent = Intent(WarpTerminalService.ACTION_WRITE).apply {
                component = android.content.ComponentName(
                    context.packageName,
                    "${context.packageName}.PtyBroadcastReceiver"
                )
                putExtra("cmd_id", activePtyCmdId)
                putExtra("data", bytes)
            }
            context.sendBroadcast(intent)
        }
    }
}
