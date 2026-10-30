package com.neilturner.aerialviews.ui.core

import com.neilturner.aerialviews.models.videos.AerialExifMetadata
import com.neilturner.aerialviews.ui.helpers.ExifMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KrillPhotoMetadataTest {
    @Test
    fun `updates available capture data and keeps missing fields`() {
        val target = AerialExifMetadata(date = "old", latitude = 1.0, longitude = 2.0, description = "known")
        KrillPhotoMetadata.applyExif(target, ExifMetadata(date = "2020:01:02 03:04:05", latitude = 41.0, longitude = 2.1))
        assertEquals("2020:01:02 03:04:05", target.date)
        assertEquals(41.0, target.latitude)
        assertEquals(2.1, target.longitude)
        assertEquals("known", target.description)
    }

    @Test
    fun `does not mix coordinates from different sources`() {
        val target = AerialExifMetadata(latitude = 1.0, longitude = 2.0)
        KrillPhotoMetadata.applyExif(target, ExifMetadata(latitude = 41.0))
        assertEquals(1.0, target.latitude)
        assertEquals(2.0, target.longitude)
    }

    @Test
    fun `invalid coordinates do not replace a known location`() {
        for ((latitude, longitude) in listOf(91.0 to 2.0, 41.0 to -181.0, Double.NaN to 2.0, 41.0 to Double.POSITIVE_INFINITY)) {
            val target = AerialExifMetadata(latitude = 1.0, longitude = 2.0)
            KrillPhotoMetadata.applyExif(target, ExifMetadata(latitude = latitude, longitude = longitude))
            assertEquals(1.0, target.latitude)
            assertEquals(2.0, target.longitude)
        }
    }
}
