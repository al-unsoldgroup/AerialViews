package com.neilturner.aerialviews.data

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import com.neilturner.aerialviews.providers.custom.CacheCandidateFingerprint
import com.neilturner.aerialviews.providers.custom.CacheCopyScan
import com.neilturner.aerialviews.providers.custom.KrillCacheMigration
import com.neilturner.aerialviews.providers.custom.KrillPrivateFeed
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

data class ReconciliationIdentity(
    val id: Int,
    val uri: String,
    val type: String,
    val source: String,
    val playlistOrder: Int,
)

internal fun reconciliationFingerprint(rows: List<ReconciliationIdentity>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    rows.forEach { row ->
        listOf(row.id.toString(), row.uri, row.type, row.source, row.playlistOrder.toString()).forEach { field ->
            val bytes = field.toByteArray(Charsets.UTF_8)
            digest.update(
                java.nio.ByteBuffer
                    .allocate(4)
                    .putInt(bytes.size)
                    .array(),
            )
            digest.update(bytes)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** Durable private recovery state. Never log its contents. */
@Entity(tableName = "private_reconciliation")
data class PrivateReconciliationJournal(
    @PrimaryKey val id: Int = 1,
    val operation: String,
    val namespace: String,
    val generationToken: String,
    @ColumnInfo(defaultValue = "''") val sourceCacheDirectory: String,
    @ColumnInfo(defaultValue = "''") val destinationCacheDirectory: String,
    val origin: String,
    val space: String,
    val revision: String,
    val cachedAt: Long,
    val settingsHash: String,
    val expectedTotal: Int,
    val playlistFingerprint: String,
    val videoComplete: Boolean,
    val removedIdsJson: String,
    val stage: String = "pending",
)

@Entity(
    tableName = "private_reconciliation_copies",
    primaryKeys = ["journalId", "cacheKey"],
    foreignKeys = [
        ForeignKey(
            entity = PrivateReconciliationJournal::class,
            parentColumns = ["id"],
            childColumns = ["journalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("journalId")],
)
data class PrivateReconciliationCopy(
    val journalId: Int = 1,
    val cacheKey: String,
    val dataDigest: String,
    val metadataDigest: String,
)

@Entity(tableName = "private_cache_generation")
data class PrivateCacheGeneration(
    @PrimaryKey val id: Int = 1,
    val namespace: String,
    val generationToken: String,
)

private data class RemovalPlan(
    val removed: Set<String>,
    val matching: List<CachedMediaEntity>,
    val retained: List<CachedMediaEntity>,
    val total: Int,
)

private fun removalPlan(
    journal: PrivateReconciliationJournal,
    state: PlaylistStateEntity,
    rows: List<CachedMediaEntity>,
    operation: String,
    generation: String,
): RemovalPlan? {
    if (journal.operation != operation || journal.generationToken != generation || journal.stage != "copied") return null
    val identities = rows.map { ReconciliationIdentity(it.id, it.uri, it.type, it.source, it.playlistOrder) }
    if (state.cachedAt != journal.cachedAt || state.settingsHash != journal.settingsHash ||
        state.totalMediaItems != journal.expectedTotal || rows.size != journal.expectedTotal ||
        reconciliationFingerprint(identities) != journal.playlistFingerprint
    ) {
        return null
    }
    val removed = runCatching { Json.decodeFromString<List<String>>(journal.removedIdsJson) }.getOrNull()?.toSet() ?: return null
    if (removed.isEmpty() || removed.any { it.isBlank() }) return null

    fun selected(row: CachedMediaEntity): Boolean =
        row.source == "KRILL" &&
            (
                KrillPrivateFeed.isSelectedImage(row.uri, journal.origin, journal.space) ||
                    KrillPrivateFeed.isSelectedVideo(row.uri, journal.origin, journal.space)
            )
    val matching =
        rows.filter { row ->
            val allowedImage = row.type == "IMAGE" && KrillPrivateFeed.isSelectedImage(row.uri, journal.origin, journal.space)
            val allowedVideo =
                row.type == "VIDEO" && journal.videoComplete &&
                    KrillPrivateFeed.isSelectedVideo(row.uri, journal.origin, journal.space)
            val mediaId =
                row.uri
                    .toHttpUrlOrNull()
                    ?.pathSegments
                    ?.getOrNull(3)
            row.source == "KRILL" && (allowedImage || allowedVideo) && mediaId in removed
        }
    if (matching
            .mapNotNull {
                it.uri
                    .toHttpUrlOrNull()
                    ?.pathSegments
                    ?.getOrNull(3)
            }.toSet() != removed
    ) {
        return null
    }
    val deletedIds = matching.map { it.id }.toSet()
    val retained =
        rows.filter {
            it.id !in deletedIds && selected(it) && (it.pairedWithNext != null || it.pairPartner != null || it.pairThird != null)
        }
    return RemovalPlan(removed, matching, retained, rows.size - matching.size)
}

@Dao
interface PrivateReconciliationDao {
    // A single-row table; REPLACE keeps activation idempotent where an upsert inside a transaction aborts it.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveGeneration(generation: PrivateCacheGeneration)

    @Query("SELECT * FROM private_cache_generation WHERE id = 1")
    suspend fun activeGeneration(): PrivateCacheGeneration?

    @Query("DELETE FROM private_cache_generation")
    suspend fun clearGeneration()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCopies(copies: List<PrivateReconciliationCopy>)

    @Query("SELECT * FROM private_reconciliation_copies WHERE journalId = 1 ORDER BY cacheKey")
    suspend fun recordedCopies(): List<PrivateReconciliationCopy>

    @Query("SELECT * FROM private_reconciliation WHERE id = 1")
    suspend fun current(): PrivateReconciliationJournal?

    @Query("SELECT * FROM playlist_state WHERE id = 1")
    suspend fun playlistState(): PlaylistStateEntity?

    @Query("SELECT COUNT(*) FROM cached_media")
    suspend fun mediaCount(): Int

    @Query("SELECT id, uri, type, source, playlistOrder FROM cached_media ORDER BY id ASC")
    suspend fun playlistIdentities(): List<ReconciliationIdentity>

    @Query("SELECT * FROM cached_media ORDER BY id ASC")
    suspend fun mediaRows(): List<CachedMediaEntity>

    @Query("DELETE FROM cached_media WHERE id IN (:ids)")
    suspend fun deleteMediaRows(ids: List<Int>)

    @Update
    suspend fun updateMediaRows(rows: List<CachedMediaEntity>)

    @Query("UPDATE playlist_state SET totalMediaItems = :count, mediaPosition = -1 WHERE id = 1")
    suspend fun resetPlaylistAfterRemoval(count: Int)

    @Query("DELETE FROM private_cache_variants WHERE namespace = :namespace AND mediaId IN (:ids)")
    suspend fun deleteVariantRows(
        namespace: String,
        ids: List<String>,
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(journal: PrivateReconciliationJournal): Long

    /** Caller must also serialize and validate the session while creating the journal. */
    @Transaction
    suspend fun begin(journal: PrivateReconciliationJournal): Boolean {
        val state = playlistState() ?: return false
        val active = activeGeneration()
        if (journal.id != 1 || journal.stage != "pending" || journal.operation.isBlank() || journal.namespace.isBlank() ||
            journal.generationToken.isBlank() ||
            journal.sourceCacheDirectory.isBlank() || journal.destinationCacheDirectory.isBlank() ||
            journal.sourceCacheDirectory == journal.destinationCacheDirectory ||
            (active?.namespace == journal.namespace && active.generationToken == journal.generationToken) ||
            journal.origin.isBlank() || journal.space.isBlank() || journal.revision.isBlank() ||
            state.cachedAt != journal.cachedAt || state.settingsHash != journal.settingsHash ||
            state.totalMediaItems != journal.expectedTotal || mediaCount() != journal.expectedTotal ||
            reconciliationFingerprint(playlistIdentities()) != journal.playlistFingerprint
        ) {
            return false
        }
        return insertIfAbsent(journal) != -1L
    }

    @Query(
        "UPDATE private_reconciliation SET stage = :next " +
            "WHERE id = 1 AND operation = :operation AND generationToken = :generation AND stage = :expected",
    )
    suspend fun advanceIfStage(
        operation: String,
        generation: String,
        expected: String,
        next: String,
    ): Int

    @Transaction
    suspend fun advance(
        operation: String,
        generation: String,
        expected: String,
        next: String,
    ): Boolean {
        if (expected != "pending" || next != "drained") return false
        return advanceIfStage(operation, generation, expected, next) == 1
    }

    /** Commit the complete verified manifest before permitting any old-cache purge. */
    @Transaction
    suspend fun recordCopies(
        operation: String,
        generation: String,
        scan: CacheCopyScan,
    ): Boolean {
        val journal = current() ?: return false
        if (journal.operation != operation || journal.generationToken != generation || journal.stage != "drained") return false
        if (scan.sourceDirectory != journal.sourceCacheDirectory ||
            scan.destinationDirectory != journal.destinationCacheDirectory
        ) {
            return false
        }
        val copies = scan.copies
        val digestPattern = Regex("[0-9a-f]{64}")
        val removed = runCatching { Json.decodeFromString<List<String>>(journal.removedIdsJson).toSet() }.getOrNull() ?: return false
        val prefix = "${journal.namespace}:"
        if (copies.any {
                it.journalId != 1 || it.cacheKey.isBlank() || !digestPattern.matches(it.dataDigest) ||
                    !digestPattern.matches(it.metadataDigest)
            } ||
            copies.map { it.cacheKey }.toSet().size != copies.size || recordedCopies().isNotEmpty()
        ) {
            return false
        }
        val state = playlistState() ?: return false
        if (state.cachedAt != journal.cachedAt || state.settingsHash != journal.settingsHash ||
            state.totalMediaItems != journal.expectedTotal || reconciliationFingerprint(playlistIdentities()) != journal.playlistFingerprint
        ) {
            return false
        }
        val candidateHash = CacheCandidateFingerprint()
        val unmatchedCopies = copies.map { it.cacheKey }.toMutableSet()
        for (key in KrillCacheMigration.retainedKnownKeys(mediaRows(), journal.namespace, journal.origin, journal.space, removed)) {
            candidateHash.add(key)
            unmatchedCopies.remove(key)
        }
        if (unmatchedCopies.isNotEmpty() || candidateHash.finish() != scan.candidateFingerprint) return false
        if (copies.any {
                val url = it.cacheKey.removePrefix(prefix)
                !it.cacheKey.startsWith(prefix) || !KrillPrivateFeed.isSelectedImage(url, journal.origin, journal.space) ||
                    url.toHttpUrlOrNull()?.pathSegments?.getOrNull(3) in removed
            }
        ) {
            return false
        }
        insertCopies(copies)
        check(advanceIfStage(operation, generation, "drained", "copied") == 1)
        return true
    }

    /** The caller must hold the playlist and session gates through the subsequent purge and apply. */
    @Transaction
    suspend fun canApplyPurgedRemoval(
        operation: String,
        generation: String,
    ): Boolean {
        val journal = current() ?: return false
        val state = playlistState() ?: return false
        return removalPlan(journal, state, mediaRows(), operation, generation) != null
    }

    /** Call only after all removed private cache variants have been durably purged. */
    @Transaction
    suspend fun applyPurgedRemoval(
        operation: String,
        generation: String,
    ): Boolean {
        val journal = current() ?: return false
        val state = playlistState() ?: return false
        val plan = removalPlan(journal, state, mediaRows(), operation, generation) ?: return false
        plan.retained.chunked(400).forEach { batch ->
            updateMediaRows(
                batch.map {
                    val partner = it.pairPartner?.takeUnless { id -> id in plan.removed }
                    val third = it.pairThird?.takeUnless { id -> partner == null || id in plan.removed }
                    it.copy(pairedWithNext = null, pairPartner = partner, pairThird = third)
                },
            )
        }
        plan.matching
            .map { it.id }
            .chunked(400)
            .forEach { deleteMediaRows(it) }
        plan.removed
            .toList()
            .chunked(400)
            .forEach { deleteVariantRows(journal.namespace, it) }
        resetPlaylistAfterRemoval(plan.total)
        check(advanceIfStage(operation, generation, "copied", "purged") == 1)
        return true
    }

    @Query(
        "DELETE FROM private_reconciliation " +
            "WHERE id = 1 AND operation = :operation AND generationToken = :generation AND stage = :expected",
    )
    suspend fun deleteIfStage(
        operation: String,
        generation: String,
        expected: String,
    ): Int

    @Transaction
    suspend fun activateGeneration(
        operation: String,
        generation: String,
    ): Boolean {
        val journal = current() ?: return false
        if (journal.operation != operation || journal.generationToken != generation || journal.stage != "purged") return false
        saveGeneration(PrivateCacheGeneration(namespace = journal.namespace, generationToken = generation))
        return true
    }

    /** Caller must serialize with session replacement before finalizing. */
    @Transaction
    suspend fun finish(
        operation: String,
        generation: String,
    ): Boolean {
        val journal = current() ?: return false
        val active = activeGeneration() ?: return false
        if (active.namespace != journal.namespace || active.generationToken != generation) return false
        return deleteIfStage(operation, generation, "purged") == 1
    }

    @Query("DELETE FROM private_reconciliation")
    suspend fun clearForFullCleanup()
}
