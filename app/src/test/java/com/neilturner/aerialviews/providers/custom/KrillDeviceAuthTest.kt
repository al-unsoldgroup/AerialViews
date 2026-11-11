package com.neilturner.aerialviews.providers.custom

import com.neilturner.aerialviews.data.network.JsonHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class KrillDeviceAuthTest {
    @Test
    fun `cancel interrupts stalled OAuth discovery and token requests`() =
        runBlocking {
            for (stallToken in listOf(false, true)) {
                MockWebServer().use { server ->
                    if (stallToken) server.enqueue(discovery(server))
                    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                    val worker =
                        launch(Dispatchers.IO) {
                            val client = cancellableKrillClient(currentCoroutineContext()[Job])
                            runCatching { KrillDeviceAuth(client, server.url("/").toString(), "client").refresh("token") }
                        }
                    assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
                    if (stallToken) assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
                    withTimeout(5000) { worker.cancelAndJoin() }
                }
            }
        }

    @Test
    fun `the TV never requests deletion and drops a deletion saved by an earlier build`() {
        MockWebServer().use { server ->
            server.enqueue(discovery(server))
            server.enqueue(
                MockResponse().setBody(
                    """{"device_code":"secret","user_code":"ABCD","verification_uri":"${server.url("/verify")}","expires_in":600}""",
                ),
            )
            KrillDeviceAuth(OkHttpClient(), server.url("/").toString(), "client").start()
            server.takeRequest()
            assertEquals("client_id=client&scope=openid+email+offline_access", server.takeRequest().body.readUtf8())
        }
        val legacy =
            """{"issuer":"i","clientId":"c","libraryOrigin":"o","accessToken":"a","refreshToken":"r","expiresAtMillis":1,""" +
                """"cacheNamespace":"n","pendingDeletion":{"imageUrl":"u","operation":"p"}}"""
        val session = JsonHelper.json.decodeFromString<KrillTokenStore.Session>(legacy)
        assertEquals("n", session.cacheNamespace)
        assertFalse(JsonHelper.json.encodeToString(KrillTokenStore.Session.serializer(), session).contains("pendingDeletion"))
    }

    private fun discovery(server: MockWebServer) =
        MockResponse().setBody(
            """{"device_authorization_endpoint":"${server.url("/device")}","token_endpoint":"${server.url("/token")}"}""",
        )

    @Test
    fun `requests device code and handles pending slow down and authorization`() {
        MockWebServer().use { server ->
            server.enqueue(discovery(server))
            server.enqueue(
                MockResponse().setBody(
                    """{"device_code":"secret","user_code":"ABCD","verification_uri":"${server.url(
                        "/verify",
                    )}","expires_in":600,"interval":7}""",
                ),
            )
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"authorization_pending"}"""))
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"slow_down"}"""))
            server.enqueue(
                MockResponse().setBody("""{"access_token":"access","refresh_token":"refresh","expires_in":3600,"token_type":"Bearer"}"""),
            )
            val auth = KrillDeviceAuth(OkHttpClient(), server.url("/").toString(), "client")
            val device = auth.start()
            assertEquals("ABCD", device.userCode)
            assertEquals(7, device.interval)
            assertEquals(KrillDeviceAuth.PollResult.Pending, auth.poll(device.deviceCode))
            assertEquals(KrillDeviceAuth.PollResult.SlowDown, auth.poll(device.deviceCode))
            val result = assertInstanceOf(KrillDeviceAuth.PollResult.Authorized::class.java, auth.poll(device.deviceCode))
            assertEquals("refresh", result.tokens.refreshToken)
            assertEquals("/.well-known/oauth-authorization-server", server.takeRequest().path)
            assertEquals("/device", server.takeRequest().path)
            assertEquals("/token", server.takeRequest().path)
        }
    }

    @Test
    fun `does not send secrets to a different origin`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"device_authorization_endpoint":"https://other.example/device","token_endpoint":"https://other.example/token"}""",
                ),
            )
            val auth = KrillDeviceAuth(OkHttpClient(), server.url("/").toString(), "client")
            assertThrows(IllegalArgumentException::class.java) { auth.start() }
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `refresh reports denied grants without leaking response contents`() {
        MockWebServer().use { server ->
            server.enqueue(discovery(server))
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant","error_description":"private"}"""))
            val auth = KrillDeviceAuth(OkHttpClient(), server.url("/").toString(), "client")
            assertEquals(KrillDeviceAuth.PollResult.Rejected("invalid_grant"), auth.refresh("refresh-secret"))
        }
    }

    @Test
    fun `credential objects do not print device codes or tokens`() {
        val device = KrillDeviceAuth.DeviceCode("private-device-code", "ABCD", "https://example.com", expiresIn = 600)
        val tokens = KrillDeviceAuth.Tokens("private-access", "private-refresh", 3600, "Bearer")
        val session =
            KrillTokenStore.Session(
                "https://example.com",
                "client",
                "https://example.com",
                "private-access",
                "private-refresh",
                1,
            )
        org.junit.jupiter.api.Assertions
            .assertFalse(device.toString().contains("private-device-code"))
        org.junit.jupiter.api.Assertions
            .assertFalse(tokens.toString().contains("private-access"))
        org.junit.jupiter.api.Assertions
            .assertFalse(tokens.toString().contains("private-refresh"))
        org.junit.jupiter.api.Assertions
            .assertFalse(session.toString().contains("private-access"))
        org.junit.jupiter.api.Assertions
            .assertFalse(session.toString().contains("private-refresh"))
    }
}
