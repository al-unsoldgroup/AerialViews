package com.neilturner.aerialviews.ui.core

import android.view.KeyEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

class DashboardScheduleTest {
    @Test
    fun `remote keys map to dashboard navigation`() {
        assertEquals(-1, DashboardConfig.navDirection(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(1, DashboardConfig.navDirection(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(0, DashboardConfig.navDirection(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(0, DashboardConfig.navDirection(KeyEvent.KEYCODE_ENTER))
        assertNull(DashboardConfig.navDirection(KeyEvent.KEYCODE_DPAD_UP))
        assertNull(DashboardConfig.navDirection(KeyEvent.KEYCODE_BACK))
        assertEquals("window.frameNav&&window.frameNav(-1)", DashboardConfig.navScript(-1))
        assertEquals("window.frameNav&&window.frameNav(1)", DashboardConfig.navScript(1))
        assertEquals("window.frameNav&&window.frameNav(0)", DashboardConfig.navScript(0))
    }

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

    private val weekdays = (1..5).map(DayOfWeek::of).toSet()

    // 2026-11-02 is a Monday; 2026-11-06 a Friday; 2026-11-07 a Saturday.
    private fun at(
        day: Int,
        hour: Int,
        minute: Int,
        second: Int = 0,
    ) = LocalDateTime.of(2026, 11, day, hour, minute, second)

    @Test
    fun `default rule shows the dashboard every five minutes on weekday daytime`() {
        val schedule = DashboardSchedule(weekdays, LocalTime.of(7, 0), LocalTime.of(18, 0), 5, 1)
        assertFalse(schedule.dashboardDue(at(2, 6, 59, 59)))
        assertTrue(schedule.dashboardDue(at(2, 7, 0)))
        assertTrue(schedule.dashboardDue(at(2, 7, 0, 59)))
        assertFalse(schedule.dashboardDue(at(2, 7, 1)))
        assertFalse(schedule.dashboardDue(at(2, 7, 4, 59)))
        assertTrue(schedule.dashboardDue(at(2, 7, 5)))
        assertTrue(schedule.dashboardDue(at(2, 17, 55, 30)))
        assertFalse(schedule.dashboardDue(at(2, 17, 59, 59)))
    }

    @Test
    fun `18h cutover and weekend show pictures only`() {
        val schedule = DashboardSchedule(weekdays, LocalTime.of(7, 0), LocalTime.of(18, 0), 5, 1)
        assertEquals(null, schedule.secondsIntoWindow(at(2, 18, 0)))
        assertFalse(schedule.dashboardDue(at(2, 18, 0)))
        assertFalse(schedule.dashboardDue(at(2, 18, 5)))
        assertTrue(schedule.dashboardDue(at(6, 17, 55)))
        assertFalse(schedule.dashboardDue(at(6, 18, 0)))
        assertFalse(schedule.dashboardDue(at(7, 7, 0)))
        assertFalse(schedule.dashboardDue(at(8, 12, 0)))
        assertTrue(schedule.dashboardDue(at(9, 7, 0)))
    }

    @Test
    fun `overnight window belongs to the day it starts on`() {
        val schedule = DashboardSchedule(setOf(DayOfWeek.FRIDAY), LocalTime.of(22, 0), LocalTime.of(6, 0), 0, 1)
        assertFalse(schedule.dashboardDue(at(6, 21, 59)))
        assertTrue(schedule.dashboardDue(at(6, 22, 0)))
        assertTrue(schedule.dashboardDue(at(7, 5, 59)))
        assertFalse(schedule.dashboardDue(at(7, 6, 0)))
        assertFalse(schedule.dashboardDue(at(7, 22, 0)))
        assertFalse(schedule.dashboardDue(at(6, 5, 0)))
    }

    @Test
    fun `equal times cover the whole day and no days disable the schedule`() {
        val allDay = DashboardSchedule(setOf(DayOfWeek.MONDAY), LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, 0, 1)
        assertTrue(allDay.dashboardDue(at(2, 0, 0)))
        assertTrue(allDay.dashboardDue(at(2, 23, 59)))
        assertFalse(allDay.dashboardDue(at(3, 0, 0)))
        assertFalse(DashboardSchedule(emptySet(), LocalTime.of(7, 0), LocalTime.of(18, 0), 5, 1).dashboardDue(at(2, 7, 0)))
    }

    @Test
    fun `parses stored settings and rejects bad times`() {
        assertEquals(
            DashboardSchedule(weekdays, LocalTime.of(7, 0), LocalTime.of(18, 0), 5, 1),
            DashboardSchedule.from(setOf("1", "2", "3", "4", "5", "9", "x"), "07:00", " 18:00 ", "5", "1"),
        )
        assertEquals(null, DashboardSchedule.from(setOf("1"), "7:00", "18:00", "5", "1"))
    }
}
