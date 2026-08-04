package dev.warp.mobile.panes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PaneLayoutTest {

    @Test
    fun testSinglePaneFillsContainer() {
        val config = listOf(PaneConfig(id = "1"))
        val viewports = PaneLayout.calculateLayout(100, 100, config)
        assertEquals(1, viewports.size)
        assertEquals(100, viewports[0].width)
        assertEquals(100, viewports[0].height)
        assertEquals(0, viewports[0].x)
        assertEquals(0, viewports[0].y)
    }

    @Test
    fun testTwoHorizontalSplitAt50Percent() {
        val configs = listOf(
            PaneConfig(id = "1", direction = SplitDirection.Horizontal, splitRatio = 0.5f),
            PaneConfig(id = "2")
        )
        val viewports = PaneLayout.calculateLayout(100, 100, configs)
        assertEquals(2, viewports.size)
        
        assertEquals(100, viewports[0].width)
        assertEquals(50, viewports[0].height)
        assertEquals(0, viewports[0].x)
        assertEquals(0, viewports[0].y)

        assertEquals(100, viewports[1].width)
        assertEquals(50, viewports[1].height)
        assertEquals(0, viewports[1].x)
        assertEquals(50, viewports[1].y)
    }

    @Test
    fun testTwoVerticalSplitAt50Percent() {
        val configs = listOf(
            PaneConfig(id = "1", direction = SplitDirection.Vertical, splitRatio = 0.5f),
            PaneConfig(id = "2")
        )
        val viewports = PaneLayout.calculateLayout(100, 100, configs)
        assertEquals(2, viewports.size)
        
        assertEquals(50, viewports[0].width)
        assertEquals(100, viewports[0].height)
        assertEquals(0, viewports[0].x)
        assertEquals(0, viewports[0].y)

        assertEquals(50, viewports[1].width)
        assertEquals(100, viewports[1].height)
        assertEquals(50, viewports[1].x)
        assertEquals(0, viewports[1].y)
    }

    @Test
    fun testThreePanesOddSplit() {
        val configs = listOf(
            PaneConfig(id = "1", direction = SplitDirection.Horizontal, splitRatio = 0.33f),
            PaneConfig(id = "2", direction = SplitDirection.Vertical, splitRatio = 0.5f),
            PaneConfig(id = "3")
        )
        val viewports = PaneLayout.calculateLayout(100, 100, configs)
        assertEquals(3, viewports.size)
        
        // 1st pane: 100x33, pos 0,0
        assertEquals(100, viewports[0].width)
        assertEquals(33, viewports[0].height)
        assertEquals(0, viewports[0].x)
        assertEquals(0, viewports[0].y)

        // remaining: 100x67. 2nd pane split vertical at 0.5 -> 50x67
        assertEquals(50, viewports[1].width)
        assertEquals(67, viewports[1].height)
        assertEquals(0, viewports[1].x)
        assertEquals(33, viewports[1].y)

        // 3rd pane gets the rest: 50x67
        assertEquals(50, viewports[2].width)
        assertEquals(67, viewports[2].height)
        assertEquals(50, viewports[2].x)
        assertEquals(33, viewports[2].y)
    }

    @Test
    fun testZeroDimensionsReturnsEmpty() {
        val config = listOf(PaneConfig(id = "1"))
        val viewports1 = PaneLayout.calculateLayout(0, 100, config)
        val viewports2 = PaneLayout.calculateLayout(100, 0, config)
        assertTrue(viewports1.isEmpty())
        assertTrue(viewports2.isEmpty())
    }

    @Test
    fun testCustomSplitRatio() {
        val configs = listOf(
            PaneConfig(id = "1", direction = SplitDirection.Horizontal, splitRatio = 0.25f),
            PaneConfig(id = "2")
        )
        val viewports = PaneLayout.calculateLayout(100, 100, configs)
        assertEquals(2, viewports.size)
        assertEquals(25, viewports[0].height)
        assertEquals(75, viewports[1].height)
    }

    @Test
    fun testAllIDsUnique() {
        val configs = listOf(
            PaneConfig(id = "duplicate", direction = SplitDirection.Horizontal, splitRatio = 0.5f),
            PaneConfig(id = "duplicate")
        )
        val viewports = PaneLayout.calculateLayout(100, 100, configs)
        // Should only return 1 pane because of distinctBy
        assertEquals(1, viewports.size)
        assertEquals("duplicate", viewports[0].id)
        assertEquals(100, viewports[0].width)
        assertEquals(100, viewports[0].height)
    }

    @Test
    fun testFullContainer1080x1920() {
        val config = listOf(PaneConfig(id = "1"))
        val viewports = PaneLayout.calculateLayout(1080, 1920, config)
        assertEquals(1, viewports.size)
        assertEquals(1080, viewports[0].width)
        assertEquals(1920, viewports[0].height)
    }
}
