package com.neilturner.aerialviews.providers.custom

/** Decide whether a source can be displayed without enlarging its pixels. */
internal object KrillResolutionPolicy {
    fun isSuitable(
        width: Int,
        height: Int,
        displayWidth: Int,
        displayHeight: Int,
        fitInside: Boolean,
    ): Boolean {
        if (width <= 0 || height <= 0 || displayWidth <= 0 || displayHeight <= 0) return false
        return if (fitInside) {
            // FIT_CENTER scales by the smaller of the two factors.
            width >= displayWidth || height >= displayHeight
        } else {
            // CENTER_CROP must cover both display dimensions.
            width >= displayWidth && height >= displayHeight
        }
    }
}
