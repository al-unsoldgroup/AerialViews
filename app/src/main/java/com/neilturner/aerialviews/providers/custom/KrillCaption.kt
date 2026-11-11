package com.neilturner.aerialviews.providers.custom

import android.content.SharedPreferences
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** Brief frame captions from verified Krill fields only; unknown fields are left out. */
internal object KrillCaption {
    private val timeFormat = DateTimeFormatter.ofPattern("EEE d MMM yyyy, h:mm a", Locale.ENGLISH)
    private val fileName = Regex("""^\S+\.(heic|heif|jpe?g|png|webp|gif|avif|dng|tiff?|mov|mp4|m4v)$""", RegexOption.IGNORE_CASE)
    private val isoTimestamp = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?$""")

    /** Default names the share upload gave items without a real one; never information (UNS-1754). */
    private val genericDefault = Regex("""^shared from (an? |my )?(iphone|ipad|ios|android|phone|device)$""", RegexOption.IGNORE_CASE)

    data class Fields(
        val placeAttraction: String? = null,
        val placeNeighborhood: String? = null,
        val placeCity: String? = null,
        val placeCountry: String? = null,
        val capturedLocal: String? = null,
        val artworkTitle: String? = null,
        val artworkArtist: String? = null,
        val art: Boolean = false,
    )

    /**
     * Artwork shows title and artist; otherwise place, then local capture time. Null when nothing is known.
     * Art with neither title nor artist is blank, which the frame shows as "Untitled", not as a capture date.
     */
    fun format(fields: Fields): String? {
        val title = fields.artworkTitle.clean()
        val artist = fields.artworkArtist.clean()
        if (title != null || artist != null) return listOfNotNull(title, artist).joinToString("\n")
        if (fields.art) return ""
        val specific = fields.placeAttraction.clean() ?: fields.placeNeighborhood.clean()
        val place = listOfNotNull(specific, fields.placeCity.clean(), fields.placeCountry.clean()).distinct().joinToString(", ")
        return listOfNotNull(place.ifEmpty { null }, prettyTime(fields.capturedLocal)).joinToString("\n").ifEmpty { null }
    }

    /** Camera wall-clock time, for example "Thu 22 Oct 2026, 4:07 pm". */
    fun prettyTime(local: String?): String? =
        try {
            local?.let {
                LocalDateTime
                    .parse(it)
                    .format(timeFormat)
                    .replace("AM", "am")
                    .replace("PM", "pm")
            }
        } catch (_: DateTimeParseException) {
            null
        }

    /** Older captions joined title, credit and description; drop file names, raw timestamps and upload defaults. */
    fun cleanLegacy(caption: String): String =
        caption
            .split(" • ")
            .map { it.trim() }
            .filterNot { part ->
                part.isEmpty() || fileName.matches(part) || genericDefault.matches(part) || isoTimestamp.matches(part) ||
                    (part.length >= 60 && part.none(Char::isWhitespace) && part.count { it == '-' || it == '_' } >= 3)
            }.distinct()
            .joinToString(" • ")

    /** Saved photo selections lead with the Krill caption; an empty (disabled) selection stays empty. */
    fun withPhotoContextFirst(selection: String): String {
        val entries = selection.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (entries.isEmpty()) return selection
        return (listOf("PHOTO_CONTEXT") + entries.filterNot { it.equals("PHOTO_CONTEXT", ignoreCase = true) }).joinToString(",")
    }

    fun migrateOverlayPrefs(prefs: SharedPreferences) {
        val captions = !prefs.getBoolean(OVERLAYS_MIGRATED, false)
        val shade = !prefs.getBoolean(SHADE_MIGRATED, false)
        if (!captions && !shade) return
        val edit = prefs.edit()
        if (captions) {
            (1..4).forEach { slot ->
                val key = "overlay_metadata${slot}_photos"
                prefs.getString(key, null)?.let { edit.putString(key, withPhotoContextFirst(it)) }
            }
            edit.putBoolean(OVERLAYS_MIGRATED, true)
        }
        // A saved full-width bottom shade is turned off once; it can be switched back on in settings.
        if (shade) edit.putBoolean("gradient_bottom_show", false).putBoolean(SHADE_MIGRATED, true)
        edit.apply()
    }

    private const val OVERLAYS_MIGRATED = "krill_caption_overlays_migrated"
    private const val SHADE_MIGRATED = "krill_caption_shade_migrated"

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
