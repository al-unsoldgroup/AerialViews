package com.neilturner.aerialviews.providers.custom

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KrillResolutionPolicyTest {
    @Test
    fun `requires enough landscape pixels for the actual output`() {
        assertTrue(KrillResolutionPolicy.isSuitable(2278, 1518, 1920, 1080, false))
        assertFalse(KrillResolutionPolicy.isSuitable(800, 600, 1920, 1080, false))
        assertFalse(KrillResolutionPolicy.isSuitable(2278, 1518, 3840, 2160, false))
        assertTrue(KrillResolutionPolicy.isSuitable(4096, 2304, 3840, 2160, false))
    }

    @Test
    fun `fits portraits at display height without enlarging`() {
        assertTrue(KrillResolutionPolicy.isSuitable(900, 1600, 1920, 1080, true))
        assertFalse(KrillResolutionPolicy.isSuitable(500, 800, 1920, 1080, true))
        assertFalse(KrillResolutionPolicy.isSuitable(900, 1600, 3840, 2160, true))
        assertFalse(KrillResolutionPolicy.isSuitable(1000, 1100, 1920, 1080, false))
        assertTrue(KrillResolutionPolicy.isSuitable(4000, 500, 1920, 1080, true))
        assertFalse(KrillResolutionPolicy.isSuitable(0, 0, 1920, 1080, true))
    }
}
