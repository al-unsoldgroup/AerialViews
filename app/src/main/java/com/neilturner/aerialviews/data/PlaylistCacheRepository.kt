package com.neilturner.aerialviews.data

import android.content.Context
import androidx.core.net.toUri
import com.neilturner.aerialviews.models.MediaFetchResult
import com.neilturner.aerialviews.models.MediaPlaylist
import com.neilturner.aerialviews.models.TimeOfDayFilter
import com.neilturner.aerialviews.models.enums.AerialMediaSource
import com.neilturner.aerialviews.models.enums.AerialMediaType
import com.neilturner.aerialviews.models.enums.SceneType
import com.neilturner.aerialviews.models.enums.TimeOfDay
import com.neilturner.aerialviews.models.music.MusicPlaylist
import com.neilturner.aerialviews.models.music.MusicTrack
import com.neilturner.aerialviews.models.prefs.GeneralPrefs
import com.neilturner.aerialviews.models.videos.AerialExifMetadata
import com.neilturner.aerialviews.models.videos.AerialMedia
import com.neilturner.aerialviews.models.videos.AerialMediaMetadata
import com.neilturner.aerialviews.providers.custom.KrillFrameOptions
import com.neilturner.aerialviews.providers.custom.KrillPortraitHistory
import com.neilturner.aerialviews.providers.custom.KrillPortraitPairing
import com.neilturner.aerialviews.providers.custom.KrillPortraitRotation
import com.neilturner.aerialviews.providers.custom.KrillPrivateFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import kotlin.time.Duration.Companion.days

/**
 * Snapshot of the cache preferences.
 *
 * Injected rather than read directly so the repository can be exercised in JVM tests without
 * initialising Kotpref.
 */
data class PlaylistCachePrefs(
    val enabled: Boolean,
    val refresh: String,
    val resumeSame: Boolean,
) {
    companion object {
        fun current(): PlaylistCachePrefs =
            PlaylistCachePrefs(
                enabled = GeneralPrefs.playlistCache,
                refresh = GeneralPrefs.playlistCacheRefresh,
                resumeSame = GeneralPrefs.playlistCacheResumeBehaviour == "same",
            )
    }
}

/**
 * The cached table holds the full playlist in `playlistOrder` order. The time-of-day filter is
 * applied when the playlist is read, never when it is written, so the cache survives day/night
 * cycles without refetching providers.
 *
 * All saved positions and chunk offsets are in the *filtered* index space. SQL applies LIMIT/OFFSET
 * after the filter predicate, so the initial window and every lazy chunk describe the same list.
 */
