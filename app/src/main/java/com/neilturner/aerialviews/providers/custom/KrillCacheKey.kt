package com.neilturner.aerialviews.providers.custom

/** Cache entries from separate sign-ins must never share a key. */
internal object KrillCacheKey {
    fun forImage(
        namespace: String,
        url: String,
    ): String {
        require(namespace.isNotBlank()) { "Missing cache namespace" }
        return "$namespace:$url"
    }
}
