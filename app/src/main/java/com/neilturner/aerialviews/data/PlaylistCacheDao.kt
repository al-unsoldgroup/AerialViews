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

    @Query(
        "SELECT id, playlistOrder, uri, type, source, timeOfDay, albumName, width, height, tagsJson, pairedWithNext FROM cached_media ORDER BY playlistOrder ASC",
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
