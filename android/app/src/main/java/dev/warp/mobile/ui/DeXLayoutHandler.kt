package dev.warp.mobile.ui

data class DeXDesktopState(
    val isDesktopMode: Boolean,
    val isMultiColumn: Boolean,
    val activePaneCount: Int
)

class DeXLayoutHandler {
    fun onWindowBoundsChanged(widthPx: Int, heightPx: Int, isDeXEnabled: Boolean = true): DeXDesktopState {
        val isMultiColumn = isDeXEnabled && widthPx >= 1200
        val activePaneCount = when {
            widthPx >= 1920 -> 3
            widthPx >= 1200 -> 2
            else -> 1
        }
        return DeXDesktopState(
            isDesktopMode = isDeXEnabled,
            isMultiColumn = isMultiColumn,
            activePaneCount = activePaneCount
        )
    }
}
