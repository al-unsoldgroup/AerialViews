package com.neilturner.aerialviews.providers.custom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class KrillInvalidationListenersTest {
    @Test
    fun `a failed listener does not prevent later displays from being cleared`() {
        val listeners = KrillInvalidationListeners()
        var calls = 0
        listeners.add { throw IOException("failure") }
        listeners.add { calls++ }
        assertThrows(IOException::class.java) { listeners.notifyAllOrThrow() }
        assertEquals(1, calls)
    }
}
