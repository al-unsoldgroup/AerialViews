package com.neilturner.aerialviews.providers.custom

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.core.net.toUri
import com.neilturner.aerialviews.data.network.JsonHelper
import com.neilturner.aerialviews.data.network.UrlValidator
import com.neilturner.aerialviews.models.enums.AerialMediaSource
import com.neilturner.aerialviews.models.enums.AerialMediaType
import com.neilturner.aerialviews.models.enums.ProviderSourceType
import com.neilturner.aerialviews.models.enums.SceneType
import com.neilturner.aerialviews.models.enums.TimeOfDay
import com.neilturner.aerialviews.models.enums.VideoQuality
import com.neilturner.aerialviews.models.prefs.CustomFeedPrefs
import com.neilturner.aerialviews.models.videos.AerialExifMetadata
import com.neilturner.aerialviews.models.videos.AerialMedia
import com.neilturner.aerialviews.models.videos.AerialMediaMetadata
import com.neilturner.aerialviews.providers.MediaProvider
import com.neilturner.aerialviews.providers.ProviderFetchResult
import com.neilturner.aerialviews.utils.filenameWithoutExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import timber.log.Timber
import java.util.concurrent.TimeUnit

class CustomFeedProvider(
    context: Context,
    private val prefs: CustomFeedPrefs,
) : MediaProvider(context) {
    override val type = ProviderSourceType.REMOTE
    val metadata = mutableMapOf<String, Pair<String, Map<Int, String>>>()
    val videos = mutableListOf<AerialMedia>()

    override val enabled: Boolean
        get() = prefs.enabled

    override fun settingsHash(): String = prefs.settingsHash()

    override suspend fun fetch(): ProviderFetchResult {
        videos.clear()
        metadata.clear()

        if (prefs.urlsCache.isBlank()) {
            Timber.w("No valid URLs configured")
            return ProviderFetchResult.Success(media = emptyList(), summary = "")
        }

        val quality = prefs.quality
        val validUrls =
            prefs.urlsCache
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }

        val okHttpClient =
            OkHttpClient
                .Builder()
                // .addInterceptor(logging)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

        val retrofit =
            Retrofit
                .Builder()
                .baseUrl("http://example.com") // Base URL required but not used for @Url
                .client(okHttpClient)
                .addConverterFactory(JsonHelper.buildSerializer())
                .build()

        val customFeedApi = retrofit.create(CustomFeedApi::class.java)
        val privateOrigin =
            runCatching {
                KrillSessionManager(context).cachedSession()?.takeIf { it.spaceId != null }?.libraryOrigin
            }.getOrNull()

        for (url in validUrls) {
            try {
                Timber.d("Processing custom feed")

                // Check if this is an RTSP stream
                if (url.startsWith("rtsp://", ignoreCase = true)) {
                    processRtspStream(url)
                    continue
                }

                // Check if this is an HLS stream
                if (url.endsWith(".m3u8", ignoreCase = true) || url.contains(".m3u8?", ignoreCase = true)) {
                    processHlsStream(url)
                    continue
                }

                if (KrillFeed.matches(url)) {
                    if (KrillFeed.privateFeedReplaces(url, privateOrigin)) {
                        Timber.i("Using the signed-in Krill library instead of its public previews")
                        continue
                    }
                    val mode =
                        (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                            .getDisplay(Display.DEFAULT_DISPLAY)
                            ?.mode
                    KrillFeed.fetch(okHttpClient, url, mode?.physicalWidth ?: 1920, mode?.physicalHeight ?: 1080).forEach { item ->
                        videos.add(
                            AerialMedia(
                                item.url.toUri(),
                                type = AerialMediaType.IMAGE,
                                source = AerialMediaSource.KRILL,
                                metadata = AerialMediaMetadata(shortDescription = item.description),
                            ),
                        )
                    }
                    continue
                }

                // Check if this is a CSV media list
                if (isCsvUrl(url)) {
                    processCsvUrl(okHttpClient, url)
                    continue
                }

                // Process as entries.json URL
                processEntriesUrl(customFeedApi, url, quality)
            } catch (e: Exception) {
                Timber.w("Failed to process custom feed")
            }
        }

        Timber.i("${metadata.count()} metadata items found")
        Timber.i("${videos.count()} $quality videos found")

        return ProviderFetchResult.Success(media = videos, summary = "")
    }

    suspend fun fetchTest(): String {
        Timber.i("Custom feed validation started")

        // Step 1: Simple validation of URLs
        val simpleValidation = performSimpleValidation()
        if (simpleValidation.hasErrors) {
            prefs.urlsCache = ""
            prefs.urlsSummary = simpleValidation.summary
            Timber.w("Custom feed simple validation failed. Summary: ${simpleValidation.summary}")
            return simpleValidation.message
        }

        // Step 2: Advanced validation - fetch and parse each URL
        val advancedValidation = performAdvancedValidation()
        prefs.urlsSummary = advancedValidation.summary
        Timber.i(
            "Custom feed validation finished. Has errors: ${advancedValidation.hasErrors}. " +
                "Summary: ${advancedValidation.summary}",
        )
        return advancedValidation.message
    }

    private fun performSimpleValidation(): ValidationResult {
        if (prefs.urls.isBlank()) {
            return ValidationResult(
                hasErrors = true,
                message = "❌ No URLs configured",
                summary = "No URLs",
            )
        }

        val validationResults = UrlValidator.validateUrls(prefs.urls)
        val validUrls = validationResults.filter { it.first }
        val invalidUrls = validationResults.filter { !it.first }
        Timber.d(
            "Custom feed format validation complete. Valid: ${validUrls.size}, " +
                "invalid: ${invalidUrls.size}",
        )

        if (invalidUrls.isEmpty()) {
            // All URLs are valid format
            return ValidationResult(
                hasErrors = false,
                message =
                    buildString {
                        append("✅ All URLs have valid format (${validUrls.size})\n\n")
                        validUrls.forEach { (_, url) ->
                            append("✅ $url\n")
                        }
                    },
                summary = "${validUrls.size} valid URLs",
            )
        }

        // Some URLs are invalid
        val message =
            buildString {
                append("Invalid URL format detected:\n\n")
                if (validUrls.isNotEmpty()) {
                    append("Valid URLs (${validUrls.size}):\n")
                    validUrls.forEach { (_, url) ->
                        append("✅ $url\n")
                    }
                    append("\n")
                }
                append("Invalid URLs (${invalidUrls.size}):\n")
                invalidUrls.forEach { (_, url) ->
                    append("❌ $url\n")
                }
            }

        return ValidationResult(
            hasErrors = true,
            message = message,
            summary = "${invalidUrls.size} invalid, ${validUrls.size} valid",
        )
    }

    private suspend fun performAdvancedValidation(): ValidationResult {
        val urls = UrlValidator.parseUrls(prefs.urls)
        Timber.i("Custom feed advanced validation parsed ${urls.size} URL(s)")
        val validEntriesUrls = mutableListOf<String>()
        val validRtspUrls = mutableListOf<String>()
        val validHlsUrls = mutableListOf<String>()
        val validCsvUrls = mutableListOf<String>()
        val validKrillUrls = mutableListOf<String>()
        var totalKrillPhotos = 0
        val errorMessages = mutableMapOf<String, String>()

        val okHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

        val retrofit =
            Retrofit
                .Builder()
                .baseUrl("http://example.com")
                .client(okHttpClient)
                .addConverterFactory(JsonHelper.buildSerializer())
                .build()

        val customFeedApi = retrofit.create(CustomFeedApi::class.java)

        for (url in urls) {
            try {
                Timber.d("Testing custom feed")

                // Check if this is an RTSP stream
                if (url.startsWith("rtsp://", ignoreCase = true)) {
                    validRtspUrls.add(url)
                    Timber.i("Valid RTSP stream")
                    continue
                }

                // Check if this is an HLS stream
                if (url.endsWith(".m3u8", ignoreCase = true) || url.contains(".m3u8?", ignoreCase = true)) {
                    validHlsUrls.add(url)
                    Timber.i("Valid HLS stream")
                    continue
                }

                if (KrillFeed.matches(url)) {
                    val items = KrillFeed.fetch(okHttpClient, url)
                    if (items.isNotEmpty()) {
                        validKrillUrls.add(url)
                        totalKrillPhotos += items.size
                    } else {
                        errorMessages[url] = "Krill feed contains no photos"
                    }
                    continue
                }

                // Check if this is a CSV media list
                if (isCsvUrl(url)) {
                    Timber.i("Custom feed detected as CSV")
                    try {
                        val csvItems = fetchCsvMediaItems(okHttpClient, url)
                        if (csvItems.isNotEmpty()) {
                            validCsvUrls.add(url)
                            Timber.i(
                                "Found ${csvItems.size} media items in CSV. " +
                                    "Videos: ${csvItems.count { it.type == AerialMediaType.VIDEO }}, ",
                            )
                        } else {
                            errorMessages[url] = "CSV contains no supported media items"
                            Timber.w("CSV parsed successfully but contained no supported media items")
                        }
                    } catch (e: Exception) {
                        errorMessages[url] = "Failed to parse CSV: ${e.message}"
                        Timber.w("CSV validation failed")
                    }
                    continue
                }

                // Check if URL ends with entries.json - parse it directly for media
                if (url.endsWith("entries.json", ignoreCase = true)) {
                    try {
                        val customVideos = customFeedApi.getVideos(url)
                        val videoCount = customVideos.assets?.size ?: 0
                        val photoCount = customVideos.photos?.size ?: 0
                        if (videoCount + photoCount > 0) {
                            validEntriesUrls.add(url)
                            Timber.i("Found $videoCount videos and $photoCount photos in entries.json")
                        } else {
                            errorMessages[url] = "entries.json contains no media"
                        }
                    } catch (e: Exception) {
                        errorMessages[url] = "Failed to parse entries.json: ${e.message}"
                    }
                    continue
                }

                // For HTTP/HTTPS URLs without entries.json, append /manifest.json if needed
                val uri = url.toUri()
                val testUrl =
                    if (!uri.toString().endsWith("manifest.json", false)) {
                        "${url.trimEnd('/')}/manifest.json"
                    } else {
                        url
                    }

                // Try to parse as manifest to get entries.json URLs
                val entriesUrls = tryParseAsManifest(customFeedApi, testUrl)
                if (entriesUrls.isNotEmpty()) {
                    validEntriesUrls.addAll(entriesUrls)
                    Timber.i("Found ${entriesUrls.size} entries URLs from manifest")
                } else {
                    errorMessages[url] = "URL does not contain a valid manifest.json"
                }
            } catch (e: Exception) {
                Timber.w("Failed to test custom feed")
                errorMessages[url] = e.message ?: "Unknown error"
            }
        }

        // Count total videos and photos from valid entries.json URLs
        var totalVideos = 0
        var totalPhotos = 0
        for (entriesUrl in validEntriesUrls) {
            try {
                val customVideos = customFeedApi.getVideos(entriesUrl)
                val videoCount = customVideos.assets?.size ?: 0
                val photoCount = customVideos.photos?.size ?: 0
                totalVideos += videoCount
                totalPhotos += photoCount
                Timber.d("Found $videoCount videos and $photoCount photos in custom feed")
            } catch (e: Exception) {
                Timber.w("Failed to count media from custom feed")
            }
        }
        var totalCsvVideos = 0
        var totalCsvPhotos = 0
        for (csvUrl in validCsvUrls) {
            try {
                val csvItems = fetchCsvMediaItems(okHttpClient, csvUrl)
                totalCsvVideos += csvItems.count { it.type == AerialMediaType.VIDEO }
                totalCsvPhotos += csvItems.count { it.type == AerialMediaType.IMAGE }
                Timber.d("Found ${csvItems.size} media items in CSV")
            } catch (e: Exception) {
                Timber.w("Failed to count media items from CSV")
            }
        }
        totalVideos += totalCsvVideos
        totalPhotos += totalCsvPhotos + totalKrillPhotos

        // Save valid URLs to cache
        val allValidUrls =
            (validEntriesUrls + validRtspUrls + validHlsUrls + validCsvUrls + validKrillUrls)
                .joinToString(",")
        prefs.urlsCache = allValidUrls
        Timber.i(
            "Custom feed valid URL cache updated. Entries: ${validEntriesUrls.size}, " +
                "RTSP: ${validRtspUrls.size}, HLS: ${validHlsUrls.size}, CSV: ${validCsvUrls.size}, ",
        )

        // Build result message
        if (validEntriesUrls.isNotEmpty() ||
            validRtspUrls.isNotEmpty() ||
            validHlsUrls.isNotEmpty() ||
            validCsvUrls.isNotEmpty() ||
            validKrillUrls.isNotEmpty()
        ) {
            val message =
                buildString {
                    append("✅ Validation successful!\n\n")
                    append("Summary:\n")
                    if (totalVideos > 0) {
                        append("• $totalVideos videos found\n")
                    }
                    if (validEntriesUrls.isNotEmpty()) {
                        append("• ${validEntriesUrls.size} feeds\n")
                    }
                    if (validRtspUrls.isNotEmpty()) {
                        append("• ${validRtspUrls.size} RTSP streams\n")
                    }
                    if (validHlsUrls.isNotEmpty()) {
                        append("• ${validHlsUrls.size} HLS streams\n")
                    }
                    if (validCsvUrls.isNotEmpty()) {
                        append("• ${validCsvUrls.size} CSV media lists\n")
                    }
                    if (validKrillUrls.isNotEmpty()) {
                        append("• ${validKrillUrls.size} Krill libraries\n")
                    }
                    if (totalPhotos > 0) {
                        append("• $totalPhotos photos found\n")
                    }

                    if (errorMessages.isNotEmpty()) {
                        append("\n⚠️ Some URLs had issues (${errorMessages.size}):\n")
                        errorMessages.forEach { (url, error) ->
                            append("❌ $error\n   $url\n")
                        }
                    }
                }

            val totalUrls = validEntriesUrls.size + validRtspUrls.size + validHlsUrls.size + validCsvUrls.size + validKrillUrls.size
            val summary =
                buildString {
                    append("$totalUrls URL${if (totalUrls != 1) "s" else ""}: ")
                    val parts = mutableListOf<String>()
                    if (totalVideos > 0) parts.add("$totalVideos video${if (totalVideos != 1) "s" else ""}")
                    if (validRtspUrls.isNotEmpty()) {
                        parts.add(
                            "${validRtspUrls.size} RTSP stream${if (validRtspUrls.size != 1) "s" else ""}",
                        )
                    }
                    if (validHlsUrls.isNotEmpty()) {
                        parts.add("${validHlsUrls.size} HLS stream${if (validHlsUrls.size != 1) "s" else ""}")
                    }
                    if (totalPhotos > 0) {
                        parts.add("$totalPhotos photo${if (totalPhotos != 1) "s" else ""}")
                    }
                    append(parts.joinToString(", "))
                }

            return ValidationResult(
                hasErrors = false,
                message = message,
                summary = summary,
            )
        } else {
            val message =
                buildString {
                    append("❌ No valid URLs found\n\n")
                    if (errorMessages.isNotEmpty()) {
                        append("Errors:\n")
                        errorMessages.forEach { (url, error) ->
                            append("❌ $error\n   $url\n\n")
                        }
                    }
                }

            return ValidationResult(
                hasErrors = true,
                message = message,
                summary = "No valid URLs",
            )
        }
    }

    private data class ValidationResult(
        val hasErrors: Boolean,
        val message: String,
        val summary: String,
    )

    override suspend fun fetchMetadata(media: List<AerialMedia>): List<AerialMedia> {
        val metadataMap = metadata
        return media.map { item ->
            val data = metadataMap[item.uri.filenameWithoutExtension.lowercase()]
            if (data != null) {
                item.metadata.shortDescription = data.first
                item.metadata.pointsOfInterest = data.second
            }
            item
        }
    }

    private suspend fun tryParseAsManifest(
        apiService: CustomFeedApi,
        url: String,
    ): List<String> =
        withContext(Dispatchers.IO) {
            val manifestUrls = mutableListOf<String>()
            try {
                val manifest = apiService.getManifest(url)
                Timber.i("Custom feed manifest found")
                manifestUrls.add(manifest.manifestUrl)
            } catch (e: Exception) {
                Timber.d("Custom feed is not a manifest format")
            }

            try {
                val manifests = apiService.getManifests(url)
                val entriesUrls = mutableListOf<String>()
                manifests.sources.forEach { source ->
                    if (source.manifestUrl.isNotBlank()) {
                        entriesUrls.add(source.manifestUrl)
                        Timber.d("Found manifest entry")
                    }
                }

                manifestUrls.addAll(entriesUrls)
            } catch (e: Exception) {
                Timber.d("Custom feed is not a manifest list format")
            }

            return@withContext manifestUrls
        }

    private fun isCsvUrl(url: String): Boolean = url.endsWith(".csv", ignoreCase = true) || url.contains(".csv?", ignoreCase = true)

    private suspend fun processEntriesUrl(
        apiService: CustomFeedApi,
        url: String,
        quality: VideoQuality?,
    ) {
        withContext(Dispatchers.IO) {
            try {
                val customVideos = apiService.getVideos(url)

                customVideos.assets?.forEach { asset ->
                    if (prefs.enabled) {
                        videos.add(
                            AerialMedia(
                                asset.uriAtQuality(quality),
                                type = AerialMediaType.VIDEO,
                                source = AerialMediaSource.CUSTOM,
                                metadata =
                                    AerialMediaMetadata(
                                        timeOfDay = TimeOfDay.UNKNOWN,
                                        scene = SceneType.UNKNOWN,
                                    ),
                                audioUri = asset.audioUri(),
                            ),
                        )
                    }

                    val data =
                        Pair(
                            asset.description,
                            asset.pointsOfInterest.mapValues { poi ->
                                poi.value // No string mapping for custom videos
                            },
                        )
                    asset.allUrls().forEach { videoUrl ->
                        metadata[videoUrl] = data
                    }
                }

                customVideos.photos?.forEach { photo ->
                    if (prefs.enabled) {
                        videos.add(
                            AerialMedia(
                                photo.uriAtQuality(quality),
                                type = AerialMediaType.IMAGE,
                                source = AerialMediaSource.CUSTOM,
                                metadata =
                                    AerialMediaMetadata(
                                        shortDescription = photo.title,
                                        // Photos have no per-timestamp POI/short-description overlay option
                                        // (that path is video-only), so surface the feed title via the EXIF
                                        // "description" field, which the default photo overlay already displays.
                                        exif = AerialExifMetadata(description = photo.title),
                                        timeOfDay = TimeOfDay.UNKNOWN,
                                        scene = SceneType.UNKNOWN,
                                    ),
                            ),
                        )
                    }
                }

                Timber.d(
                    "Processed ${customVideos.assets?.size ?: 0} videos and " +
                        "${customVideos.photos?.size ?: 0} photos from custom feed",
                )
            } catch (e: Exception) {
                Timber.w("Failed to parse custom feed entries")
            }
        }
    }

    private suspend fun processCsvUrl(
        okHttpClient: OkHttpClient,
        url: String,
    ) {
        withContext(Dispatchers.IO) {
            try {
                val csvItems = fetchCsvMediaItems(okHttpClient, url)
                csvItems.forEach { item ->
                    videos.add(
                        AerialMedia(
                            item.url.toUri(),
                            type = item.type,
                            source = AerialMediaSource.CUSTOM,
                            metadata =
                                AerialMediaMetadata(
                                    shortDescription = item.description,
                                    timeOfDay = TimeOfDay.UNKNOWN,
                                    scene = SceneType.UNKNOWN,
                                ),
                        ),
                    )
                }

                Timber.d("Processed ${csvItems.size} media items from CSV")
            } catch (e: Exception) {
                Timber.w("Failed to parse CSV")
            }
        }
    }

    private suspend fun fetchCsvMediaItems(
        okHttpClient: OkHttpClient,
        url: String,
    ): List<CustomFeedCsvParser.CsvMediaItem> =
        withContext(Dispatchers.IO) {
            Timber.i("Fetching custom feed CSV")
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                val contentType = response.body.contentType()
                val contentLength = response.body.contentLength()
                Timber.i(
                    "Custom feed CSV response: HTTP ${response.code}, " +
                        "contentType=$contentType, contentLength=$contentLength",
                )
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}")
                }
                val body = response.body.string()
                Timber.d(
                    "Custom feed CSV body received: chars=${body.length}, " +
                        "lines=${body.lineSequence().count()}",
                )
                val items = CustomFeedCsvParser.parse(body)
                Timber.i(
                    "Custom feed CSV parse result: items=${items.size}, " +
                        "videos=${items.count { it.type == AerialMediaType.VIDEO }}, ",
                )
                return@withContext items
            }
        }

    private suspend fun processRtspStream(url: String) {
        withContext(Dispatchers.IO) {
            try {
                Timber.i("Processing RTSP stream")

                // Add RTSP stream directly as a video
                // RTSP streams don't have time of day or scene metadata, so we add them without filtering
                val uri = url.toUri()
                videos.add(
                    AerialMedia(
                        uri,
                        type = AerialMediaType.VIDEO,
                        source = AerialMediaSource.RTSP,
                        metadata =
                            AerialMediaMetadata(
                                shortDescription = "",
                                timeOfDay = TimeOfDay.UNKNOWN,
                                scene = SceneType.UNKNOWN,
                            ),
                    ),
                )

                // Add metadata for the RTSP stream
                val data =
                    Pair(
                        "RTSP Stream: $url",
                        emptyMap<Int, String>(),
                    )
                metadata[url] = data

                Timber.d("Added RTSP stream")
            } catch (e: Exception) {
                Timber.w("Failed to process RTSP stream")
            }
        }
    }

    private suspend fun processHlsStream(url: String) {
        withContext(Dispatchers.IO) {
            try {
                Timber.i("Processing HLS stream")

                val uri = url.toUri()
                videos.add(
                    AerialMedia(
                        uri,
                        type = AerialMediaType.VIDEO,
                        source = AerialMediaSource.HLS,
                        metadata =
                            AerialMediaMetadata(
                                shortDescription = "",
                                timeOfDay = TimeOfDay.UNKNOWN,
                                scene = SceneType.UNKNOWN,
                            ),
                    ),
                )

                val data =
                    Pair(
                        "HLS Stream: $url",
                        emptyMap<Int, String>(),
                    )
                metadata[url] = data

                Timber.d("Added HLS stream")
            } catch (e: Exception) {
                Timber.w("Failed to process HLS stream")
            }
        }
    }
}
