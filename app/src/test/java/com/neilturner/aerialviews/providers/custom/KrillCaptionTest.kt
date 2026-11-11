package com.neilturner.aerialviews.providers.custom

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
        assertEquals("Claude Monet", KrillCaption.format(art.copy(artworkTitle = null)))
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
        assertEquals("", KrillCaption.cleanLegacy("Shared from iPhone"))
        assertEquals("Museum credit", KrillCaption.cleanLegacy("shared from iphone • Museum credit"))
        assertEquals("Shared from iPhone at the pier", KrillCaption.cleanLegacy("Shared from iPhone at the pier"))
    }

    @Test
    fun `saved photo selections lead with the Krill caption once`() {
        assertEquals("PHOTO_CONTEXT,DATE_TAKEN", KrillCaption.withPhotoContextFirst("DATE_TAKEN"))
        assertEquals(
            "PHOTO_CONTEXT,DESCRIPTION,LOCATION,DATE_TAKEN",
            KrillCaption.withPhotoContextFirst("PHOTO_CONTEXT,DESCRIPTION,LOCATION,DATE_TAKEN"),
        )
        assertEquals("PHOTO_CONTEXT,LOCATION,DATE_TAKEN", KrillCaption.withPhotoContextFirst("LOCATION, PHOTO_CONTEXT ,DATE_TAKEN"))
        assertEquals("", KrillCaption.withPhotoContextFirst(""))
    }

    @Test
    fun `overlay migration runs once and leaves unsaved and empty selections alone`() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        val prefs = mockk<SharedPreferences>()
        every { prefs.getBoolean("krill_caption_overlays_migrated", false) } returns false
        every { prefs.edit() } returns editor
        every { prefs.getString(any(), null) } returns null
        every { prefs.getString("overlay_metadata1_photos", null) } returns "PHOTO_CONTEXT,DESCRIPTION,LOCATION,DATE_TAKEN"
        every { prefs.getString("overlay_metadata4_photos", null) } returns "DATE_TAKEN"
        every { prefs.getString("overlay_metadata3_photos", null) } returns ""
        KrillCaption.migrateOverlayPrefs(prefs)
        verify { editor.putString("overlay_metadata4_photos", "PHOTO_CONTEXT,DATE_TAKEN") }
        verify { editor.putString("overlay_metadata1_photos", "PHOTO_CONTEXT,DESCRIPTION,LOCATION,DATE_TAKEN") }
        verify { editor.putString("overlay_metadata3_photos", "") }
        verify(exactly = 0) { editor.putString("overlay_metadata2_photos", any()) }
        verify { editor.putBoolean("krill_caption_overlays_migrated", true) }
        verify { editor.apply() }

        val done = mockk<SharedPreferences>()
        every { done.getBoolean("krill_caption_overlays_migrated", false) } returns true
        KrillCaption.migrateOverlayPrefs(done)
        verify(exactly = 0) { done.edit() }
    }
}
