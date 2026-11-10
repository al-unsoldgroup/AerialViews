package com.neilturner.aerialviews.ui.core

/** Place a crop around a normalized subject point while filling the viewport. */
internal object FocusCrop {
    data class Point(
        val x: Float,
        val y: Float,
    )

    data class Placement(
        val scale: Float,
        val x: Float,
        val y: Float,
    )

    /** A crop may fill the screen when the source covers it without enlargement; unknown sizes fill. */
    fun canFill(
        sourceWidth: Int?,
        sourceHeight: Int?,
        width: Int,
        height: Int,
    ): Boolean {
        if (sourceWidth == null || sourceHeight == null || sourceWidth <= 0 || sourceHeight <= 0) return true
        return sourceWidth >= width && sourceHeight >= height
    }

    fun place(
        sourceWidth: Int,
        sourceHeight: Int,
        width: Int,
        height: Int,
        focus: Point?,
    ): Placement? {
        if (sourceWidth <= 0 || sourceHeight <= 0 || width <= 0 || height <= 0) return null
        val x = focus?.x?.takeIf { it.isFinite() && it in 0f..1f } ?: 0.5f
        val y = focus?.y?.takeIf { it.isFinite() && it in 0f..1f } ?: 0.5f
        val scale = maxOf(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        val scaledWidth = sourceWidth * scale
        val scaledHeight = sourceHeight * scale
        return Placement(
            scale,
            (width / 2f - x * scaledWidth).coerceIn(minOf(0f, width - scaledWidth), 0f),
            (height / 2f - y * scaledHeight).coerceIn(minOf(0f, height - scaledHeight), 0f),
        )
    }
}
