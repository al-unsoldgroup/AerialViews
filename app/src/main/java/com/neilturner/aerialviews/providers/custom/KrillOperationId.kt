package com.neilturner.aerialviews.providers.custom

import java.util.UUID

/** Timestamp-bound identifier; UUID.randomUUID supplies the remaining random bits. */
internal object KrillOperationId {
    fun create(nowMillis: Long = System.currentTimeMillis()): String {
        require(nowMillis in 0..0xffffffffffffL) { "Invalid clock" }
        val random = UUID.randomUUID()
        val most = (nowMillis shl 16) or (random.mostSignificantBits and 0xffffL)
        return UUID(most, random.leastSignificantBits).toString()
    }
}
