package com.neilturner.aerialviews.providers.custom

import android.content.Context
import android.os.Looper
import coil3.disk.DiskCache
import coil3.disk.directory
import com.neilturner.aerialviews.data.AerialDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.UUID

/** A bounded app-private image cache shared by all frame image loaders. */
internal object KrillFrameCache {
    val fence = KrillCacheFence()

    @Volatile private var cache: DiskCache? = null

    @Volatile var epoch: Long = 0
        private set
    private val cleanupLock = Any()
    private val signOutLock = Any()
    private val invalidationListeners = KrillInvalidationListeners()
    private var recoveryChecked = false

    private fun cleanupPrefs(context: Context) =
        context.applicationContext.getSharedPreferences("krill_cache_cleanup", Context.MODE_PRIVATE)

    /** Recover a logout interrupted by process termination before any new session can be used. */
    fun recover(context: Context) =
        synchronized(cleanupLock) {
            if (recoveryChecked) return@synchronized
            val prefs = cleanupPrefs(context)
            if (prefs.contains("pending")) {
                KrillTokenStore(context.applicationContext).clear()
                purgeLegacyFiles(context)
                get(context).clear()
                purgePlaylist(context)
                KrillPortraitHistory.clear(context)
                if (!prefs.edit().remove("pending").commit()) throw IOException("Unable to finish cache cleanup")
            }
            if (prefs.contains("deletion_namespace")) finishDeletionCleanup(context)
            recoveryChecked = true
        }

    fun cleanupPending(context: Context): Boolean =
        cleanupPrefs(context).let { it.contains("pending") || it.contains("deletion_namespace") }

    /** Complete a previously recorded cleanup; safe to repeat after a process restart. */
    private fun finishDeletionCleanup(context: Context) {
        val prefs = cleanupPrefs(context)
        val namespace = prefs.getString("deletion_namespace", null) ?: return
        val operation = prefs.getString("deletion_operation", null) ?: throw IOException("Incomplete deletion cleanup marker")
        purgeLegacyFiles(context)
        get(context).clear()
        purgePlaylist(context)
        KrillPortraitHistory.clear(context)
        val store = KrillTokenStore(context.applicationContext)
        val session = store.load()
        if (session?.cacheNamespace == namespace && session.pendingDeletion?.operation == operation) {
            store.save(session.copy(cacheNamespace = UUID.randomUUID().toString(), pendingDeletion = null))
        } else if (session != null && (session.cacheNamespace == namespace || session.pendingDeletion != null)) {
            throw IOException("Deletion cleanup session changed")
        }
        if (!prefs
                .edit()
                .remove("deletion_namespace")
                .remove("deletion_operation")
                .commit()
        ) {
            throw IOException("Unable to finish deletion cleanup")
        }
    }

    /** Call off the main thread, after the server confirmed completion and the status lease is closed. */
    fun cleanConfirmedDeletion(
        context: Context,
        namespace: String,
        operation: String,
    ) = synchronized(signOutLock) {
        synchronized(cleanupLock) {
            recover(context)
            check(!cleanupPending(context)) { "Private cache cleanup is pending" }
            val session = KrillSessionManager(context).cachedSession() ?: throw IOException("Krill login required")
            check(session.cacheNamespace == namespace && session.pendingDeletion?.operation == operation) {
                "Krill deletion session changed"
            }
            if (!cleanupPrefs(context)
                    .edit()
                    .putString("deletion_namespace", namespace)
                    .putString("deletion_operation", operation)
                    .commit()
            ) {
                throw IOException("Unable to record deletion cleanup")
            }
            synchronized(this) { epoch++ }
        }
        fence.revoke(namespace)
        invalidationListeners.notifyAllOrThrow()
        if (!fence.awaitDrained(namespace, 30_000)) throw IOException("Private cache cleanup is still pending")
        synchronized(cleanupLock) { finishDeletionCleanup(context) }
    }

