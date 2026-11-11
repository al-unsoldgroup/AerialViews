package com.neilturner.aerialviews.services

import com.neilturner.aerialviews.providers.custom.processPlaylistMaintenance
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CachedPlaylistGateTest {
    @Test
    fun `production recovery hook waits for the process playlist gate`() =
        runTest {
            val owner = CachedPlaylistGate.process.register()
            val entered = CompletableDeferred<Unit>()
            val maintenance =
                async {
                    processPlaylistMaintenance {
                        entered.complete(Unit)
                        true
                    }
                }
            runCurrent()
            assertFalse(entered.isCompleted)
            CachedPlaylistGate.process.load(owner) { owner.close() }
            assertTrue(maintenance.await())
            assertTrue(entered.isCompleted)
        }

    @Test
    fun `maintenance allows stopping screen to release its owner and blocks new loads`() =
        runTest {
            val gate = CachedPlaylistGate { testScheduler.currentTime }
            val original = gate.register()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val maintenance =
                async {
                    gate.runWhenUnowned(1000) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
            runCurrent()
            gate.load(original) { original.close() }
            entered.await()
            val next = gate.register()
            var loaded = false
            val load = async { gate.load(next) { loaded = true } }
            runCurrent()
            assertFalse(loaded)
            release.complete(Unit)
            assertTrue(maintenance.await())
            load.await()
            assertTrue(loaded)
            next.close()
        }

    @Test
    fun `maintenance times out without touching an active screen`() =
        runTest {
            val gate = CachedPlaylistGate { testScheduler.currentTime }
            val owner = gate.register()
            var ran = false
            val result = async { gate.runWhenUnowned(100) { ran = true } }
            advanceUntilIdle()
            assertFalse(result.await())
            assertFalse(ran)
            owner.close()
        }

    @Test
    fun `cancelled maintenance releases the gate for a later screen`() =
        runTest {
            val gate = CachedPlaylistGate { testScheduler.currentTime }
            val entered = CompletableDeferred<Unit>()
            val maintenance =
                async {
                    gate.runWhenUnowned(1000) {
                        entered.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    }
                }
            entered.await()
            maintenance.cancelAndJoin()
            val next = gate.register()
            var loaded = false
            gate.load(next) { loaded = true }
            assertTrue(loaded)
            next.close()
        }

    @Test
    fun `cancelling a waiting maintenance attempt does not block later maintenance`() =
        runTest {
            val gate = CachedPlaylistGate { testScheduler.currentTime }
            val owner = gate.register()
            val first = async { gate.runWhenUnowned(1000) {} }
            runCurrent()
            first.cancelAndJoin()
            owner.close()
            assertTrue(gate.runWhenUnowned(1000) {})
        }

    @Test
    fun `maintenance may run longer than the acquisition timeout`() =
        runTest {
            val gate = CachedPlaylistGate { testScheduler.currentTime }
            val result = async { gate.runWhenUnowned(100) { delay(200) } }
            advanceUntilIdle()
            assertTrue(result.await())
        }

    @Test
    fun `a second player cannot rewrite the order while the first is active`() {
        val gate = CachedPlaylistGate()
        val first = gate.register()
        assertTrue(gate.canRewrite(first))
        val second = gate.register()
        assertFalse(gate.canRewrite(first))
        assertFalse(gate.canRewrite(second))
        first.close()
        assertTrue(gate.canRewrite(second))
        second.close()
        assertFalse(gate.canRewrite(second))
    }

    @Test
    fun `last stopping player can save its position`() =
        runTest {
            val gate = CachedPlaylistGate()
            val first = gate.register()
            val second = gate.register()
            var saved = false
            val firstStop =
                async {
                    gate.load(first) {
                        if (gate.canRewrite(first)) saved = true
                        first.close()
                    }
                }
            val secondStop =
                async {
                    gate.load(second) {
                        if (gate.canRewrite(second)) saved = true
                        second.close()
                    }
                }
            firstStop.await()
            secondStop.await()
            assertTrue(saved)
        }

    @Test
    fun `a newly registered screen waits until the in-flight cache load finishes`() =
        runTest {
            val gate = CachedPlaylistGate()
            val first = gate.register()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val firstLoad =
                async {
                    gate.load(first) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
            entered.await()
            val second = gate.register()
            var secondEntered = false
            val secondLoad = async { gate.load(second) { secondEntered = true } }
            runCurrent()
            assertFalse(secondEntered)
            release.complete(Unit)
            firstLoad.await()
            secondLoad.await()
            assertTrue(secondEntered)
            first.close()
            second.close()
        }
}
