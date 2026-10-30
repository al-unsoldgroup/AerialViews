package com.neilturner.aerialviews.providers.custom

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KrillPortraitHistoryTest {
    @Test
    fun `only adjacent playlist positions can be restored as a pair`() {
        assertTrue(KrillPortraitHistory.adjacent(4, 5, 10))
        assertTrue(KrillPortraitHistory.adjacent(9, 0, 10))
        assertFalse(KrillPortraitHistory.adjacent(4, 6, 10))
        assertFalse(KrillPortraitHistory.adjacent(0, 0, 1))
        assertFalse(KrillPortraitHistory.adjacent(-1, 0, 10))
    }

    @Test
    fun `restores a pair only for the same account and same photo identities`() {
        val values = mutableMapOf<String, String>()
        val context = mockk<Context>()
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(KrillPortraitHistory.PREFS, Context.MODE_PRIVATE) } returns prefs
        every { prefs.edit() } returns editor
        every { prefs.getString(any(), any()) } answers { values[firstArg()] }
        every { prefs.getBoolean(any(), any()) } answers { values[firstArg()] == "true" }
        every { prefs.contains(any()) } answers { values.containsKey(firstArg()) }
        every { prefs.getLong(any(), any()) } answers { values[firstArg()]?.toLongOrNull() ?: secondArg() }
        every { prefs.all } answers { values.toMap() }
        every { editor.putString(any(), any()) } answers {
            values[firstArg()] = secondArg()
            editor
        }
        every { editor.remove(any()) } answers {
            values.remove(firstArg())
            editor
        }
        every { editor.putBoolean(any(), any()) } answers {
            values[firstArg()] = secondArg<Boolean>().toString()
            editor
        }
        every { editor.putLong(any(), any()) } answers {
            values[firstArg()] = secondArg<Long>().toString()
            editor
        }
        every { editor.commit() } returns true
        val history = KrillPortraitHistory(context)

        history.save("account-a", 5, 4, "https://example/first", "https://example/second")
        assertEquals(4, history.findStart("account-a", 5, "https://example/second"))
        assertTrue(history.matchesFirst("account-a", 5, "https://example/first"))
        assertNull(history.findStart("account-b", 5, "https://example/second"))
        assertNull(history.findStart("account-a", 5, "https://example/other"))
        assertFalse(history.matchesFirst("account-a", 5, "https://example/other"))
        assertTrue(values.values.none { "https://" in it })
        history.remove("account-a", 5)
        assertNull(history.findStart("account-a", 5, "https://example/second"))
        history.rejectPair("account-a", "https://example/first", "https://example/second")

        fun legacyHash(value: String) =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        val legacyPhotos = listOf(legacyHash("account-a:https://example/first"), legacyHash("account-a:https://example/second")).sorted()
        val legacyKey = "rejected:${legacyHash("account-a")}:${legacyHash(legacyPhotos.joinToString(":"))}"
        assertEquals("true", values[legacyKey])
        assertTrue(history.isPairRejected("account-a", "https://example/second", "https://example/first"))
        assertTrue(history.useNativeBorder("account-a", "https://example/first"))
        assertTrue(history.useNativeBorder("account-a", "https://example/second"))
        assertFalse(history.useNativeBorder("account-b", "https://example/first"))
        assertFalse(history.isPairRejected("account-b", "https://example/first", "https://example/second"))
        history.resetRejections("account-a")
        assertFalse(history.isPairRejected("account-a", "https://example/first", "https://example/second"))
        assertFalse(history.useNativeBorder("account-a", "https://example/first"))
        val sixHours = 6 * 60 * 60 * 1000L
        assertFalse(history.planTimedOutRecently("account-a", 42, 1000))
        history.recordPlanTimeout("account-a", 42, 1000)
        assertTrue(history.planTimedOutRecently("account-a", 42, 1000 + sixHours - 1))
        assertFalse(history.planTimedOutRecently("account-a", 42, 1000 + sixHours))
        assertFalse(history.planTimedOutRecently("account-b", 42, 1001))
        assertFalse(history.planTimedOutRecently("account-a", 43, 1001))
        assertTrue(history.deferSmallReplan("account-a", 42, 1000))
        assertTrue(history.deferSmallReplan("account-a", 42, 1000 + sixHours - 1))
        assertFalse(history.deferSmallReplan("account-a", 42, 1000 + sixHours))
        assertTrue(history.deferSmallReplan("account-a", 42, 1001 + sixHours))
        assertTrue(history.deferSmallReplan("account-b", 42, 1000 + sixHours))
    }
}
