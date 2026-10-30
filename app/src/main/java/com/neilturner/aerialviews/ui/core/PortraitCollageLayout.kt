package com.neilturner.aerialviews.ui.core

/** Placement of two uncropped photographs on a landscape canvas. */
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

    fun arrange(
        canvas: Size,
        first: Size,
        second: Size,
    ): Pair<Box, Box>? {
        if (canvas.width <= 0 || canvas.height <= 0 || first.width <= 0 || first.height <= 0 || second.width <= 0 || second.height <= 0) {
            return null
        }
        // The margin and gutter scale with the output and leave a calm border around the photos.
        val margin = (canvas.height * 0.025).toInt().coerceAtLeast(1)
        val gutter = (canvas.height * 0.0125).toInt().coerceAtLeast(1)
        val availableWidth = canvas.width - 2 * margin - gutter
        val availableHeight = canvas.height - 2 * margin
        if (availableWidth < 2 || availableHeight <= 0) return null
        val leftWidth = availableWidth / 2
        val rightWidth = availableWidth - leftWidth

        fun fit(
            size: Size,
            left: Int,
            panelWidth: Int,
        ): Box {
            val scale = minOf(panelWidth.toDouble() / size.width, availableHeight.toDouble() / size.height)
            val width = (size.width * scale).toInt().coerceAtLeast(1)
            val height = (size.height * scale).toInt().coerceAtLeast(1)
            val x = left + (panelWidth - width) / 2
            val y = margin + (availableHeight - height) / 2
            return Box(x, y, x + width, y + height)
        }

        return fit(first, margin, leftWidth) to fit(second, margin + leftWidth + gutter, rightWidth)
    }
}
