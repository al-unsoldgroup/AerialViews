package com.neilturner.aerialviews.providers.custom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KrillCacheKeyTest {
    @Test
    fun `the same image is isolated across sign-ins`() {
        val url = "https://example.com/api/frame/media/photo/image?space=mine"
        assertEquals(KrillCacheKey.forImage("first", url), KrillCacheKey.forImage("first", url))
        assertNotEquals(KrillCacheKey.forImage("first", url), KrillCacheKey.forImage("second", url))
        val crop = "$url&crop=auto&width=1920&height=1080"
        assertNotEquals(KrillCacheKey.forImage("first", url), KrillCacheKey.forImage("first", crop))
        assertNotEquals(KrillCacheKey.forImage("first", crop), KrillCacheKey.forImage("second", crop))
        assertThrows(IllegalArgumentException::class.java) { KrillCacheKey.forImage("", url) }
    }
}
