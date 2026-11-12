package com.localscanner.app.domain

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * On-device Tesseract 5 wrapper (via Tesseract4Android) providing Bengali +
 * English recognition. Trained data (tessdata_fast) ships inside the APK and
 * is copied to app storage on first use - recognition never touches network.
 */
class TessOcr(private val context: Context) {

    private val api = TessBaseAPI()
    private var ready = false
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun prepareAssets() {
        scope.launch { runCatching { copyTessDataIfNeeded() } }
    }

    private fun tessRoot(): File = File(context.filesDir, "tesseract")

    private fun copyTessDataIfNeeded() {
        val dir = File(tessRoot(), "tessdata").apply { mkdirs() }
        for (name in TRAINED_DATA) {
            val out = File(dir, name)
            if (!out.exists() || out.length() < 1000) {
                context.assets.open("tessdata/$name").use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    suspend fun ensureReady() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            copyTessDataIfNeeded()
            if (!ready) {
                val ok = api.init(tessRoot().absolutePath, "ben+eng")
                if (!ok) throw IllegalStateException("Tesseract failed to initialise")
                ready = true
            }
        }
    }

    suspend fun recognize(bitmap: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        ensureReady()
        val scaled = downscaleForOcr(bitmap, 1600)
        synchronized(lock) {
            api.setImage(scaled)
            val fullText = api.getUTF8Text() ?: ""
            val lines = mutableListOf<OcrLine>()
            val iterator = api.getResultIterator()
            if (iterator != null) {
                try {
                    iterator.begin()
                    do {
                        val line = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                        val rect = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                        if (!line.isNullOrBlank()) {
                            val box = if (rect == null) {
                                null
                            } else {
                                IRect(rect.left, rect.top, rect.right, rect.bottom)
                            }
                            lines.add(OcrLine(line.trim(), box))
                        }
                    } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
                } catch (t: Throwable) {
                    // Box extraction is best-effort; text itself is already captured.
                }
            }
            api.clear()
            OcrResult(TextUtils.cleanOcrText(fullText), lines, OcrEngineOption.TESS_BEN_EN.id)
        }
    }

    fun release() {
        synchronized(lock) {
            runCatching { api.recycle() }
            ready = false
        }
    }

    private fun downscaleForOcr(src: Bitmap, maxDim: Int): Bitmap {
        val biggest = max(src.width, src.height)
        if (biggest <= maxDim) return src
        val scale = maxDim.toFloat() / biggest
        val w = (src.width * scale).toInt().coerceAtLeast(32)
        val h = (src.height * scale).toInt().coerceAtLeast(32)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    companion object {
        const val LANGS = "ben+eng"
        private val TRAINED_DATA = listOf("eng.traineddata", "ben.traineddata")
    }
}
