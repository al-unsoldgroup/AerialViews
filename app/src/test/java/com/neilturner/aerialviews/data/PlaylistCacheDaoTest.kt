package com.neilturner.aerialviews.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.neilturner.aerialviews.models.TimeOfDayFilter
import com.neilturner.aerialviews.models.enums.AerialMediaSource
import com.neilturner.aerialviews.models.enums.AerialMediaType
import com.neilturner.aerialviews.models.enums.SceneType
import com.neilturner.aerialviews.models.enums.TimeOfDay
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Runs the real cache queries against an in-memory database using the bundled SQLite driver, so
 * the time-of-day predicate and the ordering of LIMIT/OFFSET are verified by SQLite itself rather
 * than by a fake.
 */
internal class PlaylistCacheDaoTest {
    private lateinit var db: AerialDatabase
    private lateinit var dao: PlaylistCacheDao

    private val dayAllowed = TimeOfDayFilter.forPeriod(TimeOfDay.DAY, emptySet(), emptySet()).allowedNames()
    private val dayWithSunrise = TimeOfDayFilter.forPeriod(TimeOfDay.DAY, setOf("SUNRISE"), emptySet()).allowedNames()
    private val nightAllowed = TimeOfDayFilter.forPeriod(TimeOfDay.NIGHT, emptySet(), emptySet()).allowedNames()

    @Test
    fun `appending krill media preserves order and rejects stale cache identity`() =
        runTest {
            val first = photo(0).copy(source = "KRILL", uri = "https://example.test/first")
            val other = photo(1)
            val fresh = photo(0).copy(source = "KRILL", uri = "https://example.test/new")
            val saved = state(2).copy(mediaPosition = 1)
            dao.updateCache(listOf(first, other), emptyList(), saved)
            assertNull(dao.appendKrillItems(saved.cachedAt, "wrong", listOf(fresh)))
            assertNull(dao.appendKrillItems(saved.cachedAt + 1, saved.settingsHash, listOf(fresh)))
            assertEquals(1, dao.appendKrillItems(saved.cachedAt, saved.settingsHash, listOf(first, fresh, fresh)))
            assertEquals(listOf(first.uri, other.uri, fresh.uri), dao.getAllMediaItemsOrdered().map { it.uri })
            assertEquals(3, dao.getPlaylistState()?.totalMediaItems)
            assertEquals(1, dao.getPlaylistState()?.mediaPosition)
            assertEquals(0, dao.appendKrillItems(saved.cachedAt, saved.settingsHash, listOf(fresh)))
            assertEquals(3, dao.countCurrentMedia(saved.cachedAt, saved.settingsHash, dayAllowed))
            assertNull(dao.countCurrentMedia(saved.cachedAt, "wrong", dayAllowed))
        }

    @Test
    fun `upgrades an existing cached rotation to the new collage cadence`() =
        runTest {
            val context = mockk<Context>()
            val preferences = mockk<SharedPreferences>(relaxed = true)
            every { context.applicationContext } returns context
            every { context.getSharedPreferences(any(), any()) } returns preferences
            val repository = PlaylistCacheRepository(context, dao, { PlaylistCachePrefs(true, "-1", false) })
            val rows =
                (0 until 6).flatMap { i ->
                    listOf("a$i" to "b$i", "b$i" to "a$i").mapIndexed { j, (id, partner) ->
                        photo(2 * i + j).copy(
                            source = "KRILL",
                            uri = "https://photos.example/api/frame/media/$id/image?space=mine",
                            width = 1200,
                            height = 1800,
                            tagsJson = "[]",
                            pairPartner = partner,
                            pairedWithNext = j == 0,
                        )
                    }
                }
            dao.updateCache(rows, emptyList(), state(rows.size).copy(mediaPosition = -1))
            assertTrue(repository.preparePortraitPlan("https://photos.example", "mine", "session", TimeOfDayFilter.ALL, 960, 1080))
            assertEquals(listOf(0, 6), dao.getAllMediaItemsOrdered().filter { it.pairedWithNext == true }.map { it.playlistOrder })
        }

