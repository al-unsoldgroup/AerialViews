package com.neilturner.aerialviews.providers.custom

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KrillCaptionScheduleTest {
    @Test
    fun `checks every minute until the screen is stopped`() =
        runTest {
            var checks = 0
            val schedule = launch { runKrillCaptionSchedule { checks++ } }
            runCurrent()
            advanceTimeBy(60 * 1000L - 1)
            runCurrent()
            assertEquals(0, checks)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, checks)
            advanceTimeBy(60 * 1000L)
            runCurrent()
            assertEquals(2, checks)
            schedule.cancelAndJoin()
            advanceTimeBy(60 * 1000L)
            runCurrent()
            assertEquals(2, checks)
        }
}
