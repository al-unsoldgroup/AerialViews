package com.neilturner.aerialviews.providers.custom

/** Bring compatible future portraits together without dropping or replaying intervening items. */
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
     * Keep everything through [protectedThrough] unchanged, then find a partner within [lookAhead].
     * Nonadjacent automatic pairings require shared tags; missing metadata stays in its original order.
     */
    fun arrange(
        items: List<Item>,
        protectedThrough: Int,
        panelWidth: Int,
        panelHeight: Int,
        lookAhead: Int = 500,
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
            if (photo == null || !KrillPortraitPairing.eligible(photo, panelWidth, panelHeight)) {
                index++
                continue
            }
            val end = minOf(result.size, index + lookAhead + 1)
            val candidates =
                result.subList(index + 1, end).filter { candidate ->
                    val other = candidate.portrait
                    other != null && KrillPortraitPairing.eligible(other, panelWidth, panelHeight) &&
                        sharedTag(photo.tags, other.tags) && !rejected(primary, candidate)
                }
            val selected = KrillPortraitPairing.choosePartner(photo, candidates.mapNotNull { it.portrait }, panelWidth, panelHeight)
            val partnerIndex =
                if (selected == null) {
                    -1
                } else {
                    (index + 1 until end).firstOrNull { pos ->
                        result[pos].portrait === selected
                    } ?: -1
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

    private fun sharedTag(
        first: List<String>,
        second: List<String>,
    ): Boolean {
        fun normalize(tags: List<String>) = tags.map { it.trim().lowercase(java.util.Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
        return normalize(first).intersect(normalize(second)).isNotEmpty()
    }
}
