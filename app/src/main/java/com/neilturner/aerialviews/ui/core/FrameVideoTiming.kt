package com.neilturner.aerialviews.ui.core

internal data class FrameVideoTiming(
    val durationMillis: Long,
    val repeat: Boolean,
)

/** Short clips loop until the slide time; longer clips stop at the slide time. */
internal fun frameVideoTiming(
    videoMillis: Long,
    slideMillis: Long,
): FrameVideoTiming? {
    if (slideMillis <= 0) return null
    if (videoMillis <= 0) return FrameVideoTiming(slideMillis, true)
    return FrameVideoTiming(slideMillis, videoMillis < slideMillis)
}

internal class FramePlaybackClock {
    private var accumulated = 0L
    private var startedAt: Long? = null

    fun reset() {
        accumulated = 0
        startedAt = null
    }

    fun resume(now: Long) {
        if (startedAt == null) startedAt = now
    }

    fun pause(now: Long) {
        accumulated = elapsed(now)
        startedAt = null
    }

    fun elapsed(now: Long): Long = accumulated + (startedAt?.let { (now - it).coerceAtLeast(0) } ?: 0)
}
