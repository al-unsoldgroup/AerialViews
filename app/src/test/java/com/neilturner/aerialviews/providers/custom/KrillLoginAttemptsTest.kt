package com.neilturner.aerialviews.providers.custom

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KrillLoginAttemptsTest {
    @Test
    fun `later sign out invalidates a prior library selection`() {
        val attempts = KrillLoginAttempts
        val login = attempts.next()
        assertTrue(attempts.isCurrent(login))
        val logout = attempts.next()
        assertFalse(attempts.isCurrent(login))
        assertTrue(attempts.isCurrent(logout))
    }
}
