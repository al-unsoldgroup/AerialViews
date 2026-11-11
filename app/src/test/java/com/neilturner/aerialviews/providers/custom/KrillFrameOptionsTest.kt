package com.neilturner.aerialviews.providers.custom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KrillFrameOptionsTest {
    @Test
    fun `a Krill per-item rate overrides the global speed, otherwise the global speed applies`() {
        val slow = listOf("Trip", "krill-frame:rate=0.7")
        assertEquals(0.7f, KrillFrameOptions.playbackSpeed(krill = true, tags = slow, globalSpeed = 1.5f))
        assertEquals(1.5f, KrillFrameOptions.playbackSpeed(krill = true, tags = listOf("Trip"), globalSpeed = 1.5f))
        assertEquals(1.5f, KrillFrameOptions.playbackSpeed(krill = false, tags = slow, globalSpeed = 1.5f))
        assertEquals(1f, KrillFrameOptions.playbackSpeed(krill = true, tags = listOf("krill-frame:rate=3"), globalSpeed = 1f))
    }

    @Test
    fun `shown as 16 by 9 implies no collage`() {
        assertTrue(KrillFrameOptions.singleFrame(listOf(KrillFrameOptions.SINGLE_16_9)))
        assertTrue(KrillFrameOptions.showsAlone(listOf(KrillFrameOptions.SINGLE_16_9)))
        assertTrue(KrillFrameOptions.showsAlone(listOf(KrillFrameOptions.NO_COLLAGE)))
        assertFalse(KrillFrameOptions.singleFrame(listOf(KrillFrameOptions.NO_COLLAGE)))
        assertFalse(KrillFrameOptions.showsAlone(listOf("art")))
    }
}
