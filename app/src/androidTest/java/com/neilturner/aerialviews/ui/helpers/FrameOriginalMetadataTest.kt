package com.neilturner.aerialviews.ui.helpers

import android.location.Geocoder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run only with a deliberately supplied, app-private GPS-bearing test original. */
@RunWith(AndroidJUnit4::class)
class FrameOriginalMetadataTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun readsGpsFromOriginalOnDevice() {
        val file = context.filesDir.resolve("frame-metadata-probe.jpg")
        assumeTrue("No test original supplied", file.isFile)
        val header = file.inputStream().use { it.readNBytes(BitmapHelper.HEADER_BUFFER_SIZE) }
        val exif = BitmapHelper.extractExifMetadataFromHeader(header, header.size)
        assertNotNull("Original has no readable latitude", exif.latitude)
        assertNotNull("Original has no readable longitude", exif.longitude)
    }

    @Test
    fun resolvesGpsOnDevice() =
        runBlocking {
            assumeTrue("Device has no geocoder", Geocoder.isPresent())
            val file = context.filesDir.resolve("frame-metadata-probe.jpg")
            assumeTrue("No test original supplied", file.isFile)
            val header = file.inputStream().use { it.readNBytes(BitmapHelper.HEADER_BUFFER_SIZE) }
            val exif = BitmapHelper.extractExifMetadataFromHeader(header, header.size)
            val latitude = requireNotNull(exif.latitude)
            val longitude = requireNotNull(exif.longitude)
            val location = withTimeout(15_000) { GeocoderHelper.reverseGeocode(context, latitude, longitude) }
            assertNotNull("Device could not resolve the supplied location", location)
        }
}
