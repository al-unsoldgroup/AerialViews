package com.neilturner.aerialviews.providers.custom

import android.content.Context
import android.os.Looper
import coil3.disk.DiskCache
import coil3.disk.directory
import com.neilturner.aerialviews.data.AerialDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext

/** A bounded app-private image cache shared by all frame image loaders. */
internal object KrillFrameCache {
    val fence = KrillCacheFence()

    @Volatile private var cache: DiskCache? = null

    @Volatile var epoch: Long = 0
        private set
    private val cleanupLock = Any()
    private val signOutLock = Any()
    private val reconciliationJobsLock = Any()
    private val reconciliationJobs = mutableSetOf<Job>()
    private var signOutRequests = 0
    private val invalidationListeners = KrillInvalidationListeners()
    private var recoveryChecked = false

    private fun cleanupPrefs(context: Context) =
        context.applicationContext.getSharedPreferences("krill_cache_cleanup", Context.MODE_PRIVATE)

    private fun selectedCacheDirectory(context: Context): File {
        val name = cleanupPrefs(context).getString("active_cache", "frame_images") ?: throw IOException("Missing active cache")
        if (name !in setOf("frame_images", "frame_images_v2")) throw IOException("Invalid active cache")
        val root = context.applicationContext.cacheDir.canonicalFile
        val directory = root.resolve(name)
        if (directory.canonicalFile != directory.absoluteFile) throw IOException("Invalid active cache")
        return directory
    }

    /** Recover a logout interrupted by process termination before any new session can be used. */
    fun recover(context: Context) =
        synchronized(cleanupLock) {
            if (recoveryChecked) return@synchronized
            val prefs = cleanupPrefs(context)
            if (prefs.contains("pending")) {
                KrillTokenStore(context.applicationContext).clear()
                purgeLegacyFiles(context)
                get(context).clear()
                purgeDetachedReconciliationCaches(context)
                purgePlaylist(context)
                KrillPortraitHistory.clear(context)
                if (!prefs.edit().remove("pending").commit()) throw IOException("Unable to finish cache cleanup")
            }
            if (prefs.contains("deletion_namespace")) finishDeletionCleanup(context)
            recoveryChecked = true
        }

    fun cleanupPending(context: Context): Boolean =
        cleanupPrefs(context).let { it.contains("pending") || it.contains("deletion_namespace") }

    /** The durable reconciliation journal is the recovery marker: private playback waits until it is finished. */
    fun privatePlaybackBlocked(context: Context): Boolean =
        cleanupPending(context) ||
            runBlocking(Dispatchers.IO) { AerialDatabase.getInstance(context).privateReconciliationDao().current() != null }

    /** Complete a cleanup recorded by an earlier build; safe to repeat after a process restart. */
    private fun finishDeletionCleanup(context: Context) {
        val prefs = cleanupPrefs(context)
        val namespace = prefs.getString("deletion_namespace", null) ?: return
        val operation = prefs.getString("deletion_operation", null) ?: throw IOException("Incomplete deletion cleanup marker")
        purgeLegacyFiles(context)
        get(context).clear()
        purgeDetachedReconciliationCaches(context)
        purgePlaylist(context)
        KrillPortraitHistory.clear(context)
        val store = KrillTokenStore(context.applicationContext)
        val session = store.load()
        if (session?.cacheNamespace == namespace) store.save(session.copy(cacheNamespace = UUID.randomUUID().toString()))
        if (!prefs
                .edit()
                .remove("deletion_namespace")
                .remove("deletion_operation")
                .commit()
        ) {
            throw IOException("Unable to finish deletion cleanup")
        }
    }

    fun addInvalidationListener(listener: () -> Unit) {
        invalidationListeners.add(listener)
    }

    fun removeInvalidationListener(listener: () -> Unit) {
        invalidationListeners.remove(listener)
    }

