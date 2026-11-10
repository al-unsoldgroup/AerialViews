package com.neilturner.aerialviews.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PrivateCacheVariantDaoTest {
    @Test
    fun `variant inventory is idempotent and isolated by session and media`() =
        runTest {
            val db = Room.inMemoryDatabaseBuilder<AerialDatabase>().setDriver(BundledSQLiteDriver()).build()
            try {
                val dao = db.privateCacheVariantDao()
                val first = PrivateCacheVariant("session-a", "photo-a", "key-a")
                dao.record(first)
                dao.record(first)
                dao.record(PrivateCacheVariant("session-a", "photo-a", "key-b"))
                dao.record(PrivateCacheVariant("session-a", "photo-b", "key-c"))
                dao.record(PrivateCacheVariant("session-b", "photo-a", "key-d"))
                assertEquals(setOf("key-a", "key-b"), dao.keysForMedia("session-a", "photo-a").toSet())
                dao.removeMedia("session-a", "photo-a")
                assertEquals(emptyList<String>(), dao.keysForMedia("session-a", "photo-a"))
                assertEquals(listOf("key-c"), dao.keysForMedia("session-a", "photo-b"))
                assertEquals(listOf("key-d"), dao.keysForMedia("session-b", "photo-a"))
                dao.removeNamespace("session-a")
                assertEquals(emptyList<String>(), dao.keysForMedia("session-a", "photo-b"))
                assertEquals(listOf("key-d"), dao.keysForMedia("session-b", "photo-a"))
                dao.clearAll()
                assertEquals(emptyList<String>(), dao.keysForMedia("session-b", "photo-a"))
            } finally {
                db.close()
            }
        }
}
