package com.neilturner.aerialviews.providers.custom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Layout selection: a slide becomes a collage only for a mutual Krill pair that fills each half-panel. */
class KrillPortraitPairingTest {
    private fun photo(
        id: String,
        partner: String?,
        width: Int = 1200,
        height: Int = 1800,
    ) = KrillPortraitPairing.Photo("https://photos.example/api/frame/media/$id/image?space=mine", width, height, partner)

    @Test
    fun `mutual server pair of sharp portraits becomes a collage`() {
        assertTrue(KrillPortraitPairing.isPair(photo("a", "b"), photo("b", "a"), 960, 1080))
    }

    @Test
    fun `missing, one-sided or mismatched partner shows the photo alone`() {
        assertFalse(KrillPortraitPairing.isPair(photo("a", null), photo("b", "a"), 960, 1080))
        assertFalse(KrillPortraitPairing.isPair(photo("a", "b"), photo("b", null), 960, 1080))
        assertFalse(KrillPortraitPairing.isPair(photo("a", "c"), photo("b", "a"), 960, 1080))
    }

    @Test
    fun `landscape or too-small halves fall back to a single photo`() {
        assertFalse(KrillPortraitPairing.isPair(photo("a", "b", 2000, 1000), photo("b", "a"), 960, 1080))
        assertFalse(KrillPortraitPairing.isPair(photo("a", "b", 400, 700), photo("b", "a"), 960, 1080))
        assertFalse(KrillPortraitPairing.isPair(photo("a", "b"), photo("b", "a"), 1920, 2160))
        assertFalse(KrillPortraitPairing.isPair(photo("a", "b", width = 1200, height = 0), photo("b", "a"), 960, 1080))
    }

    @Test
    fun `a photo shown alone in Krill never joins a pair or triple`() {
        val solo = photo("a", "b").copy(thirdPartner = "c", showsAlone = true)
        val second = photo("b", "a").copy(thirdPartner = "c")
        val third = photo("c", null)
        assertFalse(KrillPortraitPairing.isPair(solo, second, 960, 1080))
        assertFalse(KrillPortraitPairing.isPair(second, solo, 960, 1080))
        assertFalse(KrillPortraitPairing.isTriple(solo, second, third, 960, 1080))
        assertFalse(
            KrillPortraitPairing.isTriple(
                photo("a", "b").copy(thirdPartner = "c"),
                second,
                third.copy(showsAlone = true),
                960,
                1080,
            ),
        )
        assertTrue(KrillPortraitPairing.isPair(solo.copy(showsAlone = false), second, 960, 1080))
    }

    @Test
    fun `reads the media ID from frame image URLs only`() {
        assertEquals("abc-1", KrillPortraitPairing.mediaIdOf("https://photos.example/api/frame/media/abc-1/image?space=mine"))
        assertNull(KrillPortraitPairing.mediaIdOf("https://photos.example/api/frame/media/abc-1/video?space=mine"))
        assertNull(KrillPortraitPairing.mediaIdOf("https://photos.example/m/abc-1/public"))
    }
}
