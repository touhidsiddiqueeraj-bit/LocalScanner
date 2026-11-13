package com.localscanner.app.data

import com.localscanner.app.domain.QuadF
import java.io.File

/**
 * Holds the state of an in-progress scan across the capture -> crop -> filter
 * flow (process-lifetime scope, like CamScanner's scan session).
 */
class ScanSession {

    /** Directory for raw captures and intermediate warps. */
    lateinit var workDir: File
        private set

    var targetDocId: String? = null

    val rawFiles = mutableListOf<File>()
    val warpedFiles = mutableListOf<File>()

    /**
     * Live-view quad hints parallel to [rawFiles]. The analyzer saw the page
     * edges perfectly right before the shutter fired; the crop stage uses the
     * hint when still-photo detection fails (aspect-aware).
     */
    data class RawHint(val quad: QuadF, val aspect: Float)

    val rawHints = mutableListOf<RawHint?>()

    /** True while the user is importing from the gallery (crop stage skipped). */
    var importMode = false

    fun init(appFilesDir: File, cacheDir: File) {
        workDir = File(cacheDir, "session").apply { mkdirs() }
    }

    fun newRawFile(): File = File(workDir, "raw_${System.currentTimeMillis()}_${rawFiles.size}.jpg")

    fun newWarpedFile(): File = File(workDir, "warp_${System.currentTimeMillis()}_${warpedFiles.size}.jpg")

    fun addRaw(file: File, hint: RawHint? = null) {
        rawFiles.add(file)
        while (rawHints.size < rawFiles.size - 1) rawHints.add(null)
        rawHints.add(hint)
    }

    fun addWarped(file: File) {
        warpedFiles.add(file)
    }

    fun hasWork(): Boolean = rawFiles.isNotEmpty() || warpedFiles.isNotEmpty()

    fun reset() {
        rawFiles.clear()
        rawHints.clear()
        warpedFiles.clear()
        importMode = false
        targetDocId = null
        workDir.listFiles()?.forEach { it.delete() }
    }
}