    @BeforeEach
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder<AerialDatabase>()
                .setDriver(BundledSQLiteDriver())
                .build()
        dao = db.playlistCacheDao()
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    @Test
    fun `portrait plan keeps filtered resume position and all unpaired items`() =
        runTest {
            val context = mockk<Context>()
            val preferences = mockk<SharedPreferences>(relaxed = true)
            every { context.applicationContext } returns context
            every { context.getSharedPreferences(any(), any()) } returns preferences
            val repository = PlaylistCacheRepository(context, dao, { PlaylistCachePrefs(true, "-1", false) })

            fun privatePhoto(
                order: Int,
                id: String,
            ) = photo(order).copy(
                source = "KRILL",
                uri = "https://photos.example/api/frame/media/$id/image?space=mine",
                width = 1200,
                height = 1800,
                tagsJson = "[\"trip\"]",
            )
            val rows =
                listOf(
                    privatePhoto(0, "seen").copy(pairedWithNext = true),
                    video(1, TimeOfDay.NIGHT),
                    photo(2),
                    privatePhoto(3, "a").copy(pairPartner = "b"),
                    photo(4).copy(pairedWithNext = true),
                    privatePhoto(5, "b").copy(pairPartner = "a"),
                )
            dao.updateCache(rows, emptyList(), state(7).copy(mediaPosition = 1))
            val dayFilter = TimeOfDayFilter.forPeriod(TimeOfDay.DAY, emptySet(), emptySet())
            // A day video ensures the filter stays enabled while the night video is excluded.
            dao.insertMediaItems(listOf(video(6, TimeOfDay.DAY)))
            assertTrue(repository.preparePortraitPlan("https://photos.example", "mine", "session", dayFilter, 960, 1080))
            val result = dao.getAllMediaItemsOrdered()
            assertEquals(listOf(0, 1, 2), result.take(3).map { it.playlistOrder })
            assertEquals(true, result[0].pairedWithNext)
            assertEquals(listOf("a", "b"), result.subList(3, 5).map { it.uri.substringAfter("media/").substringBefore("/") })
            assertEquals(true, result[3].pairedWithNext)
            assertEquals(true, result[5].pairedWithNext)
            assertEquals(1, dao.getPlaylistState()?.mediaPosition)
            assertEquals(7, result.size)
        }

    @Test
    fun `portrait planning protects both halves when saved position is a pair start`() =
        runTest {
            val context = mockk<Context>()
            every { context.applicationContext } returns context
            every { context.getSharedPreferences(any(), any()) } returns mockk<SharedPreferences>(relaxed = true)
            val repository = PlaylistCacheRepository(context, dao, { PlaylistCachePrefs(true, "-1", false) })

            fun privatePhoto(
                order: Int,
                id: String,
            ) = photo(order).copy(
                source = "KRILL",
                uri = "https://photos.example/api/frame/media/$id/image?space=mine",
                width = 1200,
                height = 1800,
                tagsJson = "[\"trip\"]",
            )
            val rows =
                listOf(
                    privatePhoto(0, "current").copy(pairedWithNext = true, pairPartner = "current-partner"),
                    privatePhoto(1, "current-partner").copy(pairPartner = "current"),
                    privatePhoto(2, "future").copy(pairPartner = "future-partner"),
                    photo(3),
                    privatePhoto(4, "future-partner").copy(pairPartner = "future"),
                )
            dao.updateCache(rows, emptyList(), state(5).copy(mediaPosition = 0))
            assertTrue(repository.preparePortraitPlan("https://photos.example", "mine", "session", TimeOfDayFilter.ALL, 960, 1080))
            val result = dao.getAllMediaItemsOrdered()
            assertEquals(listOf("current", "current-partner"), result.take(2).map { it.uri.substringAfter("media/").substringBefore("/") })
            assertEquals(true, result[0].pairedWithNext)
            assertEquals(
                listOf("future", "future-partner"),
                result.subList(2, 4).map { it.uri.substringAfter("media/").substringBefore("/") },
            )
            assertEquals(0, dao.getPlaylistState()?.mediaPosition)
        }

