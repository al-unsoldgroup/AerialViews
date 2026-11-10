package com.neilturner.aerialviews.providers.custom

import coil3.disk.DiskCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class KrillReconciliationLockTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `replacement opens the destination only after cancelled recovery closes it`() =
        runBlocking {
            val path = directory.resolve("destination").toOkioPath()
            val entered = CompletableDeferred<Unit>()
            val recovery =
                async(Dispatchers.IO) {
                    KrillFrameCache.withReconciliationLock {
                        val cache =
                            DiskCache
                                .Builder()
                                .directory(path)
                                .maxSizeBytes(1024 * 1024)
                                .build()
                        try {
                            requireNotNull(cache.openEditor("retained")).apply {
                                data.toFile().writeText("synthetic")
                                metadata.toFile().writeText("metadata")
                                commit()
                            }
                            entered.complete(Unit)
                            awaitCancellation()
                        } finally {
                            cache.shutdown()
                        }
                    }
                }
            entered.await()
            withTimeout(5_000) {
                withContext(Dispatchers.IO) {
                    KrillFrameCache.withSessionReplacement {
                        val reopened =
                            DiskCache
                                .Builder()
                                .directory(path)
                                .maxSizeBytes(1024 * 1024)
                                .build()
                        try {
                            requireNotNull(reopened.openSnapshot("retained")).use {
                                assertEquals("synthetic", it.data.toFile().readText())
                            }
                        } finally {
                            reopened.shutdown()
                        }
                    }
                }
            }
            recovery.join()
            assertTrue(recovery.isCancelled)
        }

    @Test
    fun `session replacement cancels recovery and waits for it to release the lock`() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val released = AtomicBoolean(false)
            val recovery =
                async(Dispatchers.IO) {
                    KrillFrameCache.withReconciliationLock {
                        try {
                            entered.complete(Unit)
                            awaitCancellation()
                        } finally {
                            released.set(true)
                        }
                    }
                }
            entered.await()
            withTimeout(5_000) {
                withContext(Dispatchers.IO) {
                    KrillFrameCache.withSessionReplacement { assertTrue(released.get()) }
                }
            }
            recovery.join()
            assertTrue(recovery.isCancelled)
        }
}
