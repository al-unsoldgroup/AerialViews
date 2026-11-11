package com.neilturner.aerialviews.models.prefs

import kotlin.reflect.KMutableProperty0

/** Every media source switch except Krill. SourceTogglesTest fails when a source is added without being listed here. */
internal fun otherSourceToggles(): List<KMutableProperty0<Boolean>> =
    listOf(
        AppleVideoPrefs::enabled,
        AmazonVideoPrefs::enabled,
        Comm1VideoPrefs::enabled,
        Comm2VideoPrefs::enabled,
        LocalMediaPrefs::enabled,
        SambaMediaPrefs::enabled,
        SambaMediaPrefs2::enabled,
        WebDavMediaPrefs::enabled,
        WebDavMediaPrefs2::enabled,
        ImmichMediaPrefs::enabled,
        NCMemoriesMediaPrefs::enabled,
        CustomFeedPrefs::enabled,
    )

/** Returns true when at least one source was on. */
internal fun disableOtherSources(): Boolean {
    val enabled = otherSourceToggles().filter { it.get() }
    enabled.forEach { it.set(false) }
    return enabled.isNotEmpty()
}
