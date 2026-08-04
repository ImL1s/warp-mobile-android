package dev.warp.mobile

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import dev.warp.mobile.test.BaseWarpUnitTest
import dev.warp.mobile.ui.TerminalCanvasController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SurfaceViewLifecycleTest : BaseWarpUnitTest() {

    private lateinit var controller: TerminalCanvasController
    private lateinit var mockHolder: SurfaceHolder

    @Before
    override fun setUp() {
        super.setUp()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        controller = TerminalCanvasController(
            context = activity,
            isRawMode = true,
            terminalMode = true,
            gridFontSizePx = 32.0f,
            gridCellWPx = 24.0f,
            gridCellHPx = 40.0f,
            gridRows = 20,
            gridCols = 50
        )

        val surfaceView = SurfaceView(activity)
        mockHolder = surfaceView.holder
    }

    @Test
    fun testSurfaceCreated_triggersRenderAttachSurfaceAndPostsFrameCallback() {
        assertFalse("Initial attached state should be false", controller.isAttached)
        assertEquals("Initial width should be -1", -1, controller.attachedWidth)

        controller.surfaceCreated(mockHolder)

        assertTrue("After surfaceCreated, isAttached should be true", controller.isAttached)
        assertTrue("After surfaceCreated, renderActive should be true", controller.renderActive)
        assertEquals("attachedWidth should stay -1 until surfaceChanged records dims", -1, controller.attachedWidth)
    }

    @Test
    fun testSurfaceChanged_spuriousFirstCall_deduplicatesAndRecordsDimensions() {
        controller.surfaceCreated(mockHolder)
        assertTrue(controller.isAttached)
        assertEquals(-1, controller.attachedWidth)

        // Spurious initial surfaceChanged call from Android framework
        controller.surfaceChanged(mockHolder, 0, 1080, 2340)

        assertTrue(controller.isAttached)
        assertTrue(controller.renderActive)
        assertEquals("First surfaceChanged should record width 1080", 1080, controller.attachedWidth)
        assertEquals("First surfaceChanged should record height 2340", 2340, controller.attachedHeight)

        // Subsequent call with identical dimensions should deduplicate
        controller.surfaceChanged(mockHolder, 0, 1080, 2340)
        assertEquals(1080, controller.attachedWidth)
        assertEquals(2340, controller.attachedHeight)
    }

    @Test
    fun testSurfaceChanged_dimensionChange_recomputesGridAndDispatchesPtyResize() {
        controller.surfaceCreated(mockHolder)
        controller.surfaceChanged(mockHolder, 0, 1080, 2340)

        assertEquals(1080, controller.attachedWidth)
        assertEquals(2340, controller.attachedHeight)

        // Simulate resize e.g. IME show/hide or rotation
        val newHeight = 1600
        val newWidth = 1080
        controller.surfaceChanged(mockHolder, 0, newWidth, newHeight)

        assertEquals(newWidth, controller.attachedWidth)
        assertEquals(newHeight, controller.attachedHeight)
        val expectedRows = maxOf(8, (newHeight / 40.0f).toInt())
        val expectedCols = maxOf(20, (newWidth / 24.0f).toInt())

        assertEquals("gridRows should be recomputed to expected rows", expectedRows, controller.gridRows)
        assertEquals("gridCols should be recomputed to expected cols", expectedCols, controller.gridCols)
    }

    @Test
    fun testSurfaceDestroyed_detachesSurfaceAndResetsRenderState() {
        controller.surfaceCreated(mockHolder)
        controller.surfaceChanged(mockHolder, 0, 1080, 2340)
        assertTrue(controller.isAttached)
        assertTrue(controller.renderActive)

        controller.surfaceDestroyed(mockHolder)

        assertFalse("isAttached should be false post destroy", controller.isAttached)
        assertFalse("renderActive should be false post destroy", controller.renderActive)
        assertEquals("attachedWidth should reset to -1", -1, controller.attachedWidth)
        assertEquals("attachedHeight should reset to -1", -1, controller.attachedHeight)
    }

    @Test
    fun testRapidSurfaceAttachDetachCycles_maintainsCleanState() {
        repeat(50) { i ->
            controller.surfaceCreated(mockHolder)
            assertTrue("Cycle $i surfaceCreated failed", controller.isAttached)

            controller.surfaceChanged(mockHolder, 0, 1080, 2340)
            assertEquals("Cycle $i surfaceChanged failed", 1080, controller.attachedWidth)

            controller.surfaceDestroyed(mockHolder)
            assertFalse("Cycle $i surfaceDestroyed failed", controller.isAttached)
        }
    }
}