    /** Serializes the complete reconciliation attempt with sign-out and confirmed deletion. */
    suspend fun <T> withReconciliationLock(block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            val parent = currentCoroutineContext()[Job]
            synchronized(reconciliationJobsLock) {
                if (signOutRequests > 0) throw CancellationException("Sign-out in progress")
                if (parent != null) reconciliationJobs.add(parent)
            }
            try {
                synchronized(signOutLock) { runBlocking(parent ?: EmptyCoroutineContext) { block() } }
            } finally {
                synchronized(reconciliationJobsLock) { reconciliationJobs.remove(parent) }
            }
        }

    /** Session replacement takes precedence over recovery and waits for its cleanup to unwind. */
    fun <T> withSessionReplacement(block: () -> T): T {
        val jobs =
            synchronized(reconciliationJobsLock) {
                signOutRequests++
                reconciliationJobs.toList()
            }
        try {
            jobs.forEach { it.cancel() }
            return synchronized(signOutLock) { block() }
        } finally {
            synchronized(reconciliationJobsLock) { signOutRequests-- }
        }
    }

    /** Caller holds the reconciliation lock. No credentials or private URLs are logged. */
    fun ownsReconciliation(
        context: Context,
        journal: com.neilturner.aerialviews.data.PrivateReconciliationJournal,
    ): Boolean =
        synchronized(cleanupLock) {
            val session = KrillTokenStore(context.applicationContext).load() ?: return@synchronized false
            if (session.cacheNamespace != journal.namespace || session.libraryOrigin != journal.origin ||
                session.spaceId != journal.space
            ) {
                return@synchronized false
            }
            val root = context.applicationContext.cacheDir.canonicalFile
            val allowed = listOf(root.resolve("frame_images"), root.resolve("frame_images_v2"))
            if (allowed.any { it.canonicalFile != it.absoluteFile }) return@synchronized false
            val source = File(journal.sourceCacheDirectory).canonicalFile
            val destination = File(journal.destinationCacheDirectory).canonicalFile
            val active = selectedCacheDirectory(context).canonicalFile
            source in allowed && destination in allowed && source != destination &&
                (active == source || (journal.stage == "purged" && active == destination))
        }

