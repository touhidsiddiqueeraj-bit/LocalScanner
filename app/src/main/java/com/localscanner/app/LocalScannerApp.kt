package com.localscanner.app

import android.app.Application
import com.localscanner.app.di.AppContainer
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.opencv.android.OpenCVLoader

/**
 * Application entry point. All engines are initialised locally — the app never
 * touches the network. Native engine loading lives in overridable hooks so that
 * JVM screenshot tests (Robolectric has no Android native libraries) can run
 * the real UI without the native layer.
 */
open class LocalScannerApp : Application() {

    lateinit var container: AppContainer
        internal set

    override fun onCreate() {
        super.onCreate()
        initEngines()
        container = AppContainer(this)
        onContainerReady()
    }

    /** Loads the bundled native engines (OpenCV, PDFBox runtime paths). */
    protected open fun initEngines() {
        OpenCVLoader.initLocal()
        PDFBoxResourceLoader.init(applicationContext)
    }

    /** Hook for first-use engine preparation (copies Tesseract trained data). */
    protected open fun onContainerReady() {
        container.tessOcr.prepareAssets()
    }
}
