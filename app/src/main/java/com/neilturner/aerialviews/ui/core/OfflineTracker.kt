package com.neilturner.aerialviews.ui.core

/**
 * Turns polled connectivity into an offline indicator and a reconnect signal.
 * The indicator appears only after [graceMillis] offline, so a brief blip does not flash it.
 */
internal class OfflineTracker(
    private val graceMillis: Long = 15_000L,
) {
    data class State(
        val showIndicator: Boolean,
        val reconnected: Boolean,
    )

    private var offlineSince: Long? = null
    private var indicatorShown = false

    fun update(
        online: Boolean,
        now: Long,
    ): State {
        if (online) {
            val reconnected = offlineSince != null
            offlineSince = null
            indicatorShown = false
            return State(false, reconnected)
        }
        val since = offlineSince ?: now.also { offlineSince = it }
        if (now - since >= graceMillis) indicatorShown = true
        return State(indicatorShown, false)
    }
}
