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

    data class Fields(
        val placeAttraction: String? = null,
        val placeNeighborhood: String? = null,
        val placeCity: String? = null,
        val placeCountry: String? = null,
        val capturedLocal: String? = null,
        val artworkTitle: String? = null,
        val artworkArtist: String? = null,
    )

    /** Artwork shows title and artist; otherwise place, then local capture time. Null when nothing is known. */
    fun format(fields: Fields): String? {
        val title = fields.artworkTitle.clean()
        if (title != null) return listOfNotNull(title, fields.artworkArtist.clean()).joinToString("\n")
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

    /** Older captions joined title, credit and description; drop file names and raw timestamps. */
    fun cleanLegacy(caption: String): String =
        caption
            .split(" • ")
            .map { it.trim() }
            .filterNot { part ->
                part.isEmpty() || fileName.matches(part) || isoTimestamp.matches(part) ||
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
        if (prefs.getBoolean(OVERLAYS_MIGRATED, false)) return
        val edit = prefs.edit()
        (1..4).forEach { slot ->
            val key = "overlay_metadata${slot}_photos"
            prefs.getString(key, null)?.let { edit.putString(key, withPhotoContextFirst(it)) }
        }
        edit.putBoolean(OVERLAYS_MIGRATED, true).apply()
    }

    private const val OVERLAYS_MIGRATED = "krill_caption_overlays_migrated"

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
