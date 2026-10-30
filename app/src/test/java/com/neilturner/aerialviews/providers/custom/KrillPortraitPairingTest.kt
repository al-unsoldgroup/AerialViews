package com.neilturner.aerialviews.providers.custom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class KrillPortraitPairingTest {
    private fun photo(
        id: String,
        width: Int? = 1200,
        height: Int? = 1800,
        occasion: String? = null,
        tags: List<String> = emptyList(),
    ) = KrillPortraitPairing.Photo(id, width, height, occasion, tags)

    @Test
    fun `avoids conflicting known tags but accepts shared tags or a shared occasion`() {
        val primary = photo("one", tags = listOf(" Penguin "))
        val unrelated = photo("map", tags = listOf("Map"))
        val shared = photo("two", tags = listOf("penguin"))
        assertNull(KrillPortraitPairing.choosePartner(primary, listOf(unrelated), 960, 1080))
        assertEquals(shared, KrillPortraitPairing.choosePartner(primary, listOf(unrelated, shared), 960, 1080))
        val sameTrip = photo("map", occasion = "trip", tags = listOf("Map"))
        assertEquals(sameTrip, KrillPortraitPairing.choosePartner(primary.copy(occasion = "trip"), listOf(sameTrip), 960, 1080))
    }

    @Test
    fun `prefers a compatible photo from the same known occasion`() {
        val primary = photo("one", occasion = "trip")
        val other = photo("two", occasion = "another")
        val sameTrip = photo("three", width = 1100, occasion = "trip")
        assertEquals(sameTrip, KrillPortraitPairing.choosePartner(primary, listOf(other, sameTrip), 960, 1080))
    }

    @Test
    fun `does not pair duplicate unknown landscape or undersized photos`() {
        val primary = photo("one")
        val choices = listOf(primary, photo("unknown", null, null), photo("wide", 1800, 1200), photo("small", 400, 700))
        assertNull(KrillPortraitPairing.choosePartner(primary, choices, 960, 1080))
        assertNull(KrillPortraitPairing.choosePartner(photo("small", 400, 700), listOf(primary), 960, 1080))
    }

    @Test
    fun `requires appropriate pixels for the panel and compatible shape`() {
        val primary = photo("one", width = 900, height = 1600)
        val partner = photo("two", width = 900, height = 1600)
        assertEquals(partner, KrillPortraitPairing.choosePartner(primary, listOf(partner), 960, 1080))
        assertNull(KrillPortraitPairing.choosePartner(primary, listOf(partner), 1920, 2160))
        assertNull(KrillPortraitPairing.choosePartner(primary, listOf(photo("narrow", 600, 2000)), 960, 1080))
    }

    @Test
    fun `unknown occasions do not take priority over shape`() {
        val primary = photo("one")
        val lessSimilar = photo("two", width = 1000)
        val similar = photo("three")
        assertEquals(similar, KrillPortraitPairing.choosePartner(primary, listOf(lessSimilar, similar), 960, 1080))
    }
}
