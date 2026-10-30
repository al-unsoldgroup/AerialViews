package com.neilturner.aerialviews.providers.custom

import android.content.Context
import androidx.core.net.toUri
import com.neilturner.aerialviews.BuildConfig
import com.neilturner.aerialviews.models.enums.AerialMediaSource
import com.neilturner.aerialviews.models.enums.AerialMediaType
import com.neilturner.aerialviews.models.enums.ProviderSourceType
import com.neilturner.aerialviews.models.videos.AerialMedia
import com.neilturner.aerialviews.models.videos.AerialMediaMetadata
import com.neilturner.aerialviews.providers.MediaProvider
import com.neilturner.aerialviews.providers.ProviderFetchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

@OptIn(InternalCoroutinesApi::class)
internal fun cancellableKrillClient(job: Job?): OkHttpClient =
    OkHttpClient
        .Builder()
        .eventListenerFactory { call ->
            object : EventListener() {
                private val registration =
                    job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
                        if (cause != null) call.cancel()
                    }

                override fun callEnd(call: Call) {
                    registration?.dispose()
                }

                override fun callFailed(
                    call: Call,
                    ioe: IOException,
                ) {
                    registration?.dispose()
                }
            }
        }.build()

/** Bound each deletion, status, permission or token request and cancel it when its screen stops. */
internal fun deletionKrillClient(job: Job?): OkHttpClient =
    cancellableKrillClient(job).newBuilder().callTimeout(30, TimeUnit.SECONDS).build()

internal fun krillSettingsHash(session: KrillTokenStore.Session?): String =
    "${session?.libraryOrigin}:${session?.spaceId}".hashCode().toString()

/** The authenticated Krill space selected during frame login. */
internal class KrillPrivateProvider(
    context: Context,
) : MediaProvider(context) {
    override val type = ProviderSourceType.REMOTE

    private fun savedSession() = KrillSessionManager(context).cachedSession()

    override val enabled: Boolean
        get() = runCatching { savedSession()?.spaceId != null }.getOrDefault(false)

    override fun settingsHash(): String =
        runCatching {
            krillSettingsHash(savedSession())
        }.getOrDefault("0")

    override suspend fun fetch(): ProviderFetchResult {
        return try {
            if (BuildConfig.DEBUG && context.getSharedPreferences("frame_debug", Context.MODE_PRIVATE).getBoolean("force_offline", false)) {
                return ProviderFetchResult.Error("Krill offline test")
            }
            currentCoroutineContext().ensureActive()
            val job = currentCoroutineContext()[Job]
            val client = cancellableKrillClient(job)
            val session =
                KrillSessionManager(context).session(client) { job?.ensureActive() }
                    ?: return ProviderFetchResult.Error("Krill login required")
            val space = session.spaceId ?: return ProviderFetchResult.Error("Select a Krill library")
            currentCoroutineContext().ensureActive()
            val feed = KrillPrivateFeed(client, session.libraryOrigin, session.accessToken)
            val config = KrillPrivateFeed.config(client, session.libraryOrigin)
            job?.ensureActive()
            if (KrillSessionManager(context).cachedSession()?.cacheNamespace == session.cacheNamespace) {
                KrillCropCapability.set(context, session.cacheNamespace, config.imageCropAvailable)
            }
            var photoRevision: String? = null
            val photos =
                feed.photos(
                    space,
                    checkActive = { job?.ensureActive() },
                    requireRevision = config.libraryRevisionAvailable,
                    onRevision = { photoRevision = it },
                )
            val videos =
                if (config.videoAvailable) {
                    feed.videos(
                        space,
                        checkActive = { job?.ensureActive() },
                        requireRevision = config.libraryRevisionAvailable,
                        onRevision = { check(it == photoRevision) { "Krill library changed between feeds" } },
                    )
                } else {
                    emptyList()
                }
            ProviderFetchResult.Success(
                media =
                    (photos + videos).map { photo ->
                        AerialMedia(
                            photo.url.toUri(),
                            type = if (photo.video) AerialMediaType.VIDEO else AerialMediaType.IMAGE,
                            source = AerialMediaSource.KRILL,
                            metadata =
                                AerialMediaMetadata(
                                    shortDescription = photo.description,
                                    width = photo.width,
                                    height = photo.height,
                                    tags = photo.tags,
                                    tagsKnown = true,
                                ),
                        )
                    },
                summary = "",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ProviderFetchResult.Error("Unable to load the Krill library")
        }
    }

    override suspend fun fetchMetadata(media: List<AerialMedia>): List<AerialMedia> = media
}
