package com.neilturner.aerialviews.providers.custom

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class KrillCacheFenceTest {
    @Test
    fun `logout blocks new operations and waits for existing requests`() {
        val fence = KrillCacheFence()
        val first = fence.acquire("session-a")!!
        val second = fence.acquire("session-a")!!
        fence.revoke("session-a")
        assertNull(fence.acquire("session-a"))
        assertNotNull(fence.acquire("session-b"))
        assertFalse(fence.awaitDrained("session-a", 0))
        first.close()
        first.close()
        assertFalse(fence.awaitDrained("session-a", 0))
        second.close()
        assertTrue(fence.awaitDrained("session-a", 0))
    }

    @Test
    fun `a waiter is released when a request finishes`() {
        val fence = KrillCacheFence()
        val lease = fence.acquire("session")!!
        fence.revoke("session")
        val thread = Thread { lease.close() }
        thread.start()
        assertTrue(fence.awaitDrained("session", 1000))
        thread.join()
    }

    @Test
    fun `revocation cancels current and late registrations without releasing their leases`() {
        val fence = KrillCacheFence()
        val early = fence.acquire("session")!!
        val late = fence.acquire("session")!!
        assertTrue(early.isValid())
        var cancellations = 0
        early.onCancel { cancellations++ }
        fence.revoke("session")
        assertFalse(early.isValid())
        late.onCancel { cancellations++ }
        assertTrue(cancellations == 2)
        assertFalse(fence.awaitDrained("session", 0))
        early.close()
        late.close()
        assertTrue(fence.awaitDrained("session", 0))
    }

    @Test
    fun `logout cancels a delayed network request before cleanup`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeadersDelay(5, TimeUnit.SECONDS).setBody("photo"))
            val fence = KrillCacheFence()
            val lease = fence.acquire("session")!!
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/image")).build())
            val succeeded = AtomicBoolean(false)
            lease.onCancel { call.cancel() }
            val thread =
                Thread {
                    try {
                        call.execute().use { succeeded.set(it.isSuccessful) }
                    } catch (_: java.io.IOException) {
                        // Cancellation is expected.
                    } finally {
                        lease.close()
                    }
                }
            thread.start()
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            fence.revoke("session")
            assertTrue(fence.awaitDrained("session", 3000))
            thread.join(3000)
            assertFalse(succeeded.get())
        }
    }
}
