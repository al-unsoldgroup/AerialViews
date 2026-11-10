package com.neilturner.aerialviews.ui.core

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import androidx.core.view.isVisible
import com.neilturner.aerialviews.BuildConfig
import com.neilturner.aerialviews.R
import com.neilturner.aerialviews.data.PlaylistCacheRepository
import com.neilturner.aerialviews.data.network.NetworkHelper
import com.neilturner.aerialviews.databinding.AerialActivityBinding
import com.neilturner.aerialviews.databinding.ImageViewBinding
import com.neilturner.aerialviews.databinding.OverlayViewBinding
import com.neilturner.aerialviews.databinding.VideoViewBinding
import com.neilturner.aerialviews.models.LoadingStatus
import com.neilturner.aerialviews.models.MediaPlaylist
import com.neilturner.aerialviews.models.MediaPlaylistContentionException
import com.neilturner.aerialviews.models.MediaPlaylistFetchException
import com.neilturner.aerialviews.models.enums.AerialMediaSource
import com.neilturner.aerialviews.models.enums.AerialMediaType
import com.neilturner.aerialviews.models.enums.ButtonType
import com.neilturner.aerialviews.models.enums.DateType
import com.neilturner.aerialviews.models.enums.LocationType
import com.neilturner.aerialviews.models.enums.MetadataType
import com.neilturner.aerialviews.models.enums.OverlayType
import com.neilturner.aerialviews.models.enums.ProgressBarLocation
import com.neilturner.aerialviews.models.music.MusicPlaylist
import com.neilturner.aerialviews.models.prefs.FrameRotationPrefs
import com.neilturner.aerialviews.models.prefs.GeneralPrefs
import com.neilturner.aerialviews.models.prefs.SonosPrefs
import com.neilturner.aerialviews.models.videos.AerialMedia
import com.neilturner.aerialviews.providers.ProviderFetchResult
import com.neilturner.aerialviews.providers.custom.KrillCachedMetadataRefresh
import com.neilturner.aerialviews.providers.custom.KrillDeletionRequest
import com.neilturner.aerialviews.providers.custom.KrillFrameCache
import com.neilturner.aerialviews.providers.custom.KrillPortraitHistory
import com.neilturner.aerialviews.providers.custom.KrillPrivateFeed
import com.neilturner.aerialviews.providers.custom.KrillPrivateProvider
import com.neilturner.aerialviews.providers.custom.KrillSessionManager
import com.neilturner.aerialviews.providers.custom.KrillTokenStore
import com.neilturner.aerialviews.providers.custom.cancellableKrillClient
import com.neilturner.aerialviews.providers.custom.deletionKrillClient
import com.neilturner.aerialviews.providers.custom.runKrillCaptionSchedule
import com.neilturner.aerialviews.services.CachedPlaylistGate
import com.neilturner.aerialviews.services.KtorServer
import com.neilturner.aerialviews.services.MediaService
import com.neilturner.aerialviews.services.MusicPlayer
import com.neilturner.aerialviews.services.NowPlayingService
import com.neilturner.aerialviews.services.sonos.SonosService
import com.neilturner.aerialviews.services.weather.WeatherService
import com.neilturner.aerialviews.ui.controls.ProgressBar
import com.neilturner.aerialviews.ui.controls.ProgressBarEvent
import com.neilturner.aerialviews.ui.controls.ProgressState
import com.neilturner.aerialviews.ui.core.ImagePlayerView.OnImagePlayerEventListener
import com.neilturner.aerialviews.ui.core.VideoPlayerView.OnVideoPlayerEventListener
import com.neilturner.aerialviews.ui.helpers.ColourHelper
import com.neilturner.aerialviews.ui.helpers.FontHelper
import com.neilturner.aerialviews.ui.helpers.GradientHelper
import com.neilturner.aerialviews.ui.helpers.NotificationHelper
import com.neilturner.aerialviews.ui.helpers.OverlayHelper
import com.neilturner.aerialviews.ui.helpers.OverlayOpacityHelper
import com.neilturner.aerialviews.ui.helpers.PermissionHelper
import com.neilturner.aerialviews.ui.helpers.RefreshRateHelper
import com.neilturner.aerialviews.ui.helpers.WindowHelper
import com.neilturner.aerialviews.ui.overlays.MessageOverlay
import com.neilturner.aerialviews.ui.overlays.MetadataOverlay
import com.neilturner.aerialviews.ui.overlays.NowPlayingOverlay
import com.neilturner.aerialviews.ui.overlays.WeatherForecastOverlay
import com.neilturner.aerialviews.ui.overlays.WeatherNowOverlay
import com.neilturner.aerialviews.ui.overlays.state.MessageOverlayState
import com.neilturner.aerialviews.ui.overlays.state.OverlayEventBridge
import com.neilturner.aerialviews.ui.overlays.state.OverlayStateStore
import com.neilturner.aerialviews.ui.overlays.state.OverlayUiState
import com.neilturner.aerialviews.utils.DreamTiming
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.kosert.flowbus.GlobalBus
import timber.log.Timber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

enum class BlackOutSource {
    NONE,
    USER,
    SLEEP_TIMER,
    SCHEDULED,
}

