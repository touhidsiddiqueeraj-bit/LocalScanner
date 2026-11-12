package com.localscanner.app.data

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

    /** True while the user is importing from the gallery (crop stage skipped). */
    var importMode = false

    fun init(appFilesDir: File, cacheDir: File) {
        workDir = File(cacheDir, "session").apply { mkdirs() }
    }

    fun newRawFile(): File = File(workDir, "raw_${System.currentTimeMillis()}_${rawFiles.size}.jpg")

    fun newWarpedFile(): File = File(workDir, "warp_${System.currentTimeMillis()}_${warpedFiles.size}.jpg")

    fun addRaw(file: File) {
        rawFiles.add(file)
    }

    fun addWarped(file: File) {
        warpedFiles.add(file)
    }

    fun hasWork(): Boolean = rawFiles.isNotEmpty() || warpedFiles.isNotEmpty()

    fun reset() {
        rawFiles.clear()
        warpedFiles.clear()
        importMode = false
        targetDocId = null
        workDir.listFiles()?.forEach { it.delete() }
    }
}
