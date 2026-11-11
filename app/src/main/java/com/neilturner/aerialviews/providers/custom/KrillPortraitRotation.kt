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
        val tripleStarts: Set<Int> = emptySet(),
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
        collageEvery: Int = 1,
        checkpoint: () -> Unit = {},
        rejected: (Item, Item) -> Boolean = { _, _ -> false },
    ): Plan {
        if (items.size < 2 || lookAhead < 1 || collageEvery < 1 || panelWidth <= 0 ||
            panelHeight <= 0
        ) {
            return Plan(items.toList(), emptySet())
        }
        val result = items.toMutableList()
        val pairStarts = mutableSetOf<Int>()
        val tripleStarts = mutableSetOf<Int>()
        var index = (protectedThrough + 1).coerceIn(0, result.size)
        // A tiny playlist would repeat its planned collages on every wrap.
        if (result.size < collageEvery) return Plan(result, emptySet())
        val requiredSingles = collageEvery - 1
        var singlesSincePair = requiredSingles
        while (index < result.lastIndex) {
            checkpoint()
            if (singlesSincePair < requiredSingles) {
                singlesSincePair++
                index++
                continue
            }
            val primary = result[index]
            val photo = primary.portrait
            if (photo?.partner == null) {
                singlesSincePair++
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
                singlesSincePair++
                index++
            } else {
                result.add(index + 1, result.removeAt(partnerIndex))
                pairStarts.add(index)
                val second = result[index + 1].portrait
                val thirdId = photo.thirdPartner
                val canvasWidth = panelWidth.toLong() * 2
                val rightStart = canvasWidth * 2 / 3
                val gutter = (panelHeight.toLong() * 70 / 1080).coerceAtLeast(1)
                val leftWidth = rightStart - gutter
                val rightWidth = canvasWidth - rightStart
                val upperHeight = (panelHeight - gutter) / 2
                val lowerHeight = panelHeight - gutter - upperHeight
                if (thirdId != null && thirdId == second?.thirdPartner &&
                    (photo.width ?: 0) >= leftWidth && (photo.height ?: 0) >= panelHeight &&
                    (second.width ?: 0) >= rightWidth && (second.height ?: 0) >= upperHeight
                ) {
                    val thirdIndex =
                        (index + 2 until end).firstOrNull { pos ->
                            val candidate = result[pos].portrait
                            candidate?.mediaId == thirdId &&
                                KrillPortraitPairing.eligible(candidate, panelWidth, panelHeight) &&
                                (candidate.width ?: 0) >= rightWidth && (candidate.height ?: 0) >= lowerHeight
                        }
                    if (thirdIndex != null) {
                        result.add(index + 2, result.removeAt(thirdIndex))
                        tripleStarts.add(index)
                    }
                }
                singlesSincePair = 0
                index += if (index in tripleStarts) 3 else 2
            }
        }
        return Plan(result, pairStarts, tripleStarts)
    }
}