class ScreenController(
    val context: Context,
) : OnVideoPlayerEventListener,
    OnImagePlayerEventListener {
    private val mainScope = CoroutineScope(Dispatchers.Main)
    private lateinit var playlist: MediaPlaylist
    private val playlistGate = CachedPlaylistGate.process
    private val playlistGateOwner = playlistGate.register()
    private val screenCacheEpoch = KrillFrameCache.epoch
    private var playlistBackedByCache = false
    private var overlayHelper: OverlayHelper
    private val resources by lazy { context.resources }
    private var isStopped = false
    private var dashboardView: android.webkit.WebView? = null
    private var dashboardWasPaused = false
    private var dashboardScheduleJob: Job? = null
    private var lastDashboardScheduleState: Boolean? = null
    private var dashboardRetryAt = 0L
    private var dashboardSchedulePending = false
    private var dashboardManuallyClosed = false
    private var dashboardMusicPaused = false
    private var frameModeIndex: FrameModeIndex? = null
    private var frameModeUris: List<String> = emptyList()
    private var frameModePreload: AerialMedia? = null
    private var frameModeMetadataRevision = -1L
    private var frameModeBuildJob: Job? = null
    private var frameModeBuildFailures = 0
    private var frameModeChangeJob: Job? = null
    private var frameMode = FrameMode.PHOTOS
    private var frameModeActive = false
    private var mixedSlide = 0L
    private var lastArtAt = 0L
    private val frameModeHistory = FrameModeHistory()
    private var playlistReadFailures = 0

    private var nowPlayingService: NowPlayingService? = null
    private var sonosService: SonosService? = null
    private var weatherService: WeatherService? = null
    private var musicPlayer: MusicPlayer? = null
    private val overlayStateStore = OverlayStateStore()
    private val overlayEventBridge = OverlayEventBridge(overlayStateStore)
    private val metadataResolver = MetadataResolver()

    private val shouldAlternateOverlays = GeneralPrefs.alternateTextPosition
    private val overlayVisibilityMode = GeneralPrefs.overlayVisibility
    private val overlayVisibilityDelay = GeneralPrefs.overlayVisibilityDelay.toLong()
    private val overlayRevealTimeout = GeneralPrefs.overlayRevealTimeout.toLong()
    private val overlayFadeOut: Long = GeneralPrefs.overlayFadeOutDuration.toLong()
    private val overlayFadeIn: Long = GeneralPrefs.overlayFadeInDuration.toLong()
    private val mediaFadeIn = GeneralPrefs.mediaFadeInDuration.toLong()
    private val mediaFadeOut = GeneralPrefs.mediaFadeOutDuration.toLong()
    private val overlayVisibleAlpha = OverlayOpacityHelper.visibleAlpha(context)
    private val backgroundVideosColour = ColourHelper.colourFromString(GeneralPrefs.backgroundVideos)

    private var canShowOverlays = false
    private var alternate = false
    private var previousItem = false
    private var currentLoadIsPrevious = false
    private var explicitSkip = false
    private var canSkip = false
    private var isPaused = false
    private var loopUntilSkipped = GeneralPrefs.loopUntilSkipped
    private var sleepTimerJob: Job? = null
    private var privateVideoJob: Job? = null
    private val metadataJobs = mutableMapOf<OverlayType, Job>()
    private var currentMedia: AerialMedia? = null
    private var navigationGeneration = 0L

    private data class PortraitSlide(
        val start: Int,
        val first: AerialMedia,
        val second: AerialMedia,
        val third: AerialMedia? = null,
    )

    private var currentPortraitSlide: PortraitSlide? = null
    private var dashboardDeferredItem: Pair<AerialMedia, PortraitSlide?>? = null
    private var dashboardDeferredPrevious: Boolean? = null
    private val portraitSlidesByEnd = mutableMapOf<Int, PortraitSlide>()
    private val portraitHistory by lazy { KrillPortraitHistory(context) }
    private var photoTransition = false
    private var photoMenu: View? = null
    private var photoMenuSelection = 2
    private var ignorePhotoMenuRelease = false
    private var photoMenuWasPaused = false
    private var pauseAfterNextImage = false
    private var photoMenuHasPair = false
    private var photoMenuIsVideo = false
    private val excludedPhotos by lazy { context.getSharedPreferences(FrameRotationPrefs.EXCLUSIONS, Context.MODE_PRIVATE) }
    private var forcedMuteVideo = false
    private var userMutedVideo = !GeneralPrefs.playsVideoAudio
    private val cacheRepository = PlaylistCacheRepository(context)
    private lateinit var videoViewBinding: VideoViewBinding
    private val imageViewBinding: ImageViewBinding
    private val overlayViewBinding: OverlayViewBinding
    private val loadingView: View
    private val overlayView: View
    private var loadingText: TextView
    private var loadingSpinner: View
    private var loadingContainer: View
    private lateinit var videoPlayer: VideoPlayerView
    private var imagePlayer: ImagePlayerView
    private val brightnessView: View
    private val gradientTopView: View
    private val gradientBottomView: View
    private val progressBarView: ProgressBar
    private val notificationContainer: ViewGroup
    val view: View

    private val topLeftIds: List<Int>
    private val topRightIds: List<Int>
    private val bottomLeftIds: List<Int>
    private val bottomRightIds: List<Int>

    var blackOutMode = false
        private set
    var blackOutSource: BlackOutSource = BlackOutSource.NONE
        private set
    private var scheduledBlackoutJob: Job? = null
    private var wasInScheduledBlackoutWindow: Boolean? = null
    private val cacheInvalidation: () -> Unit = {
        val done = CountDownLatch(1)
        val clearScreen =
            Runnable {
                try {
                    currentMedia = null
                    currentPortraitSlide = null
                    portraitSlidesByEnd.clear()
                    view.visibility = View.GONE
                    stop()
                } finally {
                    done.countDown()
                }
            }
        if (Looper.myLooper() == Looper.getMainLooper()) clearScreen.run() else Handler(Looper.getMainLooper()).post(clearScreen)
        if (!done.await(5, TimeUnit.SECONDS)) throw java.io.IOException("Unable to clear private overlays")
    }

    init {
        val inflater = LayoutInflater.from(context)
        val binding = AerialActivityBinding.inflate(inflater)

        val backgroundLoading = ColourHelper.colourFromString(GeneralPrefs.backgroundLoading)
        val backgroundPhotos = ColourHelper.colourFromString(GeneralPrefs.backgroundPhotos)

        // Setup binding for all views and controls
        view = binding.root
        loadingView = binding.loadingView.root
        loadingView.setBackgroundColor(backgroundLoading)
        loadingText = binding.loadingView.loadingText
        loadingSpinner = binding.loadingView.loadingSpinner
        loadingContainer = binding.loadingView.loadingContainer

        overlayViewBinding = binding.overlayView
        overlayView = overlayViewBinding.root
        gradientTopView = overlayViewBinding.gradientTop
        gradientBottomView = overlayViewBinding.gradientBottom

        val initialVideoRoot = binding.videoView.root
        val videoParent = initialVideoRoot.parent as? ViewGroup
        val videoRoot =
            if (videoParent != null) {
                val index = videoParent.indexOfChild(initialVideoRoot)
                // The player from the inflated layout is discarded here, so release it. Otherwise its
                // ExoPlayer is kept alive by the DefaultBandwidthMeter singleton, which retains the
                // discarded view hierarchy and the Activity for the life of the process.
                binding.videoView.videoPlayer.release()
                videoParent.removeView(initialVideoRoot)
                val replacementVideoRoot = LayoutInflater.from(context).inflate(R.layout.video_view, videoParent, false)
                videoParent.addView(replacementVideoRoot, index)
                replacementVideoRoot
            } else {
                initialVideoRoot
            }

        installVideoView(videoRoot)

        imageViewBinding = binding.imageView
        imageViewBinding.root.setBackgroundColor(backgroundPhotos)
        imagePlayer = imageViewBinding.imagePlayer
        imagePlayer.setOnPlayerListener(this)

        brightnessView = binding.brightnessView
        progressBarView = binding.progressBar
        notificationContainer = view.findViewById(R.id.notification_container)

        // Setup loading message or hide it
        if (GeneralPrefs.showLoadingText) {
            loadingText.apply {
                textSize = GeneralPrefs.loadingTextSize.toFloat()
                typeface = FontHelper.getTypeface(context, GeneralPrefs.fontTypeface, GeneralPrefs.loadingTextWeight)
                includeFontPadding = false
                val offset = FontHelper.getFontVerticalOffset(context, GeneralPrefs.fontTypeface, textSize)
                setPadding(0, offset, 0, -offset)
            }
        } else {
            loadingContainer.visibility = View.INVISIBLE
        }

        // Setup overlays and set initial positions
        overlayHelper = OverlayHelper(context, GeneralPrefs)
        val overlayIds = overlayHelper.buildOverlaysAndIds(overlayViewBinding)
        this.bottomLeftIds = overlayIds.bottomLeftIds
        this.bottomRightIds = overlayIds.bottomRightIds
        this.topLeftIds = overlayIds.topLeftIds
        this.topRightIds = overlayIds.topRightIds
        bindOverlayState()
        overlayEventBridge.start()

        // Setup progress bar
        if (GeneralPrefs.progressBarLocation != ProgressBarLocation.DISABLED) {
            val gravity = if (GeneralPrefs.progressBarLocation == ProgressBarLocation.TOP) Gravity.TOP else Gravity.BOTTOM
            (progressBarView.layoutParams as FrameLayout.LayoutParams).gravity = gravity

            val alpha = GeneralPrefs.progressBarOpacity.toFloat() / 100
            progressBarView.alpha = alpha
            Timber.i("Progress bar: $alpha, ${GeneralPrefs.progressBarLocation}")

            progressBarView.visibility = View.VISIBLE
        }

        // Setup brightness/dimness
        if (GeneralPrefs.videoBrightness != "100") {
            val view = brightnessView
            view.setBackgroundColor(Color.BLACK)
            view.alpha = abs((GeneralPrefs.videoBrightness.toFloat() - 100) / 100)
            view.visibility = View.VISIBLE
        }

        // Reset animation speed if needed
        if (GeneralPrefs.ignoreAnimationScale) {
            WindowHelper.resetSystemAnimationDuration(context)
        }

        // Gradients - set up backgrounds but visibility will be managed with overlays
        if (GeneralPrefs.showTopGradient) {
            gradientTopView.background = GradientHelper.smoothBackgroundAlt(GradientDrawable.Orientation.TOP_BOTTOM)
            gradientTopView.visibility = View.VISIBLE
        }

        if (GeneralPrefs.showBottomGradient) {
            gradientBottomView.background = GradientHelper.smoothBackgroundAlt(GradientDrawable.Orientation.BOTTOM_TOP)
            gradientBottomView.visibility = View.VISIBLE
        }

        KrillFrameCache.addInvalidationListener(cacheInvalidation)
        DreamTiming.mark("screen.built")

        // A manual change (D-pad or Back) holds until the schedule's next change, which is the next
        // dashboard start or end. Then the schedule takes over again.
        GeneralPrefs.migrateFrameSchedule()
        GeneralPrefs.migrateKrillCaptionOverlays()
        dashboardScheduleJob =
            mainScope.launch {
                val offlineTracker = OfflineTracker()
                while (isActive) {
                    val online = isOnline()
                    val connectivity = offlineTracker.update(online, android.os.SystemClock.elapsedRealtime())
                    setOfflineIndicator(connectivity.showIndicator)
                    // Back online: retry a failed dashboard now instead of waiting out its backoff.
                    if (connectivity.reconnected && dashboardRetryAt != 0L) dashboardRetryAt = android.os.SystemClock.elapsedRealtime()
                    val active =
                        DashboardSchedule
                            .from(
                                GeneralPrefs.frameScheduleDays,
                                GeneralPrefs.frameScheduleStart,
                                GeneralPrefs.frameScheduleEnd,
                                GeneralPrefs.frameScheduleInterval,
                                GeneralPrefs.frameScheduleDuration,
                            )?.dashboardDue(java.time.LocalDateTime.now()) == true
                    if (active != lastDashboardScheduleState) {
                        lastDashboardScheduleState = active
                        dashboardManuallyClosed = false
                        dashboardSchedulePending = active
                        if (!active) closeDashboard()
                    }
                    // Offline: keep the photos and videos playing and show the dashboard once the link is back.
                    if (active && online && dashboardSchedulePending && photoMenu == null && canSkip && !blackOutMode) {
                        dashboardSchedulePending = false
                        showDashboard()
                    }
                    if (active && online && photoMenu == null && canSkip && !blackOutMode && dashboardView == null &&
                        dashboardRetryAt != 0L &&
                        android.os.SystemClock.elapsedRealtime() >= dashboardRetryAt
                    ) {
                        showDashboard()
                    }
                    delay(5_000)
                }
            }

        mainScope.launch { recoverPendingDeletion() }

        mainScope.launch {
            // Launch if we have permission
            // Used for a) Skip music tracks b) music info widget
            if (PermissionHelper.hasNotificationListenerPermission(context)) {
                nowPlayingService = NowPlayingService(context)
            }

            if (SonosPrefs.enabled && SonosPrefs.ipAddress.isNotEmpty()) {
                if (PermissionHelper.hasLocalNetworkPermission(context)) {
                    sonosService = SonosService(SonosPrefs.ipAddress, SonosPrefs.pollInterval).also { it.start() }
                } else {
                    Timber.w("SonosService not started: ACCESS_LOCAL_NETWORK permission not granted")
                }
            }

            if (overlayHelper.findOverlay<MessageOverlay>().isNotEmpty() && GeneralPrefs.messageApiEnabled) {
                KtorServer.start(context) { messageEvent ->
                    GlobalBus.post(messageEvent)
                }
            }

            // Build playlist and start screensaver
            val mediaResult =
                playlistGate.load(playlistGateOwner) {
                    MediaService(
                        context,
                        cacheRewriteAllowed = { playlistGate.canRewrite(playlistGateOwner) },
                        portraitPanelSize = imagePlayer.portraitPanelSize(),
                    ).fetchMedia { status ->
                        mainScope.launch {
                            loadingText.text =
                                when (status) {
                                    LoadingStatus.RESUMING -> resources.getString(R.string.loading_resuming)
                                    LoadingStatus.BUILDING -> resources.getString(R.string.loading_building)
                                    LoadingStatus.LOADING -> resources.getString(R.string.loading_title)
                                }
                            loadingSpinner.visibility = View.VISIBLE
                        }
                    }
                }
            playlist = mediaResult.mediaPlaylist
            playlistBackedByCache = mediaResult.isFromCache
            buildFrameModeIndex()
            mainScope.launch {
                while (isActive) {
                    delay(30_000)
                    if (frameModeIndex == null || frameModeMetadataRevision != playlist.metadataRevision) buildFrameModeIndex()
                }
            }
            if (playlistBackedByCache) {
                mainScope.launch { refreshCaptionsWhileActive() }
            } else {
                mainScope.launch { refreshUncachedKrillMetadataWhileActive() }
            }
            if (BuildConfig.DEBUG) {
                val prefs = context.getSharedPreferences("frame_debug", Context.MODE_PRIVATE)
                if (prefs.contains("start_position")) {
                    val position = prefs.getInt("start_position", -1)
                    prefs.edit().remove("start_position").apply()
                    if (position in -1 until playlist.size) playlist.debugSeekTo(position)
                }
            }
            DreamTiming.mark("playlist.resolved", "size=${playlist.size};cached=${mediaResult.isFromCache}")
            if (playlist.size > 0) {
                Timber.i("Playlist size: ${playlist.size}")
                var startPlayback = true
                if (mediaResult.isFromCache) {
                    Timber.i("Playlist restored from cache - delaying ${CACHE_RESUME_DELAY}ms before starting playback")
                    delay(CACHE_RESUME_DELAY.milliseconds)
                    // stop() cancels mainScope, so a stopped session cannot be observed here;
                    // only a blackout entered while waiting should defer playback. Music, weather
                    // and scheduled blackout must still be set up.
                    startPlayback = !blackOutMode
                    if (!startPlayback) {
                        Timber.i("Blackout entered during cache resume delay, deferring playback start")
                    }
                }
                if (startPlayback) {
                    DreamTiming.mark("playback.start", "cached=${mediaResult.isFromCache}")
                    loadNextItem()
                    scheduleSleepTimer()
                }
                scheduleScheduledBlackout()
            } else {
                showLoadingError()
            }

            // Setup music service
            setupMusicPlayer(mediaResult.musicPlaylist, mediaResult.musicResumeIndex)

            // Setup weather service
            val hasWeatherNowOverlay = overlayHelper.findOverlay<WeatherNowOverlay>().isNotEmpty()
            val hasForecastOverlay = overlayHelper.findOverlay<WeatherForecastOverlay>().isNotEmpty()
            if (hasWeatherNowOverlay || hasForecastOverlay) {
                weatherService =
                    WeatherService(context).apply {
                        startUpdates(
                            fetchCurrentWeather = hasWeatherNowOverlay,
                            fetchForecast = hasForecastOverlay,
                        )
                    }
            }
        }
        // 1. Load playlist
        // 2. load video, setup location/POI, start playback call
        // 3. playback started callback, fade out loading text, fade out loading view
        // 4. when video is almost finished - or skip - fade in loading view
        // 5. goto 2
    }

    private fun scheduleSleepTimer() {
        sleepTimerJob?.cancel()
        val minutes = GeneralPrefs.sleepTimer.toLongOrNull() ?: 0L
        if (minutes <= 0L) {
            Timber.i("Sleep timer disabled")
            return
        }
        Timber.i("Scheduling sleep timer for $minutes minute(s)")
        sleepTimerJob =
            mainScope.launch {
                delay((minutes * 60_000L).milliseconds)
                if (!blackOutMode) {
                    Timber.i("Sleep timer finished - toggling blackout mode")
                    toggleBlackOutMode(BlackOutSource.SLEEP_TIMER)
                }
            }
    }

    private fun scheduleScheduledBlackout() {
        scheduledBlackoutJob?.cancel()
        wasInScheduledBlackoutWindow = null
        if (!GeneralPrefs.scheduledBlackoutEnabled) {
            Timber.i("Scheduled blackout disabled")
            return
        }
        Timber.i("Scheduling blackout check ticker")
        scheduledBlackoutJob =
            mainScope.launch {
                while (true) {
                    checkScheduledBlackout()
                    delay(15_000L.milliseconds)
                }
            }
    }

    private fun checkScheduledBlackout() {
        if (!GeneralPrefs.scheduledBlackoutEnabled) return

        val startTime = parseLocalTime(GeneralPrefs.scheduledBlackoutStart) ?: return
        val endTime = parseLocalTime(GeneralPrefs.scheduledBlackoutEnd) ?: return
        val now = java.time.LocalTime.now()

        val isNowInWindow = ScheduledBlackoutWindow.contains(startTime, endTime, now)

        if (wasInScheduledBlackoutWindow == null) {
            wasInScheduledBlackoutWindow = isNowInWindow
            if (isNowInWindow && !blackOutMode) {
                Timber.i("Initial check: inside scheduled blackout window ($startTime to $endTime)")
                enterBlackOutMode(BlackOutSource.SCHEDULED)
            }
        } else if (isNowInWindow != wasInScheduledBlackoutWindow) {
            wasInScheduledBlackoutWindow = isNowInWindow
            if (isNowInWindow && !blackOutMode) {
                Timber.i("Scheduled blackout window started ($startTime to $endTime)")
                enterBlackOutMode(BlackOutSource.SCHEDULED)
            } else if (!isNowInWindow && blackOutMode && blackOutSource == BlackOutSource.SCHEDULED) {
                Timber.i("Scheduled blackout window ended ($startTime to $endTime)")
                exitBlackOutMode()
            }
        }
    }

    private fun parseLocalTime(timeStr: String): java.time.LocalTime? =
        try {
            val parts = timeStr.split(":")
            if (parts.size == 2) {
                java.time.LocalTime.of(parts[0].trim().toInt(), parts[1].trim().toInt())
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }

    private fun setVideoForcedMute(enabled: Boolean) {
        forcedMuteVideo = enabled
        videoPlayer.setMuteState(forcedMuteVideo, userMutedVideo)
    }

    private fun setupMusicPlayer(
        musicPlaylist: MusicPlaylist?,
        resumeIndex: Int = 0,
    ) {
        val backgroundMusicSelected = GeneralPrefs.playsBackgroundMusic

        if (!backgroundMusicSelected) {
            Timber.i("MusicPlayer: background music not selected, skipping")
            setVideoForcedMute(false)
            return
        }

        if (musicPlaylist == null || musicPlaylist.size == 0) {
            Timber.i("MusicPlayer: no music playlist available, skipping")
            setVideoForcedMute(false)
            return
        }

        setVideoForcedMute(true)

        musicPlayer = MusicPlayer(context, musicPlaylist)
        musicPlayer?.onMediaItemChanged = { saveMusicTrackPosition() }
        musicPlayer?.onPlaybackFailed = { handleMusicPlaybackFailed() }
        musicPlayer?.createPlayer()
        musicPlayer?.load(resumeIndex)
        if (blackOutMode || dashboardView != null) {
            if (dashboardView != null) dashboardMusicPaused = true
            musicPlayer?.pause()
            Timber.i("MusicPlayer: not starting while blackout is active")
        } else {
            musicPlayer?.play()
            Timber.i("MusicPlayer: playing ${musicPlaylist.size} tracks")
        }
    }

    /**
     * Background music could not recover from a playback error. Release it and stay silent: the
     * user asked for background music, so video audio is not restored over the top of it.
     */
    private fun handleMusicPlaybackFailed() {
        Timber.i("MusicPlayer: playback abandoned, staying silent")
        musicPlayer?.release()
        musicPlayer = null
    }

    private fun loadItem(
        media: AerialMedia,
        portraitSlide: PortraitSlide? = null,
    ) {
        if (dashboardView != null) {
            dashboardDeferredItem = media to portraitSlide
            return
        }
        privateVideoJob?.cancel()
        // Reset pause state when loading new item
        isPaused = false
        currentMedia = media
        currentPortraitSlide = portraitSlide
        overlayStateStore.resetForNextMedia()

        if (media.source in setOf(AerialMediaSource.KRILL, AerialMediaSource.CUSTOM, AerialMediaSource.HLS, AerialMediaSource.RTSP) ||
            KrillPrivateFeed.isFrameImage(media.uri.toString()) ||
            KrillPrivateFeed.isFrameVideo(media.uri.toString())
        ) {
            Timber.i("Loading private or custom media")
        } else if (media.uri.toString().contains("smb://")) {
            val pattern = Regex("(smb://)([^:]+):([^@]+)@([\\d.]+)/")
            val replacement = "$1****:****@****/"
            val url = pattern.replace(media.uri.toString(), replacement)
            Timber.i("Loading: ${media.metadata.shortDescription} - $url (${media.metadata.pointsOfInterest})")
        } else {
            Timber.i("Loading: ${media.metadata.shortDescription} - ${media.uri} (${media.metadata.pointsOfInterest})")
        }

        updateMetadataOverlayData(media)

        // Set overlay positions
        overlayHelper.assignOverlaysAndIds(
            overlayViewBinding.flowBottomLeft,
            overlayViewBinding.flowBottomRight,
            bottomLeftIds,
            bottomRightIds,
            alternate,
        )

        overlayHelper.assignOverlaysAndIds(
            overlayViewBinding.flowTopLeft,
            overlayViewBinding.flowTopRight,
            topLeftIds,
            topRightIds,
            alternate,
        )

        if (shouldAlternateOverlays) {
            alternate = !alternate
        }

        // Videos
        if (media.type == AerialMediaType.VIDEO) {
            if (KrillPrivateFeed.isFrameVideo(media.uri.toString())) {
                playPrivateVideo(media)
            } else {
                videoPlayer.setVideo(media)
            }
            videoViewBinding.root.visibility = View.VISIBLE
            imageViewBinding.root.visibility = View.INVISIBLE
        }

        // Images
        if (media.type == AerialMediaType.IMAGE) {
            if (portraitSlide ==
                null
            ) {
                imagePlayer.setImage(media)
            } else {
                imagePlayer.setPortraitPair(media, portraitSlide.second, portraitSlide.third)
            }
            imageViewBinding.root.visibility = View.VISIBLE
            videoViewBinding.root.visibility = View.INVISIBLE
        }

        // Best to rest progress bar (if enabled) before media playback
        if (GeneralPrefs.progressBarLocation != ProgressBarLocation.DISABLED) {
            GlobalBus.post(ProgressBarEvent(ProgressState.RESET))
        }

        if (!KrillPrivateFeed.isFrameVideo(media.uri.toString())) videoPlayer.start()
    }

    private fun playPrivateVideo(media: AerialMedia) {
        privateVideoJob =
            mainScope.launch {
                try {
                    val playback =
                        withContext(Dispatchers.IO) {
                            val job = currentCoroutineContext()[Job]
                            val manager = KrillSessionManager(context.applicationContext)
                            val cached = manager.cachedSession() ?: error("Krill login required")
                            val lease = KrillFrameCache.fence.acquire(cached.cacheNamespace) ?: error("Krill session ended")
                            try {
                                lease.onCancel { job?.cancel(CancellationException("Krill session ended")) }
                                val client = cancellableKrillClient(job)
                                val session = manager.session(client) { job?.ensureActive() } ?: error("Krill login required")
                                check(lease.isValid() && session.cacheNamespace == cached.cacheNamespace)
                                val space = session.spaceId ?: error("Krill library required")
                                val url =
                                    KrillPrivateFeed(
                                        client,
                                        session.libraryOrigin,
                                        session.accessToken,
                                    ).videoPlaybackUrl(media.uri.toString(), space)
                                job?.ensureActive()
                                check(lease.isValid())
                                url
                            } finally {
                                lease.close()
                            }
                        }
                    if (isStopped || currentMedia !== media || screenCacheEpoch != KrillFrameCache.epoch ||
                        !isPrivateMediaAuthorized(media)
                    ) {
                        return@launch
                    }
                    userMutedVideo = true
                    videoPlayer.setMuteState(forcedMuteVideo, true)
                    videoPlayer.setVideo(media.copy(uri = playback.toUri(), source = AerialMediaSource.KRILL, audioUri = null))
                    videoPlayer.start()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (!isStopped && currentMedia === media) {
                        Timber.w("Private video unavailable")
                        handleError()
                    }
                }
            }
    }

    private fun fadeOutLoadingText() {
        // Fade out container (text + spinner)
        loadingContainer
            .animate()
            .alpha(0f)
            .setDuration(LOADING_FADE_OUT)
            .withEndAction {
                loadingContainer.visibility = View.GONE
            }.start()
    }

    private fun fadeInNextItem() {
        if (blackOutMode) return

        savePlaybackPosition()

        canShowOverlays = false
        var startDelay: Long = 0
        val overlayDelay = (overlayVisibilityDelay * 1000) + mediaFadeIn

        // If first video (ie. screensaver startup), fade out 'loading...' text/spinner
        if (loadingContainer.isVisible) {
            fadeOutLoadingText()
            startDelay = LOADING_DELAY
        }

        // Reset any overlay animations
        overlayHelper.getOverlaysToFade().forEach { view ->
            view.animate()?.cancel()
            view.clearAnimation()
        }

        // Hide overlays immediately
//        if (autoHideOverlayDelay.toInt() == 0) {
//            overlayHelper.isHidden = true
//            setOverlayInstancesHidden(true)
//            overlayHelper.getOverlaysToFade().forEach { it.alpha = 0f }
//            // Also hide gradients immediately if they have fading overlays
//            // AND no persistent overlays
//            if (GeneralPrefs.showTopGradient && overlayHelper.hasTopOverlaysToFade() && !overlayHelper.hasTopPersistentOverlays()) {
//                gradientTopView.alpha = 0f
        when (overlayVisibilityMode) {
            "ALWAYS_VISIBLE" -> {
                // Overlays stay visible, no hiding
                overlayHelper.getOverlaysToFade().forEach { it.alpha = overlayVisibleAlpha }
                if (GeneralPrefs.showTopGradient && overlayHelper.hasTopOverlaysToFade()) {
                    gradientTopView.alpha = 1f
                }
                if (GeneralPrefs.showBottomGradient && overlayHelper.hasBottomOverlaysToFade()) {
                    gradientBottomView.alpha = 1f
                }
                canShowOverlays = true
            }

            "ALWAYS_HIDDEN" -> {
                // Hide overlays immediately, only show on user reveal
                overlayHelper.getOverlaysToFade().forEach { it.alpha = 0f }
                if (GeneralPrefs.showTopGradient && overlayHelper.hasTopOverlaysToFade() && !overlayHelper.hasTopPersistentOverlays()) {
                    gradientTopView.alpha = 0f
                }
                if (GeneralPrefs.showBottomGradient && overlayHelper.hasBottomOverlaysToFade() &&
                    !overlayHelper.hasBottomPersistentOverlays()
                ) {
                    gradientBottomView.alpha = 0f
                }
                canShowOverlays = true
            }

            "HIDE_AFTER_DELAY" -> {
                // Show overlays, then hide after delay
                overlayHelper.getOverlaysToFade().forEach { it.alpha = overlayVisibleAlpha }
                if (GeneralPrefs.showTopGradient && overlayHelper.hasTopOverlaysToFade()) {
                    gradientTopView.alpha = 1f
                }
                if (GeneralPrefs.showBottomGradient && overlayHelper.hasBottomOverlaysToFade()) {
                    gradientBottomView.alpha = 1f
                }
                hideOverlays(overlayDelay)
            }

            "SHOW_AFTER_DELAY" -> {
                // Hide overlays initially, show after delay, stay visible
                overlayHelper.getOverlaysToFade().forEach { it.alpha = 0f }
                if (GeneralPrefs.showTopGradient && overlayHelper.hasTopOverlaysToFade() && !overlayHelper.hasTopPersistentOverlays()) {
                    gradientTopView.alpha = 0f
                }
                if (GeneralPrefs.showBottomGradient && overlayHelper.hasBottomOverlaysToFade() &&
                    !overlayHelper.hasBottomPersistentOverlays()
                ) {
                    gradientBottomView.alpha = 0f
                }
                mainScope.launch {
                    delay(overlayDelay.milliseconds)
                    val overlaysToFade = overlayHelper.getOverlaysToFade()
                    overlaysToFade.forEachIndexed { index, view ->
                        val animator =
                            view
                                .animate()
                                .alpha(overlayVisibleAlpha)
                                .setStartDelay(0)
                                .setDuration(overlayFadeIn)
                        if (index == overlaysToFade.lastIndex) {
                            animator.withEndAction { canShowOverlays = true }
                        }
                        animator.start()
                    }
                    if (GeneralPrefs.showTopGradient && overlayHelper.hasTopOverlaysToFade()) {
                        gradientTopView
                            .animate()
                            .alpha(1f)
                            .setStartDelay(0)
                            .setDuration(overlayFadeIn)
                            .start()
                    }
                    if (GeneralPrefs.showBottomGradient && overlayHelper.hasBottomOverlaysToFade()) {
                        gradientBottomView
                            .animate()
                            .alpha(1f)
                            .setStartDelay(0)
                            .setDuration(overlayFadeIn)
                            .start()
                    }
                }
            }
        }

        // Fade out LoadingView
        // Video should be playing underneath
        loadingView
            .animate()
            .alpha(0f)
            .setStartDelay(startDelay)
            .setDuration(mediaFadeIn)
            .withEndAction {
                loadingView.alpha = 0f
                loadingView.visibility = View.INVISIBLE
                canSkip = true
            }.start()
    }

    private fun fadeOutCurrentItem() {
        if (!canSkip || dashboardView != null) return
        canSkip = false

        val nextPhoto =
            if (!previousItem && !loopUntilSkipped) {
                if (frameModeActive) {
                    frameModePreload?.takeIf {
                        frameModeMetadataRevision == playlist.metadataRevision && !isExcludedFromRotation(it)
                    }
                } else {
                    playlist.peekNextItem { !isExcludedFromRotation(it) }
                }
            } else {
                null
            }
        if (currentMedia?.source == AerialMediaSource.KRILL && currentMedia?.type == AerialMediaType.IMAGE &&
            nextPhoto?.source == AerialMediaSource.KRILL && nextPhoto.type == AerialMediaType.IMAGE &&
            imagePlayer.beginPhotoTransition()
        ) {
            photoTransition = true
            explicitSkip = false
            isPaused = false
            loadNextItem()
            return
        }

        overlayHelper.findOverlay<MetadataOverlay>().forEach {
            it.isFadingOutMedia = true
        }

        if (currentMedia?.type == AerialMediaType.VIDEO) {
            videoPlayer.fadeOutAudio(mediaFadeOut)
        }

        // Fade in LoadView (ie. black screen)
        loadingView
            .animate()
            .alpha(1f)
            .setDuration(mediaFadeOut)
            .withStartAction {
                loadingView.visibility = View.VISIBLE
                loadingView.alpha = 0f
            }.withEndAction {
                // Hide content views after faded out
                videoViewBinding.root.visibility = View.INVISIBLE
                // Let setVideo() replace the source without forcing a Realtek codec teardown.

                imageViewBinding.root.visibility = View.INVISIBLE
                imageViewBinding.imagePlayer.stop()

                // Reset pause state when transitioning between items
                isPaused = false

                if (!blackOutMode) {
                    val wasExplicitSkip = explicitSkip
                    val loadPreviousItem = previousItem
                    explicitSkip = false
                    previousItem = false

                    if (wasExplicitSkip) {
                        loadNextItem(loadPreviousItem)
                    } else if (loopUntilSkipped && currentMedia != null) {
                        replayCurrentItem()
                    } else {
                        loadNextItem(false)
                    }
                } else {
                    explicitSkip = false
                    previousItem = false
                }
            }.start()
    }

    private fun replayCurrentItem() {
        val media = currentMedia
        if (media != null) {
            loadItem(media, currentPortraitSlide)
        } else {
            loadNextItem()
        }
    }

    private fun showLoadingError() {
        loadingText.text = resources.getString(R.string.loading_error)
        loadingSpinner.visibility = View.GONE
    }

    private fun hideOverlays(delay: Long = 0L) {
        val overlaysToFade = overlayHelper.getOverlaysToFade()

        if (overlaysToFade.isEmpty()) {
            canShowOverlays = true
            return
        }

        overlayHelper.isHidden = true
        setOverlayInstancesHidden(true)

        overlaysToFade.forEachIndexed { index, view ->
            val animator =
                view
                    .animate()
                    .alpha(0f)
                    .setStartDelay(delay)
                    .setDuration(overlayFadeOut)

            // Only set the end action on the last overlay
            if (index == overlaysToFade.lastIndex) {
                animator.withEndAction { canShowOverlays = true }
            }
            animator.start()
        }

        // Fade out gradients if their corresponding region has fading overlays
        // AND no persistent overlays (otherwise gradient should stay visible)
        if (GeneralPrefs.showTopGradient && overlayHelper.hasTopOverlaysToFade() && !overlayHelper.hasTopPersistentOverlays()) {
            gradientTopView
                .animate()
                .alpha(0f)
                .setStartDelay(delay)
                .setDuration(overlayFadeOut)
                .start()
        }
        if (GeneralPrefs.showBottomGradient && overlayHelper.hasBottomOverlaysToFade() && !overlayHelper.hasBottomPersistentOverlays()) {
            gradientBottomView
                .animate()
                .alpha(0f)
                .setStartDelay(delay)
                .setDuration(overlayFadeOut)
                .start()
        }
    }

    private fun setOverlayInstancesHidden(hidden: Boolean) {
        overlayHelper.findOverlay<NowPlayingOverlay>().forEach { it.isHidden = hidden }
        overlayHelper.findOverlay<WeatherNowOverlay>().forEach { it.isHidden = hidden }
        overlayHelper.findOverlay<WeatherForecastOverlay>().forEach { it.isHidden = hidden }
    }

    private fun renderMetadataOverlays(state: OverlayUiState) {
        overlayHelper.findOverlay<MetadataOverlay>().forEach { overlay ->
            state.metadata[overlay.type]?.let { overlay.render(it, videoPlayer) }
        }
    }

    fun showOverlays() {
        // Only allow reveal when overlays can be hidden
        if (overlayVisibilityMode == "ALWAYS_VISIBLE") return

        // If blackout mode is on, exit
        if (blackOutMode) return

        // If media fading in/out
        if (!canSkip) return

        // Are overlays already visible
        if (!canShowOverlays) return

        val overlaysToFade = overlayHelper.getOverlaysToFade()

        if (overlaysToFade.isEmpty()) return

        canShowOverlays = false
        overlayHelper.isHidden = false
        setOverlayInstancesHidden(false)

        overlaysToFade.forEachIndexed { index, view ->
            val animator =
                view
                    .animate()
                    .alpha(overlayVisibleAlpha)
                    .setStartDelay(0)
                    .setDuration(overlayFadeIn)

            // Only set the end action on the last overlay
            if (index == overlaysToFade.lastIndex) {
                animator.withEndAction { hideOverlays(overlayRevealTimeout * 1000) }
            }
            animator.start()
        }

        // Fade in gradients if their corresponding region has fading overlays
        if (GeneralPrefs.showTopGradient && overlayHelper.hasTopOverlaysToFade()) {
            gradientTopView
                .animate()
                .alpha(1f)
                .setStartDelay(0)
                .setDuration(overlayFadeIn)
                .start()
        }
        if (GeneralPrefs.showBottomGradient && overlayHelper.hasBottomOverlaysToFade()) {
            gradientBottomView
                .animate()
                .alpha(1f)
                .setStartDelay(0)
                .setDuration(overlayFadeIn)
                .start()
        }
    }

    private fun loadNextItem(previous: Boolean = false) {
        if (dashboardView != null) {
            dashboardDeferredPrevious = previous
            return
        }
        val navigation = ++navigationGeneration
        currentLoadIsPrevious = previous
        if (!this::playlist.isInitialized || playlist.size == 0) {
            Timber.w("ScreenController: Cannot load an item from an empty playlist")
            showLoadingError()
            return
        }

        if (previous) {
            val start = currentPortraitSlide?.start
            if (start != null) playlist.restoreCursor(start)
        }
        var media: AerialMedia? = null
        val originalPosition = playlist.currentPosition
        if (frameModeActive) {
            selectFrameModeItem(previous, navigation, originalPosition)
            return
        }
        try {
            for (ignored in 0 until playlist.size) {
                val candidate = if (previous) playlist.previousItem() else playlist.nextItem()
                if (candidate == null) break
                if (!isExcludedFromRotation(candidate)) {
                    media = candidate
                    break
                }
            }
        } catch (_: MediaPlaylistContentionException) {
            playlist.restoreCursor(originalPosition)
            retryPlaylistRead(previous, navigation)
            return
        } catch (_: MediaPlaylistFetchException) {
            playlist.restoreCursor(originalPosition)
            retryPlaylistRead(previous, navigation)
            return
        }

        if (media == null) {
            Timber.e("ScreenController: Playlist could not supply an item")
            showLoadingError()
            return
        }

        presentSelectedMedia(media, previous, navigation)
    }

    private fun retryPlaylistRead(
        previous: Boolean,
        navigation: Long,
    ) {
        playlistReadFailures++
        if (playlistReadFailures >= 3) {
            showLoadingError()
        }
        val retryDelay = if (playlistReadFailures >= 3) 30_000L else playlistReadFailures * 1_000L
        mainScope.launch {
            delay(retryDelay)
            if (!isStopped && navigation == navigationGeneration) loadNextItem(previous)
        }
    }

    private data class FrameModeChoice(
        val position: Int,
        val media: AerialMedia?,
        val stale: Boolean = false,
        val fromHistory: Boolean = false,
    )

    private fun selectFrameModeItem(
        previous: Boolean,
        navigation: Long,
        originalPosition: Int,
    ) {
        val index = frameModeIndex
        if (index == null || frameModeMetadataRevision != playlist.metadataRevision) {
            if (frameModeBuildFailures >= 3) {
                showLoadingError()
                mainScope.launch {
                    delay(30_000)
                    if (!isStopped && navigation == navigationGeneration) loadNextItem(previous)
                }
                return
            }
            buildFrameModeIndex()
            mainScope.launch {
                delay(2_000)
                if (!isStopped && navigation == navigationGeneration) loadNextItem(previous)
            }
            return
        }
        val mode = frameMode
        val mixedTarget = mixedTarget()
        val uris = frameModeUris
        val revision = frameModeMetadataRevision
        val historyPosition = if (previous) frameModeHistory.peekPrevious() else frameModeHistory.peekNext()
        mainScope.launch {
            val choice =
                try {
                    withContext(Dispatchers.IO) {
                        val session = runCatching { KrillSessionManager(context).cachedSession() }.getOrNull()
                        val lease = session?.let { KrillFrameCache.fence.acquire(it.cacheNamespace) }
                        if (session != null && lease == null) return@withContext FrameModeChoice(-1, null, stale = true)
                        try {
                            val job = currentCoroutineContext()[Job]!!
                            lease?.onCancel { job.cancel() }
                            val tried = mutableSetOf<Int>()
                            var found: FrameModeChoice? = null
                            while (tried.size < playlist.size) {
                                job.ensureActive()
                                val position =
                                    historyPosition?.takeIf {
                                        val category = index.categoryAt(it)
                                        it !in tried && category != null && FrameModePolicy.accepts(mode, category)
                                    }
                                        ?: index.next(mode, originalPosition, previous, mixedTarget) { it !in tried }
                                        ?: break
                                tried.add(position)
                                val candidate = playlist.itemAt(position) ?: continue
                                val category = FrameModePolicy.category(candidate.metadata.tags, candidate.metadata.tagsKnown)
                                if (candidate.uri.toString() != uris.getOrNull(position) || category != index.categoryAt(position)) {
                                    found = FrameModeChoice(position, null, stale = true)
                                    break
                                }
                                if (!isExcludedFromRotation(candidate, session)) {
                                    found = FrameModeChoice(position, candidate, fromHistory = position == historyPosition)
                                    break
                                }
                            }
                            job.ensureActive()
                            found
                        } finally {
                            lease?.close()
                        }
                    }
                } catch (_: MediaPlaylistContentionException) {
                    retryPlaylistRead(previous, navigation)
                    return@launch
                } catch (_: MediaPlaylistFetchException) {
                    retryPlaylistRead(previous, navigation)
                    return@launch
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Timber.w("Frame mode selection deferred")
                    retryPlaylistRead(previous, navigation)
                    return@launch
                }
            if (isStopped || navigation != navigationGeneration || screenCacheEpoch != KrillFrameCache.epoch) return@launch
            if (choice?.stale == true || revision != playlist.metadataRevision) {
                frameModeIndex = null
                frameModeUris = emptyList()
                buildFrameModeIndex()
                delay(2_000)
                if (!isStopped && navigation == navigationGeneration) loadNextItem(previous)
                return@launch
            }
            val selected = choice?.media
            if (selected == null || isExcludedFromRotation(selected)) {
                frameModeActive = false
                frameMode = FrameMode.PHOTOS
                NotificationHelper.show(notificationContainer, resources.getString(R.string.frame_mode_empty))
                playlist.restoreCursor(originalPosition)
                loadNextItem(previous)
                return@launch
            }
            val selectedChoice = choice ?: return@launch
            playlist.restoreCursor(selectedChoice.position)
            if (selectedChoice.fromHistory) {
                if (previous) frameModeHistory.previous() else frameModeHistory.next()
            } else if (previous) {
                frameModeHistory.recordPrevious(selectedChoice.position)
            } else {
                frameModeHistory.record(selectedChoice.position)
                if (mode == FrameMode.MIXED) {
                    mixedSlide++
                    if (index.categoryAt(selectedChoice.position) == FrameCategory.ART) lastArtAt = android.os.SystemClock.elapsedRealtime()
                }
            }
            presentSelectedMedia(selected, previous, navigation, restoreHistoryPair = selectedChoice.fromHistory)
        }
    }

    private fun presentSelectedMedia(
        selected: AerialMedia,
        previous: Boolean,
        navigation: Long,
        restoreHistoryPair: Boolean = false,
    ) {
        playlistReadFailures = 0
        var media = selected

        if (frameModeActive && restoreHistoryPair) {
            val restored =
                portraitSlidesByEnd.entries.firstOrNull { (end, slide) ->
                    slide.start == playlist.currentPosition && slide.first.uri == media.uri &&
                        (slide.start + if (slide.third == null) 1 else 2) % playlist.size == end &&
                        frameModeUris.getOrNull(end) == (slide.third ?: slide.second).uri.toString() &&
                        (
                            slide.third == null || frameModeUris.getOrNull(
                                (slide.start + 1) % playlist.size,
                            ) == slide.second.uri.toString()
                        ) &&
                        !isExcludedFromRotation(slide.first) && !isExcludedFromRotation(slide.second) &&
                        (slide.third == null || !isExcludedFromRotation(slide.third)) &&
                        (slide.third == null || !isTripleRejected(slide.first, slide.second, slide.third)) &&
                        !isPairRejected(slide.first, slide.second)
                }
            if (restored != null) {
                playlist.restoreCursor(restored.key)
                loadItem(restored.value.first, restored.value)
                preloadNextFrameItem()
                return
            }
        }

        var pair: PortraitSlide? = null
        if (previous && !frameModeActive) {
            if (pair == null) {
                val namespace = runCatching { KrillSessionManager(context).cachedSession()?.cacheNamespace }.getOrNull()
                val end = playlist.currentPosition
                val start = namespace?.let { portraitHistory.findStart(it, end, media.uri.toString()) }
                if (namespace != null && start != null && playlist.size >= 2 &&
                    ((start + 1) % playlist.size == end || (playlist.size >= 3 && (start + 2) % playlist.size == end))
                ) {
                    val last = media
                    imagePlayer.stopTimerForTransition()
                    mainScope.launch {
                        val first = withContext(Dispatchers.IO) { runCatching { playlist.itemAt(start) }.getOrNull() }
                        if (isStopped || navigation != navigationGeneration || playlist.currentPosition != end) return@launch
                        val tripleHistory = playlist.size >= 3 && (start + 2) % playlist.size == end
                        val middle =
                            if (tripleHistory) {
                                withContext(Dispatchers.IO) { runCatching { playlist.itemAt((start + 1) % playlist.size) }.getOrNull() }
                            } else {
                                null
                            }
                        if (isStopped || navigation != navigationGeneration || playlist.currentPosition != end) return@launch
                        val second = middle ?: last
                        val restored =
                            if (first != null && portraitHistory.matchesFirst(namespace, end, first.uri.toString()) &&
                                !isExcludedFromRotation(first) && !isExcludedFromRotation(second) && !isExcludedFromRotation(last) &&
                                !isPairRejected(first, second) && (
                                    !tripleHistory ||
                                        (
                                            middle != null && portraitHistory.matchesSecond(namespace, end, middle.uri.toString()) &&
                                                !isTripleRejected(first, middle, last) &&
                                                imagePlayer.canCompletePortraitTriple(first, middle, last)
                                        )
                                )
                            ) {
                                PortraitSlide(start, first, second, if (middle != null) last else null)
                            } else {
                                null
                            }
                        loadItem(restored?.first ?: last, restored)
                        preloadNextFrameItem()
                    }
                    return
                }
            }
            if (pair != null) media = pair.first
        } else if (!previous) {
            val start = playlist.currentPosition
            val first = media
            val next = playlist.peekNextItem { true }?.takeIf { !isExcludedFromRotation(it) }
            // Art is shown whole, never cropped into a collage panel (UNS-1750).
            val compatibleMode =
                next != null &&
                    FrameModePolicy.category(first.metadata.tags, first.metadata.tagsKnown) != FrameCategory.ART &&
                    FrameModePolicy.category(next.metadata.tags, next.metadata.tagsKnown) != FrameCategory.ART && (
                        !frameModeActive ||
                            FrameModePolicy.category(first.metadata.tags, first.metadata.tagsKnown) ==
                            FrameModePolicy.category(next.metadata.tags, next.metadata.tagsKnown)
                    )
            if (first.metadata.pairedWithNext != false && next != null && compatibleMode &&
                KrillPrivateFeed.isFrameImage(first.uri.toString()) && KrillPrivateFeed.isFrameImage(next.uri.toString())
            ) {
                imagePlayer.stopTimerForTransition()
                mainScope.launch {
                    val compatible = imagePlayer.probePortraitPair(first, next)
                    if (isStopped || navigation != navigationGeneration || playlist.currentPosition != start) return@launch
                    var chosen: PortraitSlide? = null
                    if (compatible && !isExcludedFromRotation(first) && !isExcludedFromRotation(next) && !isPairRejected(first, next)) {
                        val candidate =
                            try {
                                playlist.nextItem()
                            } catch (_: MediaPlaylistContentionException) {
                                null
                            } catch (_: MediaPlaylistFetchException) {
                                null
                            }
                        if (candidate?.uri == next.uri) {
                            var third: AerialMedia? = null
                            val possibleThird = playlist.peekNextItem { true }
                            val thirdModeMatches =
                                possibleThird != null &&
                                    FrameModePolicy.category(possibleThird.metadata.tags, possibleThird.metadata.tagsKnown) !=
                                    FrameCategory.ART && (
                                        !frameModeActive ||
                                            FrameModePolicy.category(first.metadata.tags, first.metadata.tagsKnown) ==
                                            FrameModePolicy.category(possibleThird.metadata.tags, possibleThird.metadata.tagsKnown)
                                    )
                            if (possibleThird != null && thirdModeMatches && !isExcludedFromRotation(possibleThird) &&
                                !isTripleRejected(first, candidate, possibleThird) &&
                                imagePlayer.canCompletePortraitTriple(first, candidate, possibleThird)
                            ) {
                                val pairEnd = playlist.currentPosition
                                val consumed =
                                    try {
                                        playlist.nextItem()
                                    } catch (
                                        _: MediaPlaylistContentionException,
                                    ) {
                                        null
                                    } catch (_: MediaPlaylistFetchException) {
                                        null
                                    }
                                if (consumed?.uri == possibleThird.uri) {
                                    third = consumed
                                } else if (playlist.currentPosition != pairEnd) {
                                    playlist.restoreCursor(pairEnd)
                                }
                            }
                            chosen = PortraitSlide(start, first, candidate, third)
                            if (portraitSlidesByEnd.size >= 200) {
                                portraitSlidesByEnd.keys.firstOrNull()?.let { portraitSlidesByEnd.remove(it) }
                            }
                            portraitSlidesByEnd[playlist.currentPosition] = chosen
                            runCatching { KrillSessionManager(context).cachedSession()?.cacheNamespace }.getOrNull()?.let { namespace ->
                                portraitHistory.save(
                                    namespace,
                                    playlist.currentPosition,
                                    start,
                                    first.uri.toString(),
                                    candidate.uri.toString(),
                                    third?.uri?.toString(),
                                )
                            }
                        } else if (playlist.currentPosition != start) {
                            playlist.restoreCursor(start)
                        }
                    }
                    loadItem(first, chosen)
                    preloadNextFrameItem()
                }
                return
            }
        }
        loadItem(media, pair)
        preloadNextFrameItem()
    }

    private fun preloadNextFrameItem() {
        frameModePreload = null
        if (!frameModeActive) {
            imagePlayer.preloadNext(playlist.peekNextItem { !isExcludedFromRotation(it) })
            return
        }
        val index = frameModeIndex ?: return
        val source = playlist
        val origin = source.currentPosition
        val generation = navigationGeneration
        val mode = frameMode
        val slide = mixedSlide
        val position =
            frameModeHistory.peekNext()
                ?: index.next(mode, origin, false, mixedTarget())
                ?: return
        val expectedUri = frameModeUris.getOrNull(position) ?: return
        mainScope.launch {
            val next =
                withContext(Dispatchers.IO) {
                    val session = runCatching { KrillSessionManager(context).cachedSession() }.getOrNull()
                    val lease = session?.let { KrillFrameCache.fence.acquire(it.cacheNamespace) }
                    if (session != null && lease == null) return@withContext null
                    try {
                        val job = currentCoroutineContext()[Job]!!
                        lease?.onCancel { job.cancel() }
                        val media = runCatching { source.itemAt(position) }.getOrNull()
                        job.ensureActive()
                        media?.takeIf {
                            it.uri.toString() == expectedUri &&
                                FrameModePolicy.category(it.metadata.tags, it.metadata.tagsKnown) == index.categoryAt(position) &&
                                !isExcludedFromRotation(it, session)
                        }
                    } finally {
                        lease?.close()
                    }
                }
            if (!isStopped && source === playlist && origin == source.currentPosition && generation == navigationGeneration &&
                frameModeActive && mode == frameMode && slide == mixedSlide && screenCacheEpoch == KrillFrameCache.epoch
            ) {
                frameModePreload = next
                imagePlayer.preloadNext(next)
            }
        }
    }

    /** Build an index outside the player window, fenced to the active private library session. */
    private fun buildFrameModeIndex() {
        if (frameModeBuildJob?.isActive == true) return
        val sourcePlaylist = playlist
        frameModeBuildJob =
            mainScope.launch {
                try {
                    val result =
                        withContext(Dispatchers.IO) {
                            val namespace = KrillSessionManager(context).cachedSession()?.cacheNamespace
                            val lease = namespace?.let { KrillFrameCache.fence.acquire(it) }
                            if (namespace != null && lease == null) return@withContext null
                            try {
                                val job = currentCoroutineContext()[Job]!!
                                lease?.onCancel { job.cancel() }
                                val revision = sourcePlaylist.metadataRevision
                                val projection =
                                    playlistGate.load(playlistGateOwner) {
                                        sourcePlaylist.snapshotProjection { media ->
                                            val category = FrameModePolicy.category(media.metadata.tags, media.metadata.tagsKnown)
                                            media.uri.toString() to category
                                        }
                                    }
                                job.ensureActive()
                                check(revision == sourcePlaylist.metadataRevision) { "Metadata changed while building frame modes" }
                                if (screenCacheEpoch != KrillFrameCache.epoch || (lease != null && !lease.isValid())) {
                                    return@withContext null
                                }
                                projection to revision
                            } finally {
                                lease?.close()
                            }
                        }
                    if (!isStopped && sourcePlaylist === playlist && screenCacheEpoch == KrillFrameCache.epoch && result != null &&
                        result.second == sourcePlaylist.metadataRevision
                    ) {
                        frameModeUris = result.first.map { it.first }
                        frameModeIndex = FrameModeIndex(result.first.map { it.second })
                        frameModeMetadataRevision = result.second
                        frameModeBuildFailures = 0
                        val artCount = result.first.count { it.second == FrameCategory.ART }
                        Timber.i("Frame mode index ready: ${result.first.size} items, $artCount tagged as art")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    frameModeBuildFailures++
                    Timber.w("Frame mode index deferred")
                }
            }
    }

    private fun savePlaybackPosition() {
        if (this::playlist.isInitialized && GeneralPrefs.playlistCache && playlistBackedByCache) {
            mainScope.launch {
                playlistGate.load(playlistGateOwner) {
                    if (playlistGate.canRewrite(playlistGateOwner)) {
                        Timber.d("PlaylistCache: Saving playback position: ${playlist.currentPosition}")
                        val position = playlist.currentPosition
                        saveSessionBound { cacheRepository.saveMediaPosition(position) }
                    }
                }
            }
        }
    }

    private fun saveMusicTrackPosition() {
        if (GeneralPrefs.playlistCache && playlistBackedByCache) {
            mainScope.launch {
                playlistGate.load(playlistGateOwner) {
                    if (playlistGate.canRewrite(playlistGateOwner)) {
                        val index = musicPlayer?.getCurrentTrackIndex()
                        if (index != null) saveSessionBound { cacheRepository.saveMusicTrackIndex(index) }
                    }
                }
            }
        }
    }

    private suspend fun saveSessionBound(block: suspend () -> Unit) =
        withContext(Dispatchers.IO) {
            try {
                KrillFrameCache.withSessionGate(context) {
                    if (screenCacheEpoch == KrillFrameCache.epoch) runBlocking(Dispatchers.IO) { block() }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Timber.w("Playback position was not saved")
            }
        }

    fun stop() {
        if (isStopped) return
        isStopped = true
        frameModeIndex = null
        frameModeUris = emptyList()
        frameModePreload = null
        frameModeHistory.clear()
        frameModeBuildJob?.cancel()
        frameModeChangeJob?.cancel()
        dashboardScheduleJob?.cancel()
        setOfflineIndicator(false)
        closeDashboard()
        navigationGeneration++
        KrillFrameCache.removeInvalidationListener(cacheInvalidation)

        val saveFinalPosition = GeneralPrefs.playlistCache && playlistBackedByCache
        val finalTrackIndex = if (saveFinalPosition) musicPlayer?.getCurrentTrackIndex() ?: 0 else 0
        val finalPosition = if (saveFinalPosition && this::playlist.isInitialized) playlist.currentPosition else null
        RefreshRateHelper.restoreOriginalMode(context)
        overlayEventBridge.stop()
        // Release before detaching: View.removeCallbacks() does nothing once mAttachInfo is null,
        // which would leave pending delayed messages holding the view and the Activity alive.
        videoPlayer.release()
        // Remove video view from parent to break context reference chain
        val videoParent = videoViewBinding.root.parent as? ViewGroup
        videoParent?.removeView(videoViewBinding.root)
        imagePlayer.release()
        KtorServer.stop()
        nowPlayingService?.stop()
        sonosService?.stop()
        weatherService?.stop()
        musicPlayer?.pause()
        musicPlayer?.release()
        sleepTimerJob?.cancel()
        scheduledBlackoutJob?.cancel()
        metadataJobs.values.forEach { it.cancel() }
        metadataJobs.clear()
        mainScope.cancel()
        if (saveFinalPosition) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    playlistGate.load(playlistGateOwner) {
                        try {
                            if (playlistGate.canRewrite(playlistGateOwner)) {
                                saveSessionBound {
                                    if (finalPosition != null) cacheRepository.saveMediaPosition(finalPosition)
                                    cacheRepository.saveMusicTrackIndex(finalTrackIndex)
                                }
                            }
                        } finally {
                            playlistGateOwner.close()
                        }
                    }
                } catch (_: Exception) {
                    Timber.w("Final playback position was not saved")
                } finally {
                    playlistGateOwner.close()
                }
            }
        } else {
            playlistGateOwner.close()
        }
    }

    fun skipItem(previous: Boolean = false) {
        if (dashboardView != null) return
        playlistReadFailures = 0
        explicitSkip = true
        previousItem = previous
        fadeOutCurrentItem()
    }

    private fun rotationKey(media: AerialMedia): String? {
        if (media.source != AerialMediaSource.KRILL) return null
        val segments = media.uri.pathSegments
        if (segments.size == 5 && segments.take(3) == listOf("api", "frame", "media") && segments[4] == "image") {
            val space = media.uri.getQueryParameter("space") ?: return null
            return "${media.uri.scheme}://${media.uri.authority}/frame/$space/${segments[3]}"
        }
        if (segments.size < 3 || segments.last() != "public") return null
        return "${media.uri.scheme}://${media.uri.authority}/${segments[segments.lastIndex - 1]}"
    }

    private fun isExcludedFromRotation(media: AerialMedia): Boolean {
        val session = runCatching { KrillSessionManager(context).cachedSession() }.getOrNull()
        return isExcludedFromRotation(media, session)
    }

    private fun isExcludedFromRotation(
        media: AerialMedia,
        session: KrillTokenStore.Session?,
    ): Boolean {
        if (rotationKey(media)?.let { excludedPhotos.getBoolean(it, false) } == true) return true
        val url = media.uri.toString()
        if ((KrillPrivateFeed.isFrameImage(url) || KrillPrivateFeed.isFrameVideo(url)) &&
            !KrillPrivateFeed.isAuthorizedFrameMedia(url, session?.libraryOrigin, session?.spaceId)
        ) {
            return true
        }
        val pending = session?.pendingDeletion
        return pending != null && KrillPrivateFeed.sameFramePhoto(pending.imageUrl, media.uri.toString())
    }

    private fun isPairRejected(
        first: AerialMedia,
        second: AerialMedia,
    ): Boolean {
        val namespace = runCatching { KrillSessionManager(context).cachedSession()?.cacheNamespace }.getOrNull() ?: return true
        return portraitHistory.isPairRejected(namespace, first.uri.toString(), second.uri.toString())
    }

    private fun isTripleRejected(
        first: AerialMedia,
        second: AerialMedia,
        third: AerialMedia,
    ): Boolean {
        val namespace = runCatching { KrillSessionManager(context).cachedSession()?.cacheNamespace }.getOrNull() ?: return true
        return portraitHistory.isTripleRejected(namespace, first.uri.toString(), second.uri.toString(), third.uri.toString())
    }

    private fun isPrivateMediaAuthorized(media: AerialMedia): Boolean {
        val url = media.uri.toString()
        if (!KrillPrivateFeed.isFrameImage(url) && !KrillPrivateFeed.isFrameVideo(url)) return true
        return runCatching {
            val session = KrillSessionManager(context).cachedSession()
            KrillPrivateFeed.isAuthorizedFrameMedia(url, session?.libraryOrigin, session?.spaceId)
        }.getOrDefault(false)
    }

    /** Opens a local rotation menu. Removing an item does not delete it from Krill. */
    fun openPhotoOptions(): Boolean {
        val media = currentMedia ?: return false
        val video =
            media.type == AerialMediaType.VIDEO && KrillPrivateFeed.isFrameVideo(media.uri.toString()) && isPrivateMediaAuthorized(media)
        if (!canSkip || (!video && rotationKey(media) == null) || photoMenu != null) return false
        val parent = view as? FrameLayout ?: return false
        photoMenuWasPaused = isPaused
        if (!isPaused) pauseMedia()
        photoMenuHasPair = currentPortraitSlide != null
        photoMenuIsVideo = video
        // Permanent deletion belongs to the phone library, not the TV remote.
        ignorePhotoMenuRelease = true
        val density = resources.displayMetrics.density
        val panel =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding((32 * density).toInt(), (24 * density).toInt(), (32 * density).toInt(), (24 * density).toInt())
                setBackgroundColor(Color.argb(235, 20, 20, 20))
            }

        fun row(text: Int): TextView =
            TextView(context).apply {
                setText(text)
                textSize = 22f
                setTextColor(Color.WHITE)
                setPadding(24, 18, 24, 18)
            }
        if (video) {
            panel.addView(row(R.string.frame_video_options))
            panel.addView(
                row(
                    if (forcedMuteVideo) {
                        R.string.frame_video_music_active
                    } else if (videoPlayer.mutedState) {
                        R.string.frame_video_unmute
                    } else {
                        R.string.frame_video_mute
                    },
                ),
            )
        } else {
            panel.addView(row(R.string.frame_photo_options))
            panel.addView(row(if (photoMenuHasPair) R.string.frame_remove_left_photo else R.string.frame_remove_from_rotation))
            if (photoMenuHasPair) panel.addView(row(R.string.frame_remove_right_photo))
            if (photoMenuHasPair) panel.addView(row(R.string.frame_split_pair))
            panel.addView(row(R.string.frame_restore_rotation))
            panel.addView(row(R.string.frame_reset_pairing_choices))
        }
        panel.addView(row(R.string.frame_cancel))
        photoMenuSelection = panel.childCount - 2 // Default to cancel.
        parent.addView(
            panel,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        photoMenu = panel
        updatePhotoMenuSelection()
        return true
    }

    private fun updatePhotoMenuSelection() {
        val panel = photoMenu as? LinearLayout ?: return
        for (index in 0 until panel.childCount - 1) {
            val row = panel.getChildAt(index + 1) as TextView
            row.setTextColor(if (index == photoMenuSelection) Color.BLACK else Color.WHITE)
            row.setBackgroundColor(if (index == photoMenuSelection) Color.WHITE else Color.TRANSPARENT)
        }
        (panel.getChildAt(0) as TextView).setTextColor(Color.WHITE)
    }

    /** Returns true when the photo menu owns the event. */
    fun handlePhotoOptionsKey(event: KeyEvent): Boolean {
        if (photoMenu == null) return false
        if (event.action != KeyEvent.ACTION_UP) return true
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER && ignorePhotoMenuRelease) {
            ignorePhotoMenuRelease = false
            return true
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                photoMenuSelection = (photoMenuSelection - 1).coerceAtLeast(0)
                updatePhotoMenuSelection()
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                photoMenuSelection = (photoMenuSelection + 1).coerceAtMost((photoMenu as LinearLayout).childCount - 2)
                updatePhotoMenuSelection()
            }

            KeyEvent.KEYCODE_BACK -> {
                closePhotoOptions(false)
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (photoMenuIsVideo) {
                    val toggle = photoMenuSelection == 0 && !forcedMuteVideo
                    closePhotoOptions(false)
                    if (toggle) toggleMute()
                    return true
                }
                val restoreIndex = if (photoMenuHasPair) 3 else 1
                if (photoMenuSelection == restoreIndex) excludedPhotos.edit().clear().apply()
                val resetIndex = if (photoMenuHasPair) 4 else 2
                if (photoMenuSelection == resetIndex) {
                    runCatching { KrillSessionManager(context).cachedSession()?.cacheNamespace }.getOrNull()?.let {
                        portraitHistory.resetRejections(it)
                    }
                }
                val separate = photoMenuHasPair && photoMenuSelection == 2
                val removed =
                    when {
                        photoMenuSelection == 0 -> currentMedia
                        photoMenuHasPair && photoMenuSelection == 1 -> currentPortraitSlide?.second
                        else -> null
                    }
                closePhotoOptions(false)
                if (separate) showCurrentPairSeparately()
                if (removed != null) {
                    rotationKey(removed)?.let { excludedPhotos.edit().putBoolean(it, true).apply() }
                    skipItem()
                }
            }
        }
        return true
    }

    /** Check for caption updates while this screen remains active. */
    private suspend fun refreshCaptionsWhileActive() {
        runKrillCaptionSchedule {
            if (GeneralPrefs.wifiOnly && !NetworkHelper.isOnWifiOrEthernet(context)) return@runKrillCaptionSchedule
            val session = withContext(Dispatchers.IO) { runCatching { KrillSessionManager(context).cachedSession() }.getOrNull() }
            if (session?.spaceId == null) return@runKrillCaptionSchedule
            val refresh = KrillCachedMetadataRefresh.refreshCaptions(context.applicationContext, session, playlist)
            try {
                refresh.join()
            } finally {
                refresh.cancel()
            }
        }
    }

    /** Refresh metadata in an in-memory playlist without replacing the current slide. */
    private suspend fun refreshUncachedKrillMetadataWhileActive() {
        var namespace: String? = null
        var previousRevision: String? = null
        var previousVideoAvailable: Boolean? = null
        var lastFullAt = 0L
        var lastCheckedAt = 0L
        runKrillCaptionSchedule {
            if (GeneralPrefs.wifiOnly && !NetworkHelper.isOnWifiOrEthernet(context)) return@runKrillCaptionSchedule
            val now = System.currentTimeMillis()
            if (now >= lastCheckedAt && now - lastCheckedAt < 4 * 60 * 1000L) return@runKrillCaptionSchedule
            try {
                val updates =
                    withContext(Dispatchers.IO) {
                        val job = currentCoroutineContext()[Job]!!
                        val client = cancellableKrillClient(job)
                        val session = KrillSessionManager(context).session(client) { job.ensureActive() } ?: return@withContext null
                        val space = session.spaceId ?: return@withContext null
                        val lease = KrillFrameCache.fence.acquire(session.cacheNamespace) ?: return@withContext null
                        try {
                            lease.onCancel { job.cancel() }
                            val config = KrillPrivateFeed.config(client, session.libraryOrigin)
                            val feed = KrillPrivateFeed(client, session.libraryOrigin, session.accessToken)
                            val revision = if (config.libraryRevisionAvailable) feed.libraryRevision(space) else null
                            if (config.libraryRevisionAvailable && revision == null) return@withContext null
                            val sameLibrary = namespace == session.cacheNamespace
                            val recentFull = now >= lastFullAt && now - lastFullAt < 24 * 60 * 60 * 1000L
                            if (sameLibrary && recentFull && revision != null && revision == previousRevision &&
                                previousVideoAvailable == config.videoAvailable
                            ) {
                                lastCheckedAt = now
                                return@withContext null
                            }
                            val snapshot = KrillPrivateProvider(context).fetchWithEvidence()
                            val result =
                                snapshot.result as? ProviderFetchResult.Success
                                    ?: return@withContext null
                            val evidence = snapshot.evidence ?: return@withContext null
                            if (evidence.namespace != session.cacheNamespace || evidence.revision != revision ||
                                evidence.videoAvailable != config.videoAvailable
                            ) {
                                return@withContext null
                            }
                            val activeNamespace = KrillSessionManager(context).cachedSession()?.cacheNamespace
                            val revisionChanged = revision != null && feed.libraryRevision(space) != revision
                            val capabilityChanged =
                                KrillPrivateFeed.config(client, session.libraryOrigin).videoAvailable != config.videoAvailable
                            val wrongSession = activeNamespace != session.cacheNamespace
                            if (!lease.isValid() || wrongSession || revisionChanged || capabilityChanged) return@withContext null
                            namespace = session.cacheNamespace
                            previousRevision = revision
                            previousVideoAvailable = config.videoAvailable
                            lastFullAt = now
                            lastCheckedAt = now
                            result.media.filter {
                                val uri = it.uri.toString()
                                it.source == AerialMediaSource.KRILL &&
                                    (
                                        KrillPrivateFeed.isSelectedImage(uri, session.libraryOrigin, space) ||
                                            KrillPrivateFeed.isSelectedVideo(uri, session.libraryOrigin, space)
                                    )
                            }
                        } finally {
                            lease.close()
                        }
                    }
                if (updates != null && !isStopped && screenCacheEpoch == KrillFrameCache.epoch &&
                    runCatching { KrillSessionManager(context).cachedSession()?.cacheNamespace }.getOrNull() == namespace
                ) {
                    playlist.updateCachedCaptions(updates)
                    playlist.updateCachedPairing(updates)
                    playlist.appendUncachedKrillMedia(updates)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Timber.w("Krill in-memory metadata refresh deferred")
            }
        }
    }

    /** Retry a saved operation when a screen starts and until the server confirms it. */
    private suspend fun recoverPendingDeletion() {
        while (true) {
            try {
                withContext(Dispatchers.IO) {
                    val job = currentCoroutineContext()[Job]
                    val client = deletionKrillClient(job)
                    KrillFrameCache.retryDeletionCleanup(context.applicationContext)
                    val session = KrillSessionManager(context).cachedSession()
                    val pending = session?.pendingDeletion
                    if (session != null && pending != null) {
                        val request = KrillDeletionRequest(context.applicationContext, client) { job?.ensureActive() }
                        if (request.reconcile(session.cacheNamespace) == "unknown") {
                            request.submit(session.cacheNamespace, pending.imageUrl)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the operation for the next attempt, including after restart.
            }
            delay(30_000)
        }
    }

    private fun showCurrentPairSeparately() {
        val slide = currentPortraitSlide ?: return
        val namespace = runCatching { KrillSessionManager(context).cachedSession()?.cacheNamespace }.getOrNull() ?: return
        if (slide.third == null) {
            portraitHistory.rejectPair(namespace, slide.first.uri.toString(), slide.second.uri.toString())
        } else {
            portraitHistory.rejectTriple(namespace, slide.first.uri.toString(), slide.second.uri.toString(), slide.third.uri.toString())
        }
        pauseAfterNextImage = photoMenuWasPaused
        canSkip = false
        photoTransition = imagePlayer.beginPhotoTransition()
        onPortraitPairFailed()
        currentLoadIsPrevious = false
        loadItem(slide.first)
        preloadNextFrameItem()
    }

    private fun closePhotoOptions(remove: Boolean) {
        (photoMenu?.parent as? ViewGroup)?.removeView(photoMenu)
        photoMenu = null
        if (remove) {
            currentMedia?.let { media -> rotationKey(media)?.let { excludedPhotos.edit().putBoolean(it, true).apply() } }
            if (!photoMenuWasPaused) resumeMedia()
            skipItem()
        } else if (!photoMenuWasPaused) {
            resumeMedia()
        }
    }

    fun toggleBlackOutMode(source: BlackOutSource = BlackOutSource.USER) {
        if (!this::playlist.isInitialized || playlist.size == 0) {
            return
        }

        if (!blackOutMode) {
            enterBlackOutMode(source)
        } else if (
            blackOutSource != BlackOutSource.SCHEDULED || source == BlackOutSource.SCHEDULED
        ) {
            exitBlackOutMode()
        }
    }

    /**
     * Enters blackout immediately, including during initial media preparation. The normal
     * fade-out path intentionally requires a fully displayed item, which made scheduled
     * blackout ineffective when the first item was still loading.
     */
    private fun enterBlackOutMode(source: BlackOutSource) {
        blackOutMode = true
        dashboardDeferredItem = null
        dashboardDeferredPrevious = null
        dashboardSchedulePending = lastDashboardScheduleState == true && !dashboardManuallyClosed
        dashboardRetryAt = 0L
        closeDashboard()
        photoTransition = false
        blackOutSource = source
        sleepTimerJob?.cancel()
        canSkip = false

        loadingView.animate().cancel()
        loadingView.setBackgroundColor(Color.BLACK)
        loadingContainer.visibility = View.GONE
        loadingView.alpha = 1f
        loadingView.visibility = View.VISIBLE

        videoViewBinding.root.visibility = View.INVISIBLE
        videoViewBinding.videoPlayer.stop()
        imageViewBinding.root.visibility = View.INVISIBLE
        imageViewBinding.imagePlayer.stop()

        // The loading view sits below these layers, so hide them for a true blackout.
        overlayView.visibility = View.INVISIBLE
        progressBarView.visibility = View.INVISIBLE
        brightnessView.visibility = View.INVISIBLE
        notificationContainer.visibility = View.INVISIBLE
        musicPlayer?.pause()
    }

    private fun exitBlackOutMode() {
        blackOutMode = false
        blackOutSource = BlackOutSource.NONE
        loadingView.setBackgroundColor(ColourHelper.colourFromString(GeneralPrefs.backgroundLoading))
        overlayView.visibility = View.VISIBLE
        progressBarView.visibility =
            if (GeneralPrefs.progressBarLocation == ProgressBarLocation.DISABLED) View.GONE else View.VISIBLE
        brightnessView.visibility =
            if (GeneralPrefs.videoBrightness == "100") View.GONE else View.VISIBLE
        notificationContainer.visibility = View.VISIBLE
        loadNextItem()
        musicPlayer?.resume()
        scheduleSleepTimer()
    }

    fun nextTrack() {
        val music = musicPlayer
        if (music != null && music.hasMusic()) {
            music.nextTrack()
            saveMusicTrackPosition()
        } else {
            nowPlayingService?.nextTrack()
        }
    }

    fun previousTrack() {
        val music = musicPlayer
        if (music != null && music.hasMusic()) {
            music.previousTrack()
            saveMusicTrackPosition()
        } else {
            nowPlayingService?.previousTrack()
        }
    }

    fun increaseSpeed() {
        if (blackOutMode) {
            return
        }
        videoPlayer.increaseSpeed()
    }

    fun decreaseSpeed() {
        if (blackOutMode) {
            return
        }
        videoPlayer.decreaseSpeed()
    }

    fun seekForward() {
        if (blackOutMode) {
            return
        }
        videoPlayer.seekForward()
    }

    fun seekBackward() {
        if (blackOutMode) {
            return
        }
        videoPlayer.seekBackward()
    }

    fun togglePause() {
        if (dashboardView != null) return
        if (isPaused) {
            resumeMedia()
        } else {
            pauseMedia()
        }
    }

    /** Respect configured directional actions, otherwise use up/down to cycle frame modes. */
    fun handleDashboardKey(event: KeyEvent): Boolean {
        if (blackOutMode) return false
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (dashboardView == null) {
                val press = if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) GeneralPrefs.buttonUpPress else GeneralPrefs.buttonDownPress
                val hold = if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) GeneralPrefs.buttonUpHold else GeneralPrefs.buttonDownHold
                if (press != ButtonType.IGNORE || hold != ButtonType.IGNORE) return false
            }
            if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
                changeFrameMode(event.keyCode == KeyEvent.KEYCODE_DPAD_UP)
            }
            return true
        }
        if (dashboardView == null) return false
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                dashboardRetryAt = 0L
                dashboardManuallyClosed = true
                closeDashboard()
            }
            return true
        }
        val direction = DashboardConfig.navDirection(event.keyCode) ?: return false
        if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
            dashboardView?.evaluateJavascript(DashboardConfig.navScript(direction), null)
        }
        return true
    }

    private fun mixedTarget(): FrameCategory =
        FrameModePolicy.mixedTarget(
            runCatching { ArtCadence.valueOf(GeneralPrefs.frameArtCadence) }.getOrDefault(ArtCadence.PERCENT),
            GeneralPrefs.frameArtAmount.trim().toIntOrNull() ?: 30,
            mixedSlide,
            android.os.SystemClock.elapsedRealtime() - lastArtAt,
        )

    private fun changeFrameMode(forward: Boolean) {
        val index = frameModeIndex
        if (index == null || frameModeMetadataRevision != playlist.metadataRevision) {
            if (this::playlist.isInitialized) buildFrameModeIndex()
            val message = if (frameModeBuildFailures >= 3) R.string.frame_mode_unavailable else R.string.frame_mode_loading
            NotificationHelper.show(notificationContainer, resources.getString(message))
            return
        }
        val modes = mutableListOf(FrameMode.PHOTOS)
        if (index.has(FrameCategory.ART)) modes.add(FrameMode.ART)
        modes.add(FrameMode.MIXED)
        if (DashboardConfig.validUrl(GeneralPrefs.dashboardUrl.trim())) modes.add(FrameMode.DASHBOARD)
        val current = if (dashboardView != null) FrameMode.DASHBOARD else frameMode
        val currentIndex = modes.indexOf(current).coerceAtLeast(0)
        val next =
            if (!frameModeActive && dashboardView == null) {
                if (forward) modes.first() else modes.last()
            } else {
                modes[Math.floorMod(currentIndex + if (forward) 1 else -1, modes.size)]
            }
        if (next == FrameMode.DASHBOARD) {
            dashboardManuallyClosed = false
            showDashboard()
            if (dashboardView != null) {
                Timber.i("Frame mode selected: DASHBOARD")
            } else {
                NotificationHelper.show(notificationContainer, resources.getString(R.string.frame_mode_loading))
            }
            return
        }
        dashboardManuallyClosed = dashboardView != null
        dashboardRetryAt = 0L
        closeDashboard()
        frameModeChangeJob?.cancel()
        frameModeChangeJob =
            mainScope.launch {
                while (!canSkip && !isStopped && !blackOutMode) delay(100)
                if (isStopped || blackOutMode || dashboardView != null) return@launch
                frameMode = next
                frameModeActive = true
                mixedSlide = 0
                lastArtAt = android.os.SystemClock.elapsedRealtime()
                frameModePreload = null
                Timber.i("Frame mode selected: $next")
                frameModeHistory.clear()
                val currentPosition = currentPortraitSlide?.start ?: playlist.currentPosition
                val currentCategory = index.categoryAt(currentPosition)
                if (currentCategory != null && FrameModePolicy.accepts(next, currentCategory)) {
                    frameModeHistory.record(currentPosition)
                }
                val label =
                    when (next) {
                        FrameMode.PHOTOS -> R.string.frame_mode_photos
                        FrameMode.ART -> R.string.frame_mode_art
                        FrameMode.MIXED -> R.string.frame_mode_mixed
                        FrameMode.DASHBOARD -> R.string.frame_mode_dashboard
                    }
                NotificationHelper.show(notificationContainer, resources.getString(label))
                skipItem()
            }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun showDashboard() {
        val url = GeneralPrefs.dashboardUrl.trim()
        if (dashboardView != null || !DashboardConfig.validUrl(url) || isStopped || blackOutMode || !canSkip) return
        dashboardRetryAt = 0L
        dashboardWasPaused = isPaused
        if (!isPaused) pauseMedia()
        dashboardMusicPaused = musicPlayer?.isPlaybackRequested == true
        if (dashboardMusicPaused) musicPlayer?.pause()
        val webView = android.webkit.WebView(context)
        dashboardView = webView
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        android.webkit.CookieManager
            .getInstance()
            .setAcceptThirdPartyCookies(webView, false)
        webView.webViewClient =
            object : android.webkit.WebViewClient() {
                override fun shouldInterceptRequest(
                    view: android.webkit.WebView,
                    request: android.webkit.WebResourceRequest,
                ): android.webkit.WebResourceResponse? {
                    if (request.isForMainFrame && !DashboardConfig.sameOrigin(url, request.url.toString())) {
                        view.post { dashboardLoadFailed(view, retry = false) }
                        return android.webkit.WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                    return null
                }

                override fun shouldOverrideUrlLoading(
                    view: android.webkit.WebView,
                    request: android.webkit.WebResourceRequest,
                ): Boolean {
                    val blocked = request.isForMainFrame && !DashboardConfig.sameOrigin(url, request.url.toString())
                    if (blocked) view.post { dashboardLoadFailed(view, retry = false) }
                    return blocked
                }

                override fun onReceivedSslError(
                    view: android.webkit.WebView,
                    handler: android.webkit.SslErrorHandler,
                    error: android.net.http.SslError,
                ) {
                    handler.cancel()
                    view.post { dashboardLoadFailed(view, retry = false) }
                }

                override fun onReceivedError(
                    view: android.webkit.WebView,
                    request: android.webkit.WebResourceRequest,
                    error: android.webkit.WebResourceError,
                ) {
                    if (request.isForMainFrame) view.post { dashboardLoadFailed(view) }
                }

                override fun onReceivedHttpError(
                    view: android.webkit.WebView,
                    request: android.webkit.WebResourceRequest,
                    errorResponse: android.webkit.WebResourceResponse,
                ) {
                    if (request.isForMainFrame) view.post { dashboardLoadFailed(view, retry = errorResponse.statusCode >= 500) }
                }
            }
        (view as FrameLayout).addView(webView, FrameLayout.LayoutParams(-1, -1))
        webView.loadUrl(url)
    }

    private fun isOnline(): Boolean =
        NetworkHelper.getActiveNetworkCapabilities(context)?.let {
            it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true

    private var offlineIndicator: TextView? = null

    /** A small corner label while the frame is offline and playing from its cache. */
    private fun setOfflineIndicator(show: Boolean) {
        if (show == (offlineIndicator != null)) return
        val parent = view as? FrameLayout ?: return
        if (!show) {
            offlineIndicator?.let { parent.removeView(it) }
            offlineIndicator = null
            return
        }
        val density = resources.displayMetrics.density
        offlineIndicator =
            TextView(context).apply {
                setText(R.string.frame_offline)
                textSize = 14f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.argb(160, 0, 0, 0))
                setPadding((10 * density).toInt(), (4 * density).toInt(), (10 * density).toInt(), (4 * density).toInt())
                parent.addView(
                    this,
                    FrameLayout
                        .LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.TOP or Gravity.END,
                        ).apply { setMargins(0, (16 * density).toInt(), (16 * density).toInt(), 0) },
                )
            }
    }

    private fun closeDashboard() {
        val webView = dashboardView ?: return
        dashboardView = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.destroy()
        if (!isStopped && !blackOutMode) {
            if (dashboardMusicPaused) musicPlayer?.resume()
            dashboardMusicPaused = false
            val deferredItem = dashboardDeferredItem
            val deferredPrevious = dashboardDeferredPrevious
            dashboardDeferredItem = null
            dashboardDeferredPrevious = null
            when {
                deferredItem != null -> loadItem(deferredItem.first, deferredItem.second)
                deferredPrevious != null -> loadNextItem(deferredPrevious)
                !dashboardWasPaused -> resumeMedia()
            }
        }
    }

    private fun dashboardLoadFailed(
        failed: android.webkit.WebView,
        retry: Boolean = true,
    ) {
        if (dashboardView !== failed) return
        dashboardRetryAt = if (retry) android.os.SystemClock.elapsedRealtime() + 60_000 else 0L
        closeDashboard()
    }

    fun toggleLooping() {
        loopUntilSkipped = !loopUntilSkipped
        val message =
            if (loopUntilSkipped) {
                resources.getString(
                    R.string.playlist_loop_enabled,
                )
            } else {
                resources.getString(R.string.playlist_loop_disabled)
            }
        NotificationHelper.show(notificationContainer, message)
    }

    fun restartVideo() {
        if (blackOutMode || dashboardView != null) return
        if (currentMedia?.type != AerialMediaType.VIDEO) return
        // Restart resumes playback, so clear any paused state
        if (isPaused) resumeMedia()
        videoPlayer.restart()
    }

    fun increaseBrightness() = changeBrightness(true)

    fun decreaseBrightness() = changeBrightness(false)

    private fun changeBrightness(increase: Boolean) {
        if (blackOutMode) return

        val brightnessValues = resources.getStringArray(R.array.percentage1_values)
        val currentBrightness = GeneralPrefs.videoBrightness
        val currentIndex = brightnessValues.indexOf(currentBrightness)

        if (currentIndex == -1) return

        if (increase && currentIndex == brightnessValues.size - 1) return
        if (!increase && currentIndex == 0) return

        val newIndex = if (increase) currentIndex + 1 else currentIndex - 1
        val newBrightness = brightnessValues[newIndex]

        GeneralPrefs.videoBrightness = newBrightness

        // Update view
        val view = brightnessView
        if (newBrightness == "100") {
            view.visibility = View.GONE
        } else {
            view.setBackgroundColor(Color.BLACK)
            view.alpha = abs((newBrightness.toFloat() - 100) / 100)
            view.visibility = View.VISIBLE
        }

        // Show notification
        NotificationHelper.show(notificationContainer, resources.getString(R.string.brightness_notification, newBrightness))
    }

    fun toggleMute() {
        videoPlayer.toggleMute()
        userMutedVideo = videoPlayer.mutedState
    }

    private fun pauseMedia() {
        if (isPaused) return

        isPaused = true

        // Pause video if currently showing
        if (videoViewBinding.root.isVisible) {
            videoPlayer.pause()
        }

        // Pause image timer if currently showing
        if (imageViewBinding.root.isVisible) {
            imagePlayer.pauseTimer()
        }

        // Pause progress bar
        if (GeneralPrefs.progressBarLocation != ProgressBarLocation.DISABLED) {
            GlobalBus.post(ProgressBarEvent(ProgressState.PAUSE))
        }
    }

    private fun resumeMedia() {
        if (dashboardView != null) return
        if (!isPaused) return

        isPaused = false

        // Resume video if currently showing
        if (videoViewBinding.root.isVisible) {
            videoPlayer.resume()
        }

        // Resume image timer if currently showing
        if (imageViewBinding.root.isVisible) {
            imagePlayer.resumeTimer()
        }

        // Resume progress bar
        if (GeneralPrefs.progressBarLocation != ProgressBarLocation.DISABLED) {
            GlobalBus.post(ProgressBarEvent(ProgressState.RESUME))
        }
    }

    private fun handleError() {
        if (blackOutMode) return

        if (dashboardView != null) {
            dashboardDeferredPrevious = currentLoadIsPrevious
            return
        }

        val failedNavigation = navigationGeneration
        val previous = currentLoadIsPrevious

        if (photoTransition) {
            mainScope.launch {
                delay(ERROR_DELAY.milliseconds)
                if (!isStopped && failedNavigation == navigationGeneration) loadNextItem(previous)
            }
            return
        }

        recreateVideoPlayer()

        mainScope.launch {
            delay(ERROR_DELAY.milliseconds)
            if (isStopped || failedNavigation != navigationGeneration) return@launch
            if (dashboardView != null) {
                dashboardDeferredPrevious = previous
                return@launch
            }
            if (loadingView.isVisible) {
                loadNextItem(previous)
            } else {
                explicitSkip = true
                previousItem = previous
                fadeOutCurrentItem()
            }
        }
    }

    private fun installVideoView(
        root: View,
        visibility: Int = View.VISIBLE,
    ) {
        videoViewBinding = VideoViewBinding.bind(root)
        videoViewBinding.root.setBackgroundColor(backgroundVideosColour)
        videoViewBinding.root.visibility = visibility
        videoPlayer = videoViewBinding.videoPlayer
        videoPlayer.setOnPlayerListener(this)
        videoPlayer.setMuteState(forcedMuteVideo, userMutedVideo)
    }

    private fun recreateVideoPlayer() {
        val oldRoot = videoViewBinding.root
        val videoParent = oldRoot.parent as? ViewGroup ?: return
        val index = videoParent.indexOfChild(oldRoot)
        val previousVisibility = oldRoot.visibility
        videoPlayer.release()
        videoParent.removeView(oldRoot)

        val replacement = LayoutInflater.from(context).inflate(R.layout.video_view, videoParent, false)
        videoParent.addView(replacement, index)
        installVideoView(replacement, previousVisibility)

        // The metadata overlays hold a reference to the old player for POI timings.
        renderMetadataOverlays(overlayStateStore.uiState.value)
    }

    private fun handlePlaybackSpeedChanged() {
        val message = resources.getString(R.string.playlist_playback_speed_changed, GeneralPrefs.playbackSpeed + "x")
        NotificationHelper.show(notificationContainer, message)
    }

    override fun onVideoPlaybackSpeedChanged() = handlePlaybackSpeedChanged()

    override fun onVideoAlmostFinished() = fadeOutCurrentItem()

    override fun onVideoPrepared() {
        if (!blackOutMode) fadeInNextItem()
    }

    override fun onVideoError() = handleError()

    override fun onVideoMetadataExtracted(metadata: ExtractedVideoMetadata) {
        val media = currentMedia ?: return
        val changed = applyVideoMetadataToMedia(media, metadata)

        if (changed) {
            updateMetadataOverlayData(media)
        }
    }

    override fun onImageFinished() = fadeOutCurrentItem()

    override fun onImageError() = handleError()

    override fun onPortraitPairFailed() {
        val slide = currentPortraitSlide ?: return
        portraitSlidesByEnd.remove(playlist.currentPosition)
        runCatching { KrillSessionManager(context).cachedSession()?.cacheNamespace }.getOrNull()?.let {
            portraitHistory.remove(it, playlist.currentPosition)
        }
        playlist.restoreCursor(slide.start)
        currentPortraitSlide = null
    }

    private fun updateMetadataOverlayData(media: AerialMedia) {
        val metadataSlots =
            listOf(
                OverlayType.METADATA1,
                OverlayType.METADATA2,
                OverlayType.METADATA3,
                OverlayType.METADATA4,
            )

        metadataSlots.forEach { slot ->
            metadataJobs[slot]?.cancel()
            metadataJobs[slot] =
                mainScope.launch {
                    try {
                        val preferences = getMetadataPreferences(slot)
                        val resolved = metadataResolver.resolve(context, media, preferences)
                        if (currentMedia !== media) return@launch

                        overlayStateStore.setMetadata(
                            slot,
                            resolved.text,
                            resolved.poi,
                            resolved.metadataType,
                        )
                    } catch (e: Exception) {
                        Timber.e(e, "Metadata slot $slot resolver failed")
                        if (currentMedia === media) {
                            overlayStateStore.setMetadata(slot, "", emptyMap(), MetadataType.STATIC)
                        }
                    }
                }
        }
    }

    private fun getMetadataPreferences(slot: OverlayType): MetadataResolver.Preferences =
        when (slot) {
            OverlayType.METADATA1 -> {
                MetadataResolver.Preferences(
                    videoSelection = GeneralPrefs.overlayMetadata1Videos,
                    videoFolderDepth = GeneralPrefs.overlayMetadata1VideosFolderLevel.toIntOrNull() ?: 1,
                    videoLocationType =
                        GeneralPrefs.overlayMetadata1VideosLocationType ?: LocationType.CITY_COUNTRY,
                    photoSelection = GeneralPrefs.overlayMetadata1Photos,
                    photoFolderDepth = GeneralPrefs.overlayMetadata1PhotosFolderLevel.toIntOrNull() ?: 1,
                    photoLocationType =
                        GeneralPrefs.overlayMetadata1PhotosLocationType ?: LocationType.CITY_COUNTRY,
                    photoDateType =
                        GeneralPrefs.overlayMetadata1PhotosDateType ?: DateType.COMPACT,
                    photoDateCustom = GeneralPrefs.overlayMetadata1PhotosDateCustom,
                )
            }

            OverlayType.METADATA2 -> {
                MetadataResolver.Preferences(
                    videoSelection = GeneralPrefs.overlayMetadata2Videos,
                    videoFolderDepth = GeneralPrefs.overlayMetadata2VideosFolderLevel.toIntOrNull() ?: 1,
                    videoLocationType =
                        GeneralPrefs.overlayMetadata2VideosLocationType ?: LocationType.CITY_COUNTRY,
                    photoSelection = GeneralPrefs.overlayMetadata2Photos,
                    photoFolderDepth = GeneralPrefs.overlayMetadata2PhotosFolderLevel.toIntOrNull() ?: 1,
                    photoLocationType =
                        GeneralPrefs.overlayMetadata2PhotosLocationType ?: LocationType.CITY_COUNTRY,
                    photoDateType =
                        GeneralPrefs.overlayMetadata2PhotosDateType ?: DateType.COMPACT,
                    photoDateCustom = GeneralPrefs.overlayMetadata2PhotosDateCustom,
                )
            }

            OverlayType.METADATA3 -> {
                MetadataResolver.Preferences(
                    videoSelection = GeneralPrefs.overlayMetadata3Videos,
                    videoFolderDepth = GeneralPrefs.overlayMetadata3VideosFolderLevel.toIntOrNull() ?: 1,
                    videoLocationType =
                        GeneralPrefs.overlayMetadata3VideosLocationType ?: LocationType.CITY_COUNTRY,
                    photoSelection = GeneralPrefs.overlayMetadata3Photos,
                    photoFolderDepth = GeneralPrefs.overlayMetadata3PhotosFolderLevel.toIntOrNull() ?: 1,
                    photoLocationType =
                        GeneralPrefs.overlayMetadata3PhotosLocationType ?: LocationType.CITY_COUNTRY,
                    photoDateType =
                        GeneralPrefs.overlayMetadata3PhotosDateType ?: DateType.COMPACT,
                    photoDateCustom = GeneralPrefs.overlayMetadata3PhotosDateCustom,
                )
            }

            else -> {
                MetadataResolver.Preferences(
                    videoSelection = GeneralPrefs.overlayMetadata4Videos,
                    videoFolderDepth = GeneralPrefs.overlayMetadata4VideosFolderLevel.toIntOrNull() ?: 1,
                    videoLocationType =
                        GeneralPrefs.overlayMetadata4VideosLocationType ?: LocationType.CITY_COUNTRY,
                    photoSelection = GeneralPrefs.overlayMetadata4Photos,
                    photoFolderDepth = GeneralPrefs.overlayMetadata4PhotosFolderLevel.toIntOrNull() ?: 1,
                    photoLocationType =
                        GeneralPrefs.overlayMetadata4PhotosLocationType ?: LocationType.CITY_COUNTRY,
                    photoDateType =
                        GeneralPrefs.overlayMetadata4PhotosDateType ?: DateType.COMPACT,
                    photoDateCustom = GeneralPrefs.overlayMetadata4PhotosDateCustom,
                )
            }
        }

    override fun onImagePrepared() {
        Timber.d("onImagePrepared")
        if (blackOutMode) return
        if (photoTransition) {
            photoTransition = false
            imagePlayer.finishPhotoTransition(mediaFadeIn)
        }
        currentMedia
            ?.takeIf { it.type == AerialMediaType.IMAGE }
            ?.let { updateMetadataOverlayData(it) }
        fadeInNextItem()
        if (pauseAfterNextImage) {
            pauseAfterNextImage = false
            val media = currentMedia
            imagePlayer.post {
                if (!isStopped && currentMedia === media) pauseMedia()
            }
        }
    }

    private fun bindOverlayState() {
        mainScope.launch {
            overlayStateStore.uiState.collectLatest { state ->
                renderOverlayState(state)
            }
        }
    }

    private fun renderOverlayState(state: OverlayUiState) {
        renderMetadataOverlays(state)

        overlayHelper.findOverlay<NowPlayingOverlay>().forEach {
            it.render(state.nowPlaying)
        }

        overlayHelper.findOverlay<WeatherNowOverlay>().forEach {
            it.render(state.weather)
        }

        overlayHelper.findOverlay<WeatherForecastOverlay>().forEach {
            it.render(state.forecast)
        }

        overlayHelper.findOverlay<MessageOverlay>().forEach { overlay ->
            overlay.render(state.message[overlay.type] ?: MessageOverlayState())
        }

        progressBarView.render(state.progress)
    }

    companion object {
        const val LOADING_FADE_OUT: Long = 300 // Fade out loading text
        const val LOADING_DELAY: Long = 400 // Delay before fading out loading view
        const val ERROR_DELAY: Long = 1000 // Delay before loading next item, after error
        const val CACHE_RESUME_DELAY: Long = 1000 // Delay before starting playback when restoring from cache
    }
}
