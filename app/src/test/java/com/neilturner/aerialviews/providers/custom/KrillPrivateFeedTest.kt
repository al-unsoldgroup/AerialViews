package com.neilturner.aerialviews.providers.custom

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class KrillPrivateFeedTest {
    @Test
    fun `rejects a changing total or repeated media during pagination`() {
        val firstPage = (0 until 100).joinToString(",") { """{"id":"photo-$it","title":"Photo"}""" }
        for (lastPage in listOf(
            """{"total":100,"media":[]}""",
            """{"total":101,"media":[{"id":"photo-1","title":"Photo"}]}""",
        )) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("""{"total":101,"media":[$firstPage]}"""))
                server.enqueue(MockResponse().setBody(lastPage))
                val feed = KrillPrivateFeed(OkHttpClient(), server.url("/").toString(), "test-token")
                assertThrows(IllegalStateException::class.java) { feed.photos("mine") }
            }
        }
    }

    @Test
    fun `rejects revision changes even when totals and IDs look consistent`() {
        val firstPage = (0 until 100).joinToString(",") { """{"id":"photo-$it","title":"Photo"}""" }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"total":101,"revision":"10","media":[$firstPage]}"""))
            server.enqueue(MockResponse().setBody("""{"total":101,"revision":"12","media":[{"id":"new-photo","title":"Photo"}]}"""))
            val feed = KrillPrivateFeed(OkHttpClient(), server.url("/").toString(), "test-token")
            assertThrows(IllegalStateException::class.java) { feed.photos("mine") }
        }
    }

    @Test
    fun `advertised revision is required and returned to the caller`() {
        MockWebServer().use { server ->
            val feed = KrillPrivateFeed(OkHttpClient(), server.url("/").toString(), "test-token")
            server.enqueue(MockResponse().setBody("""{"total":0,"media":[]}"""))
            assertThrows(IllegalStateException::class.java) { feed.photos("mine", requireRevision = true) }
            server.enqueue(MockResponse().setBody("""{"total":0,"revision":"12","media":[]}"""))
            var revision: String? = null
            feed.photos("mine", requireRevision = true, onRevision = { revision = it })
            assertEquals("12", revision)
        }
    }

    @Test
    fun `reads advertised private image crop capability`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"issuer":"https://auth.example","clientId":"frame","imageCropAvailable":true}"""))
            assertTrue(KrillPrivateFeed.config(OkHttpClient(), server.url("/").toString()).imageCropAvailable)
        }
    }

    @Test
    fun `lists videos and resolves only approved signed stream URLs`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"total":1,"media":[{"id":"clip","title":"Moving picture"}]}"""))
            val feed = KrillPrivateFeed(OkHttpClient(), server.url("/").toString(), "test-token")
            val item = feed.videos("mine").single()
            assertTrue(item.video)
            assertTrue(KrillPrivateFeed.isFrameVideo(item.url))
            assertTrue(KrillPrivateFeed.isSelectedVideo(item.url, server.url("/").toString(), "mine"))
            assertFalse(KrillPrivateFeed.isSelectedVideo(item.url, server.url("/").toString(), "other"))
            assertFalse(KrillPrivateFeed.isFrameVideo(server.url("/public/clip").toString()))
            assertFalse(KrillPrivateFeed.isAuthorizedFrameMedia(item.url, null, null))
            assertFalse(KrillPrivateFeed.isAuthorizedFrameMedia(item.url, server.url("/").toString(), "other"))
            assertTrue(KrillPrivateFeed.isAuthorizedFrameMedia(item.url, server.url("/").toString(), "mine"))
            assertTrue(KrillPrivateFeed.isAuthorizedFrameMedia(server.url("/public/clip").toString(), null, null))
            assertEquals("/api/frame/media?space=mine&type=video&limit=100&offset=0", server.takeRequest().path)
            server.enqueue(
                MockResponse().setBody("""{"url":"https://customer-test.cloudflarestream.com/aaa.bbb.ccc/manifest/video.m3u8"}"""),
            )
            assertEquals(
                "https://customer-test.cloudflarestream.com/aaa.bbb.ccc/manifest/video.m3u8",
                feed.videoPlaybackUrl(item.url, "mine"),
            )
            val request = server.takeRequest()
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
            assertThrows(IllegalArgumentException::class.java) { feed.videoPlaybackUrl(item.url, "other") }
            server.enqueue(MockResponse().setBody("""{"url":"https://evil.example/aaa.bbb.ccc/manifest/video.m3u8"}"""))
            assertThrows(IOException::class.java) { feed.videoPlaybackUrl(item.url, "mine") }
        }
    }

    @Test
    fun `cancelled photo request interrupts a stalled feed`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val feed = KrillPrivateFeed(OkHttpClient(), server.url("/").toString(), "test-token")
            val executor = Executors.newSingleThreadExecutor()
            try {
                val call = AtomicReference<okhttp3.Call>()
                val result =
                    executor.submit<Boolean> {
                        runCatching {
                            feed.photos("mine", onCall = { active ->
                                call.set(active);
                                {}
                            })
                        }.isFailure
                    }
                assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
                call.get().cancel()
                assertTrue(result.get(5, TimeUnit.SECONDS))
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `preserves artwork credit and description in photo captions`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"total":2,"media":[{"id":"one","title":"Painting","credit":"Artist","description":"A landscape"},{"id":"two","title":"Same","description":"Same","credit":" "}]}""",
                ),
            )
            val feed = KrillPrivateFeed(OkHttpClient(), server.url("/").toString(), "test-token")
            assertEquals(listOf("Painting • Artist • A landscape", "Same"), feed.photos("mine").map { it.description })
        }
    }

    @Test
    fun `cancel requires a server confirmed outcome`() {
        MockWebServer().use { server ->
            val feed = KrillPrivateFeed(OkHttpClient(), server.url("/").toString(), "test-token")
            val image = server.url("/api/frame/media/one/image?space=mine").toString()
            val operation = "a1b2c3d4-1234-4000-8000-123456789abc"
            server.enqueue(MockResponse().setBody("""{"status":"cancelled"}"""))
            assertEquals("cancelled", feed.cancelUnacceptedDeletion(image, "mine", operation))
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/frame/media/one/deletion-cancel?space=mine", request.path)
            assertEquals(operation, request.getHeader("Idempotency-Key"))
            server.enqueue(MockResponse().setBody("""{"status":"expired"}"""))
            assertEquals("expired", feed.cancelUnacceptedDeletion(image, "mine", operation))
            server.enqueue(MockResponse().setBody("""{"status":"unknown"}"""))
            assertThrows(IOException::class.java) { feed.cancelUnacceptedDeletion(image, "mine", operation) }
        }
    }

    @Test
    fun `checks deletion capability on the selected library`() {
        MockWebServer().use { server ->
            val feed = KrillPrivateFeed(OkHttpClient(), server.url("/").toString(), "test-token")
            server.enqueue(MockResponse().setBody("""{"canDelete":false}"""))
            assertFalse(feed.canDelete("mine"))
            val request = server.takeRequest()
            assertEquals("/api/frame/permissions?space=mine", request.path)
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
        }
    }

    @Test
    fun `pending photo identity ignores query order and fragment but retains space and origin`() {
        val original = "https://photos.example/api/frame/media/one/image?space=mine"
        assertTrue(KrillPrivateFeed.sameFramePhoto(original, "https://photos.example/api/frame/media/one/image?x=1&space=mine#detail"))
        assertFalse(KrillPrivateFeed.sameFramePhoto(original, "https://photos.example/api/frame/media/one/image?space=other"))
        assertFalse(KrillPrivateFeed.sameFramePhoto(original, "https://other.example/api/frame/media/one/image?space=mine"))
        assertFalse(KrillPrivateFeed.sameFramePhoto(original, "https://photos.example/api/frame/media/two/image?space=mine"))
    }

    @Test
    fun `checks deletion status only within the selected library`() {
        MockWebServer().use { server ->
            val feed = KrillPrivateFeed(OkHttpClient(), server.url("/").toString(), "test-token")
            val image = server.url("/api/frame/media/one/image?space=mine").toString()
            val operation = "a1b2c3d4-1234-4000-8000-123456789abc"
            assertThrows(IllegalArgumentException::class.java) { feed.deletionStatus(image, "other", operation) }
            server.enqueue(MockResponse().setBody("""{"status":"completed"}"""))
            assertEquals("completed", feed.deletionStatus(image, "mine", operation))
            val request = server.takeRequest()
            assertEquals("/api/frame/media/one/deletion-status?space=mine", request.path)
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
            assertEquals(operation, request.getHeader("Idempotency-Key"))
            server.enqueue(MockResponse().setBody("""{"status":"unexpected"}"""))
            assertThrows(IOException::class.java) { feed.deletionStatus(image, "mine", operation) }
        }
    }

    @Test
    fun `delete only sends selected library photos without following redirects`() {
        MockWebServer().use { server ->
            val feed = KrillPrivateFeed(OkHttpClient(), server.url("/").toString(), "test-token")
            val image = server.url("/api/frame/media/one/image?space=mine").toString()
            val operation = "a1b2c3d4-1234-4000-8000-123456789abc"
            assertThrows(IllegalArgumentException::class.java) { feed.deletePhoto(image, "other", operation) }
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/other")))
            assertThrows(IOException::class.java) { feed.deletePhoto(image, "mine", operation) }
            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/api/frame/media/one?space=mine", request.path)
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
            assertEquals(operation, request.getHeader("Idempotency-Key"))
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(202).setBody("""{"status":"pending","operation":"$operation"}"""))
            assertEquals("pending", feed.deletePhoto(image, "mine", operation))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"completed","operation":"$operation"}"""))
            assertEquals("completed", feed.deletePhoto(image, "mine", operation))
            server.enqueue(MockResponse().setResponseCode(200))
            assertThrows(Exception::class.java) { feed.deletePhoto(image, "mine", operation) }
        }
    }

    @Test
    fun `cached photos belong only to selected origin and space`() {
        val url = "https://photos.example/api/frame/media/one/image?space=mine"
        assertTrue(KrillPrivateFeed.isFrameImage(url))
        assertTrue(KrillPrivateFeed.isFrameImage("https://photos.example/other/../api/frame/media/one/image?space=mine"))
        assertFalse(KrillPrivateFeed.isFrameImage("https://photos.example/m/one/public"))
        assertTrue(KrillPrivateFeed.isSelectedImage(url, "https://photos.example", "mine"))
        assertFalse(KrillPrivateFeed.isSelectedImage(url, "https://photos.example", "other"))
        assertFalse(KrillPrivateFeed.isSelectedImage(url, "https://other.example", "mine"))
        assertFalse(KrillPrivateFeed.isSelectedImage(url, "https://photos.example", null))
        val match = KrillPrivateFeed.selectedImageMatcher("https://photos.example", "mine")
        assertTrue(match("https://photos.example/api/frame/media/id_123-a/image?space=mine"))
        assertTrue(match("https://photos.example/api/frame/media/id%20one/image?space=mine"))
        assertFalse(match("https://photos.example/api/frame/media/id/extra/image?space=mine"))
        assertFalse(match("https://photos.example.evil/api/frame/media/id/image?space=mine"))
        assertFalse(match("https://photos.example/api/frame/media/id/image?space=mine2"))
    }

    @Test
    fun `discovers public login configuration without credentials`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"issuer":"https://auth.example.com","clientId":"frame-client"}"""))
            val config = KrillPrivateFeed.config(OkHttpClient(), server.url("/").toString())
            assertEquals("frame-client", config.clientId)
            assertEquals("/api/frame/config", server.takeRequest().path)
        }
    }

    @Test
    fun `loads spaces and all photo pages using bearer auth`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"spaces":[{"id":"mine","name":"My library"}]}"""))
            server.enqueue(
                MockResponse().setBody(
                    """{"total":2,"media":[{"id":"one","title":"First","width":3000,"height":2000,"tags":["Penguin","Trip"],""" +
                        """"pair":{"partner_id":"two","score":0.9,"reason":"same_occasion: 4 min apart"}}]}""",
                ),
            )
            server.enqueue(MockResponse().setBody("""{"total":2,"media":[{"id":"two","description":"Second"}]}"""))
            val feed = KrillPrivateFeed(OkHttpClient(), server.url("/").toString(), "test-token")
            assertEquals("My library", feed.spaces().single().name)
            val photos = feed.photos("mine")
            assertEquals(listOf("First", "Second"), photos.map { it.description })
            assertEquals(3000, photos.first().width)
            assertEquals(2000, photos.first().height)
            assertEquals(listOf("Penguin", "Trip"), photos.first().tags)
            assertEquals(emptyList<String>(), photos.last().tags)
            assertEquals("two", photos.first().pairPartner)
            assertEquals(null, photos.last().pairPartner)
            assertEquals(null, photos.last().width)
            assertEquals(server.url("/api/frame/media/one/image?space=mine").toString(), photos.first().url)
            assertEquals("Bearer test-token", server.takeRequest().getHeader("Authorization"))
            assertEquals("0", server.takeRequest().requestUrl?.queryParameter("offset"))
            assertEquals("1", server.takeRequest().requestUrl?.queryParameter("offset"))
        }
    }

    @Test
    fun `never follows redirects with bearer credentials`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/other")))
            val feed = KrillPrivateFeed(OkHttpClient(), server.url("/").toString(), "test-token")
            assertThrows(IOException::class.java) { feed.spaces() }
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `rejects stalled pages and invalid library URLs`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"total":1,"media":[]}"""))
            val feed = KrillPrivateFeed(OkHttpClient(), server.url("/").toString(), "test-token")
            assertThrows(IllegalStateException::class.java) { feed.photos("mine") }
            assertThrows(IllegalArgumentException::class.java) {
                KrillPrivateFeed(OkHttpClient(), server.url("/untrusted/path").toString(), "test-token")
            }
        }
    }
}
