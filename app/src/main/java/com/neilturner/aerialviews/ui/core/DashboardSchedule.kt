package com.neilturner.aerialviews.ui.core

import android.view.KeyEvent
import java.net.URI
import java.time.LocalTime

/** A daily local-time window. Equal endpoints mean that scheduling is disabled. */
internal data class DashboardSchedule(
    val start: LocalTime,
    val end: LocalTime,
) {
    fun includes(time: LocalTime): Boolean =
        when {
            start == end -> false
            start < end -> time >= start && time < end
            else -> time >= start || time < end
        }
}

internal object DashboardConfig {
    /** Remote key to the dashboard page's window.frameNav direction: -1 previous, 1 next, 0 pause toggle. */
    fun navDirection(keyCode: Int): Int? =
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> 0
            else -> null
        }

    /** Calls the page's navigation hook only when the page defines it. */
    fun navScript(direction: Int): String = "window.frameNav&&window.frameNav($direction)"

    fun validUrl(value: String): Boolean =
        runCatching {
            val uri = URI(value)
            uri.scheme.equals("https", ignoreCase = true) && uri.host != null && uri.userInfo == null
        }.getOrDefault(false)

    fun parseTime(value: String): LocalTime? =
        if (Regex("\\d{2}:\\d{2}").matches(value)) {
            runCatching { LocalTime.parse(value) }.getOrNull()
        } else {
            null
        }

    fun sameOrigin(
        base: String,
        target: String,
    ): Boolean =
        runCatching {
            if (!validUrl(base) || !validUrl(target)) return false
            val a = URI(base)
            val b = URI(target)
            a.host.equals(b.host, ignoreCase = true) && (if (a.port == -1) 443 else a.port) == (if (b.port == -1) 443 else b.port)
        }.getOrDefault(false)
}
