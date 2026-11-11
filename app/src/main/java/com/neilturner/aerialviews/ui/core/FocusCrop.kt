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

    /**
     * A tight crop is acceptable only while it keeps this share of the source's longer side.
     * Below it (for example a 9:16 portrait on a 16:9 screen keeps 32%) a standing person loses head or body,
     * so the photo is fitted over the blurred backdrop instead. A saved focus point always crops.
     * ponytail: aspect heuristic, not face detection; replace with face boxes if Krill ever stores them.
     */
    const val MIN_KEPT_SHARE = 0.35f

    fun keepsSubject(
        sourceWidth: Int?,
        sourceHeight: Int?,
        width: Int,
        height: Int,
        hasSavedFocus: Boolean,
    ): Boolean {
        if (hasSavedFocus) return true
        if (sourceWidth == null || sourceHeight == null || sourceWidth <= 0 || sourceHeight <= 0 || width <= 0 || height <= 0) return true
        val source = sourceWidth.toFloat() / sourceHeight
        val target = width.toFloat() / height
        return minOf(source / target, target / source) >= MIN_KEPT_SHARE
    }

    /** Without a saved point, a portrait source is cropped around its upper part, where heads usually are. */
    fun defaultFocus(
        sourceWidth: Int,
        sourceHeight: Int,
    ): Point? = if (sourceWidth in 1 until sourceHeight) Point(0.5f, PORTRAIT_FOCUS_Y) else null

    const val PORTRAIT_FOCUS_Y = 0.38f

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
