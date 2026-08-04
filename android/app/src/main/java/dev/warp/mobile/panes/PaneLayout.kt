package dev.warp.mobile.panes

import java.util.UUID

enum class SplitDirection {
    Horizontal,
    Vertical
}

data class PaneConfig(
    val id: String = UUID.randomUUID().toString(),
    val direction: SplitDirection = SplitDirection.Horizontal,
    val splitRatio: Float = 0.5f
)

data class PaneViewport(
    val id: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

object PaneLayout {
    fun calculateLayout(
        containerWidth: Int,
        containerHeight: Int,
        configs: List<PaneConfig>
    ): List<PaneViewport> {
        if (containerWidth <= 0 || containerHeight <= 0 || configs.isEmpty()) {
            return emptyList()
        }

        if (configs.size == 1) {
            return listOf(PaneViewport(configs[0].id, 0, 0, containerWidth, containerHeight))
        }

        val result = mutableListOf<PaneViewport>()
        
        // Ensure unique IDs
        val uniqueConfigs = configs.distinctBy { it.id }
        
        var currentX = 0
        var currentY = 0
        var currentWidth = containerWidth
        var currentHeight = containerHeight
        
        for (i in uniqueConfigs.indices) {
            val config = uniqueConfigs[i]
            val isLast = i == uniqueConfigs.lastIndex
            
            if (isLast) {
                result.add(PaneViewport(config.id, currentX, currentY, currentWidth, currentHeight))
            } else {
                val ratio = config.splitRatio.coerceIn(0f, 1f)
                if (config.direction == SplitDirection.Horizontal) {
                    val splitHeight = (currentHeight * ratio).toInt()
                    result.add(PaneViewport(config.id, currentX, currentY, currentWidth, splitHeight))
                    currentY += splitHeight
                    currentHeight -= splitHeight
                } else {
                    val splitWidth = (currentWidth * ratio).toInt()
                    result.add(PaneViewport(config.id, currentX, currentY, splitWidth, currentHeight))
                    currentX += splitWidth
                    currentWidth -= splitWidth
                }
            }
        }
        
        return result
    }
}
