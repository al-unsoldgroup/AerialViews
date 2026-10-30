package com.neilturner.aerialviews.services

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.WeakHashMap

/** Serialize cache acquisition and prevent rewriting an order held by another screen. */
internal class CachedPlaylistGate {
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

    inner class Owner internal constructor() : AutoCloseable {
        override fun close() {
            synchronized(lock) { owners.remove(this) }
        }
    }

    companion object {
        val process = CachedPlaylistGate()
    }
}
