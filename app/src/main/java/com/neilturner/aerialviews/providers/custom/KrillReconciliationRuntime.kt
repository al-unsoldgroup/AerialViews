package com.neilturner.aerialviews.providers.custom

import android.content.Context
import coil3.disk.DiskCache
import coil3.disk.directory
import com.neilturner.aerialviews.data.AerialDatabase
import com.neilturner.aerialviews.data.CachedMediaEntity
import com.neilturner.aerialviews.data.PrivateReconciliationCopy
import com.neilturner.aerialviews.data.PrivateReconciliationJournal
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import java.io.File

/** Production hooks; callers still own journal creation and screen restart. */
internal class KrillReconciliationRuntime(
    context: Context,
) : KrillReconciliationHooks {
    private val context = context.applicationContext
    private val dao = AerialDatabase.getInstance(this.context).privateReconciliationDao()
    private var destination: DiskCache? = null
    private var revocation: Long? = null

    suspend fun runToPurged(
        operation: String,
        generation: String,
    ): Boolean = KrillReconciliationExecutor(dao, this).runToPurged(operation, generation)

    suspend fun complete(
        operation: String,
        generation: String,
    ): Boolean = KrillReconciliationExecutor(dao, this).complete(operation, generation)

    override suspend fun <T> withExclusiveOwnership(block: suspend () -> T): T =
        KrillFrameCache.withReconciliationLock {
            try {
                block()
            } finally {
                destination?.shutdown()
                destination = null
            }
        }

    override suspend fun validateOwnership(journal: PrivateReconciliationJournal): Boolean =
        KrillFrameCache.ownsReconciliation(context, journal)

    override suspend fun fenceAndDrain(journal: PrivateReconciliationJournal): Boolean {
        val coroutine = currentCoroutineContext()
        revocation = KrillFrameCache.fenceReconciliation(context, journal) { coroutine.ensureActive() }
        return revocation != null
    }

    override suspend fun withPlaylistMaintenance(block: suspend () -> Boolean): Boolean = processPlaylistMaintenance(block)

    override suspend fun prepareDestination(journal: PrivateReconciliationJournal) {
        target(journal).clear()
    }

    override suspend fun scanCopies(
        journal: PrivateReconciliationJournal,
        rows: List<CachedMediaEntity>,
    ): CacheCopyScan {
        val removed = Json.decodeFromString<List<String>>(journal.removedIdsJson).toSet()
        val source = source(journal)
        val keys = KrillCacheMigration.retainedKnownKeys(rows, journal.namespace, journal.origin, journal.space, removed)
        val coroutine = currentCoroutineContext()
        return KrillCacheMigration.copyKnownEntries(source, target(journal), keys.asIterable()) { coroutine.ensureActive() }
    }

    override suspend fun verifyCopies(
        journal: PrivateReconciliationJournal,
        copies: List<PrivateReconciliationCopy>,
    ) {
        val coroutine = currentCoroutineContext()
        KrillCacheMigration.verifyCopies(target(journal), copies) { coroutine.ensureActive() }
    }

    override suspend fun purgeSource(journal: PrivateReconciliationJournal) {
        source(journal).clear()
    }

    override suspend fun activateCache(journal: PrivateReconciliationJournal): Boolean {
        // The destination becomes the shared cache; never keep a second instance open on its directory.
        destination?.shutdown()
        destination = null
        return KrillFrameCache.activateReconciledCache(context, journal.operation, journal.generationToken)
    }

    override suspend fun reopenPrivateWork(journal: PrivateReconciliationJournal) {
        val revoked = checkNotNull(revocation) { "Reconciliation did not fence private work" }
        check(KrillFrameCache.fence.reopenDrained(journal.namespace, revoked)) { "Private work changed during reconciliation" }
    }

    private fun source(journal: PrivateReconciliationJournal): DiskCache =
        KrillFrameCache.get(context).also { check(it.directory.toString() == journal.sourceCacheDirectory) }

    private fun target(journal: PrivateReconciliationJournal): DiskCache {
        val active = KrillFrameCache.get(context)
        if (active.directory.toString() == journal.destinationCacheDirectory) return active
        return destination ?: DiskCache
            .Builder()
            .directory(File(journal.destinationCacheDirectory))
            .maxSizeBytes(512L * 1024 * 1024)
            .build()
            .also { destination = it }
    }
}
