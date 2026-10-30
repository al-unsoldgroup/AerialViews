package com.neilturner.aerialviews.providers.custom

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KrillCropCapabilityTest {
    @Test
    fun `capability is scoped to sign-in and expires`() {
        val context = mockk<Context>()
        val prefs = mockk<SharedPreferences>()
        every { context.applicationContext } returns context
        every { context.getSharedPreferences("krill_crop_capability", Context.MODE_PRIVATE) } returns prefs
        every { prefs.getString("namespace", null) } returns "session"
        every { prefs.getBoolean("enabled", false) } returns true
        every { prefs.getLong("checked_at", 0L) } returns System.currentTimeMillis()
        assertTrue(KrillCropCapability.enabled(context, "session"))
        assertFalse(KrillCropCapability.enabled(context, "another"))
        every { prefs.getLong("checked_at", 0L) } returns System.currentTimeMillis() - 25 * 60 * 60 * 1000L
        assertFalse(KrillCropCapability.known(context, "session"))
        assertFalse(KrillCropCapability.enabled(context, "session"))
        every { prefs.getLong("checked_at", 0L) } returns System.currentTimeMillis() + 60_000
        assertFalse(KrillCropCapability.known(context, "session"))
    }
}
