package com.neilturner.aerialviews.providers.custom

import com.neilturner.aerialviews.data.network.JsonHelper
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Read the authenticated frame API. Credentials are sent only to the configured library origin. */
internal class KrillPrivateFeed(
    client: OkHttpClient,
    origin: String,
    private val accessToken: String,
) {
    @Serializable
    data class Config(
        val issuer: String,
        val clientId: String,
        val videoAvailable: Boolean = false,
        val imageCropAvailable: Boolean = false,
        val libraryRevisionAvailable: Boolean = false,
    )

    companion object {
        fun sameFramePhoto(
            first: String,
            second: String,
        ): Boolean {
            val a = first.toHttpUrlOrNull() ?: return false
            val b = second.toHttpUrlOrNull() ?: return false
            if (!isFrameImage(first) || !isFrameImage(second)) return false
            val space = a.queryParameter("space") ?: return false
            return space.isNotBlank() && space == b.queryParameter("space") &&
                a.scheme == b.scheme && a.host == b.host && a.port == b.port && a.pathSegments[3] == b.pathSegments[3]
        }

        fun isFrameImage(url: String): Boolean {
            val parts = url.toHttpUrlOrNull()?.pathSegments ?: return false
            return isFrameImagePath(parts)
        }

        private fun isFrameImagePath(parts: List<String>): Boolean =
            parts.size == 5 && parts[0] == "api" && parts[1] == "frame" && parts[2] == "media" &&
                parts[3].isNotEmpty() && parts[4] == "image"

        fun isFrameVideo(url: String): Boolean {
            val parts = url.toHttpUrlOrNull()?.pathSegments ?: return false
            return parts.size == 5 && parts[0] == "api" && parts[1] == "frame" && parts[2] == "media" &&
                parts[3].isNotEmpty() && parts[4] == "video"
        }

        fun isSelectedVideo(
            url: String,
            origin: String,
            space: String?,
        ): Boolean {
            if (space.isNullOrBlank()) return false
            val video = url.toHttpUrlOrNull() ?: return false
            val base = origin.toHttpUrlOrNull() ?: return false
            val parts = video.pathSegments
            return video.scheme == base.scheme && video.host == base.host && video.port == base.port &&
                parts.size == 5 && parts[0] == "api" && parts[1] == "frame" && parts[2] == "media" &&
                parts[3].isNotEmpty() && parts[4] == "video" && video.queryParameter("space") == space
        }

        fun isAuthorizedFrameMedia(
            url: String,
            origin: String?,
            space: String?,
        ): Boolean =
            when {
                isFrameImage(url) -> origin != null && isSelectedImage(url, origin, space)
                isFrameVideo(url) -> origin != null && isSelectedVideo(url, origin, space)
                else -> true
            }

        fun isSelectedImage(
            url: String,
            origin: String,
            space: String?,
        ): Boolean = selectedImageMatcher(origin, space)(url)

        fun selectedImageMatcher(
            origin: String,
            space: String?,
        ): (String) -> Boolean {
            val base = origin.toHttpUrlOrNull()
            val prefix =
                base
                    ?.newBuilder()
                    ?.encodedPath("/api/frame/media/")
                    ?.query(null)
                    ?.fragment(null)
                    ?.build()
                    ?.toString()
            val query =
                if (base != null && !space.isNullOrBlank()) {
                    base
                        .newBuilder()
                        .query(null)
                        .addQueryParameter("space", space)
                        .build()
                        .encodedQuery
                } else {
                    null
                }
            val suffix = "/image?$query"
            return matcher@{ url ->
                if (base == null || space.isNullOrBlank()) return@matcher false
                if (prefix != null && query != null && url.startsWith(prefix) && url.endsWith(suffix)) {
                    val start = prefix.length
                    val end = url.length - suffix.length
                    if (end > start &&
                        (start until end).all { index ->
                            val c = url[index]
                            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_'
                        }
                    ) {
                        return@matcher true
                    }
                }
                val image = url.toHttpUrlOrNull() ?: return@matcher false
                image.scheme == base.scheme && image.host == base.host && image.port == base.port &&
                    isFrameImagePath(image.pathSegments) && image.queryParameter("space") == space
            }
        }

        fun config(
            client: OkHttpClient,
            origin: String,
        ): Config {
            val base = origin.toHttpUrl()
            require(base.scheme == "https" || base.host == "localhost") { "Krill must use HTTPS" }
            require(
                base.username.isEmpty() && base.password.isEmpty() && base.encodedPath == "/" &&
                    base.query == null && base.fragment == null,
            ) {
                "Krill URL must be an origin"
            }
            val request = Request.Builder().url(base.newBuilder().encodedPath("/api/frame/config").build()).build()
            val noRedirects =
                client
                    .newBuilder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build()
            return noRedirects.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Krill configuration HTTP ${response.code}")
                JsonHelper.json.decodeFromString<Config>(response.body.string())
            }
        }
    }

    private val client =
        client
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    private val base = origin.toHttpUrl()

    init {
        require(base.scheme == "https" || base.host == "localhost") { "Krill must use HTTPS" }
        require(
            base.username.isEmpty() && base.password.isEmpty() && base.encodedPath == "/" && base.query == null && base.fragment == null,
        ) {
            "Krill URL must be an origin"
        }
        require(accessToken.isNotBlank()) { "Access token is required" }
    }

    @Serializable
    data class Space(
        val id: String,
        val name: String,
    )

    @Serializable
    private data class Spaces(
        val spaces: List<Space>,
    )

    @Serializable
    private data class Permissions(
        val canDelete: Boolean,
    )

    fun canDelete(
        space: String,
        onCall: (Call) -> Unit = {},
    ): Boolean {
        require(space.isNotBlank()) { "A space is required" }
        val url =
            base
                .newBuilder()
                .encodedPath("/api/frame/permissions")
                .addQueryParameter("space", space)
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .build()
        val call = client.newCall(request)
        onCall(call)
        return call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("Krill frame API HTTP ${response.code}")
            JsonHelper.json.decodeFromString<Permissions>(response.body.string()).canDelete
        }
    }

    @Serializable
    private data class Page(
        val media: List<Photo>,
        val total: Int,
        val revision: String? = null,
    )

    @Serializable
    private data class Playback(
        val url: String,
    )

    fun videoPlaybackUrl(
        videoUrl: String,
        space: String,
    ): String {
        require(isSelectedVideo(videoUrl, base.toString(), space)) { "Video is outside the selected library" }
        val request =
            Request
                .Builder()
                .url(videoUrl)
                .header("Authorization", "Bearer $accessToken")
                .build()
        val playback =
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Krill video unavailable")
                JsonHelper.json
                    .decodeFromString<Playback>(response.body.string())
                    .url
                    .toHttpUrlOrNull()
                    ?: throw IOException("Invalid video playback URL")
            }
        val parts = playback.pathSegments
        if (playback.scheme != "https" || !Regex("^customer-[a-z0-9]+\\.cloudflarestream\\.com$").matches(playback.host) ||
            playback.port != 443 || playback.username.isNotEmpty() || playback.password.isNotEmpty() ||
            playback.query != null || playback.fragment != null || parts.size != 3 || parts[1] != "manifest" ||
            parts[2] != "video.m3u8" || !Regex("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$").matches(parts[0])
        ) {
            throw IOException("Invalid video playback URL")
        }
        return playback.toString()
    }

    @Serializable
    private data class Photo(
        val id: String,
        val title: String? = null,
        val description: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        @SerialName("uploaded_at") val uploadedAt: String? = null,
        val credit: String? = null,
        val tags: List<String> = emptyList(),
        @SerialName("place_attraction") val placeAttraction: String? = null,
        @SerialName("place_neighborhood") val placeNeighborhood: String? = null,
        @SerialName("place_city") val placeCity: String? = null,
        @SerialName("place_country") val placeCountry: String? = null,
        @SerialName("captured_local") val capturedLocal: String? = null,
        @SerialName("artwork_title") val artworkTitle: String? = null,
        @SerialName("artwork_artist") val artworkArtist: String? = null,
        // UNS-1750: Krill marks personal art explicitly; the frame classifies it through the art tag.
        val category: String? = null,
        val pair: ServerPair? = null,
        @SerialName("focus_revision") val focusRevision: Long? = null,
        @SerialName("frame_focus") val frameFocus: FocusPoint? = null,
    )

    /** Server-chosen collage partner (UNS-1754); score and reason are informational. */
    @Serializable
    private data class ServerPair(
        @SerialName("partner_id") val partnerId: String,
        @SerialName("third_id") val thirdId: String? = null,
        val score: Double? = null,
        val reason: String? = null,
    )

    @Serializable
    private data class FocusPoint(
        val x: Float,
        val y: Float,
    )

    data class Item(
        val url: String,
        val description: String,
        val width: Int?,
        val height: Int?,
        val tags: List<String> = emptyList(),
        val video: Boolean = false,
        val pairPartner: String? = null,
        val pairThird: String? = null,
        val focusRevision: Long? = null,
        val focusX: Float? = null,
        val focusY: Float? = null,
    )

    private fun get(
        path: String,
        space: String? = null,
        offset: Int? = null,
        onCall: (Call) -> (() -> Unit) = { {} },
        mediaType: String? = null,
        pageLimit: Int = 100,
    ): String {
        val url =
            base
                .newBuilder()
                .encodedPath(path)
                .apply {
                    if (space != null) addQueryParameter("space", space)
                    if (mediaType != null) addQueryParameter("type", mediaType)
                    if (offset != null) {
                        addQueryParameter("limit", pageLimit.toString())
                        addQueryParameter("offset", offset.toString())
                    }
                }.build()
        val request =
            Request
                .Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .build()
        val call = client.newCall(request)
        val release = onCall(call)
        try {
            return call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("Krill frame API HTTP ${response.code}")
                response.body.string()
            }
        } finally {
            release()
        }
    }

    fun spaces(): List<Space> = JsonHelper.json.decodeFromString<Spaces>(get("/api/frame/spaces")).spaces

    fun libraryRevision(space: String): String? =
        JsonHelper.json.decodeFromString<Page>(get("/api/frame/media", space, 0, pageLimit = 1)).revision

    fun deletePhoto(
        imageUrl: String,
        space: String,
        operation: String,
        onCall: (Call) -> Unit = {},
    ): String {
        require(isSelectedImage(imageUrl, base.toString(), space)) { "Photo is outside the selected library" }
        require(operation.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"))) {
            "Invalid operation ID"
        }
        val id = imageUrl.toHttpUrl().pathSegments[3]
        val url =
            base
                .newBuilder()
                .encodedPath("/api/frame/media/")
                .addPathSegment(id)
                .addQueryParameter("space", space)
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .header("Idempotency-Key", operation)
                .delete()
                .build()
        val call = client.newCall(request)
        onCall(call)
        return call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("Krill frame API HTTP ${response.code}")
            when (response.code) {
                200, 202 -> {
                    val result = JsonHelper.json.decodeFromString<DeletionRequest>(response.body.string())
                    if (result.operation != operation ||
                        (response.code == 202 && result.status != "pending") ||
                        (response.code == 200 && result.status != "completed")
                    ) {
                        throw IOException("Invalid Krill deletion response")
                    }
                    result.status
                }

                else -> {
                    throw IOException("Unexpected Krill deletion response HTTP ${response.code}")
                }
            }
        }
    }

    @Serializable
    private data class DeletionRequest(
        val status: String,
        val operation: String,
    )

    @Serializable
    private data class DeletionProgress(
        val status: String,
    )

    fun deletionStatus(
        imageUrl: String,
        space: String,
        operation: String,
        onCall: (Call) -> Unit = {},
    ): String {
        require(isSelectedImage(imageUrl, base.toString(), space)) { "Photo is outside the selected library" }
        require(operation.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"))) {
            "Invalid operation ID"
        }
        val url =
            base
                .newBuilder()
                .encodedPath("/api/frame/media/")
                .addPathSegment(imageUrl.toHttpUrl().pathSegments[3])
                .addPathSegment("deletion-status")
                .addQueryParameter("space", space)
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .header("Idempotency-Key", operation)
                .build()
        val call = client.newCall(request)
        onCall(call)
        return call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("Krill frame API HTTP ${response.code}")
            val status = JsonHelper.json.decodeFromString<DeletionProgress>(response.body.string()).status
            if (status !in
                setOf("pending", "completed", "cancelled", "expired", "unknown")
            ) {
                throw IOException("Invalid Krill deletion status")
            }
            status
        }
    }

    fun cancelUnacceptedDeletion(
        imageUrl: String,
        space: String,
        operation: String,
        onCall: (Call) -> Unit = {},
    ): String {
        require(isSelectedImage(imageUrl, base.toString(), space)) { "Photo is outside the selected library" }
        require(operation.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"))) {
            "Invalid operation ID"
        }
        val url =
            base
                .newBuilder()
                .encodedPath("/api/frame/media/")
                .addPathSegment(imageUrl.toHttpUrl().pathSegments[3])
                .addPathSegment("deletion-cancel")
                .addQueryParameter("space", space)
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .header("Idempotency-Key", operation)
                .post(ByteArray(0).toRequestBody())
                .build()
        val call = client.newCall(request)
        onCall(call)
        return call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("Krill frame API HTTP ${response.code}")
            val status = JsonHelper.json.decodeFromString<DeletionProgress>(response.body.string()).status
            if (status !in setOf("pending", "completed", "cancelled", "expired")) throw IOException("Invalid Krill cancellation status")
            status
        }
    }

    fun photos(
        space: String,
        checkActive: () -> Unit = {},
        onCall: (Call) -> (() -> Unit) = { {} },
        requireRevision: Boolean = false,
        onRevision: (String?) -> Unit = {},
    ): List<Item> = media(space, false, checkActive, onCall, requireRevision, onRevision)

    fun videos(
        space: String,
        checkActive: () -> Unit = {},
        onCall: (Call) -> (() -> Unit) = { {} },
        requireRevision: Boolean = false,
        onRevision: (String?) -> Unit = {},
    ): List<Item> = media(space, true, checkActive, onCall, requireRevision, onRevision)

    private fun media(
        space: String,
        video: Boolean,
        checkActive: () -> Unit,
        onCall: (Call) -> (() -> Unit),
        requireRevision: Boolean,
        onRevision: (String?) -> Unit,
    ): List<Item> {
        require(space.isNotBlank()) { "A space is required" }
        val result = mutableListOf<Item>()
        val ids = mutableSetOf<String>()
        var expectedTotal: Int? = null
        var expectedRevision: String? = null
        var offset = 0
        do {
            checkActive()
            val page = JsonHelper.json.decodeFromString<Page>(get("/api/frame/media", space, offset, onCall, if (video) "video" else null))
            checkActive()
            require(page.total >= 0 && page.media.size <= 100) { "Invalid Krill page" }
            check(!requireRevision || !page.revision.isNullOrBlank()) { "Missing Krill library revision" }
            if (expectedTotal == null) {
                expectedTotal = page.total
                expectedRevision = page.revision
            } else {
                check(page.revision == expectedRevision) { "Krill library changed during pagination" }
            }
            check(page.total == expectedTotal && page.media.size <= page.total - offset) { "Krill library changed during pagination" }
            check(page.media.isNotEmpty() || offset >= page.total) { "Krill pagination did not advance" }
            page.media.forEach { photo ->
                require(photo.id.isNotBlank()) { "Invalid photo ID" }
                check(ids.add(photo.id)) { "Duplicate Krill media ID" }
                val url =
                    base
                        .newBuilder()
                        .encodedPath("/api/frame/media/")
                        .addPathSegment(photo.id)
                        .addPathSegment(if (video) "video" else "image")
                        .addQueryParameter("space", space)
                        .build()
                result.add(
                    Item(
                        url.toString(),
                        KrillCaption.format(
                            KrillCaption.Fields(
                                photo.placeAttraction,
                                photo.placeNeighborhood,
                                photo.placeCity,
                                photo.placeCountry,
                                photo.capturedLocal,
                                photo.artworkTitle,
                                photo.artworkArtist,
                            ),
                        ) ?: KrillCaption.cleanLegacy(listOfNotNull(photo.title, photo.credit, photo.description).joinToString(" • ")),
                        photo.width,
                        photo.height,
                        if (photo.category == "art" && "art" !in photo.tags) photo.tags + "art" else photo.tags,
                        video,
                        photo.pair?.partnerId?.takeIf { !video && it.isNotBlank() },
                        photo.pair?.thirdId?.takeIf { !video && it.isNotBlank() },
                        photo.focusRevision?.takeIf { !video && it > 0 },
                        photo.frameFocus?.x?.takeIf {
                            !video && it.isFinite() && it in 0f..1f && photo.frameFocus.y.isFinite() &&
                                photo.frameFocus.y in 0f..1f
                        },
                        photo.frameFocus?.y?.takeIf {
                            !video && it.isFinite() && it in 0f..1f && photo.frameFocus.x.isFinite() &&
                                photo.frameFocus.x in 0f..1f
                        },
                    ),
                )
            }
            offset += page.media.size
        } while (offset < page.total)
        onRevision(expectedRevision)
        return result
    }
}
