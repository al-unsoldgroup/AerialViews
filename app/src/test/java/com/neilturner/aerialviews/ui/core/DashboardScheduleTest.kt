package com.neilturner.aerialviews.ui.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalTime

class DashboardScheduleTest {
    @Test
    fun `validates dashboard URLs and local schedule times`() {
        assertTrue(DashboardConfig.validUrl("https://example.com/dashboard"))
        assertFalse(DashboardConfig.validUrl("http://example.com/"))
        assertFalse(DashboardConfig.validUrl("https://user:password@example.com/"))
        assertFalse(DashboardConfig.validUrl("file:///private"))
        assertTrue(DashboardConfig.sameOrigin("https://example.com/", "https://example.com:443/next"))
        assertFalse(DashboardConfig.sameOrigin("https://example.com/", "https://other.example/"))
        assertFalse(DashboardConfig.sameOrigin("https://example.com/", "http://example.com/"))
        assertFalse(DashboardConfig.sameOrigin("https://example.com/", "https://example.com:8443/"))
        assertTrue(DashboardConfig.parseTime("09:30") != null)
        assertTrue(DashboardConfig.parseTime("24:00") == null)
        assertTrue(DashboardConfig.parseTime("9:30") == null)
    }

    @Test
    fun `daytime window includes start and excludes end`() {
        val schedule = DashboardSchedule(LocalTime.of(9, 0), LocalTime.of(17, 0))
        assertFalse(schedule.includes(LocalTime.of(8, 59)))
        assertTrue(schedule.includes(LocalTime.of(9, 0)))
        assertTrue(schedule.includes(LocalTime.of(16, 59)))
        assertFalse(schedule.includes(LocalTime.of(17, 0)))
    }

    @Test
    fun `overnight window crosses midnight`() {
        val schedule = DashboardSchedule(LocalTime.of(22, 0), LocalTime.of(6, 0))
        assertTrue(schedule.includes(LocalTime.of(22, 0)))
        assertTrue(schedule.includes(LocalTime.MIDNIGHT))
        assertTrue(schedule.includes(LocalTime.of(5, 59)))
        assertFalse(schedule.includes(LocalTime.of(6, 0)))
    }

    @Test
    fun `equal endpoints disable the schedule`() {
        val schedule = DashboardSchedule(LocalTime.NOON, LocalTime.NOON)
        assertFalse(schedule.includes(LocalTime.NOON))
        assertFalse(schedule.includes(LocalTime.MIDNIGHT))
    }
}
