package com.neilturner.aerialviews.providers.custom

/**
 * Krill chooses portrait partners on the server from same-trip or same-occasion evidence (UNS-1754).
 * The frame only checks that both photos name each other and can fill half the panel without enlargement.
 */
internal object KrillPortraitPairing {
    data class Photo(
        val id: String,
        val width: Int?,
        val height: Int?,
        /** Krill media ID of the server-chosen partner. */
        val partner: String? = null,
        val thirdPartner: String? = null,
        /** Set in Krill to show as 16:9 or with collages off (UNS-1762): never in a pair or triple. */
        val showsAlone: Boolean = false,
    ) {
        val mediaId: String? by lazy { mediaIdOf(id) }
    }

    /** True only for a mutual server pairing of two eligible portraits; [panelWidth] is half the screen. */
    fun isPair(
        first: Photo,
        second: Photo,
        panelWidth: Int,
        panelHeight: Int,
    ): Boolean =
        first.partner != null && first.partner == second.mediaId && second.partner == first.mediaId &&
            eligible(first, panelWidth, panelHeight) && eligible(second, panelWidth, panelHeight)

    fun isTriple(
        first: Photo,
        second: Photo,
        third: Photo,
        panelWidth: Int,
        panelHeight: Int,
    ): Boolean =
        isPair(first, second, panelWidth, panelHeight) && first.thirdPartner != null &&
            first.thirdPartner == second.thirdPartner && first.thirdPartner == third.mediaId &&
            third.mediaId != first.mediaId && third.mediaId != second.mediaId && eligible(third, panelWidth, panelHeight)

    /** Media ID from a frame image URL, `/api/frame/media/<id>/image?space=...`, without a full URL parse. */
    internal fun mediaIdOf(uri: String): String? {
        val start = uri.indexOf("/api/frame/media/").takeIf { it >= 0 }?.plus("/api/frame/media/".length) ?: return null
        val end = uri.indexOf('/', start)
        return if (end > start && uri.startsWith("/image", end)) uri.substring(start, end) else null
    }

    internal fun eligible(
        photo: Photo,
        panelWidth: Int,
        panelHeight: Int,
    ): Boolean {
        val width = photo.width ?: return false
        val height = photo.height ?: return false
        return photo.id.isNotBlank() && !photo.showsAlone && width < height &&
            KrillResolutionPolicy.isSuitable(width, height, panelWidth, panelHeight, fitInside = true)
    }
}
