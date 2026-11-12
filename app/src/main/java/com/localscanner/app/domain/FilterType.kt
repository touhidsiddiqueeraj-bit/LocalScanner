package com.localscanner.app.domain

/** Document enhancement filters (CamScanner style). */
enum class FilterType(val label: String) {
    ORIGINAL("Original"),
    MAGIC_COLOR("Magic Color"),
    GRAY("Grayscale"),
    BW("Black & White"),
}
