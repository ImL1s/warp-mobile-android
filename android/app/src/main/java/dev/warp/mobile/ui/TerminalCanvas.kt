package dev.warp.mobile.ui

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.warp.mobile.NativeBridge
import dev.warp.mobile.WarpTerminalService

/**
 * Encapsulates SurfaceView lifecycle, SurfaceHolder.Callback, Choreographer vsync scheduling,
 * setZOrderMediaOverlay(true), deduplication, and PTY resize broadcasts.
 */
class TerminalCanvasController(
    val context: Context,
    @Volatile var isRawMode: Boolean = true,
    @Volatile var terminalMode: Boolean = true,
    @Volatile var gridFontSizePx: Float = 32.0f,
    @Volatile var gridCellWPx: Float = 24.0f,
    @Volatile var gridCellHPx: Float = 40.0f,
    @Volatile var gridRows: Int = 20,
    @Volatile var gridCols: Int = 50,
    @Volatile var gridMode: Boolean = false,
    @Volatile var gridText: String = "Hello, World",
    var onSurfaceCreatedListener: ((SurfaceHolder) -> Unit)? = null,
    var onSurfaceDestroyedListener: ((SurfaceHolder) -> Unit)? = null,
    var onSurfaceChangedListener: ((SurfaceHolder, Int, Int, Int) -> Unit)? = null
) : SurfaceHolder.Callback {

    @Volatile
    var isAttached: Boolean = false

    @Volatile
    var attachedWidth: Int = -1

    @Volatile
    var attachedHeight: Int = -1

    @Volatile
    var renderActive: Boolean = false

    @Volatile
    var choreographerPosted: Boolean = false
        private set

    val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isAttached || !renderActive || !isRawMode) {
                choreographerPosted = false
                return
            }
            val ok = try {
                when {
                    terminalMode -> {
                        val pushResult = NativeBridge.terminalTakeDirtyAndPushFrame(
                            gridFontSizePx, gridRows, gridCols, gridCellWPx, gridCellHPx
                        )
                        when (pushResult) {
                            1 -> true
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
                Log.d(TAG, "render frame returned false @ ${SystemClock.uptimeMillis()}")
            }
            if (isAttached && renderActive && isRawMode) {
                Choreographer.getInstance().postFrameCallback(this)
                choreographerPosted = true
            } else {
                choreographerPosted = false
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val ts = SystemClock.uptimeMillis()
        Log.i(TAG, "surfaceCreated_ts=$ts")
        val surface = try { holder.surface } catch (t: Throwable) { null }
        attachAndStartRender(surface)
        onSurfaceCreatedListener?.invoke(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val ts = SystemClock.uptimeMillis()
        Log.i(TAG, "surfaceChanged_ts=$ts width=$width height=$height")

        if (renderActive && attachedWidth == -1 && attachedHeight == -1) {
            attachedWidth = width
            attachedHeight = height
            onSurfaceChangedListener?.invoke(holder, format, width, height)
            return
        }
        if (renderActive && attachedWidth == width && attachedHeight == height) {
            onSurfaceChangedListener?.invoke(holder, format, width, height)
            return
        }

        renderActive = false
        isAttached = false
        try {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        } catch (t: Throwable) {}
        choreographerPosted = false

        val surface = try { holder.surface } catch (t: Throwable) { null }
        attachAndStartRender(surface, width, height)

        if (terminalMode && gridCellWPx > 0 && gridCellHPx > 0) {
            val newRows = maxOf(8, (height / gridCellHPx).toInt())
            val newCols = maxOf(20, (width / gridCellWPx).toInt())
            if (newRows != gridRows || newCols != gridCols) {
                Log.i(
                    TAG,
                    "surfaceChanged → grid resize from ${gridRows}x${gridCols} to ${newRows}x${newCols}"
                )
                gridRows = newRows
                gridCols = newCols
                try {
                    NativeBridge.terminalResize(newRows, newCols)
                } catch (t: Throwable) {
                    Log.w(TAG, "terminalResize JNI fallback: ${t.message}")
                }
                val resizeIntent = Intent(WarpTerminalService.ACTION_RESIZE).apply {
                    val pkg = try { context.packageName } catch (t: Throwable) { null }
                    if (pkg != null) setPackage(pkg)
                    putExtra("cmd_id", "terminal_mode")
                    putExtra("rows", newRows)
                    putExtra("cols", newCols)
                }
                try {
                    context.sendBroadcast(resizeIntent)
                } catch (t: Throwable) {
                    // Test fallback
                }
            }
        }
        onSurfaceChangedListener?.invoke(holder, format, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val ts = SystemClock.uptimeMillis()
        Log.i(TAG, "surfaceDestroyed_ts=$ts")
        renderActive = false
        isAttached = false
        attachedWidth = -1
        attachedHeight = -1
        try {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        } catch (t: Throwable) {}
        choreographerPosted = false
        try {
            NativeBridge.renderDetachSurface()
        } catch (t: Throwable) {
            Log.w(TAG, "renderDetachSurface JNI fallback: ${t.message}")
        }
        onSurfaceDestroyedListener?.invoke(holder)
    }

    fun attachAndStartRender(surface: android.view.Surface?, width: Int = -1, height: Int = -1) {
        if (surface == null) {
            attachedWidth = width
            attachedHeight = height
            isAttached = true
            renderActive = true
            return
        }
        val ok = try {
            NativeBridge.renderAttachSurface(surface)
        } catch (t: Throwable) {
            Log.w(TAG, "renderAttachSurface JNI fallback: ${t.message}")
            true
        }
        Log.i(TAG, "renderAttachSurface ok=$ok")
        if (ok) {
            attachedWidth = width
            attachedHeight = height
            isAttached = true
            renderActive = true
            if (gridMode) {
                val initOk = try {
                    NativeBridge.renderInitStaticGrid(
                        gridText, gridFontSizePx, gridRows, gridCols, gridCellWPx, gridCellHPx
                    )
                } catch (t: Throwable) {
                    false
                }
                if (!initOk) {
                    gridMode = false
                }
            }
            if (isRawMode && !choreographerPosted) {
                try {
                    Choreographer.getInstance().postFrameCallback(frameCallback)
                    choreographerPosted = true
                } catch (t: Throwable) {
                    // Robolectric fallback
                }
            }
        }
    }

    fun updateRawMode(rawMode: Boolean) {
        val oldMode = isRawMode
        isRawMode = rawMode
        if (oldMode != rawMode) {
            if (isRawMode && renderActive && isAttached && !choreographerPosted) {
                Choreographer.getInstance().postFrameCallback(frameCallback)
                choreographerPosted = true
            } else if (!isRawMode && choreographerPosted) {
                Choreographer.getInstance().removeFrameCallback(frameCallback)
                choreographerPosted = false
            }
        }
    }

    fun cleanup() {
        renderActive = false
        isAttached = false
        attachedWidth = -1
        attachedHeight = -1
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        choreographerPosted = false
        try {
            NativeBridge.renderDetachSurface()
        } catch (t: Throwable) {
            // JNI fallback
        }
    }

    companion object {
        private const val TAG = "TerminalCanvas"
    }
}

/**
 * Standalone Composable for hosting the SurfaceView Vulkan terminal grid.
 * Configures Z-ordering via setZOrderMediaOverlay(true) to eliminate Z-fighting
 * with Compose UI components.
 */
@Composable
fun TerminalCanvas(
    modifier: Modifier = Modifier,
    isRawMode: Boolean = true,
    terminalMode: Boolean = true,
    gridFontSizePx: Float = 32.0f,
    gridCellWPx: Float = 24.0f,
    gridCellHPx: Float = 40.0f,
    gridRows: Int = 20,
    gridCols: Int = 50,
    customView: View? = null,
    onSurfaceCreated: ((SurfaceHolder) -> Unit)? = null,
    onSurfaceDestroyed: ((SurfaceHolder) -> Unit)? = null,
    onSurfaceChanged: ((SurfaceHolder, Int, Int, Int) -> Unit)? = null
) {
    val context = LocalContext.current
    val controller = remember {
        TerminalCanvasController(
            context = context,
            isRawMode = isRawMode,
            terminalMode = terminalMode,
            gridFontSizePx = gridFontSizePx,
            gridCellWPx = gridCellWPx,
            gridCellHPx = gridCellHPx,
            gridRows = gridRows,
            gridCols = gridCols,
            onSurfaceCreatedListener = onSurfaceCreated,
            onSurfaceDestroyedListener = onSurfaceDestroyed,
            onSurfaceChangedListener = onSurfaceChanged
        )
    }

    LaunchedEffect(isRawMode) {
        controller.updateRawMode(isRawMode)
    }

    DisposableEffect(Unit) {
        onDispose {
            controller.cleanup()
        }
    }

    AndroidView(
        factory = { ctx ->
            val viewToUse = customView ?: FrameLayout(ctx).apply {
                val sv = SurfaceView(ctx).apply {
                    setZOrderMediaOverlay(true)
                }
                addView(
                    sv,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            }

            val surfaceView = findSurfaceView(viewToUse)
            surfaceView?.setZOrderMediaOverlay(true)
            surfaceView?.holder?.addCallback(controller)

            viewToUse
        },
        update = {
            controller.updateRawMode(isRawMode)
        },
        modifier = modifier
    )
}

private fun findSurfaceView(view: View): SurfaceView? {
    if (view is SurfaceView) return view
    if (view is android.view.ViewGroup) {
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            val sv = findSurfaceView(child)
            if (sv != null) return sv
        }
    }
    return null
}
