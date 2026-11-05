package com.neilturner.aerialviews.providers.custom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class KrillCaptionTest {
    private val time = "2026-10-22T16:07:18"

    @Test
    fun `shows the most specific place then a pretty local time`() {
        val fields = KrillCaption.Fields("Opera House", "The Rocks", "Sydney", "Australia", time)
        assertEquals("Opera House, Sydney, Australia\nThu 22 Oct 2026, 4:07 pm", KrillCaption.format(fields))
        assertEquals(
            "The Rocks, Sydney, Australia\nThu 22 Oct 2026, 4:07 pm",
            KrillCaption.format(fields.copy(placeAttraction = null)),
        )
        assertEquals("Australia", KrillCaption.format(KrillCaption.Fields(placeCountry = "Australia")))
        assertEquals(
            "Sydney, Australia",
            KrillCaption.format(KrillCaption.Fields(placeNeighborhood = "Sydney", placeCity = "Sydney", placeCountry = "Australia")),
        )
    }

    @Test
    fun `Google Photos imports without GPS show the time alone`() {
        assertEquals("Thu 22 Oct 2026, 4:07 pm", KrillCaption.format(KrillCaption.Fields(capturedLocal = time)))
        assertEquals("Sat 3 Jan 2026, 9:05 am", KrillCaption.prettyTime("2026-01-03T09:05:00"))
    }

    @Test
    fun `artwork shows title and artist instead of place and time`() {
        val art =
            KrillCaption.Fields(
                placeCity = "Paris",
                capturedLocal = time,
                artworkTitle = "Water Lilies",
                artworkArtist = "Claude Monet",
            )
        assertEquals("Water Lilies\nClaude Monet", KrillCaption.format(art))
        assertEquals("Water Lilies", KrillCaption.format(art.copy(artworkArtist = " ")))
    }

    @Test
    fun `unknown or invalid fields are left out`() {
        assertNull(KrillCaption.format(KrillCaption.Fields()))
        assertNull(KrillCaption.format(KrillCaption.Fields(placeCity = " ", capturedLocal = "2026-10-22T03:07:18Z")))
        assertNull(KrillCaption.prettyTime("not a time"))
    }

    @Test
    fun `legacy captions drop file names and raw timestamps but keep real text`() {
        assertEquals("", KrillCaption.cleanLegacy("IMG_1234.HEIC • 2026-10-22T03:07:18Z"))
        assertEquals("", KrillCaption.cleanLegacy("PXL_20261022_030718123.jpg • 2026-10-22T03:07:18.000+11:00"))
        assertEquals("Painting • Museum credit", KrillCaption.cleanLegacy("Painting • Museum credit • Painting"))
        assertEquals("Sunset at the pier", KrillCaption.cleanLegacy("Sunset at the pier"))
    }
}
