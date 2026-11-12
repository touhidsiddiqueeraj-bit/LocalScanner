package com.localscanner.app

import android.app.Application
import com.localscanner.app.di.AppContainer
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.opencv.android.OpenCVLoader

class LocalScannerApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // All engines are initialised locally - no network is ever used.
        OpenCVLoader.initLocal()
        PDFBoxResourceLoader.init(applicationContext)
        container = AppContainer(this)
        container.tessOcr.prepareAssets()
    }

    override fun onTerminate() {
        container.tessOcr.release()
        super.onTerminate()
    }
}
