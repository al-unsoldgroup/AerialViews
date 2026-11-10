package com.neilturner.aerialviews.providers.custom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class KrillDetachedCacheCleanupTest {
    @TempDir lateinit var directory: File

    @Test
    fun `cache activation permits only the recorded source or an idempotent destination`() {
        val source = directory.resolve("frame_images").apply { mkdirs() }
        val destination = directory.resolve("frame_images_v2").apply { mkdirs() }
        assertEquals("frame_images_v2", reconciledCacheName(directory, source.path, destination.path, source.path))
        assertEquals("frame_images_v2", reconciledCacheName(directory, source.path, destination.path, destination.path))
        assertNull(reconciledCacheName(directory, source.path, source.path, source.path))
        assertNull(reconciledCacheName(directory, source.path, destination.path, directory.resolve("other").path))
        assertNull(reconciledCacheName(directory, source.path, directory.resolve("other").path, source.path))
    }

    @Test
    fun `full cleanup deletes detached cache and leaves active directory usable`() {
        val active = directory.resolve("frame_images").apply { mkdirs() }
        val detached = directory.resolve("frame_images_v2").apply { mkdirs() }
        detached.resolve("synthetic").writeText("private")
        purgeDetachedFrameCaches(directory, active.path, detached.path, active.path)
        assertTrue(active.isDirectory)
        assertFalse(detached.exists())
        purgeDetachedFrameCaches(directory, active.path, detached.path, active.path)
    }

    @Test
    fun `cleanup refuses directories outside approved cache paths`() {
        val active = directory.resolve("frame_images").apply { mkdirs() }
        val outside = directory.resolve("unrelated").apply { mkdirs() }
        val protected = outside.resolve("keep").apply { writeText("synthetic") }
        assertThrows(IOException::class.java) { purgeDetachedFrameCaches(directory, active.path, outside.path, active.path) }
        assertTrue(protected.exists())
    }

    @Test
    fun `cleanup refuses a cache directory symlink to another location`() {
        val active = directory.resolve("frame_images").apply { mkdirs() }
        val outside = directory.resolve("unrelated").apply { mkdirs() }
        val protected = outside.resolve("keep").apply { writeText("synthetic") }
        java.nio.file.Files
            .createSymbolicLink(directory.resolve("frame_images_v2").toPath(), outside.toPath())
        assertThrows(IOException::class.java) {
            purgeDetachedFrameCaches(directory, active.path, directory.resolve("frame_images_v2").path, active.path)
        }
        assertTrue(protected.exists())
    }
}
