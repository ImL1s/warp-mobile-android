package dev.warp.mobile.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface WindowSizeClass {
    object Compact : WindowSizeClass
    object Medium : WindowSizeClass
    object Expanded : WindowSizeClass
}

data class WindowSizeState(
    val widthSizeClass: WindowSizeClass,
    val heightSizeClass: WindowSizeClass,
    val isDualPane: Boolean
)

object WindowSizeClassResolver {
    fun resolve(widthDp: Int, heightDp: Int): WindowSizeState {
        val widthClass = when {
            widthDp < 600 -> WindowSizeClass.Compact
            widthDp < 840 -> WindowSizeClass.Medium
            else -> WindowSizeClass.Expanded
        }
        val heightClass = when {
            heightDp < 480 -> WindowSizeClass.Compact
            heightDp < 900 -> WindowSizeClass.Medium
            else -> WindowSizeClass.Expanded
        }
        val isDualPane = widthClass != WindowSizeClass.Compact
        return WindowSizeState(widthClass, heightClass, isDualPane)
    }
}

class WindowSizeClassHandler(
    initialWidthDp: Int = 360,
    initialHeightDp: Int = 800
) {
    private val _windowState = MutableStateFlow(WindowSizeClassResolver.resolve(initialWidthDp, initialHeightDp))
    val windowState: StateFlow<WindowSizeState> = _windowState.asStateFlow()

    fun onConfigurationChanged(widthDp: Int, heightDp: Int) {
        _windowState.value = WindowSizeClassResolver.resolve(widthDp, heightDp)
    }
}
