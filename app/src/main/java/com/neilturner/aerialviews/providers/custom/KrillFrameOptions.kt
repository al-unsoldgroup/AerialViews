package com.neilturner.aerialviews.providers.custom

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull

/**
 * Per-item frame options set in Krill's Frame focus editor (UNS-1762).
 *
 * They travel with the item as reserved tags, so cached playlists keep them and the existing cached-tag refresh
 * delivers changes, without a Room migration. ponytail: reserved tag prefix; move to columns at the next schema change.
 *
 * Speed precedence: a Krill video with a per-item rate plays at that rate, overriding the global playback speed
 * preference. Without one (default 1x, or an older server) the global preference applies as before. Other providers
 * always use the global preference.
 */
internal object KrillFrameOptions {
    private const val PREFIX = "krill-frame:"
    private const val RATE = "${PREFIX}rate="
    const val SINGLE_16_9 = "${PREFIX}single_16_9"
    const val NO_COLLAGE = "${PREFIX}no_collage"
    private val RATES = setOf(0.8f, 0.7f, 0.5f)

    /** Reserved tags for the feed fields; missing or unrecognised values mean the defaults. */
    fun tags(
        playbackRate: JsonElement?,
        layout: JsonElement?,
        collageAllowed: JsonElement?,
    ): List<String> {
        val rate = (playbackRate as? JsonPrimitive)?.floatOrNull?.takeIf { it in RATES }
        return listOfNotNull(
            rate?.let { "$RATE$it" },
            SINGLE_16_9.takeIf { (layout as? JsonPrimitive)?.takeIf { it.isString }?.content == "single_16_9" },
            NO_COLLAGE.takeIf { (collageAllowed as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == false },
        )
    }

    /** Per-item rate of a Krill video, or null when the global preference applies. */
    fun playbackRate(tags: List<String>): Float? =
        tags.firstNotNullOfOrNull { tag -> tag.removePrefix(RATE).takeIf { tag.startsWith(RATE) }?.toFloatOrNull() }
            ?.takeIf { it in RATES }

    fun playbackSpeed(
        krill: Boolean,
        tags: List<String>,
        globalSpeed: Float,
    ): Float = (if (krill) playbackRate(tags) else null) ?: globalSpeed

    /** Always shown alone as a full-screen 16:9 crop at its focus. */
    fun singleFrame(tags: List<String>): Boolean = SINGLE_16_9 in tags

    /** Never part of a pair or triple: shown as 16:9, or collages switched off. */
    fun showsAlone(tags: List<String>): Boolean = SINGLE_16_9 in tags || NO_COLLAGE in tags
}
