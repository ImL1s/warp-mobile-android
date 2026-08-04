package dev.warp.mobile.ui

enum class WarpLayoutType {
    COMPACT_SINGLE_PANE,
    MEDIUM_SPLIT_PANE,
    EXPANDED_DUAL_PANE,
    TABLETOP_POSTURE,
    BOOK_POSTURE
}

object WarpAdaptiveLayout {
    fun calculateWarpLayoutType(
        widthSizeClass: WindowSizeClass,
        posture: DevicePosture,
        isDesktopMode: Boolean = false
    ): WarpLayoutType {
        if (posture is DevicePosture.TableTop) {
            return WarpLayoutType.TABLETOP_POSTURE
        }
        if (posture is DevicePosture.Book) {
            return WarpLayoutType.BOOK_POSTURE
        }
        return when (widthSizeClass) {
            WindowSizeClass.Compact -> WarpLayoutType.COMPACT_SINGLE_PANE
            WindowSizeClass.Medium -> WarpLayoutType.MEDIUM_SPLIT_PANE
            WindowSizeClass.Expanded -> WarpLayoutType.EXPANDED_DUAL_PANE
        }
    }
}
