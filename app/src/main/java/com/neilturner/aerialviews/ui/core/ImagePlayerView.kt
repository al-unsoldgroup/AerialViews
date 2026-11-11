package com.neilturner.aerialviews.ui.core

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Display
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import androidx.exifinterface.media.ExifInterface
import coil3.ImageLoader
import coil3.asDrawable
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Scale
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.common.SMBRuntimeException
import com.neilturner.aerialviews.BuildConfig
import com.neilturner.aerialviews.data.AerialDatabase
import com.neilturner.aerialviews.models.enums.AerialMediaSource
import com.neilturner.aerialviews.models.enums.AerialMediaType
import com.neilturner.aerialviews.models.enums.AspectRatio
import com.neilturner.aerialviews.models.enums.PhotoScale
import com.neilturner.aerialviews.models.enums.ProgressBarLocation
import com.neilturner.aerialviews.models.enums.ProgressBarType
import com.neilturner.aerialviews.models.prefs.GeneralPrefs
import com.neilturner.aerialviews.models.videos.AerialMedia
import com.neilturner.aerialviews.providers.custom.KrillCacheFence
import com.neilturner.aerialviews.providers.custom.KrillCacheKey
import com.neilturner.aerialviews.providers.custom.KrillFeed
import com.neilturner.aerialviews.providers.custom.KrillFrameCache
import com.neilturner.aerialviews.providers.custom.KrillImageAuthInterceptor
import com.neilturner.aerialviews.providers.custom.KrillPortraitHistory
import com.neilturner.aerialviews.providers.custom.KrillPortraitPairing
import com.neilturner.aerialviews.providers.custom.KrillPrivateFeed
import com.neilturner.aerialviews.providers.custom.KrillResolutionPolicy
import com.neilturner.aerialviews.providers.custom.KrillSessionManager
import com.neilturner.aerialviews.services.InputStreamFetcher
import com.neilturner.aerialviews.ui.controls.ProgressBarEvent
import com.neilturner.aerialviews.ui.controls.ProgressState
import com.neilturner.aerialviews.ui.core.ImagePlayerHelper.buildGifDecoder
import com.neilturner.aerialviews.ui.core.ImagePlayerHelper.buildOkHttpClient
import com.neilturner.aerialviews.ui.helpers.BitmapHelper
import com.neilturner.aerialviews.ui.helpers.ExifMetadata
import com.neilturner.aerialviews.utils.FirebaseHelper
import com.neilturner.aerialviews.utils.filename
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.kosert.flowbus.GlobalBus
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/** Dark neutral matte behind uncropped art; dim enough for a living room at night. */
private const val ART_MATTE = 0xFF262624.toInt()

class ImagePlayerView : FrameLayout {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    private var listener: OnImagePlayerEventListener? = null
    private var finishedRunnable = Runnable { listener?.onImageFinished() }

    private val ioJob = SupervisorJob()
    private val mainJob = SupervisorJob()
    private val ioScope = CoroutineScope(Dispatchers.IO + ioJob)
    private val mainScope = CoroutineScope(Dispatchers.Main + mainJob)

    private var pausedTimestamp: Long = 0
    private var timerStartedAt: Long = 0
    private var totalDuration: Long = 0
    private var remainingDuration: Long = 0
    private var preload: Disposable? = null
    private var currentLoad: Disposable? = null
    private var collageJob: Job? = null
    private val currentLoadLock = Any()

    @Volatile private var preloadGeneration = 0

    @Volatile private var imageGeneration = 0
    private var transitionOverlay: FrameLayout? = null

    @Volatile private var currentIsPrivate = false
    private var foregroundIsPrivate = false
    private var activeFocus: FocusCrop.Point? = null
    private val cacheInvalidation: () -> Unit = {
        val done = CountDownLatch(1)
        val clearDisplay =
            Runnable {
                try {
                    preloadGeneration++
                    preload?.dispose()
                    preload = null
                    collageJob?.cancel()
                    clearTransitionOverlay()
                    imageLoader.memoryCache?.clear()
                    blurHelper.cancel()
                    backgroundImageView.setImageDrawable(null)
                    if (currentIsPrivate || foregroundIsPrivate) {
                        stop()
                        currentIsPrivate = false
                    }
                    nativeQuality.clear()
                } finally {
                    done.countDown()
                }
            }
        if (Looper.myLooper() == Looper.getMainLooper()) clearDisplay.run() else Handler(Looper.getMainLooper()).post(clearDisplay)
        if (!done.await(5, TimeUnit.SECONDS)) throw java.io.IOException("Unable to clear private display")
    }

    private data class NativeDimensions(
        val width: Int,
        val height: Int,
        val exif: ExifMetadata,
    )

    private val nativeQuality = java.util.concurrent.ConcurrentHashMap<String, NativeDimensions>()
    private val qualityRequests = Semaphore(2)
    private val portraitHistory by lazy { KrillPortraitHistory(context) }

    /**
     * Two-token sync gate: ensures [runSetupFinishedRunnable] fires only after *both*
     * the foreground image load (Coil onSuccess) and the background blur (potentially
     * async on pre-S devices) have completed for the same image request.
     *
     * - [backgroundReadyToken] is set by [markBackgroundReady] when [BackgroundBlurHelper]
     *   reports its background job is done.
     * - [pendingSetupToken] is set by [setupFinishedRunnable] when the foreground load
     *   finishes but the background isn't ready yet.
     * - [runSetupFinishedRunnable] fires when both tokens agree on the same value.
     */
    private var backgroundReadyToken: Long = -1
    private var pendingSetupToken: Long = -1

