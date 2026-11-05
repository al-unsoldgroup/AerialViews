package com.neilturner.aerialviews.providers.custom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KrillPortraitRotationTest {
    private fun photo(
        id: String,
        partner: String? = null,
        width: Int = 1200,
        height: Int = 1800,
    ) = KrillPortraitRotation.Item(
        id,
        KrillPortraitPairing.Photo("https://photos.example/api/frame/media/$id/image?space=mine", width, height, partner),
    )

    @Test
    fun `moves the server partner next to its photo without dropping intervening items`() {
        val items = listOf(photo("a", "b"), KrillPortraitRotation.Item("landscape"), photo("other"), photo("b", "a"))
        val plan = KrillPortraitRotation.arrange(items, -1, 960, 1080)
        assertEquals(setOf(0), plan.pairStarts)
        assertEquals(listOf("a", "b", "landscape", "other"), plan.items.map { it.identity })
        assertEquals(plan.items, KrillPortraitRotation.arrange(plan.items, -1, 960, 1080).items)
    }

    @Test
    fun `finds a partner far ahead in a large rotation`() {
        val filler = List(4000) { photo("p$it") }
        val plan = KrillPortraitRotation.arrange(listOf(photo("a", "b")) + filler + photo("b", "a"), -1, 960, 1080)
        assertEquals(listOf("a", "b"), plan.items.take(2).map { it.identity })
        assertEquals(4002, plan.items.size)
    }

    @Test
    fun `never pairs portraits without a server partner even when adjacent`() {
        val items = listOf(photo("a"), photo("b"))
        assertEquals(emptySet<Int>(), KrillPortraitRotation.arrange(items, -1, 960, 1080).pairStarts)
    }

    @Test
    fun `never changes the already viewed prefix`() {
        val items = listOf(photo("seen", "partner"), KrillPortraitRotation.Item("other"), photo("future"), photo("partner", "seen"))
        val result = KrillPortraitRotation.arrange(items, 1, 960, 1080)
        assertEquals(items, result.items)
        assertEquals(emptySet<Int>(), result.pairStarts)
    }

    @Test
    fun `respects one-sided pairs, rejection, resolution and lookahead`() {
        val oneSided = listOf(photo("a", "b"), photo("b", "c"))
        assertEquals(emptySet<Int>(), KrillPortraitRotation.arrange(oneSided, -1, 960, 1080).pairStarts)
        val items = listOf(photo("a", "b"), photo("x"), photo("b", "a"))
        assertEquals(items, KrillPortraitRotation.arrange(items, -1, 960, 1080) { _, candidate -> candidate.identity == "b" }.items)
        assertEquals(items, KrillPortraitRotation.arrange(items, -1, 960, 1080, lookAhead = 1).items)
        assertEquals(items, KrillPortraitRotation.arrange(items, -1, 1920, 2160).items)
    }

    @Test
    fun `cancellation leaves the supplied rotation unchanged`() {
        val items = listOf(photo("a", "c"), KrillPortraitRotation.Item("landscape"), photo("b"), photo("c", "a"))
        val original = items.toList()
        var checks = 0
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            KrillPortraitRotation.arrange(items, -1, 960, 1080, checkpoint = {
                if (++checks == 2) throw java.util.concurrent.CancellationException()
            })
        }
        assertEquals(original, items)
    }
}
