package com.neilturner.aerialviews.providers.custom

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import coil3.disk.DiskCache
import com.neilturner.aerialviews.data.AerialDatabase
import com.neilturner.aerialviews.data.CachedMediaEntity
import com.neilturner.aerialviews.data.PlaylistStateEntity
import com.neilturner.aerialviews.data.PrivateReconciliationCopy
import com.neilturner.aerialviews.data.PrivateReconciliationJournal
import com.neilturner.aerialviews.data.reconciliationFingerprint
import com.neilturner.aerialviews.services.CachedPlaylistGate
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class KrillReconciliationExecutorTest {
    @TempDir lateinit var directory: File

    @Test
    fun `retry after purge and activation interruptions completes removal and finishes the journal`() =
        runTest {
            val db = Room.inMemoryDatabaseBuilder<AerialDatabase>().setDriver(BundledSQLiteDriver()).build()
            val source =
                DiskCache
                    .Builder()
                    .directory(directory.resolve("old").toOkioPath())
                    .maxSizeBytes(1024 * 1024)
                    .build()
            val destination =
                DiskCache
                    .Builder()
                    .directory(directory.resolve("new").toOkioPath())
                    .maxSizeBytes(1024 * 1024)
                    .build()
            try {
                fun photo(
                    id: String,
                    order: Int,
                ) = CachedMediaEntity(
                    playlistOrder = order,
                    uri = "https://photos.example/api/frame/media/$id/image?space=mine",
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
                val retained = photo("retained", 0)
                val removed = photo("removed", 1)
                db.playlistCacheDao().updateCache(
                    listOf(retained, removed),
                    emptyList(),
                    PlaylistStateEntity(1, 0, 0, 123, "settings", 2, 0, false, false, false),
                )
                val retainedKey = KrillCacheKey.forImage("session", retained.uri)
                val removedKey = KrillCacheKey.forImage("session", removed.uri)
                listOf(retainedKey, removedKey).forEach { key ->
                    requireNotNull(source.openEditor(key)).apply {
                        data.toFile().writeText("synthetic")
                        metadata.toFile().writeText("metadata")
                        commit()
                    }
                }
                val dao = db.privateReconciliationDao()
                val journal =
                    PrivateReconciliationJournal(
                        operation = "op",
                        namespace = "session",
                        generationToken = "generation",
                        sourceCacheDirectory = source.directory.toString(),
                        destinationCacheDirectory = destination.directory.toString(),
                        origin = "https://photos.example",
                        space = "mine",
                        revision = "7",
                        cachedAt = 123,
                        settingsHash = "settings",
                        expectedTotal = 2,
                        playlistFingerprint = reconciliationFingerprint(dao.playlistIdentities()),
                        videoComplete = false,
                        removedIdsJson = "[\"removed\"]",
                    )
                assertTrue(dao.begin(journal))
                var interrupt = true
                var rejectVerification = true
                var ownerValid = false
                var maintenanceAllowed = false
                val gate = CachedPlaylistGate()
                val screenOwner = gate.register()
                var screenStopped = false
                var activations = 0
                var interruptActivation = true
                var reopened = 0
                val hooks =
                    object : KrillReconciliationHooks {
                        override suspend fun <T> withExclusiveOwnership(block: suspend () -> T): T = block()

                        override suspend fun validateOwnership(journal: PrivateReconciliationJournal) = ownerValid

                        override suspend fun fenceAndDrain(journal: PrivateReconciliationJournal): Boolean {
                            if (!screenStopped) {
                                gate.load(screenOwner) { screenOwner.close() }
                                screenStopped = true
                            }
                            return true
                        }

                        override suspend fun withPlaylistMaintenance(block: suspend () -> Boolean): Boolean {
                            if (!maintenanceAllowed) return false
                            var result = false
                            return gate.runWhenUnowned(1000) { result = block() } && result
                        }

                        override suspend fun prepareDestination(journal: PrivateReconciliationJournal) = destination.clear()

                        override suspend fun scanCopies(
                            journal: PrivateReconciliationJournal,
                            rows: List<CachedMediaEntity>,
                        ): CacheCopyScan =
                            KrillCacheMigration.copyKnownEntries(
                                source,
                                destination,
                                KrillCacheMigration
                                    .retainedKnownKeys(
                                        rows,
                                        journal.namespace,
                                        journal.origin,
                                        journal.space,
                                        setOf("removed"),
                                    ).asIterable(),
                            )

                        override suspend fun verifyCopies(
                            journal: PrivateReconciliationJournal,
                            copies: List<PrivateReconciliationCopy>,
                        ) {
                            KrillCacheMigration.verifyCopies(destination, copies)
                            if (rejectVerification) {
                                rejectVerification = false
                                throw IOException("synthetic verification failure")
                            }
                        }

                        override suspend fun purgeSource(journal: PrivateReconciliationJournal) {
                            source.clear()
                            if (interrupt) {
                                interrupt = false
                                throw IOException("synthetic interruption")
                            }
                        }

                        override suspend fun activateCache(journal: PrivateReconciliationJournal): Boolean {
                            activations++
                            if (interruptActivation) {
                                interruptActivation = false
                                throw IOException("synthetic activation interruption")
                            }
                            return true
                        }

                        override suspend fun reopenPrivateWork(journal: PrivateReconciliationJournal) {
                            reopened++
                        }
                    }
                val executor = KrillReconciliationExecutor(dao, hooks)
                assertEquals(false, executor.runToPurged("op", "generation"))
                assertEquals("pending", dao.current()?.stage)
                source.openSnapshot(removedKey)!!.close()
                ownerValid = true
                assertEquals(false, executor.runToPurged("op", "generation"))
                assertEquals("pending", dao.current()?.stage)
                source.openSnapshot(removedKey)!!.close()
                maintenanceAllowed = true
                assertTrue(runCatching { executor.runToPurged("op", "generation") }.exceptionOrNull() is IOException)
                assertEquals("copied", dao.current()?.stage)
                val stillPresent = source.openSnapshot(removedKey)
                assertNotNull(stillPresent)
                stillPresent?.close()
                assertTrue(runCatching { executor.runToPurged("op", "generation") }.exceptionOrNull() is IOException)
                assertEquals("copied", dao.current()?.stage)
                assertNull(source.openSnapshot(removedKey))
                assertTrue(executor.runToPurged("op", "generation"))
                assertEquals("purged", dao.current()?.stage)
                assertEquals(listOf(retained.uri), db.playlistCacheDao().getAllMediaItemsOrdered().map { it.uri })
                destination.openSnapshot(retainedKey)!!.use { assertEquals("synthetic", it.data.toFile().readText()) }
                assertNull(destination.openSnapshot(removedKey))
                // A destination that no longer matches the durable manifest blocks activation.
                destination.remove(retainedKey)
                assertTrue(runCatching { executor.complete("op", "generation") }.exceptionOrNull() is IllegalStateException)
                assertEquals("purged", dao.current()?.stage)
                assertEquals(0, activations)
                assertEquals(0, reopened)
                requireNotNull(destination.openEditor(retainedKey)).apply {
                    data.toFile().writeText("synthetic")
                    metadata.toFile().writeText("metadata")
                    commit()
                }

                // Activation interrupted after the generation is recorded: the journal stays and the fence stays closed.
                assertTrue(runCatching { executor.complete("op", "generation") }.exceptionOrNull() is IOException)
                assertEquals("purged", dao.current()?.stage)
                assertEquals("generation", dao.activeGeneration()?.generationToken)
                assertEquals(0, reopened)

                // A stale operation never finishes the current journal.
                assertEquals(false, executor.complete("other", "generation"))
                assertEquals("purged", dao.current()?.stage)

                // The retry is idempotent, removes the journal and reopens private work once.
                assertTrue(executor.complete("op", "generation"))
                assertNull(dao.current())
                assertEquals(2, activations)
                assertEquals(1, reopened)
                assertEquals(listOf(retained.uri), db.playlistCacheDao().getAllMediaItemsOrdered().map { it.uri })
                assertEquals(false, executor.complete("op", "generation"))
                assertEquals(1, reopened)
            } finally {
                source.shutdown()
                destination.shutdown()
                db.close()
            }
        }
}
