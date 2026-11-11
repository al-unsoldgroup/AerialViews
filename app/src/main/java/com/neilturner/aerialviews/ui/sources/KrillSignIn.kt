package com.neilturner.aerialviews.ui.sources

/** Prefer the address with the code prefilled, so scanning skips typing it. */
internal fun krillQrContent(
    verificationUri: String,
    verificationUriComplete: String?,
): String = verificationUriComplete?.takeIf { it.isNotBlank() } ?: verificationUri

internal fun krillConnectionSummary(
    connected: Boolean,
    libraryName: String?,
    notSignedIn: String,
    connectedWithoutName: String,
    connectedTo: (String) -> String,
): String =
    when {
        !connected -> notSignedIn
        libraryName.isNullOrBlank() -> connectedWithoutName
        else -> connectedTo(libraryName)
    }
