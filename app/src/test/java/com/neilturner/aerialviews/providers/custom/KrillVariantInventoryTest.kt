package com.neilturner.aerialviews.providers.custom

import com.neilturner.aerialviews.data.PrivateCacheVariant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KrillVariantInventoryTest {
    @Test
    fun `records selected private variants and rejects unrelated identities`() {
        val original = "https://photos.example/api/frame/media/photo/image?space=mine"
        val crop = requireNotNull(KrillFeed.privatePanelCrop(original, 925, 1080, 7))
        val key = KrillCacheKey.forImage("session", crop)
        assertEquals(
            PrivateCacheVariant("session", "photo", key),
            privateCacheVariant(key, crop, "session", "https://photos.example", "mine"),
        )
        assertNull(privateCacheVariant(null, "https://example.com/public", "session", "https://photos.example", "mine"))
        assertThrows(IllegalStateException::class.java) { privateCacheVariant(null, original, "session", "https://photos.example", "mine") }
        assertThrows(
            IllegalStateException::class.java,
        ) { privateCacheVariant(key, crop, "other-session", "https://photos.example", "mine") }
        assertThrows(
            IllegalStateException::class.java,
        ) { privateCacheVariant(key, crop, "session", "https://photos.example", "other-space") }
        val other = "https://photos.example/api/frame/media/other/image?space=mine"
        assertThrows(IllegalStateException::class.java) { privateCacheVariant(key, other, "session", "https://photos.example", "mine") }
    }
}
