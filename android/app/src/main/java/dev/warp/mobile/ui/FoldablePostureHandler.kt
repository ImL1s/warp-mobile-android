package dev.warp.mobile.ui

import android.graphics.Rect

enum class FoldOrientation {
    NONE,
    HORIZONTAL,
    VERTICAL
}

sealed interface DevicePosture {
    object Flat : DevicePosture
    data class TableTop(
        val topPaneBounds: Rect,
        val bottomPaneBounds: Rect,
        val hingeOcclusionHeightPx: Int
    ) : DevicePosture
    data class Book(
        val leftPaneBounds: Rect,
        val rightPaneBounds: Rect,
        val hingeOcclusionWidthPx: Int
    ) : DevicePosture
}

object FoldablePostureHandler {
    fun calculate(
        isSeparating: Boolean,
        orientation: FoldOrientation,
        hingeBounds: Rect,
        screenBounds: Rect
    ): DevicePosture {
        if (!isSeparating || orientation == FoldOrientation.NONE) {
            return DevicePosture.Flat
        }
        return when (orientation) {
            FoldOrientation.HORIZONTAL -> {
                val topPane = Rect(screenBounds.left, screenBounds.top, screenBounds.right, hingeBounds.top)
                val bottomPane = Rect(screenBounds.left, hingeBounds.bottom, screenBounds.right, screenBounds.bottom)
                val occlusionHeight = (hingeBounds.bottom - hingeBounds.top).coerceAtLeast(0)
                DevicePosture.TableTop(topPane, bottomPane, occlusionHeight)
            }
            FoldOrientation.VERTICAL -> {
                val leftPane = Rect(screenBounds.left, screenBounds.top, hingeBounds.left, screenBounds.bottom)
                val rightPane = Rect(hingeBounds.right, screenBounds.top, screenBounds.right, screenBounds.bottom)
                val occlusionWidth = (hingeBounds.right - hingeBounds.left).coerceAtLeast(0)
                DevicePosture.Book(leftPane, rightPane, occlusionWidth)
            }
            FoldOrientation.NONE -> DevicePosture.Flat
        }
    }
}
