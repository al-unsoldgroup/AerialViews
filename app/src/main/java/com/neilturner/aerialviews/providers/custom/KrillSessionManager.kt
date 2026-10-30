package com.neilturner.aerialviews.providers.custom

import android.content.Context
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Serializes refresh-token rotation across playlist and image requests. */
internal class KrillSessionManager(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val store = KrillTokenStore(appContext)

    companion object {
        private val refreshLock = ReentrantLock()
        private val stateLock = Any()
    }

    fun saveIf(
        session: KrillTokenStore.Session,
        allowed: () -> Boolean,
    ): Boolean =
        KrillFrameCache.withSessionGate(appContext) {
            synchronized(stateLock) {
                if (!allowed()) return@synchronized false
                store.save(session.copy(cacheNamespace = UUID.randomUUID().toString()))
                true
            }
        }

    /** Upgrade older sessions once, preserving the namespace across app restarts. */
    fun cachedSession(): KrillTokenStore.Session? =
        KrillFrameCache.withAvailableSession(appContext) {
            synchronized(stateLock) { loadOrMigrate() }
        }

    private fun loadOrMigrate(): KrillTokenStore.Session? {
        val current = store.load() ?: return null
        if (current.cacheNamespace.isNotBlank()) return current
        val migrated = current.copy(cacheNamespace = UUID.randomUUID().toString())
        KrillFrameCache.migrateLegacy(appContext) { store.save(migrated) }
        return migrated
    }

    fun clear() = synchronized(stateLock) { store.clear() }

    /** Persist the retry key before sending any request. One operation is retained per session. */
    fun prepareDeletion(
        namespace: String,
        imageUrl: String,
    ): KrillTokenStore.PendingDeletion =
        KrillFrameCache.withAvailableSession(appContext) {
            synchronized(stateLock) {
                val current = loadOrMigrate() ?: throw IOException("Krill login required")
                check(namespace.isNotBlank() && current.cacheNamespace == namespace) { "Krill account changed" }
                require(KrillPrivateFeed.isSelectedImage(imageUrl, current.libraryOrigin, current.spaceId)) {
                    "Photo is outside the selected library"
                }
                val existing = current.pendingDeletion
                if (existing != null) {
                    check(existing.imageUrl == imageUrl) { "Another deletion is pending" }
                    existing
                } else {
                    val pending = KrillTokenStore.PendingDeletion(imageUrl, KrillOperationId.create())
                    store.save(current.copy(pendingDeletion = pending))
                    pending
                }
            }
        } ?: throw IOException("Krill session is unavailable")

    fun clearDeletion(
        namespace: String,
        operation: String,
    ): Boolean =
        KrillFrameCache.withAvailableSession(appContext) {
            synchronized(stateLock) {
                val current = loadOrMigrate() ?: return@synchronized false
                if (current.cacheNamespace != namespace || current.pendingDeletion?.operation != operation) return@synchronized false
                store.save(current.copy(pendingDeletion = null))
                true
            }
        } ?: false

    fun session(
        client: OkHttpClient,
        checkActive: () -> Unit = {},
    ): KrillTokenStore.Session? {
        KrillFrameCache.recover(appContext)
        if (KrillFrameCache.cleanupPending(appContext)) return null
        while (!refreshLock.tryLock(250, TimeUnit.MILLISECONDS)) checkActive()
        try {
            checkActive()
            val current = cachedSession() ?: return null
            if (current.expiresAtMillis > System.currentTimeMillis() + 60_000) return current
            val auth = KrillDeviceAuth(client, current.issuer, current.clientId)
            val result = auth.refresh(current.refreshToken)
            val refreshedSession =
                when (result) {
                    is KrillDeviceAuth.PollResult.Authorized -> {
                        val tokens = result.tokens
                        synchronized(stateLock) {
                            val latest = store.load()
                            if (KrillFrameCache.cleanupPending(appContext) || latest?.cacheNamespace != current.cacheNamespace ||
                                latest?.refreshToken != current.refreshToken
                            ) {
                                null
                            } else {
                                val refreshed =
                                    latest.copy(
                                        accessToken = tokens.accessToken,
                                        refreshToken = tokens.refreshToken ?: current.refreshToken,
                                        expiresAtMillis = System.currentTimeMillis() + tokens.expiresIn * 1000L,
                                    )
                                store.save(refreshed)
                                refreshed
                            }
                        }
                    }

                    else -> {
                        throw IOException("Krill login needs attention")
                    }
                }
            checkActive()
            return refreshedSession
        } finally {
            refreshLock.unlock()
        }
    }
}