    /** Caller holds the reconciliation lock and must retain the returned revocation for reopening. */
    fun fenceReconciliation(
        context: Context,
        journal: com.neilturner.aerialviews.data.PrivateReconciliationJournal,
        checkpoint: () -> Unit = {},
    ): Long? {
        synchronized(cleanupLock) {
            if (!ownsReconciliation(context, journal)) return null
            synchronized(this) { epoch++ }
        }
        val revocation = fence.revoke(journal.namespace)
        invalidationListeners.notifyAllOrThrow()
        return if (fence.awaitDrained(journal.namespace, 30_000, checkpoint)) revocation else null
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

    private fun purgeDetachedReconciliationCaches(context: Context) {
        val journal =
            runBlocking(Dispatchers.IO) {
                AerialDatabase.getInstance(context).privateReconciliationDao().current()
            } ?: return
        purgeDetachedFrameCaches(
            context.applicationContext.cacheDir,
            journal.sourceCacheDirectory,
            journal.destinationCacheDirectory,
            get(context).directory.toString(),
        )
    }

    private fun purgePlaylist(context: Context) {
        if (Looper.myLooper() == Looper.getMainLooper()) throw IOException("Cache cleanup must run off the UI thread")
        runBlocking(Dispatchers.IO) {
            val database = AerialDatabase.getInstance(context)
            database.playlistCacheDao().clearAll()
            database.privateCacheVariantDao().clearAll()
            database.privateReconciliationDao().clearForFullCleanup()
            database.privateReconciliationDao().clearGeneration()
        }
    }

    fun get(context: Context): DiskCache =
        cache ?: synchronized(this) {
            cache ?: DiskCache
                .Builder()
                .directory(selectedCacheDirectory(context))
                .maxSizeBytes(512L * 1024 * 1024)
                .build()
                .also { cache = it }
        }

    /** Caller must first stop players, hold playlist maintenance, and verify the copied manifest. */
    fun activateReconciledCache(
        context: Context,
        operation: String,
        generation: String,
    ): Boolean =
        synchronized(signOutLock) {
            synchronized(cleanupLock) cleanup@{
                val database = AerialDatabase.getInstance(context)
                val (journal, activeGeneration) =
                    runBlocking(Dispatchers.IO) {
                        database.privateReconciliationDao().current() to database.privateReconciliationDao().activeGeneration()
                    }
                if (journal == null || journal.operation != operation || journal.generationToken != generation) return@cleanup false
                if (journal.stage != "purged" || activeGeneration?.namespace != journal.namespace ||
                    activeGeneration.generationToken != generation
                ) {
                    return@cleanup false
                }
                val session = KrillTokenStore(context.applicationContext).load() ?: return@cleanup false
                if (session.cacheNamespace != journal.namespace || session.libraryOrigin != journal.origin ||
                    session.spaceId != journal.space
                ) {
                    return@cleanup false
                }
                val current = selectedCacheDirectory(context).canonicalFile
                val destination = File(journal.destinationCacheDirectory).canonicalFile
                val destinationName =
                    reconciledCacheName(
                        context.applicationContext.cacheDir,
                        journal.sourceCacheDirectory,
                        journal.destinationCacheDirectory,
                        current.path,
                    ) ?: return@cleanup false
                if (current == destination) return@cleanup true
                if (!cleanupPrefs(context).edit().putString("active_cache", destinationName).commit()) {
                    throw IOException("Unable to activate reconciled cache")
                }
                synchronized(this) {
                    val old = cache
                    cache = null
                    old?.shutdown()
                }
                true
            }
        }

    /** Revoke, cancel and drain tracked private work before clearing its cached bytes. */
    fun signOut(
        context: Context,
        allowed: () -> Boolean = { true },
    ): Boolean {
        if (!allowed()) return false
        return withSessionReplacement {
            if (!allowed()) return@withSessionReplacement false
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
                purgeDetachedReconciliationCaches(context)
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
}

/** Validate a cache switch without accepting paths outside the two app-owned directories. */
internal fun reconciledCacheName(
    cacheDir: File,
    source: String,
    destination: String,
    active: String,
): String? {
    val root = cacheDir.canonicalFile
    val allowed = listOf("frame_images", "frame_images_v2").associateWith { root.resolve(it).canonicalFile }
    if (allowed.any { (name, path) -> path != root.resolve(name).absoluteFile }) return null
    val sourceFile = File(source).canonicalFile
    val destinationFile = File(destination).canonicalFile
    val activeFile = File(active).canonicalFile
    val destinationName = allowed.entries.firstOrNull { it.value == destinationFile }?.key ?: return null
    if (sourceFile !in allowed.values || sourceFile == destinationFile || !destinationFile.isDirectory) return null
    if (activeFile != sourceFile && activeFile != destinationFile) return null
    return destinationName
}

/** Called only after the active cache is cleared and cache users have been drained. */
internal fun purgeDetachedFrameCaches(
    cacheDir: File,
    source: String,
    destination: String,
    active: String,
) {
    val root = cacheDir.canonicalFile
    val expected = listOf(root.resolve("frame_images"), root.resolve("frame_images_v2"))
    if (expected.any { it.canonicalFile != it.absoluteFile }) throw IOException("Invalid reconciliation cache directories")
    val allowed = expected.map { it.canonicalFile }.toSet()
    val sourceFile = File(source).canonicalFile
    val destinationFile = File(destination).canonicalFile
    val activeFile = File(active).canonicalFile
    if (sourceFile !in allowed || destinationFile !in allowed || activeFile !in allowed || sourceFile == destinationFile) {
        throw IOException("Invalid reconciliation cache directories")
    }
    for (path in listOf(sourceFile, destinationFile)) {
        if (path != activeFile && path.exists() && !path.deleteRecursively()) throw IOException("Unable to clear detached cache")
    }
}
