package com.neilturner.aerialviews.providers.custom

import android.content.Context
import okhttp3.OkHttpClient
import java.io.IOException

/** A request is persisted and fenced to the sign-in that initiated it. */
internal class KrillDeletionRequest(
    private val context: Context,
    private val client: OkHttpClient,
    private val checkActive: () -> Unit = {},
) {
    /** Call off the main thread. A completed receipt is required before local data is cleared. */
    fun reconcile(namespace: String): String {
        val session = KrillSessionManager(context).cachedSession() ?: throw IOException("Krill login required")
        if (session.cacheNamespace != namespace) throw IOException("Krill session changed")
        val pending = session.pendingDeletion ?: throw IOException("No deletion is pending")
        val result = status(namespace)
        if (result == "completed") {
            // status() has released its lease; cleanup rechecks both session and operation under its lock.
            KrillFrameCache.cleanConfirmedDeletion(context, namespace, pending.operation)
        } else if (result == "cancelled" || result == "expired") {
            if (!KrillSessionManager(context).clearDeletion(namespace, pending.operation)) throw IOException("Krill session changed")
        }
        return result
    }

    /** Cancel only if the server atomically confirms the operation was never accepted. */
    fun cancelUnaccepted(namespace: String): String {
        val lease = KrillFrameCache.fence.acquire(namespace) ?: throw IOException("Krill session ended")
        val manager = KrillSessionManager(context)
        val (pending, result) =
            lease.use {
                val session = manager.session(client, checkActive) ?: throw IOException("Krill login required")
                val pending = session.pendingDeletion ?: throw IOException("No deletion is pending")
                if (!lease.isValid() || session.cacheNamespace != namespace) throw IOException("Krill session changed")
                val space = session.spaceId ?: throw IOException("Krill library required")
                val result =
                    KrillPrivateFeed(client, session.libraryOrigin, session.accessToken)
                        .cancelUnacceptedDeletion(pending.imageUrl, space, pending.operation) { call ->
                            lease.onCancel { call.cancel() }
                            if (!lease.isValid()) call.cancel()
                        }
                pending to result
            }
        if ((result == "cancelled" || result == "expired") && !manager.clearDeletion(namespace, pending.operation)) {
            throw IOException("Krill session changed")
        }
        return result
    }

    fun status(namespace: String): String {
        val lease = KrillFrameCache.fence.acquire(namespace) ?: throw IOException("Krill session ended")
        lease.use {
            val session = KrillSessionManager(context).session(client, checkActive) ?: throw IOException("Krill login required")
            val pending = session.pendingDeletion ?: throw IOException("No deletion is pending")
            if (!lease.isValid() || session.cacheNamespace != namespace) throw IOException("Krill session changed")
            val space = session.spaceId ?: throw IOException("Krill library required")
            return KrillPrivateFeed(client, session.libraryOrigin, session.accessToken)
                .deletionStatus(pending.imageUrl, space, pending.operation) { call ->
                    lease.onCancel { call.cancel() }
                    if (!lease.isValid()) call.cancel()
                }
        }
    }

    fun submit(
        namespace: String,
        imageUrl: String,
    ): String {
        val lease = KrillFrameCache.fence.acquire(namespace) ?: throw IOException("Krill session ended")
        lease.use {
            val manager = KrillSessionManager(context)
            val session = manager.session(client, checkActive) ?: throw IOException("Krill login required")
            if (!lease.isValid() || session.cacheNamespace != namespace) throw IOException("Krill session changed")
            val space = session.spaceId ?: throw IOException("Krill library required")
            val feed = KrillPrivateFeed(client, session.libraryOrigin, session.accessToken)
            if (!feed.canDelete(space) { call ->
                    lease.onCancel { call.cancel() }
                    if (!lease.isValid()) call.cancel()
                }
            ) {
                throw IOException("Krill deletion permission required")
            }
            if (!lease.isValid()) throw IOException("Krill session changed")
            val pending = manager.prepareDeletion(namespace, imageUrl)
            return feed.deletePhoto(pending.imageUrl, space, pending.operation) { call ->
                lease.onCancel { call.cancel() }
                if (!lease.isValid()) call.cancel()
            }
        }
    }
}
