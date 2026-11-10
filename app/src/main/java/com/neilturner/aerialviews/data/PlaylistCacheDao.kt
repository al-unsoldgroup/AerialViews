package com.neilturner.aerialviews.data

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import androidx.room3.Upsert

data class KrillMetadataUpdate(
    val uri: String,
    val width: Int?,
    val height: Int?,
    val tagsJson: String,
)

data class KrillCaptionUpdate(
    val uri: String,
    val caption: String,
)

data class KrillPairUpdate(
    val uri: String,
    val partner: String?,
    val third: String?,
    val focusRevision: Long? = null,
    val focusX: Float? = null,
    val focusY: Float? = null,
)

data class KrillCachedCaption(
    val uri: String,
    val shortDescription: String,
)

data class PortraitPlanRow(
    val id: Int,
    val playlistOrder: Int,
    val uri: String,
    val type: String,
    val source: String,
    val timeOfDay: String,
    val albumName: String,
    val width: Int?,
    val height: Int?,
    val tagsJson: String?,
    val pairedWithNext: Boolean?,
    val pairPartner: String?,
    val pairThird: String?,
)

@Dao
interface PlaylistCacheDao {
    @Upsert
    suspend fun insertMediaItems(items: List<CachedMediaEntity>)

    @Update
    suspend fun updateMediaItems(items: List<CachedMediaEntity>)

    @Upsert
    suspend fun insertMusicTracks(tracks: List<CachedMusicTrackEntity>)

    @Upsert
    suspend fun insertOrUpdateState(state: PlaylistStateEntity)

    @Query("SELECT * FROM cached_media ORDER BY playlistOrder ASC")
    suspend fun getAllMediaItemsOrdered(): List<CachedMediaEntity>

    @Query("SELECT uri FROM cached_media WHERE source = 'KRILL'")
    suspend fun getKrillUris(): List<String>

    @Query("SELECT COALESCE(MAX(playlistOrder), -1) FROM cached_media")
    suspend fun lastMediaOrder(): Int

    @Query("UPDATE playlist_state SET totalMediaItems = totalMediaItems + :count WHERE id = 1")
    suspend fun incrementMediaTotal(count: Int)

    /** Append without reordering a running playlist; reject a replacement cache. */
    @Transaction
    suspend fun appendKrillItems(
        cachedAt: Long,
        settingsHash: String,
        items: List<CachedMediaEntity>,
    ): Int? {
        val state = getPlaylistState() ?: return null
        if (state.cachedAt != cachedAt || state.settingsHash != settingsHash) return null
        val known = getKrillUris().toMutableSet()
        val added = items.filter { it.source == "KRILL" && known.add(it.uri) }
        if (added.isEmpty()) return 0
        val start = lastMediaOrder() + 1
        insertMediaItems(added.mapIndexed { index, item -> item.copy(id = 0, playlistOrder = start + index) })
        incrementMediaTotal(added.size)
        return added.size
    }

    @Query(
        "SELECT id, playlistOrder, uri, type, source, timeOfDay, albumName, width, height, tagsJson, pairedWithNext, pairPartner, pairThird FROM cached_media ORDER BY playlistOrder ASC",
    )
    suspend fun getPortraitPlanRows(): List<PortraitPlanRow>

    @Query("UPDATE cached_media SET playlistOrder = :position, pairedWithNext = :paired WHERE id = :id")
    suspend fun updatePortraitPosition(
        id: Int,
        position: Int,
        paired: Boolean?,
    )

    @Query("UPDATE cached_media SET pairedWithNext = :paired WHERE id IN (:ids)")
    suspend fun updatePortraitMarkers(
        ids: List<Int>,
        paired: Boolean?,
    )