    /** Retry a cleanup that was already authorized and recorded in this process. */
    fun retryDeletionCleanup(context: Context): Boolean =
        synchronized(signOutLock) {
            val namespace =
                synchronized(cleanupLock) {
                    recover(context)
                    cleanupPrefs(context).getString("deletion_namespace", null)
                } ?: return@synchronized false
            fence.revoke(namespace)
            invalidationListeners.notifyAllOrThrow()
            if (!fence.awaitDrained(namespace, 30_000)) throw IOException("Private cache cleanup is still pending")
            synchronized(cleanupLock) { finishDeletionCleanup(context) }
            true
        }

    fun addInvalidationListener(listener: () -> Unit) {
        invalidationListeners.add(listener)
    }

    fun removeInvalidationListener(listener: () -> Unit) {
        invalidationListeners.remove(listener)
    }

    fun <T> withSessionGate(
        context: Context,
        block: () -> T,
    ): T =
        synchronized(cleanupLock) {
            recover(context)
            check(!cleanupPending(context)) { "Private cache cleanup is pending" }
            block()
        }

    fun <T> withAvailableSession(
        context: Context,
        block: () -> T?,
    ): T? =
        synchronized(cleanupLock) {
            recover(context)
            if (cleanupPending(context)) null else block()
        }

    /** Legacy Coil cache used raw image URLs; clear it before publishing a per-login key. */
    fun migrateLegacy(
        context: Context,
        saveMigration: () -> Unit,
    ) = synchronized(cleanupLock) {
        val prefs = cleanupPrefs(context)
        if (!prefs.edit().putString("pending", "").commit()) throw IOException("Unable to record cache migration")
        purgeLegacyFiles(context)
        saveMigration()
        if (!prefs.edit().remove("pending").commit()) throw IOException("Unable to finish cache migration")
    }

    private fun purgeLegacyFiles(context: Context) {
        val legacy = context.applicationContext.cacheDir.resolve("coil3_disk_cache")
        if (legacy.exists() && !legacy.deleteRecursively()) throw IOException("Unable to clear the old image cache")
    }

    private fun purgePlaylist(context: Context) {
        if (Looper.myLooper() == Looper.getMainLooper()) throw IOException("Cache cleanup must run off the UI thread")
        runBlocking(Dispatchers.IO) { AerialDatabase.getInstance(context).playlistCacheDao().clearAll() }
    }

    fun get(context: Context): DiskCache =
        cache ?: synchronized(this) {
            cache ?: DiskCache
                .Builder()
                .directory(context.applicationContext.cacheDir.resolve("frame_images"))
                .maxSizeBytes(512L * 1024 * 1024)
                .build()
                .also { cache = it }
        }

    /** Revoke, cancel and drain tracked private work before clearing its cached bytes. */
    fun signOut(
        context: Context,
        allowed: () -> Boolean = { true },
    ): Boolean =
        synchronized(signOutLock) {
            if (!allowed()) return@synchronized false
            val prefs = cleanupPrefs(context)
            val namespace =
                synchronized(cleanupLock) {
                    recover(context)
                    val manager = KrillSessionManager(context)
                    val current =
                        prefs.getString("pending", null) ?: prefs.getString("deletion_namespace", null)
                            ?: manager.cachedSession()?.cacheNamespace.orEmpty()
                    if (!prefs.edit().putString("pending", current).commit()) throw IOException("Unable to record cache cleanup")
                    synchronized(this) { epoch++ }
                    manager.clear()
                    current
                }
            if (namespace.isNotBlank()) fence.revoke(namespace)
            invalidationListeners.notifyAllOrThrow()
            if (namespace.isNotBlank() && !fence.awaitDrained(namespace, 30_000)) {
                throw IOException("Private cache cleanup is still pending")
            }
            synchronized(cleanupLock) {
                purgeLegacyFiles(context)
                get(context).clear()
                purgePlaylist(context)
                KrillPortraitHistory.clear(context)
                if (!prefs
                        .edit()
                        .remove("pending")
                        .remove("deletion_namespace")
                        .remove("deletion_operation")
                        .commit()
                ) {
                    throw IOException("Unable to finish cache cleanup")
                }
            }
            true
        }
}
