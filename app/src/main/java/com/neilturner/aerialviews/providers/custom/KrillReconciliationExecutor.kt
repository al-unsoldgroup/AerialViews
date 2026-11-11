package com.neilturner.aerialviews.providers.custom

import com.neilturner.aerialviews.data.CachedMediaEntity
import com.neilturner.aerialviews.data.PrivateReconciliationCopy
import com.neilturner.aerialviews.data.PrivateReconciliationDao
import com.neilturner.aerialviews.data.PrivateReconciliationJournal
import com.neilturner.aerialviews.services.CachedPlaylistGate

/** Uses the exact process gate that protects screen playlist loads and final saves. */
internal suspend fun processPlaylistMaintenance(block: suspend () -> Boolean): Boolean {
    var result = false
    val ran = CachedPlaylistGate.process.runWhenUnowned(30_000) { result = block() }
    return ran && result
}

/** Hooks must serialize session replacement, sign-out and playlist rewrites for the entire block. */
internal interface KrillReconciliationHooks {
    suspend fun <T> withExclusiveOwnership(block: suspend () -> T): T

    suspend fun validateOwnership(journal: PrivateReconciliationJournal): Boolean

    suspend fun fenceAndDrain(journal: PrivateReconciliationJournal): Boolean

    /** Acquire only after old screens have been stopped, so their finalizers can release the gate. */
    suspend fun withPlaylistMaintenance(block: suspend () -> Boolean): Boolean

    suspend fun prepareDestination(journal: PrivateReconciliationJournal)

    suspend fun scanCopies(
        journal: PrivateReconciliationJournal,
        rows: List<CachedMediaEntity>,
    ): CacheCopyScan

    suspend fun verifyCopies(
        journal: PrivateReconciliationJournal,
        copies: List<PrivateReconciliationCopy>,
    )

    suspend fun purgeSource(journal: PrivateReconciliationJournal)

    /** Switch the active private cache to the verified destination; idempotent after a restart. */
    suspend fun activateCache(journal: PrivateReconciliationJournal): Boolean

    /** Reopen the fence revoked by [fenceAndDrain] in this attempt, after the journal is finished. */
    suspend fun reopenPrivateWork(journal: PrivateReconciliationJournal)
}

/** Run the crash-retryable journal stages; screen restart is owned by the caller. */
internal class KrillReconciliationExecutor(
    private val dao: PrivateReconciliationDao,
    private val hooks: KrillReconciliationHooks,
) {
    suspend fun runToPurged(
        operation: String,
        generation: String,
    ): Boolean = run(operation, generation, activate = false)

    /** Run every remaining stage, activate the new generation and cache, then remove the journal. */
    suspend fun complete(
        operation: String,
        generation: String,
    ): Boolean = run(operation, generation, activate = true)

    private suspend fun run(
        operation: String,
        generation: String,
        activate: Boolean,
    ): Boolean =
        hooks.withExclusiveOwnership {
            var journal = dao.current() ?: return@withExclusiveOwnership false
            if (journal.operation != operation || journal.generationToken != generation) return@withExclusiveOwnership false
            if (!hooks.validateOwnership(journal)) return@withExclusiveOwnership false
            if (!hooks.fenceAndDrain(journal)) return@withExclusiveOwnership false
            val done =
                hooks.withPlaylistMaintenance maintenance@{
                    while (journal.stage != "purged") {
                        if (!hooks.validateOwnership(journal)) return@maintenance false
                        when (journal.stage) {
                            "pending" -> {
                                hooks.prepareDestination(journal)
                                if (!dao.advance(operation, generation, "pending", "drained")) return@maintenance false
                            }

                            "drained" -> {
                                val scan = hooks.scanCopies(journal, dao.mediaRows())
                                if (!dao.recordCopies(operation, generation, scan)) return@maintenance false
                            }

                            "copied" -> {
                                hooks.verifyCopies(journal, dao.recordedCopies())
                                if (!dao.canApplyPurgedRemoval(operation, generation)) return@maintenance false
                                hooks.purgeSource(journal)
                                if (!dao.applyPurgedRemoval(operation, generation)) return@maintenance false
                            }

                            else -> {
                                return@maintenance false
                            }
                        }
                        journal = dao.current() ?: return@maintenance false
                        if (journal.operation != operation || journal.generationToken != generation) return@maintenance false
                    }
                    if (!hooks.validateOwnership(journal)) return@maintenance false
                    hooks.verifyCopies(journal, dao.recordedCopies())
                    if (!activate) return@maintenance true
                    // Each step is idempotent, so a restart between them resumes from the purged journal.
                    dao.activateGeneration(operation, generation) && hooks.activateCache(journal) && dao.finish(operation, generation)
                }
            if (done && activate) hooks.reopenPrivateWork(journal)
            done
        }
}
