package com.neilturner.aerialviews.ui.core

import com.neilturner.aerialviews.models.videos.AerialExifMetadata
import com.neilturner.aerialviews.ui.helpers.ExifMetadata

/** Carry only information present in the original; a missing field must not erase known metadata. */
internal object KrillPhotoMetadata {
    fun applyExif(
        target: AerialExifMetadata,
        source: ExifMetadata,
    ) {
        if (source.date != null) {
            target.date = source.date
            target.offset = source.offset
        }
        // Treat the location as one value, never combine a new coordinate with an old one.
        if (source.latitude != null && source.longitude != null &&
            source.latitude.isFinite() && source.longitude.isFinite() &&
            source.latitude in -90.0..90.0 && source.longitude in -180.0..180.0
        ) {
            target.latitude = source.latitude
            target.longitude = source.longitude
        }
        source.description?.let { target.description = it }
    }
}