    @Transaction
    suspend fun applyPortraitPlanRows(
        expectedState: PlaylistStateEntity,
        expected: List<PortraitPlanRow>,
        planned: List<PortraitPlanRow>,
    ): Boolean {
        if (getPlaylistState() != expectedState || getPortraitPlanRows() != expected) return false
        if (planned.size != expected.size || planned.map { it.id }.toSet() != expected.map { it.id }.toSet()) return false
        val previous = expected.associateBy { it.id }
        val markerOnly = mutableMapOf<Boolean?, MutableList<Int>>()
        for (item in planned) {
            val old = previous.getValue(item.id)
            if (item.playlistOrder != old.playlistOrder) {
                updatePortraitPosition(item.id, item.playlistOrder, item.pairedWithNext)
            } else if (item.pairedWithNext != old.pairedWithNext) {
                markerOnly.getOrPut(item.pairedWithNext) { mutableListOf() }.add(item.id)
            }
        }
        for ((marker, ids) in markerOnly) {
            for (batch in ids.chunked(400)) updatePortraitMarkers(batch, marker)
        }
        return true
    }

    /**
     * Counts the rows that survive the time-of-day filter. Photos, and any non-video type, always
     * match; only videos are restricted to [allowed].
     */
    @Query("SELECT COUNT(*) FROM cached_media WHERE (type != 'VIDEO' OR timeOfDay IN (:allowed))")
    suspend fun countMediaItems(allowed: List<String>): Int

    @Transaction
    suspend fun countCurrentMedia(
        cachedAt: Long,
        settingsHash: String,
        allowed: List<String>,
    ): Int? {
        val state = getPlaylistState() ?: return null
        if (state.cachedAt != cachedAt || state.settingsHash != settingsHash) return null
        return countMediaItems(allowed)
    }

    /** Counts matching videos, used to decide whether the filter should fall back to everything. */
    @Query("SELECT COUNT(*) FROM cached_media WHERE type = 'VIDEO' AND timeOfDay IN (:allowed)")
    suspend fun countVideoItems(allowed: List<String>): Int

    /**
     * Returns a window of the filtered playlist. LIMIT/OFFSET are applied after the filter, so the
     * offsets are in the same index space as the filtered total and the saved position.
     */
    @Query(
        "SELECT * FROM cached_media WHERE (type != 'VIDEO' OR timeOfDay IN (:allowed)) " +
            "ORDER BY playlistOrder ASC LIMIT :limit OFFSET :offset",
    )
    suspend fun getMediaItemsChunkFiltered(
        allowed: List<String>,
        limit: Int,
        offset: Int,
    ): List<CachedMediaEntity>

    @Query("SELECT * FROM cached_music_tracks ORDER BY playlistOrder ASC")
    suspend fun getAllMusicTracksOrdered(): List<CachedMusicTrackEntity>

    @Query("SELECT * FROM playlist_state WHERE id = 1")
    suspend fun getPlaylistState(): PlaylistStateEntity?

    @Query("UPDATE playlist_state SET mediaPosition = :position WHERE id = 1")
    suspend fun updateMediaPosition(position: Int)

    @Query("UPDATE playlist_state SET musicTrackIndex = :index WHERE id = 1")
    suspend fun updateMusicTrackIndex(index: Int)

    @Query("SELECT uri FROM cached_media WHERE source = 'KRILL' AND tagsJson IS NULL")
    suspend fun krillUrisMissingTags(): List<String>

    @Query("SELECT uri FROM cached_media WHERE source = 'KRILL' AND pairedWithNext IS NULL AND playlistOrder > :after")
    suspend fun unplannedKrillUrisAfter(after: Int): List<String>

    @Query("SELECT uri FROM cached_media WHERE source = 'KRILL' AND pairedWithNext IS NOT NULL AND playlistOrder > :after")
    suspend fun plannedKrillUrisAfter(after: Int): List<String>

    @Query(
        "UPDATE cached_media SET width = COALESCE(:width, width), height = COALESCE(:height, height), tagsJson = :tagsJson " +
            "WHERE source = 'KRILL' AND uri = :uri AND tagsJson IS NULL",
    )
    suspend fun updateKrillMetadata(
        uri: String,
        width: Int?,
        height: Int?,
        tagsJson: String,
    )

