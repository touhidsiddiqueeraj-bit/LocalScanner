package com.localscanner.app.ui.screens.capture

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localscanner.app.LocalScannerApp
import com.localscanner.app.data.ScanSession
import com.localscanner.app.domain.CaptureMode
import com.localscanner.app.domain.QuadF
import com.localscanner.app.domain.ScannerVision
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class CaptureViewModel(app: Application) : AndroidViewModel(app) {

    val container = (app as LocalScannerApp).container
    val session: ScanSession = container.scanSession

    val imageCapture: ImageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .build()

    val imageAnalysis: ImageAnalysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        .build()

    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /** Document quad in normalized coordinates, updated live by the analyzer. */
    var detectedQuad: QuadF? by mutableStateOf(null)
        private set

    /** Aspect ratio (w/h) of the current analyzer frame in display orientation. */
    var frameAspect: Float by mutableStateOf(0.75f)
        private set

    var pagesCaptured: Int by mutableStateOf(session.rawFiles.size)
        private set

    var capturing: Boolean by mutableStateOf(false)
        private set

    /** Single / Batch shooting mode (persisted in settings). */
    var captureMode: CaptureMode by mutableStateOf(CaptureMode.SINGLE)
        private set

    /** Set once a page is captured in SINGLE mode; the screen navigates onward. */
    var singleCaptureDone: Boolean by mutableStateOf(false)
        private set

    var flashMode: Int by mutableStateOf(ImageCapture.FLASH_MODE_OFF)
        private set

    private var tone: ToneGenerator? = null

    init {
        // Carry default from settings; a fresh entry clears finished sessions.
        if (!session.hasWork()) {
            session.reset()
        }
        viewModelScope.launch {
            captureMode = container.settings.settings.first().captureMode
        }
    }

    fun setFlash(mode: Int) {
        flashMode = mode
        imageCapture.flashMode = mode
    }

    fun updateCaptureMode(mode: CaptureMode) {
        captureMode = mode
        viewModelScope.launch { container.settings.setCaptureMode(mode) }
    }

    fun beginScan() {
        if (!session.hasWork()) session.reset()
    }

    /** Analyzer callback: RGBA frame -> bitmap -> quad detection (live edges only). */
    fun analyze(proxy: ImageProxy) {
        val bmp = try {
            proxyToBitmap(proxy)
        } catch (t: Throwable) {
            null
        }
        if (bmp == null) {
            proxy.close()
            return
        }
        frameAspect = bmp.width.toFloat() / bmp.height.toFloat()
        detectedQuad = ScannerVision.detectQuad(bmp, 448)
        bmp.recycle()
        proxy.close()
    }

    fun capture() {
        if (capturing) return
        capturing = true
        val file: File = session.newRawFile()
        // Keep the live-view quad as a hint for the crop stage: the analyzer
        // frame and the JPEG usually share the same 4:3 aspect, so normalized
        // coordinates transfer 1:1 when the still-photo detection fails.
        val hint = detectedQuad?.let { ScanSession.RawHint(it, frameAspect) }
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        val context = getApplication<Application>()
        imageCapture.takePicture(
            options,
            androidx.core.content.ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    session.addRaw(file, hint)
                    pagesCaptured = session.rawFiles.size
                    capturing = false
                    beep()
                    if (captureMode == CaptureMode.SINGLE) singleCaptureDone = true
                }

                override fun onError(exception: ImageCaptureException) {
                    file.delete()
                    capturing = false
                }
            },
        )
    }

    fun consumeSingleCaptureDone() {
        singleCaptureDone = false
    }

    fun finish() {
        // Crop stage consumes session.rawFiles.
    }

    private fun beep() {
        runCatching {
            val t = tone ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).also { tone = it }
            t.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        }
    }

    /** Converts an RGBA_8888 ImageProxy to a display-oriented Bitmap. */
    private fun proxyToBitmap(proxy: ImageProxy): Bitmap? {
        val plane = proxy.planes[0]
        val w = proxy.width
        val h = proxy.height
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride
        val buffer = plane.buffer

        val bitmap = if (rowStride == w * pixStride && buffer.remaining() >= w * h * pixStride) {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                buffer.rewind()
                copyPixelsFromBuffer(buffer)
            }
        } else {
            val data = ByteArray(w * h * pixStride)
            var offset = 0
            for (row in 0 until h) {
                buffer.position(row * rowStride)
                buffer.get(data, offset, w * pixStride)
                offset += w * pixStride
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                copyPixelsFromBuffer(ByteBuffer.wrap(data))
            }
        }

        val rotation = proxy.imageInfo.rotationDegrees
        if (rotation == 0) return bitmap
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }

    override fun onCleared() {
        super.onCleared()
        analysisExecutor.shutdown()
        runCatching { tone?.release() }
        tone = null
    }

    companion object {
        /** Live-view frames below this area ratio are unlikely to be documents. */
        const val MIN_DOC_AREA = 0.18f
    }
}