    @Test
    fun `portrait planning keeps all three members of the current collage in place`() =
        runTest {
            val context = mockk<Context>()
            every { context.applicationContext } returns context
            every { context.getSharedPreferences(any(), any()) } returns mockk<SharedPreferences>(relaxed = true)
            val repository = PlaylistCacheRepository(context, dao, { PlaylistCachePrefs(true, "-1", false) })

            fun privatePhoto(
                order: Int,
                id: String,
            ) = photo(order).copy(
                source = "KRILL",
                uri = "https://photos.example/api/frame/media/$id/image?space=mine",
                width = 1600,
                height = 1800,
                tagsJson = "[]",
            )
            val rows =
                listOf(
                    privatePhoto(0, "a").copy(pairedWithNext = true, pairPartner = "b", pairThird = "c"),
                    privatePhoto(1, "b").copy(pairPartner = "a", pairThird = "c"),
                    privatePhoto(2, "c").copy(pairPartner = "d"),
                    privatePhoto(3, "d").copy(pairPartner = "c"),
                    privatePhoto(4, "e"),
                )
            dao.updateCache(rows, emptyList(), state(rows.size).copy(mediaPosition = 0))
            assertTrue(repository.preparePortraitPlan("https://photos.example", "mine", "session", TimeOfDayFilter.ALL, 960, 1080))
            assertEquals(
                listOf("a", "b", "c"),
                dao.getAllMediaItemsOrdered().take(3).map { it.uri.substringAfter("media/").substringBefore("/") },
            )
        }

    @Test
    fun `pair refresh upgrades old cached rows without reordering or overwriting a replacement cache`() =
        runTest {
            val old = photo(0).copy(source = "KRILL", pairPartner = "b", pairedWithNext = true)
            val state = state(1)
            dao.updateCache(listOf(old), emptyList(), state)
            assertEquals(1, dao.updateKrillPairs(state.cachedAt, listOf(KrillPairUpdate(old.uri, "b", "c"))))
            assertEquals("c", dao.getAllMediaItemsOrdered().single().pairThird)
            assertEquals(true, dao.getAllMediaItemsOrdered().single().pairedWithNext)
            assertEquals(0, dao.updateKrillPairs(state.cachedAt, listOf(KrillPairUpdate(old.uri, "b", "c"))))
            dao.clearState()
            dao.insertOrUpdateState(state.copy(cachedAt = state.cachedAt + 1))
            assertNull(dao.updateKrillPairs(state.cachedAt, listOf(KrillPairUpdate(old.uri, null, null))))
            assertEquals("c", dao.getAllMediaItemsOrdered().single().pairThird)
        }

    @Test
    fun `pair refresh adds and removes the art tag on an already cached row`() =
        runTest {
            val old = photo(0).copy(source = "KRILL", tagsJson = "[\"trip\"]")
            val state = state(1)
            dao.updateCache(listOf(old), emptyList(), state)
            val marked = KrillPairUpdate(old.uri, null, null, tagsJson = "[\"trip\",\"art\"]")
            assertEquals(1, dao.updateKrillPairs(state.cachedAt, listOf(marked)))
            assertEquals("[\"trip\",\"art\"]", dao.getAllMediaItemsOrdered().single().tagsJson)
            assertEquals(0, dao.updateKrillPairs(state.cachedAt, listOf(marked)))
            assertEquals(0, dao.updateKrillPairs(state.cachedAt, listOf(KrillPairUpdate(old.uri, null, null))))
            assertEquals("[\"trip\",\"art\"]", dao.getAllMediaItemsOrdered().single().tagsJson)
            assertEquals(1, dao.updateKrillPairs(state.cachedAt, listOf(marked.copy(tagsJson = "[\"trip\"]"))))
            assertEquals("[\"trip\"]", dao.getAllMediaItemsOrdered().single().tagsJson)
        }

