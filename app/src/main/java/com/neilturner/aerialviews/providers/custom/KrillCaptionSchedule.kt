package com.neilturner.aerialviews.providers.custom

import kotlinx.coroutines.delay

/** Check periodically while the calling screen's coroutine is active. */
internal suspend fun runKrillCaptionSchedule(
    intervalMillis: Long = 60 * 1000L,
    refresh: suspend () -> Unit,
) {
    require(intervalMillis > 0)
    while (true) {
        delay(intervalMillis)
        refresh()
    }
}
