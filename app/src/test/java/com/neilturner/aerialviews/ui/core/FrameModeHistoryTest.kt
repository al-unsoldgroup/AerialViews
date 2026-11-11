package com.neilturner.aerialviews.ui.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FrameModeHistoryTest {
    @Test
    fun `back and forward follow the slides actually shown`() {
        val history = FrameModeHistory()
        listOf(2, 80, 7).forEach(history::record)
        assertEquals(80, history.previous())
        assertEquals(2, history.previous())
        assertNull(history.previous())
        assertEquals(80, history.next())
        assertEquals(7, history.next())
        assertNull(history.next())
    }

    @Test
    fun `new selection discards forward history and respects capacity`() {
        val history = FrameModeHistory(capacity = 2)
        listOf(1, 2, 3).forEach(history::record)
        assertEquals(2, history.previous())
        assertNull(history.previous())
        history.record(9)
        assertNull(history.next())
        assertEquals(2, history.previous())
        history.clear()
        assertNull(history.next())
    }

    @Test
    fun `an older discovered slide can be followed forward again`() {
        val history = FrameModeHistory()
        history.record(12)
        history.recordPrevious(7)
        assertEquals(12, history.peekNext())
        assertEquals(12, history.next())
        assertEquals(7, history.peekPrevious())
    }
}
