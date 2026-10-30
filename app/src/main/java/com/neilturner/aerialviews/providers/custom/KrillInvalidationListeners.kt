package com.neilturner.aerialviews.providers.custom

import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet

internal class KrillInvalidationListeners {
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun add(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun remove(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun notifyAllOrThrow() {
        var failure: Exception? = null
        listeners.forEach { listener ->
            try {
                listener()
            } catch (error: Exception) {
                if (failure == null) failure = error
            }
        }
        failure?.let { throw IOException("Unable to clear private display", it) }
    }
}
