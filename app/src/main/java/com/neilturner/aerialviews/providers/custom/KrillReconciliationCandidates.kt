package com.neilturner.aerialviews.providers.custom

import com.neilturner.aerialviews.data.CachedMediaEntity
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class ReconciliationMedia(
    val url: String,
    val type: String,
)

/** Null means the snapshot cannot safely establish absence; an empty set means no removals. */
internal fun reconciliationCandidates(
    cached: List<CachedMediaEntity>,
    fresh: List<ReconciliationMedia>,
    origin: String,
    space: String,
    revision: String?,
    videoComplete: Boolean,
): Set<String>? {
    if (revision.isNullOrBlank() || space.isBlank()) return null

    fun identity(
        url: String,
        type: String,
    ): String? {
        val valid =
            when (type) {
                "IMAGE" -> KrillPrivateFeed.isSelectedImage(url, origin, space)
                "VIDEO" -> videoComplete && KrillPrivateFeed.isSelectedVideo(url, origin, space)
                else -> false
            }
        return if (valid) url.toHttpUrlOrNull()?.pathSegments?.getOrNull(3) else null
    }
    val freshIds = mutableSetOf<String>()
    for (item in fresh) {
        val id = identity(item.url, item.type) ?: return null
        if (!freshIds.add(id)) return null
    }
    val cachedIds = mutableSetOf<String>()
    for (row in cached) {
        if (row.source != "KRILL") continue
        val isImage = KrillPrivateFeed.isSelectedImage(row.uri, origin, space)
        val isVideo = KrillPrivateFeed.isSelectedVideo(row.uri, origin, space)
        if (!isImage && !isVideo) continue
        if (isVideo && !videoComplete) continue
        val id = identity(row.uri, row.type) ?: return null
        if (!cachedIds.add(id)) return null
    }
    return cachedIds - freshIds
}