    @Test
    fun `caption refresh preserves rotation and ignores a replaced cache`() =
        runTest {
            val image = photo(0).copy(source = "KRILL", shortDescription = "Old")
            val local = photo(1).copy(uri = image.uri, shortDescription = "Local")
            val initialState = state(2)
            dao.updateCache(listOf(image, local), emptyList(), initialState)
            assertTrue(dao.updateKrillCaptions(initialState.cachedAt, listOf(KrillCaptionUpdate(image.uri, "Painting • Artist"))))
            val result = dao.getAllMediaItemsOrdered()
            assertEquals(listOf("Painting • Artist", "Local"), result.map { it.shortDescription })
            assertEquals(listOf(0, 1), result.map { it.playlistOrder })
            dao.clearState()
            dao.insertOrUpdateState(initialState.copy(cachedAt = initialState.cachedAt + 1))
            assertFalse(dao.updateKrillCaptions(initialState.cachedAt, listOf(KrillCaptionUpdate(image.uri, "Stale"))))
            assertEquals("Painting • Artist", dao.getAllMediaItemsOrdered().first().shortDescription)
        }

    @Test
    fun `portrait plan preserves unrelated metadata and rejects changed planning inputs`() =
        runTest {
            dao.updateCache(listOf(photo(0), photo(1)), emptyList(), state(2))
            val expectedState = dao.getPlaylistState()!!
            val snapshot = dao.getPortraitPlanRows()
            val planned = listOf(snapshot[1].copy(playlistOrder = 0), snapshot[0].copy(playlistOrder = 1, pairedWithNext = true))
            val first = dao.getAllMediaItemsOrdered().first()
            dao.updateMediaItems(listOf(first.copy(shortDescription = "Updated description")))
            assertTrue(dao.applyPortraitPlanRows(expectedState, snapshot, planned))
            assertEquals("Updated description", dao.getAllMediaItemsOrdered().last().shortDescription)

            val nextSnapshot = dao.getPortraitPlanRows()
            val changed = dao.getAllMediaItemsOrdered().first()
            dao.updateMediaItems(listOf(changed.copy(width = 1200)))
            assertFalse(dao.applyPortraitPlanRows(expectedState, nextSnapshot, nextSnapshot))
        }

    @Test
    fun `unknown source dimensions remain eligible for later planning`() =
        runTest {
            val context = mockk<Context>()
            val preferences = mockk<SharedPreferences>(relaxed = true)
            every { context.applicationContext } returns context
            every { context.getSharedPreferences(any(), any()) } returns preferences
            every { preferences.getBoolean(match { it.startsWith("cadence-v2:") }, false) } returnsMany listOf(false, true, true)
            every { preferences.contains(any()) } returns true
            every { preferences.getLong(any(), any()) } answers { System.currentTimeMillis() - 1000 }
            every { preferences.getLong(match { it.startsWith("timeout:") }, any()) } returns 0L
            val repository = PlaylistCacheRepository(context, dao, { PlaylistCachePrefs(true, "-1", false) })
            val unknown =
                photo(0).copy(
                    source = "KRILL",
                    uri = "https://photos.example/api/frame/media/a/image?space=mine",
                    width = null,
                    height = null,
                    tagsJson = "[\"trip\"]",
                    pairPartner = "b",
                )
            val partner =
                unknown.copy(
                    playlistOrder = 1,
                    uri = "https://photos.example/api/frame/media/b/image?space=mine",
                    width = 1200,
                    height = 1800,
                    pairPartner = "a",
                )
            dao.updateCache(listOf(unknown, partner), emptyList(), state(11))
            assertTrue(repository.preparePortraitPlan("https://photos.example", "mine", "session", TimeOfDayFilter.ALL, 960, 1080))
            val initial = dao.getAllMediaItemsOrdered()
            assertEquals(false, initial[0].pairedWithNext)
            dao.updateMissingKrillDimensions(initial[0].uri, 1200, 1800)
            assertEquals(null, dao.getAllMediaItemsOrdered()[0].pairedWithNext)
            assertFalse(repository.preparePortraitPlan("https://photos.example", "mine", "session", TimeOfDayFilter.ALL, 960, 1080))
            val extra =
                List(
                    9,
                ) { index ->
                    partner.copy(
                        id = 0,
                        playlistOrder = index + 2,
                        uri = "https://photos.example/api/frame/media/x$index/image?space=mine",
                        pairPartner = null,
                    )
                }
            dao.insertMediaItems(extra)
            assertTrue(repository.preparePortraitPlan("https://photos.example", "mine", "session", TimeOfDayFilter.ALL, 960, 1080))
            assertEquals(true, dao.getAllMediaItemsOrdered()[0].pairedWithNext)
        }

