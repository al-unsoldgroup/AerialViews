package com.neilturner.aerialviews.ui.core

/** Viewports for two photographs on a landscape canvas. */
internal object PortraitCollageLayout {
    data class Size(
        val width: Int,
        val height: Int,
    )

    data class Box(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    fun arrangeThree(
        canvas: Size,
        first: Size,
        second: Size,
        third: Size,
    ): Triple<Box, Box, Box>? {
        if (canvas.width <= 0 || canvas.height <= 0 || listOf(first, second, third).any { it.width <= 0 || it.height <= 0 }) {
            return null
        }
        val gutter = (canvas.height * 70L / 1080).toInt().coerceAtLeast(1)
        val rightStart = (canvas.width * 2L / 3).toInt()
        val upperHeight = (canvas.height - gutter) / 2
        if (rightStart <= gutter || rightStart >= canvas.width || upperHeight <= 0) return null
        return Triple(
            Box(0, 0, rightStart - gutter, canvas.height),
            Box(rightStart, 0, canvas.width, upperHeight),
            Box(rightStart, upperHeight + gutter, canvas.width, canvas.height),
        )
    }

    fun arrange(
        canvas: Size,
        first: Size,
        second: Size,
    ): Pair<Box, Box>? {
        if (canvas.width <= 0 || canvas.height <= 0 || first.width <= 0 || first.height <= 0 || second.width <= 0 || second.height <= 0) {
            return null
        }
        // 70 pixels at 1080p is approximately one inch on a 32-inch 16:9 display.
        val gutter = (canvas.height * 70L / 1080).toInt().coerceAtLeast(1)
        val availableWidth = canvas.width - gutter
        if (availableWidth < 2) return null
        val leftWidth = availableWidth / 2
        return Box(0, 0, leftWidth, canvas.height) to Box(leftWidth + gutter, 0, canvas.width, canvas.height)
    }
}
