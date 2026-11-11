package com.neilturner.aerialviews.providers.custom

import android.content.Context

/** Capability is scoped to the current sign-in, never to an image URL. */
internal object KrillCropCapability {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("krill_crop_capability", Context.MODE_PRIVATE)

    fun set(
        context: Context,
        namespace: String,
        enabled: Boolean,
    ) {
        prefs(context)
            .edit()
            .putString("namespace", namespace)
            .putBoolean("enabled", enabled)
            .putLong("checked_at", System.currentTimeMillis())
            .apply()
    }

    fun enabled(
        context: Context,
        namespace: String,
    ): Boolean = known(context, namespace) && prefs(context).getBoolean("enabled", false)

    fun known(
        context: Context,
        namespace: String,
    ): Boolean {
        val saved = prefs(context)
        val checked = saved.getLong("checked_at", 0L)
        val now = System.currentTimeMillis()
        return saved.getString("namespace", null) == namespace && checked > 0 && now >= checked && now - checked < 24 * 60 * 60 * 1000L
    }
}
