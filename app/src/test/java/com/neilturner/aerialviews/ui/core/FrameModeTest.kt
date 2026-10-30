package com.neilturner.aerialviews.ui.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FrameModeTest {
    @Test
    fun `history entries must still belong to the selected mode`() {
        assertTrue(FrameModePolicy.accepts(FrameMode.ART, FrameCategory.ART))
        assertFalse(FrameModePolicy.accepts(FrameMode.ART, FrameCategory.PHOTO))
        assertFalse(FrameModePolicy.accepts(FrameMode.PHOTOS, FrameCategory.ART))
        assertTrue(FrameModePolicy.accepts(FrameMode.MIXED, FrameCategory.ART))
    }

    @Test
    fun `mode selection preserves order wraps and skips excluded items`() {
        val index = FrameModeIndex(listOf(FrameCategory.PHOTO, FrameCategory.ART, FrameCategory.UNKNOWN, FrameCategory.ART))
        assertEquals(2, index.next(FrameMode.PHOTOS, 0, false, 0, 50))
        assertEquals(0, index.next(FrameMode.PHOTOS, 2, false, 0, 50))
        assertEquals(3, index.next(FrameMode.ART, 1, false, 0, 50))
        assertEquals(1, index.next(FrameMode.ART, 1, true, 0, 50) { it != 3 })
        assertEquals(3, index.next(FrameMode.MIXED, 2, false, 1, 50))
    }

    @Test
    fun `mixed mode falls back when a category is unavailable`() {
        val index = FrameModeIndex(listOf(FrameCategory.PHOTO, FrameCategory.UNKNOWN))
        assertEquals(1, index.next(FrameMode.MIXED, 0, false, 1, 50))
        assertEquals(null, index.next(FrameMode.ART, 0, false, 0, 50))
    }

    @Test
    fun `only explicit art tags classify artwork`() {
        assertEquals(FrameCategory.ART, FrameModePolicy.category(listOf("  Artwork "), true))
        assertEquals(FrameCategory.ART, FrameModePolicy.category(listOf("ART"), true))
        assertEquals(FrameCategory.PHOTO, FrameModePolicy.category(listOf("party"), true))
        assertEquals(FrameCategory.UNKNOWN, FrameModePolicy.category(emptyList(), false))
    }

    @Test
    fun `mixed distribution honors the selected percentage and bounds`() {
        for (percentage in listOf(0, 1, 25, 50, 73, 100)) {
            val artSlides = (0L until 100L).count { FrameModePolicy.mixedTarget(it, percentage) == FrameCategory.ART }
            assertEquals(percentage, artSlides)
        }
        assertEquals(FrameCategory.PHOTO, FrameModePolicy.mixedTarget(0, -1))
        assertEquals(FrameCategory.ART, FrameModePolicy.mixedTarget(0, 101))
    }
}
