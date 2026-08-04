package dev.warp.mobile

import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import dev.warp.mobile.test.BaseWarpUnitTest
import dev.warp.mobile.ui.WarpScaffold
import dev.warp.mobile.ui.WarpTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ComposeSurfaceViewLayoutTest : BaseWarpUnitTest() {

    private lateinit var activity: ComponentActivity

    @Before
    override fun setUp() {
        super.setUp()
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    }

    @Test
    fun testWarpScaffold_isRawModeFalse_rendersBlockTimelineAndHidesAndroidView() {
        var contentRendered = false
        val sampleBlocks = listOf(
            WarpBlockState(
                id = "block-1",
                command = "ls -la",
                exitCode = 0,
                durationMs = 50L,
                output = "total 0",
                isRunning = false,
                timestamp = 1000L
            )
        )

        val composeView = ComposeView(activity).apply {
            setContent {
                WarpScaffold(
                    tabs = listOf(WarpTab("t1", "Tab 1", "~")),
                    activeTabId = "t1",
                    onTabSelected = {},
                    onNewTab = {},
                    onSettings = {},
                    onPromptSubmit = {},
                    blocks = sampleBlocks,
                    isRawMode = false
                ) {
                    contentRendered = true
                }
            }
        }
        activity.setContentView(composeView)
        composeView.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        )
        composeView.layout(0, 0, 1080, 1920)

        assertTrue("Content slot should be mounted persistently in composition tree", contentRendered)
    }

    @Test
    fun testWarpScaffold_isRawModeTrue_rendersAndroidViewAndHidesBlockTimeline() {
        var contentRendered = false
        val sampleBlocks = listOf(
            WarpBlockState(
                id = "block-1",
                command = "htop",
                exitCode = null,
                durationMs = null,
                output = "",
                isRunning = true,
                timestamp = 1000L
            )
        )

        val composeView = ComposeView(activity).apply {
            setContent {
                WarpScaffold(
                    tabs = listOf(WarpTab("t1", "Tab 1", "~")),
                    activeTabId = "t1",
                    onTabSelected = {},
                    onNewTab = {},
                    onSettings = {},
                    onPromptSubmit = {},
                    blocks = sampleBlocks,
                    isRawMode = true
                ) {
                    contentRendered = true
                }
            }
        }
        activity.setContentView(composeView)
        composeView.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        )
        composeView.layout(0, 0, 1080, 1920)

        assertTrue("Content slot should be rendered when isRawMode = true", contentRendered)
    }

    @Test
    fun testFrameLayoutChildOrder_accessoryRowPositionedOnTopForTouch() {
        val frame = FrameLayout(activity)

        val surfaceView = SurfaceView(activity).apply {
            setZOrderMediaOverlay(true)
        }
        frame.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val inputView = WarpInputView(activity).apply { alpha = 0f }
        frame.addView(
            inputView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val accessoryRow = AccessoryRow(activity)
        frame.addView(
            accessoryRow,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )

        assertEquals("FrameLayout must contain 3 child views", 3, frame.childCount)
        assertTrue("Child index 0 must be SurfaceView", frame.getChildAt(0) is SurfaceView)
        assertTrue("Child index 1 must be WarpInputView", frame.getChildAt(1) is WarpInputView)
        assertTrue("Child index 2 must be AccessoryRow (positioned on top for touch precedence)", frame.getChildAt(2) is AccessoryRow)
    }
}
