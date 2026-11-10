package com.neilturner.aerialviews.models

import android.net.Uri
import com.neilturner.aerialviews.models.enums.AerialMediaSource
import com.neilturner.aerialviews.models.videos.AerialMedia
import com.neilturner.aerialviews.models.videos.AerialMediaMetadata
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal class MediaPlaylistTest {
    @Test
    fun `appending invalidates a refill started before the source grew`() =
        runTest {
            val photos = MutableList(60) { testMedia() }
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val calls = AtomicInteger()
            val result = AtomicReference<AerialMedia?>()
            val playlist =
                MediaPlaylist(photos.take(50), size = 60, fetchChunk = { offset, limit ->
                    if (calls.incrementAndGet() == 1) {
                        val old = photos.drop(offset).take(limit)
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        old
                    } else {
                        photos.drop(offset).take(limit)
                    }
                }, countSource = { photos.size })
            val worker = Thread { result.set(playlist.itemAt(55)) }
            worker.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            photos.add(testMedia())
            playlist.refreshAppendedSourceSize()
            release.countDown()
            worker.join(5000)
            assertFalse(worker.isAlive)
            assertSame(photos[55], result.get())
            assertEquals(61, playlist.size)
            assertSame(photos[60], playlist.itemAt(60))
        }

    @Test
    fun `appended cached items become reachable without moving the current cursor`() =
        runTest {
            val photos = MutableList(3) { testMedia() }
            val playlist =
                MediaPlaylist(
                    photos.toList(),
                    startPosition = 1,
                    size = 3,
                    fetchChunk = { offset, limit -> photos.drop(offset).take(limit) },
                    countSource = { photos.size },
                )
            val added = testMedia()
            photos.add(added)
            playlist.refreshAppendedSourceSize()
            assertEquals(4, playlist.size)
            assertEquals(1, playlist.currentPosition)
            assertSame(photos[2], playlist.nextItem())
            assertSame(added, playlist.nextItem())
        }

    @Test
    fun `in memory append deduplicates and keeps the current cursor`() {
        fun krill(id: String): AerialMedia {
            val uri = mockk<Uri>()
            every { uri.toString() } returns id
            return AerialMedia(uri, source = AerialMediaSource.KRILL)
        }
        val current = krill("current")
        val added = krill("added")
        val playlist = MediaPlaylist(listOf(current), startPosition = 0)
        playlist.appendUncachedKrillMedia(listOf(current, added, added))
        assertEquals(2, playlist.size)
        assertEquals(0, playlist.currentPosition)
        assertSame(added, playlist.nextItem())
    }

    @Test
    fun `a failed chunk read preserves playlist size and can be retried`() {
        val photos = List(60) { testMedia() }
        var calls = 0
        val playlist =
            MediaPlaylist(photos.take(50), startPosition = 2, size = photos.size, fetchChunk = { offset, limit ->
                if (calls++ == 0) throw java.io.IOException("temporary database failure")
                photos.drop(offset).take(limit)
            })
        assertThrows(MediaPlaylistFetchException::class.java) { playlist.itemAt(55) }
        assertEquals(60, playlist.size)
        assertEquals(2, playlist.currentPosition)
        assertSame(photos[55], playlist.itemAt(55))
    }

    @Test
    fun `snapshot projection reads all chunks without moving the playback cursor`() =
        runTest {
            val photos = List(120) { testMedia() }
            val playlist =
                MediaPlaylist(photos.take(50), startPosition = 10, size = photos.size, fetchChunk = { offset, limit ->
                    photos.drop(offset).take(limit)
                })
            assertEquals(120, playlist.snapshotProjection { it }.size)
            assertEquals(10, playlist.currentPosition)
            assertSame(photos[11], playlist.nextItem())
        }

    @Test
    fun `repeated window contention keeps the requested position for retry`() {
        val photos = List(60) { testMedia() }
        val entered = List(2) { CountDownLatch(1) }
        val release = List(2) { CountDownLatch(1) }
        val calls = AtomicInteger()
        val error = AtomicReference<Throwable?>()
        val playlist =
            MediaPlaylist(photos.take(50), startPosition = 54, size = 60, fetchChunk = { offset, limit ->
                val attempt = calls.getAndIncrement()
                if (attempt < 2) {
                    entered[attempt].countDown()
                    check(release[attempt].await(5, TimeUnit.SECONDS))
                }
                photos.drop(offset).take(limit)
            })
        val worker =
            Thread {
                try {
                    playlist.nextItem()
                } catch (exception: Throwable) {
                    error.set(exception)
                }
            }
        worker.start()
        repeat(2) { attempt ->
            assertTrue(entered[attempt].await(5, TimeUnit.SECONDS))
            playlist.invalidateRefillsAndNeedsCachedMetadata { true }
            release[attempt].countDown()
        }
        worker.join(5000)
        assertFalse(worker.isAlive)
        assertTrue(error.get() is MediaPlaylistContentionException)
        assertEquals(54, playlist.currentPosition)
        assertSame(photos[55], playlist.nextItem())
        assertEquals(55, playlist.currentPosition)
    }

    @Test
    fun `metadata commit invalidates a stale synchronous window and retries`() {
        val stale = List(60) { testMedia() }
        val fresh = List(60) { testMedia() }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val result = AtomicReference<AerialMedia?>()
        val playlist =
            MediaPlaylist(stale.take(50), size = 60, fetchChunk = { offset, limit ->
                if (calls.incrementAndGet() == 1) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    stale.drop(offset).take(limit)
                } else {
                    fresh.drop(offset).take(limit)
                }
            })
        val worker = Thread { result.set(playlist.itemAt(55)) }
        worker.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        playlist.invalidateRefillsAndNeedsCachedMetadata { true }
        release.countDown()
        worker.join(5000)
        assertFalse(worker.isAlive)
        assertEquals(2, calls.get())
        assertSame(fresh[55], result.get())
    }

    @Test
    fun `metadata refresh updates matching Krill items in the active window without moving position`() {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "https://example/photo"
        val item = AerialMedia(uri, source = AerialMediaSource.KRILL, metadata = AerialMediaMetadata(width = 1100))
        val update =
            AerialMedia(
                uri,
                source = AerialMediaSource.KRILL,
                metadata = AerialMediaMetadata(width = 1200, height = 1800, tags = listOf("trip")),
            )
        val playlist = MediaPlaylist(listOf(item), startPosition = 0)
        assertTrue(playlist.invalidateRefillsAndNeedsCachedMetadata { true })
        assertFalse(playlist.invalidateRefillsAndNeedsCachedMetadata { false })
        playlist.updateCachedMetadata(listOf(update))
        update.metadata.shortDescription = "Painting • Artist"
        playlist.updateCachedCaptions(listOf(update))
        assertEquals("Painting • Artist", item.metadata.shortDescription)
        assertEquals(1100, item.metadata.width)
        assertEquals(1800, item.metadata.height)
        assertEquals(listOf("trip"), item.metadata.tags)
        assertFalse(playlist.invalidateRefillsAndNeedsCachedMetadata { true })
        assertEquals(0, playlist.currentPosition)
    }

    @Test
    fun `debug seek starts at the following playlist entry`() {
        val first = testMedia()
        val second = testMedia()
        val playlist = MediaPlaylist(listOf(first, second))
        playlist.debugSeekTo(0)
        assertSame(second, playlist.nextItem())
    }

    @Test
    fun `inspect an absolute position without changing playback or wrapping invalid indices`() {
        val first = testMedia()
        val second = testMedia()
        val playlist = MediaPlaylist(listOf(first, second), startPosition = 1)
        assertSame(first, playlist.itemAt(0))
        assertNull(playlist.itemAt(-1))
        assertNull(playlist.itemAt(2))
        assertEquals(1, playlist.currentPosition)
    }

    @Test
    fun `peek skips excluded items without changing playback position`() {
        val first = testMedia()
        val excluded = testMedia()
        val next = testMedia()
        val playlist = MediaPlaylist(listOf(first, excluded, next), startPosition = 0)

        assertSame(next, playlist.peekNextItem { it !== excluded })
        assertEquals(0, playlist.currentPosition)
        assertSame(excluded, playlist.nextItem())
    }

    @Test
    fun `nextItem starts after saved current position`() {
        val first = testMedia()
        val second = testMedia()
        val third = testMedia()
        val playlist = MediaPlaylist(listOf(first, second, third), startPosition = 0)

        assertSame(second, playlist.nextItem())
    }

    @Test
    fun `nextItem starts at first item when no position has been saved`() {
        val first = testMedia()
        val second = testMedia()
        val playlist = MediaPlaylist(listOf(first, second), startPosition = -1)

        assertSame(first, playlist.nextItem())
    }

    @Test
    fun `nextItem loops back to first item when reaching end of playlist with chunk windowing`() {
        val totalItems = 60
        val allMedia = List(totalItems) { testMedia() }

        // Initial window of size 50 (items 0..49)
        val initialChunk = allMedia.subList(0, 50)

        val playlist =
            MediaPlaylist(
                initialVideos = initialChunk,
                startPosition = -1,
                size = totalItems,
                windowOffset = 0,
                fetchChunk = { offset, limit ->
                    val end = (offset + limit).coerceAtMost(totalItems)
                    if (offset < totalItems) allMedia.subList(offset, end) else emptyList()
                },
            )

        // Iterate through all 60 items
        for (i in 0 until totalItems) {
            val item = playlist.nextItem()
            assertSame(allMedia[i], item, "Expected item at index $i")
        }

        // 61st item (wrap around to index 0)
        val loopedItem = playlist.nextItem()
        assertSame(allMedia[0], loopedItem, "Expected playlist to loop back to item 0")
    }

    @Test
    fun `previousItem loops back to end of playlist when at position 0 with chunk windowing`() {
        val totalItems = 60
        val allMedia = List(totalItems) { testMedia() }

        val playlist =
            MediaPlaylist(
                initialVideos = allMedia.subList(0, 50),
                startPosition = 0,
                size = totalItems,
                windowOffset = 0,
                fetchChunk = { offset, limit ->
                    val end = (offset + limit).coerceAtMost(totalItems)
                    if (offset < totalItems) allMedia.subList(offset, end) else emptyList()
                },
            )

        val prevItem = playlist.previousItem()
        assertSame(allMedia[59], prevItem, "Expected previousItem from 0 to loop to item 59")
    }

    private fun testMedia() = AerialMedia(uri = mockk<Uri>(relaxed = true))

    /**
     * Regression: `size` stated by the playlist is larger than the number of rows the chunk source
     * can serve. This is what happened when WiFi-only filtering was applied as a SQL view over the
     * playlist cache, so the cached size counted remote items the filtered query could not return.
     * Iterating used to replay the tail of the filtered window and then throw
     * IllegalStateException("Playlist is empty").
     */
    @Test
    fun `playlist shrinks instead of crashing when size exceeds what the chunk source can serve`() {
        val declaredSize = 100
        val servable = 40
        val allMedia = List(declaredSize) { testMedia() }
        val servableMedia = allMedia.subList(0, servable)

        val playlist =
            MediaPlaylist(
                initialVideos = servableMedia,
                startPosition = -1,
                size = declaredSize,
                windowOffset = 0,
                fetchChunk = { offset, limit ->
                    val end = (offset + limit).coerceAtMost(servable)
                    if (offset < servable) servableMedia.subList(offset, end) else emptyList()
                },
            )

        // Must cycle the servable items in order, three times over, with no repeats and no
        // exception. Previously the tail of the filtered window was replayed and then
        // IllegalStateException("Playlist is empty") was thrown.
        val expected = List(3) { servableMedia }.flatten()
        val actual = (0 until expected.size).map { playlist.nextItem() }

        assertEquals(expected, actual)
        assertEquals(servable, playlist.size, "Playlist should have discovered its real length")
    }

    @Test
    fun `previousItem shrinks instead of crashing when size exceeds what the chunk source can serve`() {
        val declaredSize = 100
        val servable = 40
        val allMedia = List(declaredSize) { testMedia() }
        val servableMedia = allMedia.subList(0, servable)

        val playlist =
            MediaPlaylist(
                initialVideos = servableMedia,
                startPosition = 0,
                size = declaredSize,
                windowOffset = 0,
                fetchChunk = { offset, limit ->
                    val end = (offset + limit).coerceAtMost(servable)
                    if (offset < servable) servableMedia.subList(offset, end) else emptyList()
                },
            )

        // The first backwards step wraps using the declared size, which is only disproved once the
        // short chunk comes back, so it lands wherever the clamp puts it. It must still be a
        // servable item rather than an exception or a replay.
        val first = playlist.previousItem()
        assertTrue(first in servableMedia, "Expected a servable item, got a replay or a crash")

        // From there the playlist walks a complete backwards cycle over the real length: every
        // servable item exactly once, no repeats, no exception.
        val collected = listOf(first) + (0 until servable - 1).map { playlist.previousItem() }

        assertEquals(servable, playlist.size, "Playlist should have discovered its real length")
        assertEquals(servable, collected.distinct().size, "Expected no repeated items in a full backwards cycle")
        collected.forEach { assertTrue(it in servableMedia, "Expected only servable items") }
    }

    @Test
    fun `nextItem returns null instead of throwing when the chunk source is empty`() {
        val playlist =
            MediaPlaylist(
                initialVideos = emptyList(),
                startPosition = -1,
                size = 25,
                windowOffset = 0,
                fetchChunk = { _, _ -> emptyList() },
            )

        assertNull(playlist.nextItem())
        assertNull(playlist.previousItem())
        assertEquals(0, playlist.size)
    }

    @Test
    fun `nextItem returns null on an empty in-memory playlist`() {
        val playlist = MediaPlaylist(emptyList())

        assertEquals(0, playlist.size)
        assertNull(playlist.nextItem())
        assertNull(playlist.previousItem())
    }
}
