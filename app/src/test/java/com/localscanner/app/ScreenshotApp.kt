package com.localscanner.app

/**
 * Application used by Robolectric screenshot tests. It reuses the real DI
 * container, Room database, repository and DataStore, but skips loading the
 * native engines (OpenCV / Tesseract / ML Kit natives do not exist on the
 * desktop JVM).
 */
class ScreenshotApp : LocalScannerApp() {

    override fun initEngines() {
        // No native engines on the JVM — ScannerVision/OCR calls are avoided by tests.
    }

    override fun onContainerReady() {
        // Skip the Tesseract traineddata asset copy (not needed for UI rendering).
    }
}
