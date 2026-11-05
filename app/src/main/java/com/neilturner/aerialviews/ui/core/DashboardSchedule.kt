package com.neilturner.aerialviews.ui.core

import java.net.URI
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * One weekly rule: on [days], from [start] until [end], show the dashboard for [durationMinutes]
 * every [intervalMinutes], with pictures in between. Outside the rule only pictures play.
 * An interval of 0 keeps the dashboard up for the whole window. Equal times mean a 24-hour
 * window, and a window that crosses midnight belongs to the day it starts on. An empty
 * [days] set disables the schedule.
 */
internal data class DashboardSchedule(
    val days: Set<DayOfWeek>,
    val start: LocalTime,
    val end: LocalTime,
    val intervalMinutes: Int,
    val durationMinutes: Int,
) {
    /** Seconds since the window opened, or null outside it. */
    fun secondsIntoWindow(now: LocalDateTime): Long? {
        val today = now.toLocalDate()
        val opened = (if (now.toLocalTime() >= start) today else today.minusDays(1)).atTime(start)
        if (opened.dayOfWeek !in days) return null
        val length = Duration.between(start, end).let { if (it.isNegative || it.isZero) it.plusDays(1) else it }
        val elapsed = Duration.between(opened, now)
        return if (elapsed < length) elapsed.seconds else null
    }

    fun dashboardDue(now: LocalDateTime): Boolean {
        val elapsed = secondsIntoWindow(now) ?: return false
        if (intervalMinutes <= 0) return true
        return elapsed % (intervalMinutes * 60L) < durationMinutes.coerceAtLeast(1) * 60L
    }

    companion object {
        /** Parses stored settings; invalid times disable the schedule. */
        fun from(
            days: Set<String>,
            start: String,
            end: String,
            interval: String,
            duration: String,
        ): DashboardSchedule? {
            val startTime = DashboardConfig.parseTime(start.trim()) ?: return null
            val endTime = DashboardConfig.parseTime(end.trim()) ?: return null
            return DashboardSchedule(
                days.mapNotNull { day -> day.toIntOrNull()?.takeIf { it in 1..7 }?.let(DayOfWeek::of) }.toSet(),
                startTime,
                endTime,
                interval.toIntOrNull() ?: 5,
                duration.toIntOrNull() ?: 1,
            )
        }
    }
}

internal object DashboardConfig {
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
