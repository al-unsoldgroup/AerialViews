package com.neilturner.aerialviews.ui.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OfflineTrackerTest {
    @Test
    fun onlineNeverShowsIndicatorOrReconnect() {
        val tracker = OfflineTracker(15_000)
        assertEquals(OfflineTracker.State(false, false), tracker.update(true, 0))
        assertEquals(OfflineTracker.State(false, false), tracker.update(true, 60_000))
    }

    @Test
    fun indicatorWaitsForGracePeriod() {
        val tracker = OfflineTracker(15_000)
        assertFalse(tracker.update(false, 1_000).showIndicator)
        assertFalse(tracker.update(false, 15_999).showIndicator)
        assertTrue(tracker.update(false, 16_000).showIndicator)
        assertTrue(tracker.update(false, 20_000).showIndicator)
    }

    @Test
    fun briefBlipReconnectsWithoutIndicator() {
        val tracker = OfflineTracker(15_000)
        tracker.update(false, 0)
        val back = tracker.update(true, 5_000)
        assertFalse(back.showIndicator)
        assertTrue(back.reconnected)
        assertFalse(tracker.update(true, 10_000).reconnected)
    }

    @Test
    fun reconnectClearsIndicatorAndRestartsGrace() {
        val tracker = OfflineTracker(15_000)
        tracker.update(false, 0)
        assertTrue(tracker.update(false, 20_000).showIndicator)
        val back = tracker.update(true, 21_000)
        assertFalse(back.showIndicator)
        assertTrue(back.reconnected)
        assertFalse(tracker.update(false, 22_000).showIndicator)
    }
}
