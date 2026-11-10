package com.neilturner.aerialviews.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import coil3.disk.DiskCache
import com.neilturner.aerialviews.providers.custom.CacheCopyScan
import com.neilturner.aerialviews.providers.custom.KrillCacheMigration
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PrivateReconciliationDaoTest {
    @TempDir lateinit var directory: File

    private fun scanCache(
        keys: Iterable<String>,
        present: List<PrivateReconciliationCopy> = emptyList(),
        alternateSource: Boolean = false,
    ): CacheCopyScan {
        val old =
            DiskCache
                .Builder()
                .directory(directory.resolve(if (alternateSource) "other-cache" else "scan-old").toOkioPath())
                .maxSizeBytes(1024 * 1024)
                .build()
        val destination =
            DiskCache
                .Builder()
                .directory(directory.resolve("scan-new").toOkioPath())
                .maxSizeBytes(1024 * 1024)
                .build()
        try {
            old.clear()
            destination.clear()
            present.forEach { entry ->
                requireNotNull(old.openEditor(entry.cacheKey)).apply {
                    data.toFile().writeText("synthetic data")
                    metadata.toFile().writeText("synthetic metadata")
                    commit()
                }
            }
            return KrillCacheMigration.copyKnownEntries(old, destination, keys)
        } finally {
            old.shutdown()
            destination.shutdown()
        }
    }

    private suspend fun completeScan(
        dao: PrivateReconciliationDao,
        journal: PrivateReconciliationJournal,
        removed: Set<String>,
        copies: List<PrivateReconciliationCopy> = emptyList(),
    ): CacheCopyScan {
        val keys = KrillCacheMigration.retainedKnownKeys(dao.mediaRows(), journal.namespace, journal.origin, journal.space, removed)
        return scanCache(keys.asIterable(), copies)
    }

    @Test
    fun `pending journal and generation survive database restart`() =
        runTest {
            val filename = directory.resolve("journal.db").absolutePath

            fun open() = Room.databaseBuilder<AerialDatabase>(name = filename).setDriver(BundledSQLiteDriver()).build()
            val first = open()
            val retained =
                CachedMediaEntity(
                    playlistOrder = 0,
                    uri = "https://photos.example/api/frame/media/retained/image?space=mine",
                    type = "IMAGE",
                    source = "KRILL",
                    shortDescription = "",
                    pointsOfInterest = emptyMap(),
                    timeOfDay = "UNKNOWN",
                    scene = "UNKNOWN",
                    albumName = "",
                    title = "",
                    exifDate = null,
                    exifOffset = null,
                    exifLatitude = null,
                    exifLongitude = null,
                    exifCity = null,
                    exifState = null,
                    exifCountry = null,
                    exifDescription = null,
                )
            first.playlistCacheDao().updateCache(
                listOf(retained),
                emptyList(),
                PlaylistStateEntity(1, -1, 0, 123, "settings", 1, 0, false, false, false),
            )
            val journal =
                PrivateReconciliationJournal(
                    operation = "op",
                    namespace = "session",
                    generationToken = "generation",
                    sourceCacheDirectory = directory.resolve("scan-old").absolutePath,
                    destinationCacheDirectory = directory.resolve("scan-new").absolutePath,
                    origin = "https://photos.example",
                    space = "mine",
                    revision = "7",
                    cachedAt = 123,
                    settingsHash = "settings",
                    expectedTotal = 1,
                    playlistFingerprint = reconciliationFingerprint(first.privateReconciliationDao().playlistIdentities()),
                    videoComplete = false,
                    removedIdsJson = "[\"photo\"]",
                )
            try {
                assertTrue(first.privateReconciliationDao().begin(journal))
            } finally {
                first.close()
            }
            val reopened = open()
            val retainedCopy =
                PrivateReconciliationCopy(
                    cacheKey = "session:https://photos.example/api/frame/media/retained/image?space=mine",
                    dataDigest = "a".repeat(64),
                    metadataDigest = "b".repeat(64),
                )
            lateinit var expectedCopies: List<PrivateReconciliationCopy>
            try {
                assertEquals(journal, reopened.privateReconciliationDao().current())
                assertTrue(reopened.privateReconciliationDao().advance("op", "generation", "pending", "drained"))
                val dao = reopened.privateReconciliationDao()
                val scan = completeScan(dao, journal, setOf("photo"), listOf(retainedCopy))
                expectedCopies = scan.copies
                assertTrue(dao.recordCopies("op", "generation", scan))
            } finally {
                reopened.close()
            }
            val finalOpen = open()
            try {
                assertEquals("copied", finalOpen.privateReconciliationDao().current()?.stage)
                assertEquals(expectedCopies, finalOpen.privateReconciliationDao().recordedCopies())
                assertEquals(1, finalOpen.privateReconciliationDao().advanceIfStage("op", "generation", "copied", "purged"))
                assertTrue(finalOpen.privateReconciliationDao().activateGeneration("op", "generation"))
                assertTrue(finalOpen.privateReconciliationDao().finish("op", "generation"))
            } finally {
                finalOpen.close()
            }
            val activated = open()
            try {
                assertNull(activated.privateReconciliationDao().current())
                assertEquals(
                    PrivateCacheGeneration(namespace = "session", generationToken = "generation"),
                    activated.privateReconciliationDao().activeGeneration(),
                )
            } finally {
                activated.close()
            }
        }

    @Test
    fun `purged removal resets playback and preserves unavailable videos and unrelated media`() =
        runTest {
            val db = Room.inMemoryDatabaseBuilder<AerialDatabase>().setDriver(BundledSQLiteDriver()).build()
            try {
                fun item(
                    order: Int,
                    id: String,
                    type: String = "IMAGE",
                    source: String = "KRILL",
                ) = CachedMediaEntity(
                    playlistOrder = order,
                    uri = "https://photos.example/api/frame/media/$id/${if (type == "VIDEO") "video" else "image"}?space=mine",
                    type = type,
                    source = source,
                    shortDescription = "",
                    pointsOfInterest = emptyMap(),
                    timeOfDay = "UNKNOWN",
                    scene = "UNKNOWN",
                    albumName = "",
                    title = "",
                    exifDate = null,
                    exifOffset = null,
                    exifLatitude = null,
                    exifLongitude = null,
                    exifCity = null,
                    exifState = null,
                    exifCountry = null,
                    exifDescription = null,
                )
                val deleted = item(0, "deleted")
                val retained = item(1, "retained").copy(pairPartner = "deleted", pairedWithNext = true)
                val video = item(2, "video", "VIDEO")
                val unrelated = item(3, "unrelated", source = "LOCAL")
                val state = PlaylistStateEntity(1, 1, 0, 123, "settings", 4, 0, false, false, false)
                db.playlistCacheDao().updateCache(listOf(deleted, retained, video, unrelated), emptyList(), state)
                db.privateCacheVariantDao().record(PrivateCacheVariant("session", "deleted", "cached-key"))
                val dao = db.privateReconciliationDao()
                val journal =
                    PrivateReconciliationJournal(
                        operation = "op",
                        namespace = "session",
                        generationToken = "generation",
                        sourceCacheDirectory = directory.resolve("scan-old").absolutePath,
                        destinationCacheDirectory = directory.resolve("scan-new").absolutePath,
                        origin = "https://photos.example",
                        space = "mine",
                        revision = "5",
                        cachedAt = 123,
                        settingsHash = "settings",
                        expectedTotal = 4,
                        playlistFingerprint = reconciliationFingerprint(dao.playlistIdentities()),
                        videoComplete = false,
                        removedIdsJson = "[\"deleted\"]",
                    )
                assertTrue(dao.begin(journal))
                assertFalse(dao.advance("op", "wrong-generation", "pending", "drained"))
                assertTrue(dao.advance("op", "generation", "pending", "drained"))
                val emptyScan = scanCache(emptyList())
                assertFalse(dao.recordCopies("op", "wrong-generation", emptyScan))
                assertFalse(dao.recordCopies("op", "generation", emptyScan))
                val digest = "a".repeat(64)
                val removedCopy =
                    PrivateReconciliationCopy(cacheKey = "session:${deleted.uri}", dataDigest = digest, metadataDigest = digest)
                val retainedCopy =
                    PrivateReconciliationCopy(cacheKey = "session:${retained.uri}", dataDigest = digest, metadataDigest = digest)
                assertFalse(dao.recordCopies("op", "generation", scanCache(listOf(removedCopy.cacheKey), listOf(removedCopy))))
                val expectedKeys =
                    KrillCacheMigration
                        .retainedKnownKeys(
                            dao.mediaRows(),
                            journal.namespace,
                            journal.origin,
                            journal.space,
                            setOf("deleted"),
                        ).toList()
                assertTrue(expectedKeys.size > 1)
                assertFalse(dao.recordCopies("op", "generation", scanCache(expectedKeys.dropLast(1), listOf(retainedCopy))))
                val retainedScan = completeScan(dao, journal, setOf("deleted"), listOf(retainedCopy))
                val wrongSource =
                    scanCache(
                        KrillCacheMigration
                            .retainedKnownKeys(
                                dao.mediaRows(),
                                journal.namespace,
                                journal.origin,
                                journal.space,
                                setOf("deleted"),
                            ).asIterable(),
                        listOf(retainedCopy),
                        alternateSource = true,
                    )
                assertFalse(dao.recordCopies("op", "generation", wrongSource))
                val cachedRetained = db.playlistCacheDao().getAllMediaItemsOrdered().first { it.uri == retained.uri }
                db.playlistCacheDao().updateMediaItems(listOf(cachedRetained.copy(focusRevision = 8)))
                assertFalse(dao.recordCopies("op", "generation", retainedScan))
                db.playlistCacheDao().updateMediaItems(listOf(cachedRetained))
                assertTrue(dao.recordCopies("op", "generation", retainedScan))
                assertEquals(retainedScan.copies, dao.recordedCopies())
                assertFalse(dao.canApplyPurgedRemoval("op", "wrong-generation"))
                assertTrue(dao.canApplyPurgedRemoval("op", "generation"))
                assertFalse(dao.applyPurgedRemoval("op", "wrong-generation"))
                assertTrue(dao.applyPurgedRemoval("op", "generation"))
                val remaining = db.playlistCacheDao().getAllMediaItemsOrdered()
                assertEquals(listOf(retained.uri, video.uri, unrelated.uri), remaining.map { it.uri })
                assertNull(remaining.first().pairPartner)
                assertNull(remaining.first().pairedWithNext)
                assertEquals(3, db.playlistCacheDao().getPlaylistState()?.totalMediaItems)
                assertEquals(-1, db.playlistCacheDao().getPlaylistState()?.mediaPosition)
                assertEquals(emptyList<String>(), db.privateCacheVariantDao().keysForMedia("session", "deleted"))
                assertFalse(dao.finish("op", "wrong-generation"))
                assertFalse(dao.finish("op", "generation"))
                assertFalse(dao.activateGeneration("op", "wrong-generation"))
                assertTrue(dao.activateGeneration("op", "generation"))
                assertTrue(dao.finish("op", "generation"))
                assertEquals(PrivateCacheGeneration(namespace = "session", generationToken = "generation"), dao.activeGeneration())
                assertEquals(emptyList<PrivateReconciliationCopy>(), dao.recordedCopies())
                val unavailableVideo =
                    journal.copy(
                        operation = "second",
                        generationToken = "generation-2",
                        expectedTotal = 3,
                        playlistFingerprint = reconciliationFingerprint(dao.playlistIdentities()),
                        removedIdsJson = "[\"video\"]",
                    )
                assertFalse(dao.begin(unavailableVideo.copy(generationToken = "generation")))
                assertTrue(dao.begin(unavailableVideo))
                assertTrue(dao.advance("second", "generation-2", "pending", "drained"))
                assertTrue(dao.recordCopies("second", "generation-2", completeScan(dao, unavailableVideo, setOf("video"))))
                assertFalse(dao.canApplyPurgedRemoval("second", "generation-2"))
                assertFalse(dao.applyPurgedRemoval("second", "generation-2"))
                assertEquals(3, db.playlistCacheDao().getAllMediaItemsOrdered().size)
            } finally {
                db.close()
            }
        }

    @Test
    fun `journal rejects stale playlists and advances only its own operation in order`() =
        runTest {
            val db = Room.inMemoryDatabaseBuilder<AerialDatabase>().setDriver(BundledSQLiteDriver()).build()
            try {
                val state = PlaylistStateEntity(1, -1, 0, 123, "settings", 0, 0, false, false, false)
                db.playlistCacheDao().insertOrUpdateState(state)
                val dao = db.privateReconciliationDao()
                val journal =
                    PrivateReconciliationJournal(
                        operation = "op",
                        namespace = "session",
                        generationToken = "generation",
                        sourceCacheDirectory = directory.resolve("scan-old").absolutePath,
                        destinationCacheDirectory = directory.resolve("scan-new").absolutePath,
                        origin = "origin",
                        space = "space",
                        revision = "5",
                        cachedAt = 123,
                        settingsHash = "settings",
                        expectedTotal = 0,
                        playlistFingerprint = reconciliationFingerprint(emptyList()),
                        videoComplete = false,
                        removedIdsJson = "[]",
                    )
                assertFalse(dao.begin(journal.copy(cachedAt = 124)))
                assertFalse(dao.begin(journal.copy(expectedTotal = 1)))
                assertFalse(dao.begin(journal.copy(generationToken = "")))
                assertFalse(dao.begin(journal.copy(playlistFingerprint = "stale")))
                assertTrue(dao.begin(journal))
                assertEquals("generation", dao.current()?.generationToken)
                assertFalse(dao.begin(journal.copy(operation = "other")))
                assertFalse(dao.advance("op", "generation", "pending", "purged"))
                assertFalse(dao.advance("other", "generation", "pending", "drained"))
                assertFalse(dao.finish("op", "generation"))
                assertTrue(dao.advance("op", "generation", "pending", "drained"))
                assertEquals("drained", dao.current()?.stage)
                assertFalse(dao.advance("op", "generation", "drained", "purged"))
                assertFalse(dao.applyPurgedRemoval("op", "generation"))
                val emptyScan = scanCache(emptyList())
                assertTrue(dao.recordCopies("op", "generation", emptyScan))
                assertEquals("copied", dao.current()?.stage)
                assertFalse(dao.recordCopies("op", "generation", emptyScan))
                assertFalse(dao.applyPurgedRemoval("op", "generation"))
                assertEquals("copied", dao.current()?.stage)
            } finally {
                db.close()
            }
        }
}