    @Test
    fun `stale plan cannot overwrite a changed playback position`() =
        runTest {
            dao.updateCache(listOf(photo(0), photo(1)), emptyList(), state(2))
            val snapshotState = dao.getPlaylistState()!!
            val snapshot = dao.getAllMediaItemsOrdered()
            dao.updateMediaPosition(0)
            assertFalse(
                dao.applyPortraitPlan(
                    snapshotState,
                    snapshot,
                    snapshot.reversed().mapIndexed {
                        i,
                        row,
                        ->
                        row.copy(playlistOrder = i)
                    },
                ),
            )
            assertEquals(snapshot, dao.getAllMediaItemsOrdered())
        }

    @Test
    fun `metadata refresh updates only missing matching Krill rows without reordering`() =
        runTest {
            val first = photo(0).copy(source = "KRILL", uri = "https://example/one", width = 1200)
            val second = photo(1).copy(source = "KRILL", uri = "https://example/two", tagsJson = "[\"old\"]")
            val local = photo(2).copy(uri = "https://example/one")
            dao.insertMediaItems(listOf(first, second, local))
            assertEquals(listOf(first.uri), dao.krillUrisMissingTags())
            assertEquals(listOf(second.uri), dao.unplannedKrillUrisAfter(0))
            dao.updateKrillMetadataBatch(
                listOf(
                    KrillMetadataUpdate(first.uri, null, 1800, "[\"trip\"]"),
                    KrillMetadataUpdate(second.uri, 500, 500, "[\"new\"]"),
                ),
            )
            val result = dao.getAllMediaItemsOrdered()
            assertEquals(listOf(0, 1, 2), result.map { it.playlistOrder })
            assertEquals(1200, result[0].width)
            assertEquals(1800, result[0].height)
            assertEquals("[\"trip\"]", result[0].tagsJson)
            assertEquals("[\"old\"]", result[1].tagsJson)
            assertEquals(null, result[2].tagsJson)
            assertEquals(emptyList<String>(), dao.krillUrisMissingTags())
        }

    @Test
    fun `counts match the time-of-day predicate and always include photos`() =
        runTest {
            dao.insertMediaItems(
                listOf(
                    video(0, TimeOfDay.DAY),
                    video(1, TimeOfDay.NIGHT),
                    video(2, TimeOfDay.SUNRISE),
                    photo(3),
                ),
            )

            assertEquals(2, dao.countMediaItems(dayAllowed))
            assertEquals(1, dao.countVideoItems(dayAllowed))
            assertEquals(3, dao.countMediaItems(dayWithSunrise))
            assertEquals(2, dao.countVideoItems(dayWithSunrise))
            assertEquals(2, dao.countMediaItems(nightAllowed))
            assertEquals(1, dao.countVideoItems(nightAllowed))
        }

    @Test
    fun `chunk offsets are applied after filtering`() =
        runTest {
            dao.insertMediaItems(
                listOf(
                    video(0, TimeOfDay.DAY),
                    video(1, TimeOfDay.NIGHT),
                    video(2, TimeOfDay.DAY),
                    video(3, TimeOfDay.NIGHT),
                    video(4, TimeOfDay.DAY),
                ),
            )

            val window = dao.getMediaItemsChunkFiltered(dayAllowed, limit = 2, offset = 1)

            assertEquals(listOf(2, 4), window.map { it.playlistOrder })
        }

