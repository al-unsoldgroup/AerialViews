package com.neilturner.aerialviews.providers.custom

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KrillFeedTest {
    @Test
    fun `private portrait panel requests preserve selected space and use automatic crop`() {
        val image = "https://photos.example/api/frame/media/picture/image?space=mine"
        assertEquals(
            "https://photos.example/api/frame/media/picture/image?space=mine&crop=auto&width=925&height=1080",
            KrillFeed.privatePanelCrop(image, 925, 1080),
        )
        assertNull(KrillFeed.privatePanelCrop("https://photos.example/public/image", 925, 1080))
        assertNull(KrillFeed.privatePanelCrop(image, 926, 1080))
        assertEquals(
            "https://photos.example/api/frame/media/picture/image?space=mine&crop=auto&width=640&height=505",
            KrillFeed.privatePanelCrop(image, 640, 505),
        )
        assertEquals(
            "https://photos.example/api/frame/media/picture/image?space=mine&crop=auto&width=640&height=505&focus_revision=7",
            KrillFeed.privatePanelCrop(image, 640, 505, 7),
        )
    }

    @Test
    fun `private smart crop keeps the selected library and fills the display`() {
        val image = "https://photos.example/api/frame/media/picture/image?space=mine"
        assertEquals(
            "https://photos.example/api/frame/media/picture/image?space=mine&crop=auto&width=1920&height=1080",
            KrillFeed.cropForDimensions(image, 2400, 1600, 1920, 1080, privateSmartCrop = true),
        )
        assertNull(KrillFeed.cropForDimensions(image, 2400, 1600, 1920, 1080))
        assertEquals(
            "https://photos.example/api/frame/media/picture/image?space=mine&crop=auto&width=1920&height=1080",
            KrillFeed.cropForDimensions(image, 1200, 800, 1920, 1080, privateSmartCrop = true),
        )
        assertNull(KrillFeed.cropForDimensions(image, 1920, 1080, 1920, 1080, privateSmartCrop = true))
        assertNull(KrillFeed.cropForDimensions(image, 2400, 1600, 1900, 1000, privateSmartCrop = true))
        assertNull(KrillFeed.cropForDimensions("https://photos.example/public/image", 2400, 1600, 1920, 1080, privateSmartCrop = true))
    }

    @Test
    fun `private portrait can request a full screen automatic crop`() {
        val image = "https://photos.example/api/frame/media/picture/image?space=mine"
        assertEquals(
            "https://photos.example/api/frame/media/picture/image?space=mine&crop=auto&width=1920&height=1080",
            KrillFeed.cropForDimensions(image, 2400, 3200, 1920, 1080, privateSmartCrop = true),
        )
    }

    @Test
    fun `signed in private feed replaces only matching public origin`() {
        assertTrue(KrillFeed.privateFeedReplaces("https://example.com/api/public/media?frame_crop=auto", "https://example.com"))
        assertFalse(KrillFeed.privateFeedReplaces("https://example.com/api/public/media", "https://other.example"))
        assertFalse(KrillFeed.privateFeedReplaces("https://example.com/api/public/media", "https://example.com:8443"))
        assertFalse(KrillFeed.privateFeedReplaces("https://example.com/api/public/media", null))
    }

    @Test
    fun `loads all pages and creates same origin photo URLs`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody("""{"data":[{"type":"photo","cloudflare_id":"image-1","title":"First"}],"hasMore":true}"""),
            )
            server.enqueue(
                MockResponse().setBody("""{"data":[{"type":"photo","cloudflare_id":"image-2","description":"Second"}],"hasMore":false}"""),
            )
            val items = KrillFeed.fetch(OkHttpClient(), server.url("/api/public/media?tag=travel").toString())
            assertEquals(listOf("First", "Second"), items.map { it.description })
            assertEquals(server.url("/m/image-1/public").toString(), items[0].url)
            assertEquals("0", server.takeRequest().requestUrl?.queryParameter("offset"))
            val second = server.takeRequest().requestUrl!!
            assertEquals("1", second.queryParameter("offset"))
            assertEquals("travel", second.queryParameter("tag"))
        }
    }

    @Test
    fun `rejects a stalled pagination response`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":[],"hasMore":true}"""))
            assertThrows(IllegalStateException::class.java) {
                KrillFeed.fetch(OkHttpClient(), server.url("/api/public/media").toString())
            }
        }
    }

    @Test
    fun `reports a failed page instead of returning a partial library`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":[{"type":"photo","cloudflare_id":"first"}],"hasMore":true}"""))
            server.enqueue(MockResponse().setResponseCode(503))
            assertThrows(IllegalStateException::class.java) {
                KrillFeed.fetch(OkHttpClient(), server.url("/api/public/media").toString())
            }
        }
    }

    @Test
    fun `smart crop is opt in and only applied to landscape photos`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"data":[{"type":"photo","cloudflare_id":"wide","width":4000,"height":3000},{"type":"photo","cloudflare_id":"tall","width":2000,"height":3000}],"hasMore":false}""",
                ),
            )
            val items = KrillFeed.fetch(OkHttpClient(), server.url("/api/public/media?frame_crop=auto").toString())
            assertEquals(
                server.url("/cdn-cgi/image/width=1920,height=1080,fit=aspect-crop,gravity=auto/m/wide/public").toString(),
                items[0].url,
            )
            assertEquals(server.url("/m/tall/public").toString(), items[1].url)
            assertEquals(null, server.takeRequest().requestUrl?.queryParameter("frame_crop"))
        }
    }

    @Test
    fun `unknown dimensions can be checked at playback`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":[{"type":"photo","cloudflare_id":"unknown"}],"hasMore":false}"""))
            val item = KrillFeed.fetch(OkHttpClient(), server.url("/api/public/media?frame_crop=auto").toString()).single()
            assertEquals(server.url("/m/unknown/public#frame-auto").toString(), item.url)
            assertNull(KrillFeed.cropForDimensions(item.url, 600, 900))
            assertEquals(
                server.url("/cdn-cgi/image/width=1920,height=1080,fit=aspect-crop,gravity=auto/m/unknown/public").toString(),
                KrillFeed.cropForDimensions(item.url, 1200, 800),
            )
        }
    }

    @Test
    fun `smart crop requests the active display resolution`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody("""{"data":[{"type":"photo","cloudflare_id":"wide","width":4000,"height":3000}],"hasMore":false}"""),
            )
            val item = KrillFeed.fetch(OkHttpClient(), server.url("/api/public/media?frame_crop=auto").toString(), 3840, 2160).single()
            assertEquals(
                server.url("/cdn-cgi/image/width=3840,height=2160,fit=aspect-crop,gravity=auto/m/wide/public").toString(),
                item.url,
            )
            assertEquals(
                server.url("/cdn-cgi/image/width=3840,height=2160,fit=aspect-crop,gravity=auto/m/unknown/public").toString(),
                KrillFeed.cropForDimensions(server.url("/m/unknown/public#frame-auto").toString(), 4000, 3000, 3840, 2160),
            )
        }
    }
}
