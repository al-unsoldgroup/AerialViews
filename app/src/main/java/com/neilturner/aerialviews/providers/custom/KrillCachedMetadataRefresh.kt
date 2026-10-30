package com.neilturner.aerialviews.providers.custom

import android.content.Context
import androidx.core.net.toUri
import com.neilturner.aerialviews.data.AerialDatabase
import com.neilturner.aerialviews.data.KrillCaptionUpdate
import com.neilturner.aerialviews.data.KrillMetadataUpdate
import com.neilturner.aerialviews.models.MediaPlaylist
import com.neilturner.aerialviews.models.enums.AerialMediaSource
import com.neilturner.aerialviews.models.videos.AerialMedia
import com.neilturner.aerialviews.models.videos.AerialMediaMetadata
import com.neilturner.aerialviews.providers.ProviderFetchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.security.MessageDigest

/** Fill metadata missing from old cached playlists without replacing their order or offline images. */
internal object KrillCachedMetadataRefresh {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val captionMutex = Mutex()

    fun launch(
        context: Context,
        session: KrillTokenStore.Session,
        playlist: MediaPlaylist,
    ) {
        val appContext = context.applicationContext
        if (!KrillCropCapability.known(appContext, session.cacheNamespace)) {
            scope.launch {
                val lease = KrillFrameCache.fence.acquire(session.cacheNamespace) ?: return@launch
                try {
                    val job = currentCoroutineContext()[Job]!!
                    lease.onCancel { job.cancel() }
                    val config = KrillPrivateFeed.config(cancellableKrillClient(job), session.libraryOrigin)
                    job.ensureActive()
                    if (lease.isValid() && KrillSessionManager(appContext).cachedSession()?.cacheNamespace == session.cacheNamespace) {
                        KrillCropCapability.set(appContext, session.cacheNamespace, config.imageCropAvailable)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Timber.w("Krill crop capability check deferred")
                } finally {
                    lease.close()
                }
            }
        }
        refreshCaptions(appContext, session, playlist)
        scope.launch {
            val lease = KrillFrameCache.fence.acquire(session.cacheNamespace) ?: return@launch
            try {
                val currentJob = currentCoroutineContext()[Job]!!
                lease.onCancel { currentJob.cancel() }
                val dao = AerialDatabase.getInstance(appContext).playlistCacheDao()
                val missing =
                    dao
                        .krillUrisMissingTags()
                        .filter {
                            KrillPrivateFeed.isSelectedImage(it, session.libraryOrigin, session.spaceId)
                        }.toSet()
                if (KrillSessionManager(appContext).cachedSession()?.cacheNamespace != session.cacheNamespace) return@launch
                if (missing.isEmpty()) {
                    val needsHydration =
                        withContext(Dispatchers.Main) {
                            playlist.invalidateRefillsAndNeedsCachedMetadata {
                                KrillPrivateFeed.isSelectedImage(it.uri.toString(), session.libraryOrigin, session.spaceId)
                            }
                        }
                    if (!needsHydration) return@launch
                }
                if (missing.isNotEmpty()) {
                    val result = KrillPrivateProvider(appContext).fetch()
                    currentCoroutineContext().ensureActive()
                    if (!lease.isValid()) return@launch
                    if (result is ProviderFetchResult.Success) {
                        val updates =
                            result.media.filter { it.uri.toString() in missing }.map {
                                KrillMetadataUpdate(
                                    it.uri.toString(),
                                    it.metadata.width,
                                    it.metadata.height,
                                    Json.encodeToString(it.metadata.tags),
                                )
                            }
                        if (updates.isNotEmpty()) dao.updateKrillMetadataBatch(updates)
                    }
                }
                val cached =
                    dao
                        .getAllMediaItemsOrdered()
                        .filter {
                            it.tagsJson != null && KrillPrivateFeed.isSelectedImage(it.uri, session.libraryOrigin, session.spaceId)
                        }.map { row ->
                            val tags = runCatching { Json.decodeFromString<List<String>>(row.tagsJson!!) }.getOrDefault(emptyList())
                            AerialMedia(
                                row.uri.toUri(),
                                source = AerialMediaSource.KRILL,
                                metadata = AerialMediaMetadata(width = row.width, height = row.height, tags = tags),
                            )
                        }
                withContext(Dispatchers.Main) {
                    if (lease.isValid()) playlist.updateCachedMetadata(cached)
                }
                Timber.i("Krill cached metadata refresh completed")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Timber.w("Krill cached metadata refresh deferred")
            } finally {
                lease.close()
            }
        }
    }

    fun refreshCaptions(
        context: Context,
        session: KrillTokenStore.Session,
        playlist: MediaPlaylist,
    ): Job =
        scope.launch {
            captionMutex.withLock {
                val lease = KrillFrameCache.fence.acquire(session.cacheNamespace) ?: return@withLock
                try {
                    val job = currentCoroutineContext()[Job]!!
                    lease.onCancel { job.cancel() }
                    val dao = AerialDatabase.getInstance(context).playlistCacheDao()
                    val state = dao.getPlaylistState() ?: return@withLock
                    val keyBytes =
                        MessageDigest
                            .getInstance("SHA-256")
                            .digest("${session.cacheNamespace}:${state.cachedAt}".toByteArray(Charsets.UTF_8))
                    val key = keyBytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    val prefs = context.getSharedPreferences("krill_caption_refresh", Context.MODE_PRIVATE)
                    val refreshedAt = prefs.getLong("completed_at", 0L)
                    val now = System.currentTimeMillis()
                    if (prefs.getString("completed", null) == key && refreshedAt > 0 && now >= refreshedAt &&
                        now - refreshedAt < 24 * 60 * 60 * 1000L
                    ) {
                        val matches = KrillPrivateFeed.selectedImageMatcher(session.libraryOrigin, session.spaceId)
                        val cached =
                            dao.getKrillCaptions().filter { matches(it.uri) }.map {
                                AerialMedia(
                                    it.uri.toUri(),
                                    source = AerialMediaSource.KRILL,
                                    metadata = AerialMediaMetadata(shortDescription = it.shortDescription),
                                )
                            }
                        currentCoroutineContext().ensureActive()
                        withContext(Dispatchers.Main) { if (lease.isValid()) playlist.updateCachedCaptions(cached) }
                        return@withLock
                    }
                    val result = KrillPrivateProvider(context).fetch()
                    currentCoroutineContext().ensureActive()
                    if (!lease.isValid() ||
                        KrillSessionManager(context).cachedSession()?.cacheNamespace != session.cacheNamespace
                    ) {
                        return@withLock
                    }
                    if (result !is ProviderFetchResult.Success) return@withLock
                    val captions = result.media.associate { it.uri.toString() to it.metadata.shortDescription }
                    val updates =
                        dao.getKrillCaptions().mapNotNull { cached ->
                            val caption = captions[cached.uri] ?: return@mapNotNull null
                            if (caption == cached.shortDescription) null else KrillCaptionUpdate(cached.uri, caption)
                        }
                    if (!dao.updateKrillCaptions(state.cachedAt, updates)) return@withLock
                    currentCoroutineContext().ensureActive()
                    if (!lease.isValid()) return@withLock
                    withContext(Dispatchers.Main) { if (lease.isValid()) playlist.updateCachedCaptions(result.media) }
                    if (lease.isValid()) {
                        prefs
                            .edit()
                            .putString(
                                "completed",
                                key,
                            ).putLong("completed_at", System.currentTimeMillis())
                            .apply()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Timber.w("Krill caption refresh deferred")
                } finally {
                    lease.close()
                }
            }
        }
}
