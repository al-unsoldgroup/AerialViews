package com.neilturner.aerialviews.providers.custom

import com.neilturner.aerialviews.data.network.JsonHelper
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Read Krill's publicly visible photos. Private libraries need a separate authenticated integration. */
internal object KrillFeed {
    internal val privatePanelSizes =
        setOf(
            616 to 720,
            617 to 720,
            925 to 1080,
            1233 to 1440,
            1234 to 1440,
            1850 to 2160,
            807 to 720,
            427 to 337,
            1210 to 1080,
            640 to 505,
            1613 to 1440,
            854 to 673,
            854 to 674,
            2420 to 2160,
            1280 to 1010,
        )
    internal val privateDisplaySizes = setOf(1280 to 720, 1920 to 1080, 2560 to 1440, 3840 to 2160)

    @Serializable
    private data class Page(
        val data: List<Photo>,
        val hasMore: Boolean,
    )

    @Serializable
    private data class Photo(
        val type: String,
        @SerialName("cloudflare_id") val cloudflareId: String,
        val title: String? = null,
        val description: String? = null,
        val width: Int? = null,
        val height: Int? = null,
    )

    data class Item(
        val url: String,
        val description: String,
    )

    fun matches(url: String): Boolean = runCatching { url.toHttpUrl().encodedPath.trimEnd('/') == "/api/public/media" }.getOrDefault(false)

    fun privateFeedReplaces(
        url: String,
        privateOrigin: String?,
    ): Boolean {
        val feed = url.toHttpUrlOrNull() ?: return false
        val source = privateOrigin?.toHttpUrlOrNull() ?: return false
        return matches(url) && feed.scheme == source.scheme && feed.host == source.host && feed.port == source.port
    }

    /** Request a bounded, private collage-panel crop from the authenticated frame API. */
    fun privatePanelCrop(
        url: String,
        width: Int,
        height: Int,
        focusRevision: Long? = null,
    ): String? {
        if (!KrillPrivateFeed.isFrameImage(url) || (width to height) !in privatePanelSizes) return null
        return url
            .toHttpUrl()
            .newBuilder()
            .fragment(null)
            .setQueryParameter("crop", "auto")
            .setQueryParameter("width", width.toString())
            .setQueryParameter("height", height.toString())
            .apply { focusRevision?.takeIf { it > 0 }?.let { setQueryParameter("focus_revision", it.toString()) } }
            .build()
            .toString()
    }

    /** Resolve unknown dimensions at playback and request a crop when supported. */
    fun cropForDimensions(
        url: String,
        width: Int,
        height: Int,
        targetWidth: Int = 1920,
        targetHeight: Int = 1080,
        privateSmartCrop: Boolean = false,
        focusRevision: Long? = null,
    ): String? {
        val image = url.toHttpUrl()
        if (width <= 0 || height <= 0) return null
        require(targetWidth > 0 && targetHeight > 0) { "Invalid display size" }
        if (privateSmartCrop) {
            if (!KrillPrivateFeed.isFrameImage(url) || (targetWidth to targetHeight) !in privateDisplaySizes) {
                return null
            }
            if (kotlin.math.abs(width.toDouble() / height - targetWidth.toDouble() / targetHeight) < 0.01) return null
            return image
                .newBuilder()
                .fragment(null)
                .setQueryParameter("crop", "auto")
                .setQueryParameter("width", targetWidth.toString())
                .setQueryParameter("height", targetHeight.toString())
                .apply { focusRevision?.takeIf { it > 0 }?.let { setQueryParameter("focus_revision", it.toString()) } }
                .build()
                .toString()
        }
        if (width.toDouble() / height < 1.2) return null
        if (image.fragment != "frame-auto") return null
        return image
            .newBuilder()
            .encodedPath("/cdn-cgi/image/width=$targetWidth,height=$targetHeight,fit=aspect-crop,gravity=auto${image.encodedPath}")
            .fragment(null)
            .build()
            .toString()
    }

    fun fetch(
        client: OkHttpClient,
        url: String,
        targetWidth: Int = 1920,
        targetHeight: Int = 1080,
    ): List<Item> {
        require(targetWidth > 0 && targetHeight > 0) { "Invalid display size" }
        val endpoint = url.toHttpUrl()
        require(endpoint.scheme == "https" || endpoint.host == "localhost") { "Krill feed must use HTTPS" }
        require(endpoint.username.isEmpty() && endpoint.password.isEmpty()) { "Credentials in feed URLs are not supported" }
        val result = mutableListOf<Item>()
        val smartCrop = endpoint.queryParameter("frame_crop") == "auto"
        var offset = 0
        do {
            val pageUrl =
                endpoint
                    .newBuilder()
                    .removeAllQueryParameters("frame_crop")
                    .setQueryParameter("limit", "100")
                    .setQueryParameter("offset", offset.toString())
                    .build()
            val request =
                Request
                    .Builder()
                    .url(pageUrl)
                    .header("User-Agent", "AerialViews-Krill/1.0")
                    .build()
            val page =
                client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Krill HTTP ${response.code}" }
                    JsonHelper.json.decodeFromString<Page>(response.body.string())
                }
            page.data.filter { it.type == "photo" }.forEach { photo ->
                require(photo.cloudflareId.isNotBlank() && '/' !in photo.cloudflareId) { "Invalid image ID" }
                val isLandscape =
                    photo.width != null && photo.height != null && photo.height > 0 &&
                        photo.width.toDouble() / photo.height >= 1.2
                val imageUrl =
                    endpoint
                        .newBuilder()
                        .encodedPath("/")
                        .query(null)
                        .apply {
                            if (smartCrop && isLandscape) {
                                addPathSegment("cdn-cgi")
                                addPathSegment("image")
                                addPathSegment("width=$targetWidth,height=$targetHeight,fit=aspect-crop,gravity=auto")
                            }
                        }.addPathSegment("m")
                        .addPathSegment(photo.cloudflareId)
                        .addPathSegment("public")
                        .apply {
                            if (smartCrop && (photo.width == null || photo.height == null || photo.width <= 0 || photo.height <= 0)) {
                                fragment("frame-auto")
                            }
                        }.build()
                result.add(
                    Item(
                        imageUrl.toString(),
                        KrillCaption.cleanLegacy(photo.title?.takeIf { it.isNotBlank() } ?: photo.description.orEmpty()),
                    ),
                )
            }
            check(!page.hasMore || page.data.isNotEmpty()) { "Krill pagination did not advance" }
            offset += page.data.size
        } while (page.hasMore)
        return result
    }
}
