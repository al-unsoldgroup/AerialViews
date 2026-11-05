package com.neilturner.aerialviews.providers.custom

/** Bring server-paired future portraits together without dropping or replaying intervening items. */
internal object KrillPortraitRotation {
    data class Item(
        val identity: String,
        val portrait: KrillPortraitPairing.Photo? = null,
    )

    data class Plan(
        val items: List<Item>,
        val pairStarts: Set<Int>,
    )

    /**
     * Keep everything through [protectedThrough] unchanged, then move each photo's Krill partner, if found within
     * [lookAhead], directly after it. Photos without a partner keep their original order.
     */
    fun arrange(
        items: List<Item>,
        protectedThrough: Int,
        panelWidth: Int,
        panelHeight: Int,
        lookAhead: Int = Int.MAX_VALUE,
        checkpoint: () -> Unit = {},
        rejected: (Item, Item) -> Boolean = { _, _ -> false },
    ): Plan {
        if (items.size < 2 || lookAhead < 1 || panelWidth <= 0 || panelHeight <= 0) return Plan(items.toList(), emptySet())
        val result = items.toMutableList()
        val pairStarts = mutableSetOf<Int>()
        var index = (protectedThrough + 1).coerceIn(0, result.size)
        while (index < result.lastIndex) {
            checkpoint()
            val primary = result[index]
            val photo = primary.portrait
            if (photo?.partner == null) {
                index++
                continue
            }
            val end = (index.toLong() + lookAhead + 1).coerceAtMost(result.size.toLong()).toInt()
            var partnerIndex = -1
            for (pos in index + 1 until end) {
                val other = result[pos].portrait ?: continue
                if (other.mediaId == photo.partner) {
                    if (KrillPortraitPairing.isPair(photo, other, panelWidth, panelHeight) &&
                        !rejected(primary, result[pos])
                    ) {
                        partnerIndex = pos
                    }
                    break
                }
            }
            if (partnerIndex < 0) {
                index++
            } else {
                result.add(index + 1, result.removeAt(partnerIndex))
                pairStarts.add(index)
                index += 2
            }
        }
        return Plan(result, pairStarts)
    }
}
