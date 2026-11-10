package com.neilturner.aerialviews.providers.custom

import android.content.Context
import java.security.MessageDigest

/** Persist playlist positions with non-reversible identity checks, not private photo URLs. */
internal class KrillPortraitHistory(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(
        namespace: String,
        end: Int,
        start: Int,
        firstUrl: String,
        secondUrl: String,
        thirdUrl: String? = null,
    ) {
        if (namespace.isBlank() || start < 0 || end < 0) return
        val fields =
            listOfNotNull(
                start.toString(),
                hash("$namespace:$firstUrl"),
                hash("$namespace:$secondUrl"),
                thirdUrl?.let { hash("$namespace:$it") },
            )
        prefs.edit().putString(key(namespace, end), fields.joinToString(":")).apply()
    }

    fun findStart(
        namespace: String,
        end: Int,
        secondUrl: String,
    ): Int? {
        if (namespace.isBlank() || end < 0) return null
        val fields = prefs.getString(key(namespace, end), null)?.split(':') ?: return null
        if (fields.size !in 3..4 || fields.last() != hash("$namespace:$secondUrl")) return null
        return fields[0].toIntOrNull()?.takeIf { it >= 0 }
    }

    fun matchesFirst(
        namespace: String,
        end: Int,
        firstUrl: String,
    ): Boolean {
        val fields = prefs.getString(key(namespace, end), null)?.split(':') ?: return false
        return fields.size in 3..4 && fields[1] == hash("$namespace:$firstUrl")
    }

    fun matchesSecond(
        namespace: String,
        end: Int,
        secondUrl: String,
    ): Boolean {
        val fields = prefs.getString(key(namespace, end), null)?.split(':') ?: return false
        return fields.size == 4 && fields[2] == hash("$namespace:$secondUrl")
    }

    fun remove(
        namespace: String,
        end: Int,
    ) {
        if (namespace.isNotBlank() && end >= 0) prefs.edit().remove(key(namespace, end)).apply()
    }

    fun rejectPair(
        namespace: String,
        firstUrl: String,
        secondUrl: String,
    ) {
        if (namespace.isBlank()) return
        prefs
            .edit()
            .putBoolean(rejectionKey(namespace, firstUrl, secondUrl), true)
            .putBoolean(borderKey(namespace, firstUrl), true)
            .putBoolean(borderKey(namespace, secondUrl), true)
            .apply()
    }

    fun isPairRejected(
        namespace: String,
        firstUrl: String,
        secondUrl: String,
    ): Boolean = namespace.isNotBlank() && prefs.getBoolean(rejectionKey(namespace, firstUrl, secondUrl), false)

    fun rejectTriple(
        namespace: String,
        firstUrl: String,
        secondUrl: String,
        thirdUrl: String,
    ) {
        if (namespace.isNotBlank()) prefs.edit().putBoolean(tripleKey(namespace, firstUrl, secondUrl, thirdUrl), true).apply()
    }

    fun isTripleRejected(
        namespace: String,
        firstUrl: String,
        secondUrl: String,
        thirdUrl: String,
    ): Boolean = namespace.isNotBlank() && prefs.getBoolean(tripleKey(namespace, firstUrl, secondUrl, thirdUrl), false)

    fun useNativeBorder(
        namespace: String,
        url: String,
    ): Boolean = namespace.isNotBlank() && prefs.getBoolean(borderKey(namespace, url), false)

    fun resetRejections(namespace: String) {
        if (namespace.isBlank()) return
        val prefixes = listOf(rejectionPrefix(namespace), borderPrefix(namespace))
        val editor = prefs.edit()
        prefs.all.keys
            .filter { key -> prefixes.any { key.startsWith(it) } }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    /** Delay small replans for up to six hours, then allow a bounded retry. */
    fun deferSmallReplan(
        namespace: String,
        cacheStamp: Long,
        now: Long,
    ): Boolean {
        val key = "replan:${hash("$namespace:$cacheStamp")}"
        val previous = prefs.getLong(key, 0L)
        if (!prefs.contains(key) || now < previous) return prefs.edit().putLong(key, now).commit()
        if (now - previous < 6 * 60 * 60 * 1000L) return true
        prefs.edit().putLong(key, now).commit()
        return false
    }

    fun clearReplanDelay(
        namespace: String,
        cacheStamp: Long,
    ) {
        prefs.edit().remove("replan:${hash("$namespace:$cacheStamp")}").apply()
    }

    fun needsCadenceUpgrade(namespace: String): Boolean = !prefs.getBoolean("cadence-v2:${hash(namespace)}", false)

    fun markCadenceUpgraded(namespace: String) {
        prefs.edit().putBoolean("cadence-v2:${hash(namespace)}", true).apply()
    }

    fun invalidateCadence(namespace: String) {
        prefs.edit().remove("cadence-v2:${hash(namespace)}").apply()
    }

    fun recordPlanTimeout(
        namespace: String,
        cacheStamp: Long,
        now: Long,
    ) {
        prefs.edit().putLong("timeout:${hash("$namespace:$cacheStamp")}", now).apply()
    }

    fun planTimedOutRecently(
        namespace: String,
        cacheStamp: Long,
        now: Long,
    ): Boolean {
        val previous = prefs.getLong("timeout:${hash("$namespace:$cacheStamp")}", 0L)
        return previous > 0 && now >= previous && now - previous < 6 * 60 * 60 * 1000L
    }

    companion object {
        const val PREFS = "krill_portrait_history"

        fun adjacent(
            start: Int,
            end: Int,
            size: Int,
        ): Boolean = size >= 2 && start in 0 until size && end in 0 until size && (start + 1) % size == end

        fun clear(context: Context) {
            check(
                context.applicationContext
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit(),
            ) {
                "Unable to clear portrait history"
            }
        }

        private fun key(
            namespace: String,
            end: Int,
        ) = hash("$namespace:$end")

        private fun rejectionPrefix(namespace: String) = "rejected:${hash(namespace)}:"

        private fun borderPrefix(namespace: String) = "border:${hash(namespace)}:"

        private fun borderKey(
            namespace: String,
            url: String,
        ) = borderPrefix(namespace) + hash("$namespace:$url")

        private fun rejectionKey(
            namespace: String,
            firstUrl: String,
            secondUrl: String,
        ): String {
            val photos = listOf(hash("$namespace:$firstUrl"), hash("$namespace:$secondUrl")).sorted()
            return rejectionPrefix(namespace) + hash(photos.joinToString(":"))
        }

        private fun tripleKey(
            namespace: String,
            firstUrl: String,
            secondUrl: String,
            thirdUrl: String,
        ): String {
            val photos = listOf(firstUrl, secondUrl, thirdUrl).map { hash("$namespace:$it") }.sorted()
            return rejectionPrefix(namespace) + hash(photos.joinToString(":"))
        }

        private fun hash(value: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            val hex = "0123456789abcdef"
            return buildString(bytes.size * 2) {
                bytes.forEach {
                    val value = it.toInt() and 0xff
                    append(hex[value ushr 4])
                    append(hex[value and 0x0f])
                }
            }
        }
    }
}
