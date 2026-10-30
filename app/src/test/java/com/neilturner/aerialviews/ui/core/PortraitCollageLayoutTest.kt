package com.neilturner.aerialviews.ui.core

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PortraitCollageLayoutTest {
    @Test
    fun `fits both portraits inside separate panels without distortion`() {
        val (first, second) =
            PortraitCollageLayout.arrange(
                PortraitCollageLayout.Size(1920, 1080),
                PortraitCollageLayout.Size(900, 1600),
                PortraitCollageLayout.Size(1000, 1500),
            )!!
        assertTrue(first.left > 0 && first.top > 0 && first.bottom < 1080)
        assertTrue(second.left > first.right && second.right < 1920 && second.bottom < 1080)
        assertTrue(kotlin.math.abs((first.right - first.left).toDouble() / (first.bottom - first.top) - 900.0 / 1600) < 0.002)
        assertTrue(kotlin.math.abs((second.right - second.left).toDouble() / (second.bottom - second.top) - 1000.0 / 1500) < 0.002)
    }

    @Test
    fun `scales placement with the display and rejects invalid dimensions`() {
        val photo = PortraitCollageLayout.Size(1200, 1800)
        val fullHd = PortraitCollageLayout.arrange(PortraitCollageLayout.Size(1920, 1080), photo, photo)!!
        val fourK = PortraitCollageLayout.arrange(PortraitCollageLayout.Size(3840, 2160), photo, photo)!!
        assertTrue(kotlin.math.abs(2 * fullHd.first.left - fourK.first.left) <= 2)
        assertTrue(kotlin.math.abs(2 * fullHd.first.bottom - fourK.first.bottom) <= 2)
        assertNull(PortraitCollageLayout.arrange(PortraitCollageLayout.Size(0, 1080), photo, photo))
        assertNull(PortraitCollageLayout.arrange(PortraitCollageLayout.Size(1920, 1080), PortraitCollageLayout.Size(0, 1), photo))
    }
}
