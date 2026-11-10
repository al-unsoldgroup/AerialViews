package com.neilturner.aerialviews.providers.custom

import com.neilturner.aerialviews.data.CachedMediaEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class KrillReconciliationCandidatesTest {
    private fun url(
        id: String,
        video: Boolean = false,
        space: String = "mine",
    ) = "https://photos.example/api/frame/media/$id/${if (video) "video" else "image"}?space=$space"

    private fun cached(
        id: String,
        type: String = "IMAGE",
        space: String = "mine",
    ) = CachedMediaEntity(
        playlistOrder = 0,
        uri = url(id, type == "VIDEO", space),
        type = type,
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

    @Test
    fun `complete photo snapshot identifies only missing selected photos when video is unavailable`() {
        val existing = listOf(cached("kept"), cached("removed"), cached("video", "VIDEO"), cached("other", space = "elsewhere"))
        val fresh = listOf(ReconciliationMedia(url("kept"), "IMAGE"))
        assertEquals(setOf("removed"), reconciliationCandidates(existing, fresh, "https://photos.example", "mine", "7", false))
        assertNull(reconciliationCandidates(existing, fresh, "https://photos.example", "mine", null, false))
    }

    @Test
    fun `duplicate or foreign snapshot items cannot authorize removal`() {
        val existing = listOf(cached("kept"))
        val item = ReconciliationMedia(url("kept"), "IMAGE")
        assertNull(reconciliationCandidates(existing, listOf(item, item), "https://photos.example", "mine", "7", true))
        assertNull(
            reconciliationCandidates(
                existing,
                listOf(ReconciliationMedia(url("other", space = "elsewhere"), "IMAGE")),
                "https://photos.example",
                "mine",
                "7",
                true,
            ),
        )
    }
}
