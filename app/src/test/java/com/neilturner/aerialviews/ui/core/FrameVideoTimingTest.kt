package com.neilturner.aerialviews.ui.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FrameVideoTimingTest {
    @Test
    fun `short videos repeat and longer videos stop at slide time`() {
        assertEquals(FrameVideoTiming(30_000, true), frameVideoTiming(8_000, 30_000))
        assertEquals(FrameVideoTiming(30_000, false), frameVideoTiming(30_000, 30_000))
        assertEquals(FrameVideoTiming(30_000, false), frameVideoTiming(45_000, 30_000))
        assertEquals(FrameVideoTiming(30_000, true), frameVideoTiming(-1, 30_000))
        assertNull(frameVideoTiming(8_000, 0))
    }

    @Test
    fun `unknown duration keeps elapsed slide time across loops and pauses`() {
        val clock = FramePlaybackClock()
        clock.resume(1_000)
        assertEquals(16_000, clock.elapsed(17_000))
        clock.pause(17_000)
        assertEquals(16_000, clock.elapsed(25_000))
        clock.resume(25_000)
        assertEquals(20_000, clock.elapsed(29_000))
        clock.reset()
        assertEquals(0, clock.elapsed(30_000))
    }
}
