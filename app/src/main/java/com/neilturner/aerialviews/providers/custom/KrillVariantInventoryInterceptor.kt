package com.neilturner.aerialviews.providers.custom

import android.content.Context
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import com.neilturner.aerialviews.data.AerialDatabase
import com.neilturner.aerialviews.data.PrivateCacheVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun privateCacheVariant(
    key: String?,
    requestData: String,
    namespace: String,
    origin: String,
    space: String?,
): PrivateCacheVariant? {
    val keyedUrl = key?.substringAfter(':', "").orEmpty()
    if (!KrillPrivateFeed.isFrameImage(keyedUrl) && !KrillPrivateFeed.isFrameImage(requestData)) return null
    check(key != null && key == KrillCacheKey.forImage(namespace, keyedUrl)) { "Invalid private cache key" }
    check(KrillPrivateFeed.isSelectedImage(keyedUrl, origin, space)) { "Private image outside selected library" }
    if (KrillPrivateFeed.isFrameImage(requestData)) {
        check(KrillPrivateFeed.sameFramePhoto(keyedUrl, requestData)) { "Private cache key does not match image" }
    }
    val mediaId = requireNotNull(keyedUrl.toHttpUrlOrNull()).pathSegments[3]
    return PrivateCacheVariant(namespace, mediaId, key)
}

/** Record private variants before Coil can write them, fenced against sign-out. */
internal class KrillVariantInventoryInterceptor(
    private val context: Context,
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val key = chain.request.diskCacheKey
        val keyedUrl = key?.substringAfter(':', "").orEmpty()
        val privateRequest = KrillPrivateFeed.isFrameImage(keyedUrl) || KrillPrivateFeed.isFrameImage(chain.request.data.toString())
        if (!privateRequest) return chain.proceed()
        val session = KrillSessionManager(context).cachedSession() ?: error("Private session unavailable")
        val variant =
            requireNotNull(
                privateCacheVariant(key, chain.request.data.toString(), session.cacheNamespace, session.libraryOrigin, session.spaceId),
            )
        val lease = KrillFrameCache.fence.acquire(session.cacheNamespace) ?: error("Private cache unavailable")
        try {
            val job = currentCoroutineContext()[Job]
            lease.onCancel { job?.cancel() }
            withContext(Dispatchers.IO) {
                AerialDatabase.getInstance(context).privateCacheVariantDao().record(variant)
            }
            currentCoroutineContext().ensureActive()
            check(lease.isValid()) { "Private cache unavailable" }
            return chain.proceed()
        } finally {
            lease.close()
        }
    }
}