    @Query(
        "UPDATE cached_media SET width = :width, height = :height, pairedWithNext = NULL " +
            "WHERE source = 'KRILL' AND uri = :uri AND (width IS NULL OR height IS NULL OR width <= 0 OR height <= 0)",
    )
    suspend fun updateMissingKrillDimensions(
        uri: String,
        width: Int,
        height: Int,
    )

    @Transaction
    suspend fun updateKrillMetadataBatch(items: List<KrillMetadataUpdate>) {
        for (item in items) updateKrillMetadata(item.uri, item.width, item.height, item.tagsJson)
    }

    @Query("UPDATE cached_media SET shortDescription = :caption WHERE source = 'KRILL' AND uri = :uri")
    suspend fun updateKrillCaption(
        uri: String,
        caption: String,
    )

    @Query(
        "UPDATE cached_media SET pairPartner = :partner, pairThird = :third, focusRevision = :focusRevision, " +
            "focusX = :focusX, focusY = :focusY " +
            "WHERE source = 'KRILL' AND uri = :uri AND " +
            "(pairPartner IS NOT :partner OR pairThird IS NOT :third OR focusRevision IS NOT :focusRevision " +
            "OR focusX IS NOT :focusX OR focusY IS NOT :focusY)",
    )
    suspend fun updateKrillPair(
        uri: String,
        partner: String?,
        third: String?,
        focusRevision: Long?,
        focusX: Float?,
        focusY: Float?,
    ): Int

    @Transaction
    suspend fun updateKrillPairs(
        cachedAt: Long,
        items: List<KrillPairUpdate>,
    ): Int? {
        if (getPlaylistState()?.cachedAt != cachedAt) return null
        var changed = 0
        for (item in items) changed += updateKrillPair(item.uri, item.partner, item.third, item.focusRevision, item.focusX, item.focusY)
        return changed
    }

    @Query("SELECT uri, shortDescription FROM cached_media WHERE source = 'KRILL'")
    suspend fun getKrillCaptions(): List<KrillCachedCaption>

    @Transaction
    suspend fun updateKrillCaptions(
        cachedAt: Long,
        items: List<KrillCaptionUpdate>,
    ): Boolean {
        if (getPlaylistState()?.cachedAt != cachedAt) return false
        for (item in items) updateKrillCaption(item.uri, item.caption)
        return true
    }

    /** Reject stale plans if playback position, playlist, or metadata changed during planning. */
    @Transaction
    suspend fun applyPortraitPlan(
        expectedState: PlaylistStateEntity,
        expectedMedia: List<CachedMediaEntity>,
        plannedMedia: List<CachedMediaEntity>,
    ): Boolean {
        if (getPlaylistState() != expectedState || getAllMediaItemsOrdered() != expectedMedia) return false
        if (plannedMedia.size != expectedMedia.size ||
            plannedMedia.map { it.id }.toSet() != expectedMedia.map { it.id }.toSet()
        ) {
            return false
        }
        val previous = expectedMedia.associateBy { it.id }
        val changed = plannedMedia.filter { it != previous[it.id] }
        if (changed.isNotEmpty()) updateMediaItems(changed)
        return true
    }

    @Query("DELETE FROM cached_media")
    suspend fun clearMediaItems()

    @Query("DELETE FROM cached_music_tracks")
    suspend fun clearMusicTracks()

    @Query("DELETE FROM playlist_state")
    suspend fun clearState()

    @Transaction
    suspend fun updateCache(
        media: List<CachedMediaEntity>,
        music: List<CachedMusicTrackEntity>,
        state: PlaylistStateEntity,
    ) {
        clearMediaItems()
        clearMusicTracks()
        clearState()
        insertMediaItems(media)
        insertMusicTracks(music)
        insertOrUpdateState(state)
    }

    @Transaction
    suspend fun clearAll() {
        clearMediaItems()
        clearMusicTracks()
        clearState()
    }
}