class PlaylistCacheRepository(
    private val appContext: Context,
    private val dao: PlaylistCacheDao = AerialDatabase.getInstance(appContext).playlistCacheDao(),
    private val prefsProvider: () -> PlaylistCachePrefs = { PlaylistCachePrefs.current() },
) {
    /** Plan the unplayed part of a cached playlist without changing its filtered resume index. */
    internal suspend fun preparePortraitPlan(
        origin: String,
        space: String,
        namespace: String,
        filter: TimeOfDayFilter,
        panelWidth: Int,
        panelHeight: Int,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            val state = dao.getPlaylistState() ?: return@withContext false
            Timber.d("Portrait plan loaded state in ${(System.nanoTime() - started) / 1_000_000}ms")
            val history = KrillPortraitHistory(appContext)
            val cadenceUpgrade = history.needsCadenceUpgrade(namespace)
            val matchesImage = KrillPrivateFeed.selectedImageMatcher(origin, space)
            val selectedImages = mutableMapOf<String, Boolean>()

            fun isSelectedImage(uri: String): Boolean = selectedImages.getOrPut(uri) { matchesImage(uri) }
            if (history.planTimedOutRecently(namespace, state.cachedAt, System.currentTimeMillis())) return@withContext false
            val resolvedFilter = resolveFilter(filter)
            val allowed = resolvedFilter.allowedNames().toSet()
            val current =
                if (state.mediaPosition < 0) {
                    null
                } else {
                    dao.getMediaItemsChunkFiltered(allowed.toList(), 1, state.mediaPosition).singleOrNull() ?: return@withContext false
                }
            val protectedCount =
                if (current?.pairedWithNext == true) {
                    if (current.pairThird != null) {
                        2
                    } else {
                        1
                    }
                } else {
                    0
                }
            val protectedOrder = (current?.playlistOrder ?: -1) + protectedCount
            val unplanned =
                dao.unplannedKrillUrisAfter(protectedOrder).filter {
                    isSelectedImage(it)
                }
            Timber.d("Portrait plan checked candidates in ${(System.nanoTime() - started) / 1_000_000}ms")
            if (unplanned.isEmpty() && !cadenceUpgrade) return@withContext false
            // Batch newly measured photos so one preload cannot rebuild the rotation at every startup.
            if (!cadenceUpgrade && unplanned.size < 10 &&
                dao.plannedKrillUrisAfter(protectedOrder).any {
                    isSelectedImage(it)
                } && history.deferSmallReplan(namespace, state.cachedAt, System.currentTimeMillis())
            ) {
                return@withContext false
            }
            val media = dao.getPortraitPlanRows()
            Timber.d("Portrait plan loaded cache in ${(System.nanoTime() - started) / 1_000_000}ms")
            var protectedThrough = current?.let { saved -> media.indexOfFirst { it.id == saved.id } } ?: -1
            if (current != null && protectedThrough < 0) return@withContext false
            protectedThrough = (protectedThrough + protectedCount).coerceAtMost(media.lastIndex)
            val future =
                media.drop(protectedThrough + 1).filter {
                    it.source == AerialMediaSource.KRILL.name && isSelectedImage(it.uri)
                }
            if (future.isEmpty() || (!cadenceUpgrade && future.none { it.pairedWithNext == null }) || future.any { it.tagsJson == null }) {
                return@withContext false
            }
            val selectedIds = future.map { it.id }.toSet()
            val items =
                media.map { item ->
                    val portrait =
                        if (item.id in selectedIds && item.type == AerialMediaType.IMAGE.name) {
                            val tags = item.tagsJson?.let { runCatching { Json.decodeFromString<List<String>>(it) }.getOrNull() }.orEmpty()
                            KrillPortraitPairing.Photo(
                                item.uri,
                                item.width,
                                item.height,
                                item.pairPartner,
                                item.pairThird,
                                KrillFrameOptions.showsAlone(tags),
                            )
                        } else {
                            null
                        }
                    KrillPortraitRotation.Item(item.id.toString(), portrait)
                }
            Timber.d("Portrait plan prepared candidates in ${(System.nanoTime() - started) / 1_000_000}ms")
            val planningJob = currentCoroutineContext()[Job]
            val plan =
                KrillPortraitRotation.arrange(items, protectedThrough, panelWidth, panelHeight, collageEvery = 5, checkpoint = {
                    planningJob?.ensureActive()
                }) { first, second ->
                    val firstUrl = first.portrait?.id ?: return@arrange true
                    val secondUrl = second.portrait?.id ?: return@arrange true
                    history.isPairRejected(namespace, firstUrl, secondUrl)
                }
            Timber.d("Portrait plan selected pairs in ${(System.nanoTime() - started) / 1_000_000}ms")
            val byId = media.associateBy { it.id.toString() }
            val planned =
                plan.items.mapIndexed { index, item ->
                    val original = byId.getValue(item.identity)
                    val marker =
                        if (index <= protectedThrough || original.id !in selectedIds) {
                            original.pairedWithNext
                        } else {
                            index in plan.pairStarts
                        }
                    original.copy(playlistOrder = index, pairedWithNext = marker)
                }
            planningJob?.ensureActive()
            val applied = dao.applyPortraitPlanRows(state, media, planned)
            if (applied) {
                history.clearReplanDelay(namespace, state.cachedAt)
                history.markCadenceUpgraded(namespace)
            }
            Timber.d("Portrait plan applied cache in ${(System.nanoTime() - started) / 1_000_000}ms")
            applied
        }

    suspend fun recordPortraitPlanTimeout(namespace: String) =
        withContext(Dispatchers.IO) {
            dao.getPlaylistState()?.let { state ->
                KrillPortraitHistory(appContext).recordPlanTimeout(namespace, state.cachedAt, System.currentTimeMillis())
            }
        }

    suspend fun isCacheValid(
        settingsHash: String,
        filter: TimeOfDayFilter,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val prefs = prefsProvider()
            if (!prefs.enabled) {
                Timber.d("PlaylistCache: Cache disabled in settings")
                clearCache()
                return@withContext false
            }

            val state =
                dao.getPlaylistState() ?: run {
                    Timber.d("PlaylistCache: No existing cache state found")
                    return@withContext false
                }

            // Check hash
            if (state.settingsHash != settingsHash) {
                Timber.i("PlaylistCache: Cache invalidated (settings changed). Hash: ${state.settingsHash} -> $settingsHash")
                return@withContext false
            }

            val intervalWeeks = prefs.refresh.toIntOrNull() ?: -1

            if (intervalWeeks == -1) {
                // Valid until end of playlist. The filtered list can shrink when the time-of-day
                // period changes, so an out-of-range position means "restart", not "refetch".
                val filteredTotal = countFiltered(filter)
                val outOfRange = state.mediaPosition >= filteredTotal
                val isValid = filteredTotal > 0 && (outOfRange || state.mediaPosition < filteredTotal - 1)
                if (!isValid) {
                    Timber.i("PlaylistCache: Cache invalidated (reached end of playlist: ${state.mediaPosition}/$filteredTotal)")
                } else {
                    Timber.d("PlaylistCache: Cache valid (position ${state.mediaPosition}/$filteredTotal)")
                }
                return@withContext isValid
            } else {
                // Time based validity
                val cacheAgeMs = System.currentTimeMillis() - state.cachedAt
                val maxAgeMs = (intervalWeeks * 7).days.inWholeMilliseconds
                val isValid = cacheAgeMs < maxAgeMs
                if (!isValid) Timber.i("Cache invalidated: age limit reached ($intervalWeeks weeks)")
                return@withContext isValid
            }
        }

    suspend fun getCachedPlaylist(
        filter: TimeOfDayFilter,
        requiredSettingsHash: String? = null,
    ): MediaFetchResult? =
        withContext(Dispatchers.IO) {
            val state = dao.getPlaylistState() ?: return@withContext null
            if (requiredSettingsHash != null && state.settingsHash != requiredSettingsHash) return@withContext null
            if (state.totalMediaItems == 0) return@withContext null

            val resolvedFilter = resolveFilter(filter)
            val filteredTotal = dao.countMediaItems(resolvedFilter.allowedNames())
            if (filteredTotal == 0) {
                Timber.w("PlaylistCache: DB state exists but no media items match the current filter")
                return@withContext null
            }

            Timber.d(
                "PlaylistCache: Restoring state from DB. Position: ${state.mediaPosition}, " +
                    "cached: ${state.totalMediaItems}, filtered: $filteredTotal, period: ${resolvedFilter.period}",
            )

            // The saved media position is the last visual item that started. nextItem()
            // pre-increments, so restoring from this value starts the following item. If "same" is
            // chosen, decrement by 1 so pre-increment lands back on the saved position. A saved
            // position outside the current filtered list means the period changed: restart.
            val resumeSame = prefsProvider().resumeSame
            val resumePosition =
                when {
                    state.mediaPosition < 0 || state.mediaPosition >= filteredTotal -> -1
                    resumeSame -> (state.mediaPosition - 1).coerceIn(-1, filteredTotal - 1)
                    else -> state.mediaPosition.coerceIn(-1, filteredTotal - 1)
                }

            val windowLimit = 50
            val windowOffset = 0.coerceAtLeast(resumePosition - 5)
            Timber.d("PlaylistCache: Loading initial window. Offset: $windowOffset, Limit: $windowLimit")
            val cachedMediaChunks = dao.getMediaItemsChunkFiltered(resolvedFilter.allowedNames(), windowLimit, windowOffset)

            val cachedMusic = dao.getAllMusicTracksOrdered()

            if (cachedMediaChunks.isEmpty()) {
                Timber.w("PlaylistCache: DB state exists but no media items found")
                return@withContext null
            }

            val mediaList =
                try {
                    cachedMediaChunks.map { mapEntityToMedia(it) }
                } catch (e: Exception) {
                    Timber.e(e, "PlaylistCache: Failed to map cached media entities")
                    clearCache()
                    return@withContext null
                }

            val musicList =
                try {
                    cachedMusic.map { entity ->
                        MusicTrack(
                            uri = entity.uri.toUri(),
                            source = enumValueOf<AerialMediaSource>(entity.source),
                        )
                    }
                } catch (e: Exception) {
                    Timber.e(e, "PlaylistCache: Failed to map cached music entities")
                    clearCache()
                    return@withContext null
                }

            val musicPlaylist =
                if (musicList.isNotEmpty()) {
                    MusicPlaylist(
                        tracks = musicList,
                        shuffle = state.musicShuffleEnabled,
                        repeat = state.musicRepeatEnabled,
                    )
                } else {
                    null
                }

            Timber.i(
                "PlaylistCache: Cache restored successfully. Music track: " +
                    "${state.musicTrackIndex}/${state.totalMusicTracks}",
            )

            Timber.d(
                "PlaylistCache: Last started position ${state.mediaPosition}, filtered total $filteredTotal, " +
                    "resumeSame=$resumeSame, startPosition set to $resumePosition",
            )

            MediaFetchResult(
                mediaPlaylist =
                    MediaPlaylist(
                        initialVideos = mediaList,
                        startPosition = resumePosition,
                        size = filteredTotal,
                        windowOffset = windowOffset,
                        fetchChunk = { offset, limit ->
                            Timber.d("PlaylistCache: Lazy fetching chunk: offset $offset, limit $limit")
                            getMediaChunk(offset, limit, resolvedFilter)
                        },
                        countSource = {
                            dao.countCurrentMedia(state.cachedAt, state.settingsHash, resolvedFilter.allowedNames())
                        },
                    ),
                musicPlaylist = musicPlaylist,
                musicResumeIndex = state.musicTrackIndex,
                isFromCache = true,
            )
        }

    suspend fun getMediaChunk(
        offset: Int,
        limit: Int,
        filter: TimeOfDayFilter,
    ): List<AerialMedia> =
        withContext(Dispatchers.IO) {
            dao.getMediaItemsChunkFiltered(filter.allowedNames(), limit, offset).map { mapEntityToMedia(it) }
        }

    /** Number of cached items that match [filter] right now, for the cache status screen. */
    suspend fun countMatching(filter: TimeOfDayFilter): Int =
        withContext(Dispatchers.IO) {
            countFiltered(filter)
        }

    private suspend fun countFiltered(filter: TimeOfDayFilter): Int = dao.countMediaItems(resolveFilter(filter).allowedNames())

    /**
     * If no videos match the preferred filter, fall back to the full list. This mirrors the
     * in-memory behaviour so a filter can never hide the entire library.
     */
    private suspend fun resolveFilter(filter: TimeOfDayFilter): TimeOfDayFilter {
        if (!filter.enabled) return TimeOfDayFilter.ALL
        val matchingVideos = dao.countVideoItems(filter.allowedNames())
        return if (matchingVideos == 0) {
            Timber.w("PlaylistCache: No videos match ${filter.period}; using unfiltered list")
            TimeOfDayFilter.ALL
        } else {
            filter
        }
    }

    private fun mapEntityToMedia(entity: CachedMediaEntity): AerialMedia =
        AerialMedia(
            uri = entity.uri.toUri(),
            type = enumValueOf<AerialMediaType>(entity.type),
            source = enumValueOf<AerialMediaSource>(entity.source),
            metadata =
                AerialMediaMetadata(
                    shortDescription = entity.shortDescription,
                    pointsOfInterest = entity.pointsOfInterest,
                    timeOfDay = enumValueOf<TimeOfDay>(entity.timeOfDay),
                    scene = enumValueOf<SceneType>(entity.scene),
                    albumName = entity.albumName,
                    title = entity.title,
                    width = entity.width,
                    height = entity.height,
                    pairedWithNext = entity.pairedWithNext,
                    pairPartner = entity.pairPartner,
                    pairThird = entity.pairThird,
                    focusRevision = entity.focusRevision,
                    focusX = entity.focusX,
                    focusY = entity.focusY,
                    tags = entity.tagsJson?.let { runCatching { Json.decodeFromString<List<String>>(it) }.getOrNull() } ?: emptyList(),
                    tagsKnown = entity.tagsJson != null,
                    exif =
                        AerialExifMetadata(
                            date = entity.exifDate,
                            offset = entity.exifOffset,
                            latitude = entity.exifLatitude,
                            longitude = entity.exifLongitude,
                            city = entity.exifCity,
                            state = entity.exifState,
                            country = entity.exifCountry,
                            description = entity.exifDescription,
                        ),
                ),
            audioUri = entity.audioUri?.toUri(),
        )

    suspend fun cachePlaylist(
        media: List<AerialMedia>,
        musicPlaylist: MusicPlaylist?,
        settingsHash: String,
        shuffleEnabled: Boolean,
    ) = withContext(Dispatchers.IO) {
        val music = musicPlaylist?.tracks ?: emptyList()
        val mediaEntities = media.mapIndexed { index, item -> mediaEntity(item, index) }

        val musicEntities =
            music.mapIndexed { index, t ->
                CachedMusicTrackEntity(
                    playlistOrder = index,
                    uri = t.uri.toString(),
                    source = t.source.name,
                )
            }

        val state =
            PlaylistStateEntity(
                mediaPosition = -1,
                musicTrackIndex = 0,
                cachedAt = System.currentTimeMillis(),
                settingsHash = settingsHash,
                totalMediaItems = media.size,
                totalMusicTracks = music.size,
                shuffleEnabled = shuffleEnabled,
                musicShuffleEnabled = musicPlaylist?.shuffle ?: false,
                musicRepeatEnabled = musicPlaylist?.repeat ?: false,
            )

        dao.updateCache(mediaEntities, musicEntities, state)
        Timber.i("PlaylistCache: Saved new cache. Media: ${media.size}, Music: ${music.size}, Hash: $settingsHash")
    }

    internal suspend fun appendKrillMedia(
        state: PlaylistStateEntity,
        media: List<AerialMedia>,
    ): Int? =
        withContext(Dispatchers.IO) {
            dao.appendKrillItems(state.cachedAt, state.settingsHash, media.mapIndexed { index, item -> mediaEntity(item, index) })
        }

    private fun mediaEntity(
        m: AerialMedia,
        index: Int,
    ): CachedMediaEntity =
        CachedMediaEntity(
            playlistOrder = index,
            uri = m.uri.toString(),
            type = m.type.name,
            source = m.source.name,
            shortDescription = m.metadata.shortDescription,
            pointsOfInterest = m.metadata.pointsOfInterest,
            timeOfDay = m.metadata.timeOfDay.name,
            scene = m.metadata.scene.name,
            albumName = m.metadata.albumName,
            title = m.metadata.title,
            exifDate = m.metadata.exif.date,
            exifOffset = m.metadata.exif.offset,
            exifLatitude = m.metadata.exif.latitude,
            exifLongitude = m.metadata.exif.longitude,
            exifCity = m.metadata.exif.city,
            exifState = m.metadata.exif.state,
            exifCountry = m.metadata.exif.country,
            exifDescription = m.metadata.exif.description,
            audioUri = m.audioUri?.toString(),
            width = m.metadata.width,
            height = m.metadata.height,
            tagsJson = Json.encodeToString(m.metadata.tags),
            pairedWithNext = m.metadata.pairedWithNext,
            pairPartner = m.metadata.pairPartner,
            pairThird = m.metadata.pairThird,
            focusRevision = m.metadata.focusRevision,
            focusX = m.metadata.focusX,
            focusY = m.metadata.focusY,
        )

    suspend fun saveMediaPosition(position: Int) {
        if (prefsProvider().enabled) {
            dao.updateMediaPosition(position)
        }
    }

    suspend fun saveMusicTrackIndex(index: Int) {
        if (prefsProvider().enabled) {
            dao.updateMusicTrackIndex(index)
        }
    }

    suspend fun clearCache() =
        withContext(Dispatchers.IO) {
            try {
                dao.clearAll()
            } catch (e: Exception) {
                Timber.e(e, "PlaylistCache: Room clear failed, attempting file deletion")
                try {
                    AerialDatabase.closeAndReset()
                    appContext.deleteDatabase(AerialDatabase.dbName())
                } catch (e2: Exception) {
                    Timber.e(e2, "PlaylistCache: Failed to delete database file")
                }
            }
        }
}