    @Test
    fun `filtered chunks stay inside the filtered count`() =
        runTest {
            dao.insertMediaItems(List(10) { index -> video(index, TimeOfDay.DAY) })

            val first = dao.getMediaItemsChunkFiltered(dayAllowed, limit = 4, offset = 0)
            val last = dao.getMediaItemsChunkFiltered(dayAllowed, limit = 4, offset = 8)

            assertEquals(listOf(0, 1, 2, 3), first.map { it.playlistOrder })
            assertEquals(listOf(8, 9), last.map { it.playlistOrder })
        }

    @Test
    fun `updateCache replaces the previous contents transactionally`() =
        runTest {
            dao.updateCache(
                media = listOf(video(0, TimeOfDay.DAY)),
                music = emptyList(),
                state = state(totalMediaItems = 1),
            )

            dao.updateCache(
                media = listOf(video(0, TimeOfDay.NIGHT), video(1, TimeOfDay.NIGHT)),
                music = emptyList(),
                state = state(totalMediaItems = 2),
            )

            assertEquals(2, dao.getAllMediaItemsOrdered().size)
            assertEquals(2, dao.getPlaylistState()?.totalMediaItems)
        }

    @Test
    fun `saving a position updates the cached state`() =
        runTest {
            dao.insertOrUpdateState(state(totalMediaItems = 3))

            dao.updateMediaPosition(2)
            dao.updateMusicTrackIndex(1)

            assertEquals(2, dao.getPlaylistState()?.mediaPosition)
            assertEquals(1, dao.getPlaylistState()?.musicTrackIndex)
        }

    @Test
    fun `clearing the cache removes media music and state`() =
        runTest {
            dao.updateCache(
                media = listOf(video(0, TimeOfDay.DAY)),
                music = listOf(CachedMusicTrackEntity(playlistOrder = 0, uri = "file:///music.mp3", source = AerialMediaSource.LOCAL.name)),
                state = state(totalMediaItems = 1),
            )

            dao.clearAll()

            assertEquals(0, dao.getAllMediaItemsOrdered().size)
            assertEquals(0, dao.getAllMusicTracksOrdered().size)
            assertEquals(null, dao.getPlaylistState())
        }

    private fun state(totalMediaItems: Int) =
        PlaylistStateEntity(
            mediaPosition = -1,
            musicTrackIndex = 0,
            cachedAt = System.currentTimeMillis(),
            settingsHash = "test-hash",
            totalMediaItems = totalMediaItems,
            totalMusicTracks = 0,
            shuffleEnabled = false,
            musicShuffleEnabled = false,
            musicRepeatEnabled = false,
        )

    private fun video(
        order: Int,
        timeOfDay: TimeOfDay,
    ) = cachedMedia(order, AerialMediaType.VIDEO, timeOfDay)

    private fun photo(order: Int) = cachedMedia(order, AerialMediaType.IMAGE, TimeOfDay.UNKNOWN)

    private fun cachedMedia(
        order: Int,
        type: AerialMediaType,
        timeOfDay: TimeOfDay,
    ) = CachedMediaEntity(
        playlistOrder = order,
        uri = "file:///test/${type.name.lowercase()}-$order.mp4",
        type = type.name,
        source = AerialMediaSource.LOCAL.name,
        shortDescription = "",
        pointsOfInterest = emptyMap(),
        timeOfDay = timeOfDay.name,
        scene = SceneType.UNKNOWN.name,
        albumName = "",
        title = "${type.name.lowercase()}-$order",
        exifDate = null,
        exifOffset = null,
        exifLatitude = null,
        exifLongitude = null,
        exifCity = null,
        exifState = null,
        exifCountry = null,
        exifDescription = null,
    )
}
