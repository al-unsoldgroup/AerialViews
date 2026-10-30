package com.neilturner.aerialviews.providers.custom

/** Select a compatible portrait without forcing a pair when there is no good candidate. */
internal object KrillPortraitPairing {
    data class Photo(
        val id: String,
        val width: Int?,
        val height: Int?,
        val occasion: String? = null,
        val tags: List<String> = emptyList(),
    )

    /** The dimensions are orientation-corrected pixels; [candidates] are in rotation order. */
    fun choosePartner(
        photo: Photo,
        candidates: List<Photo>,
        panelWidth: Int,
        panelHeight: Int,
    ): Photo? {
        if (!eligible(photo, panelWidth, panelHeight)) return null
        val primaryRatio = photo.width!!.toDouble() / photo.height!!
        val occasion = photo.occasion?.trim()?.takeIf { it.isNotEmpty() }
        val primaryTags = normalizeTags(photo.tags)
        return candidates
            .asSequence()
            .filter { it.id != photo.id && eligible(it, panelWidth, panelHeight) }
            .filter { candidate ->
                val tags = normalizeTags(candidate.tags)
                val sameOccasion = occasion != null && candidate.occasion?.trim() == occasion
                primaryTags.isEmpty() || tags.isEmpty() || sameOccasion || primaryTags.any { it in tags }
            }
            // Very different shapes leave unequal visual weight in an uncropped composition.
            .filter { candidate ->
                val ratio = candidate.width!!.toDouble() / candidate.height!!
                ratio / primaryRatio in 0.7..(1.0 / 0.7)
            }.minWithOrNull(
                compareBy<Photo> { candidate ->
                    if (occasion != null && candidate.occasion?.trim() == occasion) 0 else 1
                }.thenBy { candidate ->
                    if (primaryTags.any { it in normalizeTags(candidate.tags) }) 0 else 1
                }.thenBy { candidate ->
                    kotlin.math.abs(candidate.width!!.toDouble() / candidate.height!! - primaryRatio)
                },
            )
    }

    private fun normalizeTags(tags: List<String>): Set<String> =
        tags.map { it.trim().lowercase(java.util.Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()

    internal fun eligible(
        photo: Photo,
        panelWidth: Int,
        panelHeight: Int,
    ): Boolean {
        val width = photo.width ?: return false
        val height = photo.height ?: return false
        return photo.id.isNotBlank() && width < height &&
            KrillResolutionPolicy.isSuitable(width, height, panelWidth, panelHeight, fitInside = true)
    }
}
