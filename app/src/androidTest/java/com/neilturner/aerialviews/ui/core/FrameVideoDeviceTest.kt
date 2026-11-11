package com.neilturner.aerialviews.ui.core

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.ViewGroup
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neilturner.aerialviews.models.enums.AerialMediaSource
import com.neilturner.aerialviews.models.enums.AerialMediaType
import com.neilturner.aerialviews.models.prefs.GeneralPrefs
import com.neilturner.aerialviews.models.videos.AerialMedia
import com.neilturner.aerialviews.ui.screensaver.TestActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Uses synthetic files supplied in app-private storage for an explicit device test. */
@RunWith(AndroidJUnit4::class)
class FrameVideoDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun shortVideoLoopsAndLongVideoStopsAtSlideTime() {
        val short = context.filesDir.resolve("frame-video-short-test.mp4")
        val long = context.filesDir.resolve("frame-video-long-test.mp4")
        assumeTrue("Synthetic video fixtures not supplied", short.isFile && long.isFile)
        val originalSpeed = GeneralPrefs.slideshowSpeed
        val originalFade = GeneralPrefs.mediaFadeOutDuration
        val originalPlaybackSpeed = GeneralPrefs.playbackSpeed
        var activity: android.app.Activity? = null
        try {
            GeneralPrefs.slideshowSpeed = "3"
            GeneralPrefs.mediaFadeOutDuration = "0"
            GeneralPrefs.playbackSpeed = "1"
            val intent = Intent(context, TestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val testActivity = instrumentation.startActivitySync(intent)
            activity = testActivity
            runClip(testActivity, short, Player.REPEAT_MODE_ALL)
            runClip(testActivity, long, Player.REPEAT_MODE_OFF)
        } finally {
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
            GeneralPrefs.slideshowSpeed = originalSpeed
            GeneralPrefs.mediaFadeOutDuration = originalFade
            GeneralPrefs.playbackSpeed = originalPlaybackSpeed
        }
    }

    private fun runClip(
        activity: android.app.Activity,
        file: java.io.File,
        repeatMode: Int,
    ) {
        val ready = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val startAt = AtomicLong()
        val finishAt = AtomicLong()
        var view: VideoPlayerView? = null
        try {
            instrumentation.runOnMainSync {
                val playerView = VideoPlayerView(context)
                view = playerView
                activity.addContentView(
                    playerView,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
                playerView.setMuteState(forcedMute = false, muted = true)
                assertTrue(playerView.mutedState)
                playerView.toggleMute()
                assertTrue(!playerView.mutedState)
                playerView.toggleMute()
                playerView.setOnPlayerListener(
                    object : VideoPlayerView.OnVideoPlayerEventListener {
                        override fun onVideoAlmostFinished() {
                            finishAt.set(SystemClock.elapsedRealtime())
                            finished.countDown()
                        }

                        override fun onVideoError() = Unit

                        override fun onVideoPrepared() {
                            startAt.compareAndSet(0, SystemClock.elapsedRealtime())
                            ready.countDown()
                        }

                        override fun onVideoPlaybackSpeedChanged() = Unit

                        override fun onVideoMetadataExtracted(metadata: ExtractedVideoMetadata) = Unit
                    },
                )
                playerView.setVideo(AerialMedia(Uri.fromFile(file), AerialMediaType.VIDEO, AerialMediaSource.KRILL))
                playerView.start()
            }
            assertTrue("Video did not prepare", ready.await(15, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { assertEquals(repeatMode, view?.player?.repeatMode) }
            assertTrue("Video did not reach its slide boundary", finished.await(10, TimeUnit.SECONDS))
            assertTrue("Video advanced too early", finishAt.get() - startAt.get() >= 1_500)
            assertTrue("Video exceeded the slide boundary", finishAt.get() - startAt.get() < 5_000)
        } finally {
            instrumentation.runOnMainSync {
                view?.release()
                (view?.parent as? ViewGroup)?.removeView(view)
            }
        }
    }
}
