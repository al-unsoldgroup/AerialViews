package com.neilturner.aerialviews.services

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CachedPlaylistGateTest {
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
