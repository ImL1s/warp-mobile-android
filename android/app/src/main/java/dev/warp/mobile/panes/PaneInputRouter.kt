package dev.warp.mobile.panes

class PaneInputRouter {
    private var focusedPaneId: String? = null

    fun focusPane(paneId: String) {
        focusedPaneId = paneId
    }

    fun getFocusedPaneId(): String? {
        return focusedPaneId
    }

    fun routeInput(input: ByteArray): String {
        val id = focusedPaneId ?: throw IllegalStateException("No pane is focused")
        // Logic to route input would go here in actual implementation
        return id
    }
}
