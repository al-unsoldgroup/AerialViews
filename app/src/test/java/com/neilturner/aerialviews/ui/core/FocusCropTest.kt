package com.neilturner.aerialviews.ui.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FocusCropTest {
    @Test
    fun `focus fills a nonstandard display and clamps at image edges`() {
        val centered = FocusCrop.place(1200, 1800, 1366, 768, null)!!
        val top = FocusCrop.place(1200, 1800, 1366, 768, FocusCrop.Point(0.5f, 0f))!!
        val bottom = FocusCrop.place(1200, 1800, 1366, 768, FocusCrop.Point(0.5f, 1f))!!
        assertEquals(0f, top.y)
        assertEquals(768f - 1800 * bottom.scale, bottom.y, 0.01f)
        assertEquals((top.y + bottom.y) / 2, centered.y, 0.01f)
        val resized = FocusCrop.place(1200, 1800, 768, 1366, FocusCrop.Point(0.5f, 0f))!!
        assertEquals(0f, resized.y)
        assertEquals(1366f, 1800 * resized.scale, 0.01f)
        assertNull(FocusCrop.place(0, 1800, 1366, 768, null))
    }

    @Test
    fun `single photo fills when the source covers the screen and falls back to fit otherwise`() {
        assertTrue(FocusCrop.canFill(3000, 4000, 1920, 1080))
        assertTrue(FocusCrop.canFill(1920, 1080, 1920, 1080))
        assertTrue(FocusCrop.canFill(null, null, 1920, 1080))
        assertFalse(FocusCrop.canFill(1600, 1200, 1920, 1080))
        assertFalse(FocusCrop.canFill(1080, 1920 - 1, 1920, 1080))
    }
}
