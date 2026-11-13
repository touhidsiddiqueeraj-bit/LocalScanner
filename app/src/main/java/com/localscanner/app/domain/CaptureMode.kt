package com.localscanner.app.domain

/**
 * Camera shooting modes, mirroring CamScanner:
 *  - SINGLE: one tap = one page; the app auto-crops it and jumps straight
 *    to post-processing (no multi-page review).
 *  - BATCH: keep snapping pages, review/auto-crop all of them at the end.
 */
enum class CaptureMode(val id: String, val label: String) {
    SINGLE("single", "Single"),
    BATCH("batch", "Batch");

    companion object {
        fun byId(id: String?): CaptureMode? = entries.firstOrNull { it.id == id }
    }
}
