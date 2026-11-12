package com.localscanner.app.domain

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Facade over the two on-device OCR engines:
 *  - ML Kit v2 (bundled Latin model): fast, high accuracy for English.
 *  - Tesseract 5 (ben+eng): Bengali support, still fully offline.
 */
class OcrEngine(private val context: Context, private val tess: TessOcr) {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun recognize(bitmap: Bitmap, engine: OcrEngineOption): OcrResult = when (engine) {
        OcrEngineOption.MLKIT_EN -> recognizeMlKit(bitmap)
        OcrEngineOption.TESS_BEN_EN -> tess.recognize(bitmap)
    }

    private suspend fun recognizeMlKit(bitmap: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val text = recognizer.process(image).await()
        val lines = text.textBlocks
            .flatMap { it.lines }
            .map { OcrLine(it.text.trim(), it.boundingBox?.let { r -> IRect(r.left, r.top, r.right, r.bottom) }) }
            .filter { it.text.isNotEmpty() }
        OcrResult(TextUtils.cleanOcrText(text.text), lines, OcrEngineOption.MLKIT_EN.id)
    }

    fun close() {
        runCatching { recognizer.close() }
    }
}
