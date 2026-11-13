package com.localscanner.app.di

import android.app.Application
import androidx.room.Room
import com.localscanner.app.data.DocsRepository
import com.localscanner.app.data.ScanSession
import com.localscanner.app.data.SettingsRepository
import com.localscanner.app.data.db.AppDatabase
import com.localscanner.app.domain.OcrEngine
import com.localscanner.app.domain.PdfExporter
import com.localscanner.app.domain.TessOcr

class AppContainer(app: Application) {

    private val database: AppDatabase = Room.databaseBuilder(
        app,
        AppDatabase::class.java,
        "localscanner.db",
    ).build()

    val docDao = database.documentDao()
    val pageDao = database.pageDao()
    val repository = DocsRepository(app, docDao, pageDao)
    val settings = SettingsRepository(app)

    // OCR engines are created on first use: they own JNI-backed objects, so
    // constructing them lazily keeps JVM-only environments (screenshot tests)
    // safe and trims cold-start work when the user never opens OCR.
    val tessOcr: TessOcr by lazy { TessOcr(app) }
    val ocr: OcrEngine by lazy { OcrEngine(app, tessOcr) }

    val pdfExporter = PdfExporter(app)
    val scanSession = ScanSession().apply {
        init(app.filesDir, app.cacheDir)
    }
}
