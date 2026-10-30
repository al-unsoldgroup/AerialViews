package com.neilturner.aerialviews.providers.custom

/** Tracks private operations so logout can prevent new work and wait for existing work to finish. */
internal class KrillCacheFence {
    private val lock = java.lang.Object()
    private val active = mutableMapOf<String, MutableSet<Lease>>()
    private val revoked = mutableSetOf<String>()

    fun acquire(namespace: String): Lease? =
        synchronized(lock) {
            require(namespace.isNotBlank())
            if (namespace in revoked) return@synchronized null
            Lease(namespace).also { active.getOrPut(namespace) { mutableSetOf() }.add(it) }
        }

    fun revoke(namespace: String) {
        val toCancel =
            synchronized(lock) {
                require(namespace.isNotBlank())
                revoked.add(namespace)
                lock.notifyAll()
                active[namespace]?.mapNotNull { it.cancellation } ?: emptyList()
            }
        toCancel.forEach { runCatching { it() } }
    }

    fun awaitDrained(
        namespace: String,
        timeoutMillis: Long,
    ): Boolean =
        synchronized(lock) {
            require(namespace in revoked) { "Revoke before waiting for cleanup" }
            require(timeoutMillis >= 0)
            val start = System.nanoTime()
            while (active[namespace]?.isNotEmpty() == true) {
                val elapsed = (System.nanoTime() - start) / 1_000_000L
                val remaining = timeoutMillis - elapsed
                if (remaining <= 0) return@synchronized false
                lock.wait(remaining)
            }
            true
        }

    inner class Lease internal constructor(
        private val namespace: String,
    ) : AutoCloseable {
        private var closed = false
        internal var cancellation: (() -> Unit)? = null
            private set

        fun isValid(): Boolean = synchronized(lock) { !closed && namespace !in revoked }

        /** Register even after revocation: cancellation is delivered immediately in that case. */
        fun onCancel(action: () -> Unit) {
            val cancelNow =
                synchronized(lock) {
                    if (closed) return
                    cancellation = action
                    namespace in revoked
                }
            if (cancelNow) action()
        }

        override fun close() =
            synchronized(lock) {
                if (!closed) {
                    closed = true
                    active[namespace]?.let { leases ->
                        leases.remove(this)
                        if (leases.isEmpty()) active.remove(namespace)
                    }
                    lock.notifyAll()
                }
            }
    }
}
