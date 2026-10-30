package com.neilturner.aerialviews.providers.custom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class KrillOperationIdTest {
    @Test
    fun `operation identifiers retain timestamp and random UUID bits`() {
        val now = 1_800_000_000_000L
        val first = KrillOperationId.create(now)
        val second = KrillOperationId.create(now)
        assertEquals(now, UUID.fromString(first).mostSignificantBits ushr 16)
        assertEquals(4, UUID.fromString(first).version())
        assertNotEquals(first, second)
    }
}
