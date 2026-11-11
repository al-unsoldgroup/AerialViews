package com.neilturner.aerialviews.ui.sources

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KrillSignInTest {
    @Test
    fun `qr prefers the complete address and falls back to the plain one`() {
        assertEquals("https://a.test/device?code=X", krillQrContent("https://a.test/device", "https://a.test/device?code=X"))
        assertEquals("https://a.test/device", krillQrContent("https://a.test/device", null))
        assertEquals("https://a.test/device", krillQrContent("https://a.test/device", " "))
    }

    @Test
    fun `summary names the connected library or falls back`() {
        val summary = { connected: Boolean, name: String? -> krillConnectionSummary(connected, name, "off", "on") { "on: $it" } }
        assertEquals("on: Family", summary(true, "Family"))
        assertEquals("on", summary(true, null))
        assertEquals("on", summary(true, ""))
        assertEquals("off", summary(false, "Family"))
    }
}