    private val foregroundImageView =
        AppCompatImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
    private val backgroundImageView =
        AppCompatImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = GONE
        }
    private val progressBar =
        GeneralPrefs.progressBarLocation != ProgressBarLocation.DISABLED &&
            GeneralPrefs.progressBarType != ProgressBarType.VIDEOS

    private val blurHelper =
        BackgroundBlurHelper(
            backgroundImageView = backgroundImageView,
            ioScope = ioScope,
            mainScope = mainScope,
            resolveTargetSize = ::resolveTargetSize,
            onReady = ::markBackgroundReady,
        )

    init {
        addView(backgroundImageView)
        addView(foregroundImageView)
        foregroundImageView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) applyFocusedPlacement()
        }
        KrillFrameCache.addInvalidationListener(cacheInvalidation)
    }

    fun release() {
        KrillFrameCache.removeInvalidationListener(cacheInvalidation)
        imageGeneration++
        collageJob?.cancel()
        synchronized(currentLoadLock) {
            currentLoad?.dispose()
            currentLoad = null
        }
        preloadGeneration++
        preload?.dispose()
        clearTransitionOverlay()
        ioJob.cancel()
        mainJob.cancel()
        removeCallbacks(finishedRunnable)
        listener = null
    }

    private val imageClient by lazy {
        val refreshClient = OkHttpClient()
        buildOkHttpClient()
            .newBuilder()
            .addNetworkInterceptor(
                KrillImageAuthInterceptor {
                    val session = KrillSessionManager(context).session(refreshClient)
                    session?.let { it.libraryOrigin to it.accessToken }
                },
            ).build()
    }

    private val imageLoader =
        ImageLoader
            .Builder(context)
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.1).build() }
            .diskCache { KrillFrameCache.get(context) }
            // .logger(logger)
            .components {
                add(
                    com.neilturner.aerialviews.providers.custom
                        .KrillVariantInventoryInterceptor(context.applicationContext),
                )
                add(OkHttpNetworkFetcherFactory(imageClient))
                add(InputStreamFetcher.Factory())
                add(buildGifDecoder())
            }.build()

    /** Warm the next Krill image without changing the currently displayed photo. */
    fun preloadNext(media: AerialMedia?) {
        preloadGeneration++
        val generation = preloadGeneration
        preload?.dispose()
        preload = null
        if (media?.source != AerialMediaSource.KRILL || media.type != AerialMediaType.IMAGE) return
        if (isPrivateKrillImage(media)) {
            ioScope.launch {
                qualityRequests.withPermit {
                    if (generation == preloadGeneration) runCatching { hasDisplayResolution(media) }
                }
                if (generation == preloadGeneration) {
                    mainScope.launch {
                        if (generation == preloadGeneration) preloadResolved(media, generation)
                    }
                }
            }
            return
        }
        preloadResolved(media, generation)
    }

    private fun preloadResolved(
        media: AerialMedia,
        generation: Int,
    ) {
        val access = if (isPrivateKrillImage(media)) acquirePrivateAccess(media) ?: return else null
        var handedOff = false
        try {
            val privateKey = access?.first
            val (width, height) = resolveTargetSize()
            val request =
                ImageRequest
                    .Builder(context.applicationContext)
                    .data(stripCredentialsForCoil(media))
                    .apply {
                        if (privateKey != null) {
                            diskCacheKey(privateKey)
                            memoryCacheKey(privateKey)
                            scale(Scale.FILL)
                            if (forceOfflineForTest()) networkCachePolicy(CachePolicy.DISABLED)
                        }
                    }.size(width, height)
                    .allowHardware(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || !GeneralPrefs.photoBackgroundBlurEnabled)
                    .listener(
                        onSuccess = { _, result ->
                            if (generation == preloadGeneration && (access?.second?.isValid() != false)) {
                                Timber.d("Krill image preload completed")
                                val cropUrl =
                                    if (fitsArt(media) || fitsTall(media, result.image.width, result.image.height)) {
                                        null
                                    } else {
                                        runCatching {
                                            val (targetWidth, targetHeight) = resolveTargetSize()
                                            KrillFeed.cropForDimensions(
                                                media.uri.toString(),
                                                media.metadata.width ?: result.image.width,
                                                media.metadata.height ?: result.image.height,
                                                targetWidth,
                                                targetHeight,
                                                privateSmartCrop = isPrivateCropEnabled(media),
                                                focusRevision = media.metadata.focusRevision,
                                            )
                                        }.getOrNull()
                                    }
                                if (cropUrl != null) {
                                    val cropAccess = if (access != null) acquirePrivateAccess(media, cropUrl) else null
                                    if (access == null || cropAccess != null) {
                                        var cropHandedOff = false
                                        try {
                                            val cropRequest =
                                                ImageRequest
                                                    .Builder(context.applicationContext)
                                                    .data(cropUrl)
                                                    .apply {
                                                        cropAccess?.first?.let { key ->
                                                            diskCacheKey(key)
                                                            memoryCacheKey(key)
                                                        }
                                                        if (cropAccess != null &&
                                                            forceOfflineForTest()
                                                        ) {
                                                            networkCachePolicy(CachePolicy.DISABLED)
                                                        }
                                                    }.size(width, height)
                                                    .allowHardware(
                                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ||
                                                            !GeneralPrefs.photoBackgroundBlurEnabled,
                                                    ).build()
                                            preload = enqueueTracked(cropRequest, cropAccess?.second)
                                            cropHandedOff = true
                                        } finally {
                                            if (!cropHandedOff) cropAccess?.second?.close()
                                        }
                                    }
                                }
                            }
                        },
                    ).build()
            preload = enqueueTracked(request, access?.second)
            handedOff = true
        } finally {
            if (!handedOff) access?.second?.close()
        }
    }

    /** Keep the current photo visible until the next image has finished loading. */
    fun beginPhotoTransition(): Boolean {
        val current = foregroundImageView.drawable ?: return false
        clearTransitionOverlay()
        removeCallbacks(finishedRunnable)
        val overlay = FrameLayout(context)
        val background = backgroundImageView.drawable
        if (backgroundImageView.visibility == VISIBLE && background != null) {
            overlay.addView(
                ImageView(context).apply {
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                    scaleType = backgroundImageView.scaleType
                    setImageDrawable(background)
                },
            )
        }
        overlay.addView(
            ImageView(context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                scaleType = foregroundImageView.scaleType
                setImageDrawable(current)
            },
        )
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        transitionOverlay = overlay
        return true
    }

    fun finishPhotoTransition(duration: Long) {
        val overlay = transitionOverlay ?: return
        overlay
            .animate()
            .alpha(0f)
            .setDuration(duration)
            .withEndAction {
                if (transitionOverlay === overlay) clearTransitionOverlay()
            }.start()
    }

    private fun clearTransitionOverlay() {
        transitionOverlay?.let {
            it.animate().cancel()
            removeView(it)
        }
        transitionOverlay = null
    }

    fun setImage(media: AerialMedia) {
        collageJob?.cancel()
        pausedTimestamp = 0
        timerStartedAt = 0
        remainingDuration = 0
        val generation = ++imageGeneration
        currentIsPrivate = isPrivateKrillImage(media)
        synchronized(currentLoadLock) {
            currentLoad?.dispose()
            currentLoad = null
        }
        ioScope.launch {
            if (isPrivateKrillImage(media)) {
                qualityRequests.withPermit {
                    if (generation == imageGeneration) runCatching { hasDisplayResolution(media) }
                }
                if (generation != imageGeneration) return@launch
            }
            val baseStream = ImagePlayerHelper.streamFromMedia(context, media)
            if (generation != imageGeneration) {
                baseStream?.close()
                return@launch
            }
            if (baseStream == null) {
                loadImage(media, stripCredentialsForCoil(media), generation)
                return@launch
            }

            if (
                (media.source == AerialMediaSource.IMMICH) ||
                (media.source == AerialMediaSource.NCMEMORIES)
            ) {
                loadImage(media, baseStream, generation)
                return@launch
            }

            val totalStartTime = System.currentTimeMillis()
            val stream =
                PushbackInputStream(
                    BufferedInputStream(baseStream, STREAM_BUFFER_SIZE),
                    BitmapHelper.HEADER_BUFFER_SIZE,
                )

            try {
                val headerBytes = ByteArray(BitmapHelper.HEADER_BUFFER_SIZE)
                val headerLength = readUpTo(stream, headerBytes, headerBytes.size)
                if (generation != imageGeneration) {
                    stream.close()
                    return@launch
                }
                if (headerLength <= 0) {
                    stream.close()
                    loadImage(media, stripCredentialsForCoil(media), generation)
                    return@launch
                }
                stream.unread(headerBytes, 0, headerLength)

                val exifMetadata = BitmapHelper.extractExifMetadataFromHeader(headerBytes, headerLength)

                if (media.source != AerialMediaSource.IMMICH) {
                    media.metadata.exif.date = exifMetadata.date
                    media.metadata.exif.offset = exifMetadata.offset
                    media.metadata.exif.latitude = exifMetadata.latitude
                    media.metadata.exif.longitude = exifMetadata.longitude
                    media.metadata.exif.description = exifMetadata.description
                }

                Timber.d(
                    "ImagePlayerView: Extracted EXIF in ${System.currentTimeMillis() - totalStartTime}ms...",
                )

                loadImage(media, stream, generation)
            } catch (e: TransportException) {
                Timber.e(e, "SMB transport dropped while reading image header")
                stream.close()
                onPlayerError(generation)
            } catch (e: SMBRuntimeException) {
                Timber.e(e, "SMB runtime error while reading image header")
                stream.close()
                onPlayerError(generation)
            } catch (e: Exception) {
                Timber.e(e, "Unexpected error reading image stream")
                stream.close()
                onPlayerError(generation)
            }
        }
    }

    /** Load both photos before replacing the current slide. Each private request is fenced through completion. */
    internal fun canPairPortraits(
        first: AerialMedia,
        second: AerialMedia,
    ): Boolean {
        if (!isPrivateKrillImage(first) || !isPrivateKrillImage(second)) return false
        val (width, height) = resolveTargetSize()

        return KrillPortraitPairing.isPair(pairingPhoto(first), pairingPhoto(second), width / 2, height)
    }

    private fun pairingPhoto(media: AerialMedia) =
        KrillPortraitPairing.Photo(
            media.uri.toString(),
            media.metadata.width,
            media.metadata.height,
            media.metadata.pairPartner,
            media.metadata.pairThird,
        )

    internal fun canCompletePortraitTriple(
        first: AerialMedia,
        second: AerialMedia,
        third: AerialMedia,
    ): Boolean {
        if (!isPrivateKrillImage(third)) return false
        val (width, height) = resolveTargetSize()
        val sizes = listOf(first, second, third).map { PortraitCollageLayout.Size(it.metadata.width ?: 0, it.metadata.height ?: 0) }
        val boxes =
            PortraitCollageLayout.arrangeThree(PortraitCollageLayout.Size(width, height), sizes[0], sizes[1], sizes[2])
                ?: return false
        return KrillPortraitPairing.isTriple(pairingPhoto(first), pairingPhoto(second), pairingPhoto(third), width / 2, height) &&
            sizes.zip(listOf(boxes.first, boxes.second, boxes.third)).all { (size, box) ->
                size.width >= box.right - box.left && size.height >= box.bottom - box.top
            }
    }

    internal suspend fun probePortraitPair(
        first: AerialMedia,
        second: AerialMedia,
    ): Boolean {
        if (!isPrivateKrillImage(first) || !isPrivateKrillImage(second)) return false
        val (width, height) = resolveTargetSize()
        val panel = width / 2 to height
        return withContext(Dispatchers.IO) {
            try {
                hasDisplayResolution(first, panel, fitInside = true) &&
                    hasDisplayResolution(second, panel, fitInside = true) && canPairPortraits(first, second)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }
    }

    internal fun setPortraitPair(
        first: AerialMedia,
        second: AerialMedia,
        third: AerialMedia? = null,
    ) {
        collageJob?.cancel()
        removeCallbacks(finishedRunnable)
        pausedTimestamp = 0
        timerStartedAt = 0
        remainingDuration = 0
        val generation = ++imageGeneration
        currentIsPrivate = true
        synchronized(currentLoadLock) {
            currentLoad?.dispose()
            currentLoad = null
        }
        collageJob =
            mainScope.launch {
                var firstAccess: Pair<String, KrillCacheFence.Lease>? = null
                var secondAccess: Pair<String, KrillCacheFence.Lease>? = null
                var thirdAccess: Pair<String, KrillCacheFence.Lease>? = null
                try {
                    val access1 = acquirePrivateAccess(first) ?: throw IllegalStateException("Private photo unavailable")
                    firstAccess = access1
                    val access2 = acquirePrivateAccess(second) ?: throw IllegalStateException("Private photo unavailable")
                    secondAccess = access2
                    val access3 = third?.let { acquirePrivateAccess(it) ?: throw IllegalStateException("Private photo unavailable") }
                    thirdAccess = access3
                    val job = currentCoroutineContext()[Job]!!
                    access1.second.onCancel { job.cancel() }
                    access2.second.onCancel { job.cancel() }
                    access3?.second?.onCancel { job.cancel() }
                    val (width, height) = resolveTargetSize()
                    val panel = (width / 2) to height
                    val canvas = PortraitCollageLayout.Size(width, height)

                    fun size(media: AerialMedia) = PortraitCollageLayout.Size(media.metadata.width ?: 0, media.metadata.height ?: 0)
                    val boxes =
                        if (third == null) {
                            val pair =
                                PortraitCollageLayout.arrange(canvas, size(first), size(second))
                                    ?: throw IllegalStateException("Invalid portrait dimensions")
                            listOf(pair.first, pair.second)
                        } else {
                            val triple =
                                PortraitCollageLayout.arrangeThree(canvas, size(first), size(second), size(third))
                                    ?: throw IllegalStateException("Invalid portrait dimensions")
                            listOf(triple.first, triple.second, triple.third)
                        }
                    val pair =
                        withContext(Dispatchers.IO) {
                            fun target(box: PortraitCollageLayout.Box) = (box.right - box.left) to (box.bottom - box.top)
                            check(hasDisplayResolution(first, target(boxes[0]), fitInside = false)) { "First portrait is too small" }
                            check(hasDisplayResolution(second, target(boxes[1]), fitInside = false)) { "Second portrait is too small" }
                            if (third !=
                                null
                            ) {
                                check(
                                    hasDisplayResolution(third, target(boxes[2]), fitInside = false),
                                ) { "Third portrait is too small" }
                            }
                            check(KrillPortraitPairing.isPair(pairingPhoto(first), pairingPhoto(second), panel.first, panel.second)) {
                                "Photos are not a Krill pair"
                            }

                            suspend fun load(
                                media: AerialMedia,
                                key: String,
                                box: PortraitCollageLayout.Box,
                            ): Pair<SuccessResult, Boolean> {
                                val panelWidth = box.right - box.left
                                val panelHeight = box.bottom - box.top
                                val cropUrl =
                                    if (isPrivateCropEnabled(media)) {
                                        KrillFeed.privatePanelCrop(
                                            media.uri.toString(),
                                            panelWidth,
                                            panelHeight,
                                            media.metadata.focusRevision,
                                        )
                                    } else {
                                        null
                                    }
                                if (cropUrl != null) {
                                    val cropAccess = acquirePrivateAccess(media, cropUrl)
                                    if (cropAccess != null) {
                                        try {
                                            cropAccess.second.onCancel { job.cancel() }
                                            val cropRequest =
                                                ImageRequest
                                                    .Builder(context.applicationContext)
                                                    .data(cropUrl)
                                                    .diskCacheKey(cropAccess.first)
                                                    .memoryCacheKey(cropAccess.first)
                                                    .apply { if (forceOfflineForTest()) networkCachePolicy(CachePolicy.DISABLED) }
                                                    .size(panelWidth, panelHeight)
                                                    .allowHardware(true)
                                                    .build()
                                            val result = imageLoader.execute(cropRequest)
                                            if (cropAccess.second.isValid() && result is SuccessResult) return result to true
                                        } finally {
                                            cropAccess.second.close()
                                        }
                                    }
                                }
                                val request =
                                    ImageRequest
                                        .Builder(context.applicationContext)
                                        .data(stripCredentialsForCoil(media))
                                        .diskCacheKey(key)
                                        .memoryCacheKey(key)
                                        .scale(Scale.FILL)
                                        .apply { if (forceOfflineForTest()) networkCachePolicy(CachePolicy.DISABLED) }
                                        .size(panelWidth, panelHeight)
                                        .allowHardware(true)
                                        .build()
                                val original =
                                    imageLoader.execute(request) as? SuccessResult ?: throw java.io.IOException("Portrait unavailable")
                                return original to false
                            }
                            listOf(load(first, access1.first, boxes[0]), load(second, access2.first, boxes[1])) +
                                if (third != null && access3 != null) listOf(load(third, access3.first, boxes[2])) else emptyList()
                        }
                    if (generation != imageGeneration || !access1.second.isValid() || !access2.second.isValid() ||
                        (access3 != null && !access3.second.isValid())
                    ) {
                        return@launch
                    }
                    val firstSize = PortraitCollageLayout.Size(pair[0].first.image.width, pair[0].first.image.height)
                    val secondSize = PortraitCollageLayout.Size(pair[1].first.image.width, pair[1].first.image.height)

                    fun focus(
                        media: AerialMedia,
                        usedServerCrop: Boolean,
                    ): FocusCrop.Point? {
                        val x = media.metadata.focusX ?: return null
                        val y = media.metadata.focusY ?: return null
                        return if (usedServerCrop) null else FocusCrop.Point(x, y)
                    }
                    val drawable =
                        PortraitCollageDrawable(
                            pair[0].first.image.asDrawable(resources),
                            pair[1].first.image.asDrawable(resources),
                            firstSize,
                            secondSize,
                            pair
                                .getOrNull(2)
                                ?.first
                                ?.image
                                ?.asDrawable(resources),
                            pair
                                .getOrNull(2)
                                ?.first
                                ?.image
                                ?.let { PortraitCollageLayout.Size(it.width, it.height) },
                            focus(first, pair[0].second),
                            focus(second, pair[1].second),
                            if (third != null && pair.size > 2) focus(third, pair[2].second) else null,
                        )
                    blurHelper.update(null)
                    background = null
                    foregroundIsPrivate = true
                    foregroundImageView.scaleType = ImageView.ScaleType.FIT_XY
                    setForegroundDrawable(drawable)
                    Timber.d("Portrait pair displayed")
                    setupFinishedRunnable()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (generation == imageGeneration) {
                        Timber.d("Portrait pair unavailable; loading first photo")
                        listener?.onPortraitPairFailed()
                        setImage(first)
                    }
                } finally {
                    firstAccess?.second?.close()
                    secondAccess?.second?.close()
                    thirdAccess?.second?.close()
                }
            }
    }

    private fun isPrivateKrillImage(media: AerialMedia): Boolean = KrillPrivateFeed.isFrameImage(media.uri.toString())

    private fun acquirePrivateAccess(
        media: AerialMedia,
        requestUrl: String = media.uri.toString(),
    ): Pair<String, KrillCacheFence.Lease>? {
        val session = runCatching { KrillSessionManager(context).cachedSession() }.getOrNull() ?: return null
        if (!KrillPrivateFeed.isSelectedImage(media.uri.toString(), session.libraryOrigin, session.spaceId)) return null
        if (!KrillPrivateFeed.isSelectedImage(requestUrl, session.libraryOrigin, session.spaceId) ||
            !KrillPrivateFeed.sameFramePhoto(media.uri.toString(), requestUrl)
        ) {
            return null
        }
        val lease = KrillFrameCache.fence.acquire(session.cacheNamespace) ?: return null
        return KrillCacheKey.forImage(session.cacheNamespace, requestUrl) to lease
    }

    private fun isPrivateCropEnabled(media: AerialMedia): Boolean =
        runCatching {
            val session = KrillSessionManager(context).cachedSession() ?: return@runCatching false
            KrillPrivateFeed.isSelectedImage(media.uri.toString(), session.libraryOrigin, session.spaceId) &&
                com.neilturner.aerialviews.providers.custom.KrillCropCapability
                    .enabled(context, session.cacheNamespace)
        }.getOrDefault(false)

    private fun enqueueTracked(
        request: ImageRequest,
        lease: KrillCacheFence.Lease?,
    ): Disposable {
        try {
            val disposable = imageLoader.enqueue(request)
            lease?.onCancel { disposable.dispose() }
            disposable.job.invokeOnCompletion { lease?.close() }
            return disposable
        } catch (error: Exception) {
            lease?.close()
            throw error
        }
    }

    /** Read the original JPEG header before Coil samples the image for the screen. */
    private fun hasDisplayResolution(
        media: AerialMedia,
        target: Pair<Int, Int>? = null,
        fitInside: Boolean? = null,
    ): Boolean {
        val session = KrillSessionManager(context).cachedSession() ?: return false
        if (!KrillPrivateFeed.isSelectedImage(media.uri.toString(), session.libraryOrigin, session.spaceId)) return false
        val lease = KrillFrameCache.fence.acquire(session.cacheNamespace) ?: return false
        return try {
            hasDisplayResolutionWithLease(media, session.cacheNamespace, lease, target, fitInside)
        } finally {
            lease.close()
        }
    }

    private fun hasDisplayResolutionWithLease(
        media: AerialMedia,
        namespace: String,
        lease: KrillCacheFence.Lease,
        target: Pair<Int, Int>? = null,
        fitInside: Boolean? = null,
    ): Boolean {
        val cacheKey = KrillCacheKey.forImage(namespace, media.uri.toString())
        val (targetWidth, targetHeight) = target ?: resolveTargetSize()
        val key = "$cacheKey|$targetWidth|$targetHeight|$fitInside|${GeneralPrefs.photoScalePortrait}|${GeneralPrefs.photoScaleLandscape}"
        nativeQuality[key]?.let {
            persistMissingDimensions(media, it, lease)
            return applyNativeResolution(media, it, targetWidth, targetHeight, fitInside)
        }
        val cache = KrillFrameCache.get(context)
        val snapshot = cache.openSnapshot(cacheKey)
        val cachedResult =
            snapshot?.use { entry ->
                runCatching {
                    entry.data
                        .toFile()
                        .inputStream()
                        .use { readNativeResolution(it) }
                }.getOrNull()
            }
        if (snapshot != null && cachedResult == null) cache.remove(cacheKey)
        val result =
            cachedResult ?: run {
                if (forceOfflineForTest()) throw java.io.IOException("Network disabled for cache test")
                val request = Request.Builder().url(media.uri.toString()).build()
                val call = imageClient.newCall(request)
                lease.onCancel { call.cancel() }
                call.execute().use { response ->
                    if (!response.isSuccessful) throw java.io.IOException("Krill image unavailable")
                    response.body.byteStream().use { readNativeResolution(it) }
                }
            }
        persistMissingDimensions(media, result, lease)
        if (nativeQuality.size >= 1024) nativeQuality.clear()
        nativeQuality[key] = result
        return applyNativeResolution(media, result, targetWidth, targetHeight, fitInside)
    }

    private fun persistMissingDimensions(
        media: AerialMedia,
        dimensions: NativeDimensions,
        lease: KrillCacheFence.Lease,
    ) {
        if (GeneralPrefs.playlistCache && lease.isValid() && ((media.metadata.width ?: 0) <= 0 || (media.metadata.height ?: 0) <= 0)) {
            runCatching {
                runBlocking(Dispatchers.IO) {
                    AerialDatabase.getInstance(context).playlistCacheDao().updateMissingKrillDimensions(
                        media.uri.toString(),
                        dimensions.width,
                        dimensions.height,
                    )
                }
            }
        }
    }

    private fun readNativeResolution(stream: InputStream): NativeDimensions {
        val header = ByteArray(4 * 1024 * 1024)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        var length = 0
        while (length < header.size && options.outWidth <= 0) {
            val end = minOf(length + BitmapHelper.HEADER_BUFFER_SIZE, header.size)
            while (length < end) {
                val count = stream.read(header, length, end - length)
                if (count < 0) break
                if (count == 0) break
                length += count
            }
            BitmapFactory.decodeByteArray(header, 0, length, options)
            if (length < end) break
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) throw java.io.IOException("Image dimensions unavailable")
        val exif = BitmapHelper.extractExifMetadataFromHeader(header, length)
        Timber.d("Krill EXIF available: date=${exif.date != null}, location=${exif.latitude != null && exif.longitude != null}")
        val orientation = exif.orientation
        val rotated =
            orientation in
                setOf(
                    ExifInterface.ORIENTATION_ROTATE_90,
                    ExifInterface.ORIENTATION_ROTATE_270,
                    ExifInterface.ORIENTATION_TRANSPOSE,
                    ExifInterface.ORIENTATION_TRANSVERSE,
                )
        val width = if (rotated) options.outHeight else options.outWidth
        val height = if (rotated) options.outWidth else options.outHeight
        return NativeDimensions(width, height, exif)
    }

    private fun applyNativeResolution(
        media: AerialMedia,
        dimensions: NativeDimensions,
        targetWidth: Int,
        targetHeight: Int,
        fitInsideOverride: Boolean?,
    ): Boolean {
        media.metadata.width = dimensions.width
        media.metadata.height = dimensions.height
        KrillPhotoMetadata.applyExif(media.metadata.exif, dimensions.exif)
        val fitInside =
            fitInsideOverride ?: (resolveForegroundScaleType(dimensions.width, dimensions.height) == ImageView.ScaleType.FIT_CENTER)
        val suitable = KrillResolutionPolicy.isSuitable(dimensions.width, dimensions.height, targetWidth, targetHeight, fitInside)
        Timber.d("Krill source ${dimensions.width}x${dimensions.height}; display ${targetWidth}x$targetHeight; suitable=$suitable")
        return suitable || (fitInsideOverride == null && useNativeBorder(media))
    }

    private fun useNativeBorder(media: AerialMedia): Boolean {
        if (!isPrivateKrillImage(media)) return false
        val session = runCatching { KrillSessionManager(context).cachedSession() }.getOrNull() ?: return false
        if (!KrillPrivateFeed.isSelectedImage(media.uri.toString(), session.libraryOrigin, session.spaceId)) return false
        return portraitHistory.useNativeBorder(session.cacheNamespace, media.uri.toString())
    }

    private fun forceOfflineForTest(): Boolean =
        BuildConfig.DEBUG && context.getSharedPreferences("frame_debug", Context.MODE_PRIVATE).getBoolean("force_offline", false)

    private fun loadImage(
        media: AerialMedia,
        data: Any?,
        generation: Int,
        cropFallback: Boolean = false,
    ) {
        // Errors from the actual async network/disk load are delivered via onError, not here.
        var lease: KrillCacheFence.Lease? = null
        var handedOff = false
        try {
            val requestUrl = if (cropFallback) data as? String ?: return else media.uri.toString()
            val access = if (isPrivateKrillImage(media)) acquirePrivateAccess(media, requestUrl) ?: return else null
            lease = access?.second
            val privateKey = access?.first
            val (targetWidth, targetHeight) = resolveTargetSize()
            val request =
                ImageRequest
                    .Builder(context.applicationContext)
                    .data(data)
                    .apply {
                        if (privateKey != null) {
                            diskCacheKey(privateKey)
                            memoryCacheKey(privateKey)
                            scale(Scale.FILL)
                            if (forceOfflineForTest()) networkCachePolicy(CachePolicy.DISABLED)
                        }
                    }.size(targetWidth, targetHeight)
                    // The pre-S blurred background is a CPU bitmap operation. Coil hardware
                    // bitmaps cannot be drawn into the software canvas used by Drawable.toBitmap().
                    .allowHardware(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || !GeneralPrefs.photoBackgroundBlurEnabled)
                    .target(
                        onStart = {
                            // resetImageTransforms()
                        },
                        onSuccess = { image ->
                            if (generation == imageGeneration && (access?.second?.isValid() != false)) {
                                val drawable = image.asDrawable(resources)
                                blurHelper.update(drawable.takeIf { shouldShowBlurBackground(media, it) })
                                if (fitsArt(media)) setBackgroundColor(ART_MATTE) else background = null
                                foregroundIsPrivate = isPrivateKrillImage(media)
                                setForegroundDrawable(drawable)
                                setScaleMode(image.width, image.height, media, cropFallback)
                            }
                        },
                    ).listener(
                        onSuccess = success@{ _, result ->
                            if (generation != imageGeneration || access?.second?.isValid() == false) return@success
                            if (media.source == AerialMediaSource.KRILL) {
                                Timber.d("Krill image loaded from ${result.dataSource}")
                            }
                            setScaleMode(result.image.width, result.image.height, media, cropFallback)
                            if (media.source == AerialMediaSource.KRILL && !cropFallback && !fitsArt(media) &&
                                !fitsTall(media, result.image.width, result.image.height)
                            ) {
                                val cropUrl =
                                    runCatching {
                                        KrillFeed.cropForDimensions(
                                            media.uri.toString(),
                                            media.metadata.width ?: result.image.width,
                                            media.metadata.height ?: result.image.height,
                                            targetWidth,
                                            targetHeight,
                                            privateSmartCrop = isPrivateCropEnabled(media),
                                            focusRevision = media.metadata.focusRevision,
                                        )
                                    }.getOrNull()
                                if (cropUrl != null) {
                                    Timber.d("Krill smart crop selected after measuring photo")
                                    loadImage(media, cropUrl, generation, cropFallback = true)
                                    return@success
                                }
                            }
                            setupFinishedRunnable()
                        },
                        onError = error@{ _, result ->
                            if (generation != imageGeneration || access?.second?.isValid() == false) return@error
                            if (cropFallback) {
                                if (access != null || media.source == AerialMediaSource.KRILL || media.source == AerialMediaSource.CUSTOM) {
                                    Timber.w("Private smart crop unavailable; showing original photo")
                                } else {
                                    Timber.w(result.throwable, "Smart crop unavailable; showing original photo")
                                }
                                setupFinishedRunnable()
                            } else {
                                handleImageError(result.throwable, generation, media)
                            }
                        },
                    ).build()
            synchronized(currentLoadLock) {
                if (generation == imageGeneration) {
                    currentLoad = enqueueTracked(request, access?.second)
                    handedOff = true
                } else if (data is InputStream) {
                    data.close()
                    access?.second?.close()
                } else {
                    access?.second?.close()
                }
            }
        } catch (ex: Exception) {
            if (media.source == AerialMediaSource.KRILL || media.source == AerialMediaSource.CUSTOM || isPrivateKrillImage(media)) {
                Timber.w("Private or custom image load failed")
            } else {
                Timber.e(ex, "Exception while trying to load image: ${ex.message}")
            }

            if (data is InputStream) {
                try {
                    data.close()
                } catch (_: Exception) {
                    // ignore
                }
            }

            if (generation == imageGeneration) post { if (generation == imageGeneration) listener?.onImageError() }
        } finally {
            if (!handedOff) lease?.close()
        }
    }

    private fun readUpTo(
        stream: InputStream,
        buffer: ByteArray,
        maxBytes: Int,
    ): Int {
        var total = 0
        val limit = minOf(maxBytes, buffer.size)
        while (total < limit) {
            val read = stream.read(buffer, total, limit - total)
            if (read <= 0) break
            total += read
        }
        return total
    }

    private fun setForegroundDrawable(drawable: Drawable?) {
        activeFocus = null
        (foregroundImageView.drawable as? Animatable)?.stop()
        foregroundImageView.setImageDrawable(drawable)
        (drawable as? Animatable)?.start()
    }

    private fun shouldSkipBlurBackground(
        media: AerialMedia,
        drawable: Drawable,
    ): Boolean = media.uri.filename.endsWith(".gif", ignoreCase = true) || drawable is Animatable || fitsArt(media)

    /** Personal art without a manual crop point is shown whole on a neutral matte (UNS-1750). */
    private fun fitsArt(media: AerialMedia): Boolean =
        isPrivateKrillImage(media) &&
            FrameModePolicy.fitsArt(media.metadata.tags, media.metadata.tagsKnown, media.metadata.focusX, media.metadata.focusY)

    /** A portrait too tall to crop without losing the subject is fitted over the blurred backdrop (UNS-1754). */
    private fun fitsTall(
        media: AerialMedia,
        width: Int,
        height: Int,
    ): Boolean {
        if (!isPrivateKrillImage(media)) return false
        val (screenWidth, screenHeight) = resolveTargetSize()
        return !FocusCrop.keepsSubject(
            media.metadata.width ?: width,
            media.metadata.height ?: height,
            screenWidth,
            screenHeight,
            media.metadata.focusX != null && media.metadata.focusY != null,
        )
    }

    private fun shouldShowBlurBackground(
        media: AerialMedia,
        drawable: Drawable,
    ): Boolean {
        if (shouldSkipBlurBackground(media, drawable)) {
            return false
        }

        val imageWidth = drawable.intrinsicWidth
        val imageHeight = drawable.intrinsicHeight
        if (imageWidth <= 0 || imageHeight <= 0) {
            return false
        }

        val (containerWidth, containerHeight) = resolveTargetSize()
        if (containerWidth <= 0 || containerHeight <= 0) {
            return false
        }

        val scale =
            minOf(
                containerWidth.toFloat() / imageWidth.toFloat(),
                containerHeight.toFloat() / imageHeight.toFloat(),
            )
        val displayedWidth = imageWidth * scale
        val displayedHeight = imageHeight * scale
        val epsilon = 0.5f

        return displayedWidth < containerWidth - epsilon || displayedHeight < containerHeight - epsilon
    }

    private fun stripCredentialsForCoil(media: AerialMedia): Uri {
        if (media.source != AerialMediaSource.WEBDAV) {
            return media.uri
        }
        return ImagePlayerHelper.stripUserinfoFromUri(media.uri)
    }

    companion object {
        private const val STREAM_BUFFER_SIZE = 64 * 1024 // 64KB - helps reduce network round-trips
    }

    private fun resolveTargetSize(): Pair<Int, Int> {
        val mode =
            (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
                ?.mode
        if (mode != null && mode.physicalWidth > 0 && mode.physicalHeight > 0) {
            return Pair(mode.physicalWidth, mode.physicalHeight)
        }
        val width = if (this.width > 0) this.width else resources.displayMetrics.widthPixels
        val height = if (this.height > 0) this.height else resources.displayMetrics.heightPixels
        return Pair(width, height)
    }

    internal fun portraitPanelSize(): Pair<Int, Int> {
        val (width, height) = resolveTargetSize()
        return width / 2 to height
    }

    private fun handleImageError(
        throwable: Throwable,
        generation: Int,
        media: AerialMedia,
    ) {
        if (media.source == AerialMediaSource.KRILL || media.source == AerialMediaSource.CUSTOM || isPrivateKrillImage(media)) {
            Timber.w("Private or custom image load failed")
        } else {
            Timber.e(throwable, "Exception while loading image: ${throwable.message}")
            FirebaseHelper.crashlyticsException(throwable)
        }

        onPlayerError(generation)
    }

    fun stop() {
        imageGeneration++
        collageJob?.cancel()
        synchronized(currentLoadLock) {
            currentLoad?.dispose()
            currentLoad = null
        }
        clearTransitionOverlay()
        removeCallbacks(finishedRunnable)
        setForegroundDrawable(null)
        foregroundIsPrivate = false
        pausedTimestamp = 0
        timerStartedAt = 0
        remainingDuration = 0
        blurHelper.cancel()
    }

    fun pauseTimer() {
        if (pausedTimestamp > 0 || timerStartedAt == 0L) return
        pausedTimestamp = SystemClock.elapsedRealtime()
        remainingDuration = maxOf(0, remainingDuration - (pausedTimestamp - timerStartedAt))
        removeCallbacks(finishedRunnable)
    }

    internal fun stopTimerForTransition() {
        removeCallbacks(finishedRunnable)
    }

    fun resumeTimer() {
        if (pausedTimestamp > 0) {
            if (remainingDuration > 0) {
                timerStartedAt = SystemClock.elapsedRealtime()
                postDelayed(finishedRunnable, remainingDuration)
            } else {
                // If time has expired, finish immediately
                listener?.onImageFinished()
            }
            pausedTimestamp = 0
        }
    }

    private fun setScaleMode(
        width: Int,
        height: Int,
        media: AerialMedia,
        alreadyCropped: Boolean,
    ) {
        if (fitsArt(media)) {
            activeFocus = null
            foregroundImageView.scaleType = ImageView.ScaleType.FIT_CENTER
            return
        }
        if (isPrivateKrillImage(media) && !alreadyCropped) {
            // Fill the screen unless the known source would have to be enlarged; then fit it over the blurred backdrop.
            val (screenWidth, screenHeight) = resolveTargetSize()
            if (!FocusCrop.canFill(media.metadata.width, media.metadata.height, screenWidth, screenHeight)) {
                activeFocus = null
                foregroundImageView.scaleType = ImageView.ScaleType.FIT_CENTER
                return
            }
        }
        if (!alreadyCropped && fitsTall(media, width, height)) {
            activeFocus = null
            foregroundImageView.scaleType = ImageView.ScaleType.FIT_CENTER
            return
        }
        val x = media.metadata.focusX
        val y = media.metadata.focusY
        if (isPrivateKrillImage(media) && !alreadyCropped) {
            activeFocus =
                if (x != null && y != null) {
                    FocusCrop.Point(x, y)
                } else {
                    FocusCrop.defaultFocus(media.metadata.width ?: width, media.metadata.height ?: height)
                }
            if (applyFocusedPlacement()) return
        }
        foregroundImageView.scaleType =
            if (isPrivateKrillImage(media)) ImageView.ScaleType.CENTER_CROP else resolveForegroundScaleType(width, height)
    }

    private fun applyFocusedPlacement(): Boolean {
        val focus = activeFocus ?: return false
        val drawable = foregroundImageView.drawable ?: return false
        val placement =
            FocusCrop.place(
                drawable.intrinsicWidth,
                drawable.intrinsicHeight,
                foregroundImageView.width,
                foregroundImageView.height,
                focus,
            ) ?: return false
        foregroundImageView.scaleType = ImageView.ScaleType.MATRIX
        foregroundImageView.imageMatrix =
            Matrix().apply {
                setScale(placement.scale, placement.scale)
                postTranslate(placement.x, placement.y)
            }
        return true
    }

    private fun resolveForegroundScaleType(
        width: Int,
        height: Int,
    ): ImageView.ScaleType {
        val aspect = AspectRatio.fromDimensions(width, height)
        Timber.i("Aspect ratio: $aspect")
        return when (aspect) {
            AspectRatio.SQUARE, AspectRatio.PORTRAIT -> getScaleType(GeneralPrefs.photoScalePortrait)
            AspectRatio.LANDSCAPE -> getScaleType(GeneralPrefs.photoScaleLandscape)
        }
    }

    private fun getScaleType(scale: PhotoScale?): ImageView.ScaleType =
        try {
            ImageView.ScaleType.valueOf(scale.toString())
        } catch (e: Exception) {
            Timber.e(e)
            ImageView.ScaleType.valueOf(PhotoScale.CENTER_CROP.toString())
        }

    private fun setupFinishedRunnable() {
        removeCallbacks(finishedRunnable)
        val token = blurHelper.currentToken
        if (backgroundReadyToken == token) {
            runSetupFinishedRunnable()
        } else {
            pendingSetupToken = token
        }
    }

    private fun runSetupFinishedRunnable() {
        listener?.onImagePrepared()

        val duration = GeneralPrefs.slideshowSpeed.toLong() * 1000
        val fadeDuration = GeneralPrefs.mediaFadeOutDuration.toLong()
        val durationMinusFade = duration - fadeDuration

        totalDuration = duration
        remainingDuration = durationMinusFade
        timerStartedAt = SystemClock.elapsedRealtime()

        Timber.i("Delay: ${durationMinusFade.milliseconds}")
        if (progressBar) GlobalBus.post(ProgressBarEvent(ProgressState.START, 0, duration))
        postDelayed(finishedRunnable, durationMinusFade)
    }

    private fun markBackgroundReady(token: Long) {
        backgroundReadyToken = token
        if (pendingSetupToken == token) {
            pendingSetupToken = -1
            runSetupFinishedRunnable()
        }
    }

    private fun onPlayerError(generation: Int) {
        // Notify immediately; the single error backoff is applied by
        // ScreenController.handleError(). Delaying here too would double it.
        post {
            if (generation == imageGeneration) {
                removeCallbacks(finishedRunnable)
                listener?.onImageError()
            }
        }
    }

    fun setOnPlayerListener(listener: ScreenController) {
        this.listener = listener
    }

    interface OnImagePlayerEventListener {
        fun onImageFinished()

        fun onImageError()

        fun onPortraitPairFailed()

        fun onImagePrepared()
    }
}
