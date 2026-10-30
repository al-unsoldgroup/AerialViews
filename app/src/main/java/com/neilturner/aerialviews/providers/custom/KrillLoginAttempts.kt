package com.neilturner.aerialviews.providers.custom

import java.util.concurrent.atomic.AtomicLong

/** A later sign-in or sign-out invalidates callbacks from an earlier attempt. */
internal object KrillLoginAttempts {
    private val current = AtomicLong()

    fun next(): Long = current.incrementAndGet()

    fun isCurrent(attempt: Long): Boolean = current.get() == attempt
}
