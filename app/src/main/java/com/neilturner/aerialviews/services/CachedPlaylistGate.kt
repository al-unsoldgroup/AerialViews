package com.neilturner.aerialviews.services

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.WeakHashMap

/** Serialize cache acquisition and prevent rewriting an order held by another screen. */
internal class CachedPlaylistGate(
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val lock = Any()
    private val loadMutex = Mutex()
    private val owners = WeakHashMap<Owner, Unit>()

    fun register(): Owner = synchronized(lock) { Owner().also { owners[it] = Unit } }

    fun canRewrite(owner: Owner): Boolean = synchronized(lock) { owners.containsKey(owner) && owners.size == 1 }

    suspend fun <T> load(
        owner: Owner,
        block: suspend () -> T,
    ): T =
        loadMutex.withLock {
            check(synchronized(lock) { owners.containsKey(owner) }) { "Screen stopped before playlist loading" }
            block()
        }

    /** Wait without blocking stopping screens; newly registered screens cannot load during maintenance. */
    suspend fun runWhenUnowned(
        timeoutMillis: Long,
        block: suspend () -> Unit,
    ): Boolean {
        require(timeoutMillis > 0)
        val start = clockMillis()
        while (true) {
            currentCoroutineContext().ensureActive()
            val elapsed = (clockMillis() - start).coerceAtLeast(0)
            if (elapsed >= timeoutMillis) return false
            if (loadMutex.tryLock()) {
                try {
                    if (synchronized(lock) { owners.isEmpty() }) {
                        block()
                        return true
                    }
                } finally {
                    loadMutex.unlock()
                }
            }
            delay(minOf(50L, timeoutMillis - elapsed))
        }
    }

    inner class Owner internal constructor() : AutoCloseable {
        override fun close() {
            synchronized(lock) { owners.remove(this) }
        }
    }

    companion object {
        val process = CachedPlaylistGate()
    }
}
