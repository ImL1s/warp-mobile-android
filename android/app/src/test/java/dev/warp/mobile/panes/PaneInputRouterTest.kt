package dev.warp.mobile.panes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaneInputRouterTest {

    @Test
    fun testDefaultNoFocus() {
        val router = PaneInputRouter()
        assertNull(router.getFocusedPaneId())
    }

    @Test
    fun testFocusPaneRoutesInput() {
        val router = PaneInputRouter()
        router.focusPane("pane1")
        assertEquals("pane1", router.getFocusedPaneId())
        
        val routedId = router.routeInput(byteArrayOf(0x01, 0x02))
        assertEquals("pane1", routedId)
    }

    @Test
    fun testSwitchFocusBetweenPanes() {
        val router = PaneInputRouter()
        router.focusPane("pane1")
        router.focusPane("pane2")
        
        assertEquals("pane2", router.getFocusedPaneId())
        
        val routedId = router.routeInput(byteArrayOf(0x01))
        assertEquals("pane2", routedId)
    }

    @Test(expected = IllegalStateException::class)
    fun testRouteInputWithoutFocusThrows() {
        val router = PaneInputRouter()
        router.routeInput(byteArrayOf(0x01))
    }
}
