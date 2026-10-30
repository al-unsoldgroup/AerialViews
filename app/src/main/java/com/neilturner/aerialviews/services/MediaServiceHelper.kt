package com.neilturner.aerialviews.services

import com.neilturner.aerialviews.models.music.MusicTrack
import com.neilturner.aerialviews.models.videos.AerialMedia
import com.neilturner.aerialviews.providers.MediaProvider
import com.neilturner.aerialviews.providers.ProviderFetchResult
import com.neilturner.aerialviews.providers.custom.KrillPrivateProvider
import com.neilturner.aerialviews.utils.parallelForEach
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

internal object MediaServiceHelper {
    data class ProviderContent(
        val media: List<AerialMedia>,
        val tracks: List<MusicTrack>,
        val krillFailed: Boolean,
    )

    suspend fun addMetadataToManifestVideos(
        media: List<AerialMedia>,
        providers: List<MediaProvider>,
    ): Pair<List<AerialMedia>, List<AerialMedia>> {
        val matched = CopyOnWriteArrayList<AerialMedia>()
        val unmatched = CopyOnWriteArrayList<AerialMedia>()

        // Let each provider enrich the media list with metadata
        var enrichedMedia = media
        providers.forEach {
            try {
                enrichedMedia = it.fetchMetadata(enrichedMedia)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (ex: Exception) {
                Timber.e(ex, "Exception while fetching metadata")
            }
        }

        // Split into matched (has metadata) and unmatched
        enrichedMedia.forEach { video ->
            if (video.metadata.shortDescription.isNotEmpty() || video.metadata.pointsOfInterest.isNotEmpty()) {
                matched.add(video)
            } else {
                unmatched.add(video)
            }
        }

        return Pair(matched, unmatched)
    }

    suspend fun buildProviderContent(providers: List<MediaProvider>): ProviderContent {
        val media = CopyOnWriteArrayList<AerialMedia>()
        val tracks = CopyOnWriteArrayList<MusicTrack>()
        val krillFailed = AtomicBoolean(false)

        providers
            // MediaService selects Krill from the captured, fenced session; do not read it a second time here.
            .filter { it is KrillPrivateProvider || it.enabled }
            .parallelForEach {
                try {
                    it.prepare()
                    when (val result = it.fetch()) {
                        is ProviderFetchResult.Success -> {
                            media.addAll(result.media)
                        }

                        is ProviderFetchResult.Error -> {
                            if (it is KrillPrivateProvider) krillFailed.set(true)
                            Timber.w("Provider ${it.type} returned error: ${result.message}")
                        }
                    }
                    tracks.addAll(it.fetchMusic())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (ex: Exception) {
                    if (it is KrillPrivateProvider) krillFailed.set(true)
                    Timber.e(ex, "Exception while fetching media from ${it.type}")
                    // FirebaseHelper.logExceptionIfRecent(ex)
                }
            }
        return ProviderContent(media, tracks, krillFailed.get())
    }

    fun weightedInterleavedShuffle(
        media: List<AerialMedia>,
        random: Random = Random.Default,
    ): List<AerialMedia> {
        if (media.size < 2) return media

        val queues =
            media
                .groupBy { it.source }
                .values
                .map { items -> items.shuffled(random).toCollection(ArrayDeque()) }
                .filter { it.isNotEmpty() }

        val result = ArrayList<AerialMedia>(media.size)

        while (queues.any { it.isNotEmpty() }) {
            val totalRemaining = queues.sumOf { it.size }
            var selection = random.nextInt(totalRemaining)

            for (queue in queues) {
                if (queue.isEmpty()) continue
                if (selection < queue.size) {
                    result += queue.removeFirst()
                    break
                }
                selection -= queue.size
            }
        }

        return result
    }
}
