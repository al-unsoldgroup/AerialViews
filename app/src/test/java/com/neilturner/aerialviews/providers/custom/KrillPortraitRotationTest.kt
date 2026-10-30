package com.neilturner.aerialviews.providers.custom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KrillPortraitRotationTest {
    private fun photo(
        id: String,
        tags: List<String> = listOf("Trip"),
        width: Int = 1200,
        height: Int = 1800,
    ) = KrillPortraitRotation.Item(id, KrillPortraitPairing.Photo(id, width, height, tags = tags))

    @Test
    fun `moves a compatible future portrait next to its partner without dropping intervening items`() {
        val items = listOf(photo("a"), KrillPortraitRotation.Item("landscape"), photo("b"), photo("other", listOf("map")))
        val plan = KrillPortraitRotation.arrange(items, -1, 960, 1080)
        val result = plan.items
        assertEquals(setOf(0), plan.pairStarts)
        assertEquals(listOf("a", "b", "landscape", "other"), result.map { it.identity })
        assertEquals(items.map { it.identity }.sorted(), result.map { it.identity }.sorted())
        assertEquals(result, KrillPortraitRotation.arrange(result, -1, 960, 1080).items)
    }

    @Test
    fun `never changes the already viewed prefix or moves nonportrait items`() {
        val items = listOf(photo("seen"), KrillPortraitRotation.Item("other-space"), photo("future"), photo("partner"))
        val result = KrillPortraitRotation.arrange(items, 1, 960, 1080).items
        assertEquals(items.take(2), result.take(2))
        assertEquals(listOf("seen", "other-space", "future", "partner"), result.map { it.identity })
    }

    @Test
    fun `respects tag evidence rejection size and lookahead`() {
        val primary = photo("a")
        val noTag = photo("untagged", emptyList())
        val small = photo("small", width = 400, height = 700)
        val rejected = photo("rejected")
        val good = photo("good", tags = listOf(" trip "))
        val items = listOf(primary, noTag, small, rejected, good)
        val arranged = KrillPortraitRotation.arrange(items, -1, 960, 1080) { _, candidate -> candidate.identity == "rejected" }.items
        assertEquals(listOf("a", "good", "untagged", "small", "rejected"), arranged.map { it.identity })
        assertEquals(items, KrillPortraitRotation.arrange(items, -1, 960, 1080, lookAhead = 2).items)
        assertEquals(items, KrillPortraitRotation.arrange(items, -1, 1920, 2160).items)
    }

    @Test
    fun `cancellation leaves the supplied rotation unchanged`() {
        val items = listOf(photo("a"), KrillPortraitRotation.Item("landscape"), photo("b"), photo("c"))
        val original = items.toList()
        var checks = 0
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            KrillPortraitRotation.arrange(items, -1, 960, 1080, checkpoint = {
                if (++checks == 2) throw java.util.concurrent.CancellationException()
            })
        }
        assertEquals(original, items)
    }

    @Test
    fun `does not compare landscape photos as portrait candidates`() {
        val landscapes = List(100) { photo("wide-$it", width = 2000, height = 1000) }
        val items = landscapes + listOf(photo("a"), photo("b"))
        var comparisons = 0
        val plan =
            KrillPortraitRotation.arrange(items, -1, 960, 1080) { _, _ ->
                comparisons++
                false
            }
        assertEquals(1, comparisons)
        assertEquals(setOf(100), plan.pairStarts)
    }

    @Test
    fun `explicit pair boundary prevents an untagged preceding photo stealing a planned partner`() {
        val items = listOf(photo("untagged", emptyList()), photo("first"), photo("second"))
        val plan = KrillPortraitRotation.arrange(items, -1, 960, 1080)
        assertEquals(listOf("untagged", "first", "second"), plan.items.map { it.identity })
        assertEquals(setOf(1), plan.pairStarts)
    }
}
