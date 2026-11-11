package com.neilturner.aerialviews.ui.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PortraitCollageLayoutTest {
    @Test
    fun `three-photo composition fills screen with a large photo beside two smaller ones`() {
        val photo = PortraitCollageLayout.Size(1200, 1800)
        val boxes = PortraitCollageLayout.arrangeThree(PortraitCollageLayout.Size(1920, 1080), photo, photo, photo)!!
        assertEquals(PortraitCollageLayout.Box(0, 0, 1210, 1080), boxes.first)
        assertEquals(PortraitCollageLayout.Box(1280, 0, 1920, 505), boxes.second)
        assertEquals(PortraitCollageLayout.Box(1280, 575, 1920, 1080), boxes.third)
        val fourK = PortraitCollageLayout.arrangeThree(PortraitCollageLayout.Size(3840, 2160), photo, photo, photo)!!
        assertEquals(PortraitCollageLayout.Box(0, 0, 2420, 2160), fourK.first)
        assertEquals(PortraitCollageLayout.Box(2560, 1150, 3840, 2160), fourK.third)
        assertNull(
            PortraitCollageLayout.arrangeThree(PortraitCollageLayout.Size(1920, 1080), photo, photo, PortraitCollageLayout.Size(0, 1)),
        )
    }

    @Test
    fun `fills screen with two panels separated by a 70 pixel black divider`() {
        val (first, second) =
            PortraitCollageLayout.arrange(
                PortraitCollageLayout.Size(1920, 1080),
                PortraitCollageLayout.Size(900, 1600),
                PortraitCollageLayout.Size(1000, 1500),
            )!!
        assertEquals(PortraitCollageLayout.Box(0, 0, 925, 1080), first)
        assertEquals(PortraitCollageLayout.Box(995, 0, 1920, 1080), second)
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
