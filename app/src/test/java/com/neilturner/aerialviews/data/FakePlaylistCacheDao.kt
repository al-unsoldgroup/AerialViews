package com.neilturner.aerialviews.data

/**
 * In-memory stand-in for [PlaylistCacheDao] that reproduces the SQL predicates used by the real
 * cache queries. It exists because Room needs an Android runtime, and the filtering rules are what
 * these tests are about, not Room itself.
 */
internal class FakePlaylistCacheDao : PlaylistCacheDao {
    val mediaItems = mutableListOf<CachedMediaEntity>()
    val musicTracks = mutableListOf<CachedMusicTrackEntity>()
    var state: PlaylistStateEntity? = null

    override suspend fun insertMediaItems(items: List<CachedMediaEntity>) {
        items.forEach { item ->
            val id = if (item.id == 0) (mediaItems.maxOfOrNull { it.id } ?: 0) + 1 else item.id
            mediaItems += item.copy(id = id)
        }
    }

    override suspend fun updateMediaItems(items: List<CachedMediaEntity>) {
        items.forEach { updated ->
            val index = mediaItems.indexOfFirst { it.id == updated.id }
            if (index >= 0) mediaItems[index] = updated
        }
    }

    override suspend fun insertMusicTracks(tracks: List<CachedMusicTrackEntity>) {
        musicTracks += tracks
    }

    override suspend fun insertOrUpdateState(state: PlaylistStateEntity) {
        this.state = state
    }

    override suspend fun getAllMediaItemsOrdered(): List<CachedMediaEntity> = orderedMedia()

    override suspend fun getPortraitPlanRows(): List<PortraitPlanRow> =
        orderedMedia().map {
            PortraitPlanRow(
                it.id,
                it.playlistOrder,
                it.uri,
                it.type,
                it.source,
                it.timeOfDay,
                it.albumName,
                it.width,
                it.height,
                it.tagsJson,
                it.pairedWithNext,
                it.pairPartner,
            )
        }

    override suspend fun updatePortraitPosition(
        id: Int,
        position: Int,
        paired: Boolean?,
    ) {
        val index = mediaItems.indexOfFirst { it.id == id }
        if (index >= 0) mediaItems[index] = mediaItems[index].copy(playlistOrder = position, pairedWithNext = paired)
    }

    override suspend fun updatePortraitMarkers(
        ids: List<Int>,
        paired: Boolean?,
    ) {
        mediaItems.indices.forEach { index ->
            if (mediaItems[index].id in ids) mediaItems[index] = mediaItems[index].copy(pairedWithNext = paired)
        }
    }

    override suspend fun countMediaItems(allowed: List<String>): Int = filteredMedia(allowed).size

    override suspend fun countVideoItems(allowed: List<String>): Int = mediaItems.count { it.type == VIDEO && it.timeOfDay in allowed }

    override suspend fun getMediaItemsChunkFiltered(
        allowed: List<String>,
        limit: Int,
        offset: Int,
    ): List<CachedMediaEntity> = filteredMedia(allowed).drop(offset).take(limit)

    override suspend fun getAllMusicTracksOrdered(): List<CachedMusicTrackEntity> = musicTracks.sortedBy { it.playlistOrder }

    override suspend fun getPlaylistState(): PlaylistStateEntity? = state

    override suspend fun updateMediaPosition(position: Int) {
        state = state?.copy(mediaPosition = position)
    }

    override suspend fun updateMusicTrackIndex(index: Int) {
        state = state?.copy(musicTrackIndex = index)
    }

    override suspend fun krillUrisMissingTags(): List<String> =
        mediaItems.filter { it.source == "KRILL" && it.tagsJson == null }.map { it.uri }

    override suspend fun unplannedKrillUrisAfter(after: Int): List<String> =
        mediaItems.filter { it.source == "KRILL" && it.pairedWithNext == null && it.playlistOrder > after }.map { it.uri }

    override suspend fun plannedKrillUrisAfter(after: Int): List<String> =
        mediaItems.filter { it.source == "KRILL" && it.pairedWithNext != null && it.playlistOrder > after }.map { it.uri }

    override suspend fun updateKrillMetadata(
        uri: String,
        width: Int?,
        height: Int?,
        tagsJson: String,
    ) {
        mediaItems.indices.forEach { index ->
            val item = mediaItems[index]
            if (item.source == "KRILL" && item.uri == uri && item.tagsJson == null) {
                mediaItems[index] = item.copy(width = width ?: item.width, height = height ?: item.height, tagsJson = tagsJson)
            }
        }
    }

    override suspend fun updateKrillCaption(
        uri: String,
        caption: String,
    ) {
        mediaItems.indices.forEach { index ->
            val item = mediaItems[index]
            if (item.source == "KRILL" && item.uri == uri) mediaItems[index] = item.copy(shortDescription = caption)
        }
    }

    override suspend fun getKrillCaptions(): List<KrillCachedCaption> =
        mediaItems.filter { it.source == "KRILL" }.map { KrillCachedCaption(it.uri, it.shortDescription) }

    override suspend fun updateMissingKrillDimensions(
        uri: String,
        width: Int,
        height: Int,
    ) {
        mediaItems.indices.forEach { index ->
            val item = mediaItems[index]
            if (item.source == "KRILL" && item.uri == uri && ((item.width ?: 0) <= 0 || (item.height ?: 0) <= 0)) {
                mediaItems[index] = item.copy(width = width, height = height, pairedWithNext = null)
            }
        }
    }

    override suspend fun clearMediaItems() {
        mediaItems.clear()
    }

    override suspend fun clearMusicTracks() {
        musicTracks.clear()
    }

    override suspend fun clearState() {
        state = null
    }

    private fun orderedMedia(): List<CachedMediaEntity> = mediaItems.sortedBy { it.playlistOrder }

    private fun filteredMedia(allowed: List<String>): List<CachedMediaEntity> =
        orderedMedia().filter { it.type != VIDEO || it.timeOfDay in allowed }

    private companion object {
        const val VIDEO = "VIDEO"
    }
}
