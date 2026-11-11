package com.neilturner.aerialviews.providers.custom

import coil3.disk.DiskCache
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CancellationException

/** Synthetic feasibility check, not the production recovery protocol. */
class KrillLegacyCacheMigrationTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `cancellation while copying preserves source and permits retry`() {
        val old =
            DiskCache
                .Builder()
                .directory(directory.resolve("old").toOkioPath())
                .maxSizeBytes(1024 * 1024)
                .build()
        val replacement =
            DiskCache
                .Builder()
                .directory(directory.resolve("new").toOkioPath())
                .maxSizeBytes(1024 * 1024)
                .build()
        try {
            requireNotNull(old.openEditor("retained")).apply {
                data.toFile().writeBytes(ByteArray(64 * 1024) { 42 })
                metadata.toFile().writeText("metadata")
                commit()
            }
            var checkpoints = 0
            assertThrows(CancellationException::class.java) {
                KrillCacheMigration.copyKnownEntry(old, replacement, "retained") {
                    if (++checkpoints == 15) throw CancellationException()
                }
            }
            assertNull(replacement.openSnapshot("retained"))
            old.openSnapshot("retained")!!.use {
                assertEquals(
                    64 * 1024,
                    it.data
                        .toFile()
                        .length()
                        .toInt(),
                )
            }
            assertTrue(KrillCacheMigration.copyKnownEntry(old, replacement, "retained"))
        } finally {
            old.shutdown()
            replacement.shutdown()
        }
    }

    @Test
    fun `manifest verifies retained copy after old cache is cleared and destination reopens`() {
        val oldPath = directory.resolve("old").toOkioPath()
        val newPath = directory.resolve("new").toOkioPath()
        val old =
            DiskCache
                .Builder()
                .directory(oldPath)
                .maxSizeBytes(1024 * 1024)
                .build()
        val destination =
            DiskCache
                .Builder()
                .directory(newPath)
                .maxSizeBytes(1024 * 1024)
                .build()
        val scan =
            try {
                requireNotNull(old.openEditor("retained")).apply {
                    data.toFile().writeText("retained bytes")
                    metadata.toFile().writeText("metadata")
                    commit()
                }
                KrillCacheMigration.copyKnownEntries(old, destination, listOf("retained")).also { old.clear() }
            } finally {
                old.shutdown()
                destination.shutdown()
            }
        val reopened =
            DiskCache
                .Builder()
                .directory(newPath)
                .maxSizeBytes(1024 * 1024)
                .build()
        try {
            KrillCacheMigration.verifyCopies(reopened, scan.copies)
            reopened.openSnapshot("retained")!!.use { assertEquals("retained bytes", it.data.toFile().readText()) }
        } finally {
            reopened.shutdown()
        }
    }

    @Test
    fun `known variants include original and current crop keys only for the selected space`() {
        val image = "https://photos.example/api/frame/media/picture/image?space=mine"
        val keys = KrillCacheMigration.knownKeys("session", "https://photos.example", "mine", image, 7)
        assertTrue(KrillCacheKey.forImage("session", image) in keys)
        val panel = requireNotNull(KrillFeed.privatePanelCrop(image, 925, 1080, 7))
        assertTrue(KrillCacheKey.forImage("session", panel) in keys)
        val full = requireNotNull(KrillFeed.cropForDimensions(image, 1, 1, 1920, 1080, true, 7))
        assertTrue(KrillCacheKey.forImage("session", full) in keys)
        assertThrows(IllegalArgumentException::class.java) {
            KrillCacheMigration.knownKeys("session", "https://photos.example", "another", image, 7)
        }
    }

    @Test
    fun `conflicting existing copy is rejected without touching source`() {
        val old =
            DiskCache
                .Builder()
                .directory(directory.resolve("old").toOkioPath())
                .maxSizeBytes(1024 * 1024)
                .build()
        val replacement =
            DiskCache
                .Builder()
                .directory(directory.resolve("new").toOkioPath())
                .maxSizeBytes(1024 * 1024)
                .build()
        try {
            listOf(old to "original", replacement to "wrong").forEach { (cache, value) ->
                requireNotNull(cache.openEditor("retained")).apply {
                    data.toFile().writeText(value)
                    metadata.toFile().writeText("metadata")
                    commit()
                }
            }
            assertThrows(IllegalStateException::class.java) { KrillCacheMigration.copyKnownEntry(old, replacement, "retained") }
            old.openSnapshot("retained")!!.use { assertEquals("original", it.data.toFile().readText()) }
        } finally {
            old.shutdown()
            replacement.shutdown()
        }
    }

    @Test
    fun `reopening after an unfinished destination edit leaves source available`() {
        val oldPath = directory.resolve("old").toOkioPath()
        val newPath = directory.resolve("new").toOkioPath()
        val old =
            DiskCache
                .Builder()
                .directory(oldPath)
                .maxSizeBytes(1024 * 1024)
                .build()
        val replacement =
            DiskCache
                .Builder()
                .directory(newPath)
                .maxSizeBytes(1024 * 1024)
                .build()
        try {
            requireNotNull(old.openEditor("retained")).apply {
                data.toFile().writeText("original")
                metadata.toFile().writeText("metadata")
                commit()
            }
            requireNotNull(replacement.openEditor("retained")).data.toFile().writeText("partial")
            replacement.shutdown()
            val reopened =
                DiskCache
                    .Builder()
                    .directory(newPath)
                    .maxSizeBytes(1024 * 1024)
                    .build()
            try {
                assertNull(reopened.openSnapshot("retained"))
                old.openSnapshot("retained")!!.use { assertEquals("original", it.data.toFile().readText()) }
            } finally {
                reopened.shutdown()
            }
        } finally {
            old.shutdown()
        }
    }

    @Test
    fun `an aborted copy leaves the original intact and can be retried`() {
        val old =
            DiskCache
                .Builder()
                .directory(directory.resolve("old").toOkioPath())
                .maxSizeBytes(1024 * 1024)
                .build()
        val replacement =
            DiskCache
                .Builder()
                .directory(directory.resolve("new").toOkioPath())
                .maxSizeBytes(1024 * 1024)
                .build()
        try {
            requireNotNull(old.openEditor("retained")).apply {
                data.toFile().writeText("original")
                metadata.toFile().writeText("metadata")
                commit()
            }
            requireNotNull(replacement.openEditor("retained")).apply {
                data.toFile().writeText("partial")
                abort()
            }
            assertNull(replacement.openSnapshot("retained"))
            old.openSnapshot("retained")!!.use { source ->
                assertEquals("original", source.data.toFile().readText())
                requireNotNull(replacement.openEditor("retained")).apply {
                    source.data.toFile().copyTo(data.toFile(), overwrite = true)
                    source.metadata.toFile().copyTo(metadata.toFile(), overwrite = true)
                    commit()
                }
            }
            replacement.openSnapshot("retained")!!.use { assertEquals("original", it.data.toFile().readText()) }
        } finally {
            old.shutdown()
            replacement.shutdown()
        }
    }

    @Test
    fun `copying known retained keys before purge keeps their bytes and excludes unknown keys`() {
        val old =
            DiskCache
                .Builder()
                .directory(directory.resolve("old").toOkioPath())
                .maxSizeBytes(1024 * 1024)
                .build()
        val replacement =
            DiskCache
                .Builder()
                .directory(directory.resolve("new").toOkioPath())
                .maxSizeBytes(1024 * 1024)
                .build()
        try {
            fun put(
                key: String,
                value: String,
            ) {
                val editor = requireNotNull(old.openEditor(key))
                editor.data.toFile().writeText(value)
                editor.metadata.toFile().writeText("synthetic metadata")
                editor.commit()
            }
            put("retained-known", "retained bytes")
            put("retained-unknown", "unknown variant")
            put("deleted-unknown", "deleted bytes")
            assertFalse(KrillCacheMigration.copyKnownEntry(old, replacement, "missing"))
            val scan = KrillCacheMigration.copyKnownEntries(old, replacement, listOf("missing", "retained-known", "retained-known"))
            assertEquals(1, scan.copies.size)
            assertTrue(KrillCacheMigration.copyKnownEntry(old, replacement, "retained-known"))
            replacement.openSnapshot("retained-known")!!.use {
                assertEquals("retained bytes", it.data.toFile().readText())
                assertEquals("synthetic metadata", it.metadata.toFile().readText())
            }
            old.clear()
            KrillCacheMigration.verifyCopies(replacement, scan.copies)
            assertNull(old.openSnapshot("deleted-unknown"))
            assertNull(replacement.openSnapshot("deleted-unknown"))
            assertNull(replacement.openSnapshot("retained-unknown"))
            replacement.openSnapshot("retained-known")!!.use { assertEquals("retained bytes", it.data.toFile().readText()) }
            requireNotNull(replacement.openEditor("retained-known")).apply {
                data.toFile().writeText("corrupted")
                metadata.toFile().writeText("synthetic metadata")
                commit()
            }
            assertThrows(IllegalStateException::class.java) { KrillCacheMigration.verifyCopies(replacement, scan.copies) }
        } finally {
            old.shutdown()
            replacement.shutdown()
        }
    }
}
