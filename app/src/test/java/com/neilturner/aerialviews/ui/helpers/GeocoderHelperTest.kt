package com.neilturner.aerialviews.ui.helpers

import android.content.Context
import android.location.Address
import android.location.Geocoder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GeocoderHelperTest {
    @Suppress("DEPRECATION")
    @Test
    fun `legacy geocoder timeout returns even when platform call ignores interruption`() =
        runTest {
            mockkStatic(Geocoder::class)
            mockkConstructor(Geocoder::class)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            try {
                every { Geocoder.isPresent() } returns true
                every { anyConstructed<Geocoder>().getFromLocation(39.12345, -1.23456, 1) } answers {
                    entered.countDown()
                    try {
                        while (release.count > 0) {
                            try {
                                release.await(100, TimeUnit.MILLISECONDS)
                            } catch (_: InterruptedException) {
                                // Simulate a platform implementation that does not respond to interruption.
                            }
                        }
                        null
                    } finally {
                        finished.countDown()
                    }
                }
                val context = mockk<Context>(relaxed = true)
                val result =
                    async(Dispatchers.Default) {
                        withTimeoutOrNull(300) { GeocoderHelper.reverseGeocode(context, 39.12345, -1.23456) }
                    }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertNull(withContext(Dispatchers.Default) { withTimeout(2000) { result.await() } })
            } finally {
                release.countDown()
                finished.await(5, TimeUnit.SECONDS)
                unmockkConstructor(Geocoder::class)
                unmockkStatic(Geocoder::class)
            }
        }

    @Suppress("DEPRECATION")
    @Test
    fun `retries a failed location lookup but caches a successful one`() =
        runTest {
            mockkStatic(Geocoder::class)
            mockkConstructor(Geocoder::class)
            try {
                every { Geocoder.isPresent() } returns true
                val address = mockk<Address>(relaxed = true)
                every { address.locality } returns "Barcelona"
                every { address.countryName } returns "Spain"
                every { anyConstructed<Geocoder>().getFromLocation(41.23456, 2.12345, 1) } throws IOException("temporary") andThen
                    listOf(address)
                val context = mockk<Context>(relaxed = true)
                assertNull(GeocoderHelper.reverseGeocode(context, 41.23456, 2.12345))
                assertEquals("Barcelona", GeocoderHelper.reverseGeocode(context, 41.23456, 2.12345)?.city)
                assertEquals("Barcelona", GeocoderHelper.reverseGeocode(context, 41.23456, 2.12345)?.city)
                verify(exactly = 2) { anyConstructed<Geocoder>().getFromLocation(41.23456, 2.12345, 1) }
            } finally {
                unmockkConstructor(Geocoder::class)
                unmockkStatic(Geocoder::class)
            }
        }
}
