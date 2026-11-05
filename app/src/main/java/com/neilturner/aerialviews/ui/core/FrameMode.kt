package com.neilturner.aerialviews.ui.core

import java.util.Locale

internal enum class FrameMode { PHOTOS, ART, MIXED, DASHBOARD }

internal enum class FrameCategory { PHOTO, ART, UNKNOWN }

/** How Mixed mode spaces art: X percent of slides, one in every X slides, or once every X minutes. */
internal enum class ArtCadence { PERCENT, SLIDES, MINUTES }

internal object FrameModePolicy {
    fun accepts(
        mode: FrameMode,
        category: FrameCategory,
    ): Boolean =
        when (mode) {
            FrameMode.PHOTOS -> category != FrameCategory.ART
            FrameMode.ART -> category == FrameCategory.ART
            FrameMode.MIXED -> true
            FrameMode.DASHBOARD -> false
        }

    /** Only explicit library tags identify art; absent metadata stays unknown. */
    fun category(
        tags: List<String>,
        tagsKnown: Boolean,
    ): FrameCategory {
        if (!tagsKnown) return FrameCategory.UNKNOWN
        return if (tags.any { it.trim().lowercase(Locale.ROOT) in setOf("art", "artwork") }) {
            FrameCategory.ART
        } else {
            FrameCategory.PHOTO
        }
    }

    fun mixedTarget(
        cadence: ArtCadence,
        amount: Int,
        slide: Long,
        millisSinceArt: Long,
    ): FrameCategory =
        when (cadence) {
            ArtCadence.PERCENT -> {
                mixedTarget(slide, amount)
            }

            ArtCadence.SLIDES -> {
                if (amount > 0 && Math.floorMod(slide + 1, amount.toLong()) == 0L) FrameCategory.ART else FrameCategory.PHOTO
            }

            ArtCadence.MINUTES -> {
                if (amount > 0 && millisSinceArt >= amount * 60_000L) FrameCategory.ART else FrameCategory.PHOTO
            }
        }

    /** Selects an even distribution across a hundred slides without randomness. */
    fun mixedTarget(
        slide: Long,
        artPercent: Int,
    ): FrameCategory {
        val amount = artPercent.coerceIn(0, 100)
        val index = Math.floorMod(slide, 100L).toInt()
        return if (((index + 1) * amount / 100) != (index * amount / 100)) FrameCategory.ART else FrameCategory.PHOTO
    }
}

/** Positions use the same ordering as the playlist, including wraparound. */
internal class FrameModeIndex(
    categories: List<FrameCategory>,
) {
    private val categoryByPosition = categories.toList()
    private val photos = categories.indices.filter { categories[it] != FrameCategory.ART }
    private val art = categories.indices.filter { categories[it] == FrameCategory.ART }

    fun categoryAt(position: Int): FrameCategory? = categoryByPosition.getOrNull(position)

    fun has(category: FrameCategory): Boolean = if (category == FrameCategory.ART) art.isNotEmpty() else photos.isNotEmpty()

    fun next(
        mode: FrameMode,
        current: Int,
        previous: Boolean,
        mixedTarget: FrameCategory = FrameCategory.PHOTO,
        eligible: (Int) -> Boolean = { true },
    ): Int? {
        val target =
            when (mode) {
                FrameMode.PHOTOS -> FrameCategory.PHOTO
                FrameMode.ART -> FrameCategory.ART
                FrameMode.MIXED -> mixedTarget
                FrameMode.DASHBOARD -> return null
            }

        fun find(category: FrameCategory): Int? {
            val entries = if (category == FrameCategory.ART) art else photos
            if (entries.isEmpty()) return null
            val found = entries.binarySearch(current)
            val insertion = if (found >= 0) found else -found - 1
            val first =
                if (previous) {
                    insertion - 1
                } else if (found >= 0) {
                    found + 1
                } else {
                    insertion
                }
            for (step in entries.indices) {
                val offset = if (previous) first - step else first + step
                val position = entries[Math.floorMod(offset, entries.size)]
                if (eligible(position)) return position
            }
            return null
        }
        return find(target) ?: if (mode == FrameMode.MIXED) {
            find(if (target == FrameCategory.ART) FrameCategory.PHOTO else FrameCategory.ART)
        } else {
            null
        }
    }
}
