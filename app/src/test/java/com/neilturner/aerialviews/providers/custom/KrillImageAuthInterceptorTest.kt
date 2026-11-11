package com.neilturner.aerialviews.providers.custom

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class KrillImageAuthInterceptorTest {
    @Test
    fun `adds bearer only to frame image paths and refuses redirects`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/unrelated")))
            val client =
                OkHttpClient
                    .Builder()
                    .addNetworkInterceptor(
                        KrillImageAuthInterceptor { server.url("/").toString() to "private-token" },
                    ).build()
            assertThrows(IOException::class.java) {
                client.newCall(Request.Builder().url(server.url("/api/frame/media/photo/image?space=mine")).build()).execute().close()
            }
            assertEquals("Bearer private-token", server.takeRequest().getHeader("Authorization"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `does not add a bearer for another origin`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            val client =
                OkHttpClient
                    .Builder()
                    .addNetworkInterceptor(
                        KrillImageAuthInterceptor { "https://other.example/" to "private-token" },
                    ).build()
            client.newCall(Request.Builder().url(server.url("/api/frame/media/photo/image")).build()).execute().close()
            assertNull(server.takeRequest().getHeader("Authorization"))
        }
    }
}
