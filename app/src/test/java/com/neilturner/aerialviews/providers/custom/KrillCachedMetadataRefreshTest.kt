package com.neilturner.aerialviews.providers.custom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class KrillCachedMetadataRefreshTest {
    private val refreshInterval = 4 * 60 * 1000L
    private val now = 1_800_000_000_000L

    @Test
    fun `unchanged library revision avoids another full feed until the daily caption refresh`() {
        val key = KrillCachedMetadataRefresh.captionRefreshKey("ns", 42L)
        assertTrue(KrillCachedMetadataRefresh.revisionStillFresh(key, now - 1000L, key, now, "7", "7", "true", true))
        assertFalse(KrillCachedMetadataRefresh.revisionStillFresh(key, now - 1000L, key, now, "7", "8", "true", true))
        assertFalse(KrillCachedMetadataRefresh.revisionStillFresh(key, now - 24 * 60 * 60 * 1000L, key, now, "7", "7", "true", true))
        assertFalse(KrillCachedMetadataRefresh.revisionStillFresh("another", now - 1000L, key, now, "7", "7", "true", true))
        assertFalse(KrillCachedMetadataRefresh.revisionStillFresh(key, now - 1000L, key, now, null, null, "true", true))
        assertFalse(KrillCachedMetadataRefresh.revisionStillFresh(key, now - 1000L, key, now, "7", "7", "false", true))
        assertFalse(KrillCachedMetadataRefresh.revisionStillFresh(key, now - 1000L, key, now, "7", "7", null, true))
    }

    private fun legacyKey(
        namespace: String,
        cachedAt: Long,
    ) = MessageDigest
        .getInstance("SHA-256")
        .digest("$namespace:$cachedAt".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test
    fun `an upgraded caption schema refetches at once despite a recent completed refresh`() {
        val key = KrillCachedMetadataRefresh.captionRefreshKey("ns", 42L)
        assertNotEquals(legacyKey("ns", 42L), key)
        assertNotEquals(KrillCachedMetadataRefresh.captionRefreshKey("ns", 42L, schema = 1), key)
        assertFalse(KrillCachedMetadataRefresh.captionsFresh(legacyKey("ns", 42L), now - 60_000L, key, now))
    }

    @Test
    fun `a matching recent refresh is reused for up to four minutes`() {
        val key = KrillCachedMetadataRefresh.captionRefreshKey("ns", 42L)
        assertEquals(key, KrillCachedMetadataRefresh.captionRefreshKey("ns", 42L))
        assertTrue(KrillCachedMetadataRefresh.captionsFresh(key, now - 60_000L, key, now))
        assertFalse(KrillCachedMetadataRefresh.captionsFresh(key, now - refreshInterval, key, now))
        assertFalse(KrillCachedMetadataRefresh.captionsFresh(key, now + 60_000L, key, now))
        assertFalse(KrillCachedMetadataRefresh.captionsFresh(key, 0L, key, now))
        assertFalse(KrillCachedMetadataRefresh.captionsFresh(null, now - 60_000L, key, now))
    }
}
