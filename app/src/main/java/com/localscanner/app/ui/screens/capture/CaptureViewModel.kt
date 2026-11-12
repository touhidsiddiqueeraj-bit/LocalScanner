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
import com.localscanner.app.domain.QuadF
import com.localscanner.app.domain.ScannerVision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.abs
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

    val analysisExecutor: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor()

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

    var autoCaptureEnabled: Boolean by mutableStateOf(true)
    var flashMode: Int by mutableStateOf(ImageCapture.FLASH_MODE_OFF)
        private set

    /** Increments whenever the auto-capture condition fires; UI observes it. */
    var autoTrigger: Long by mutableStateOf(0L)
        private set

    private var lastQuad: QuadF? = null
    private var stableFrames = 0
    private var lastCaptureAt = 0L
    private var tone: ToneGenerator? = null

    init {
        // Carry default from settings; a fresh entry clears finished sessions.
        if (!session.hasWork()) {
            session.reset()
        }
    }

    fun setFlash(mode: Int) {
        flashMode = mode
        imageCapture.flashMode = mode
    }

    fun beginScan() {
        if (!session.hasWork()) session.reset()
    }

    /** Analyzer callback: RGBA frame -> bitmap -> quad detection -> stability. */
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
        val quad = ScannerVision.detectQuad(bmp, 384)
        bmp.recycle()
        detectedQuad = quad
        updateStability(quad)
        proxy.close()
    }

    private fun updateStability(quad: QuadF?) {
        val prev = lastQuad
        val stable = quad != null && prev != null &&
            QuadF.centroidDistance(quad, prev) <= 0.035f &&
            abs(quad.area() - prev.area()) <= 0.05f
        stableFrames = if (stable) stableFrames + 1 else 0
        lastQuad = quad

        if (autoCaptureEnabled && stableFrames >= AUTO_STABLE_FRAMES && !capturing &&
            quad != null && quad.area() >= MIN_DOC_AREA &&
            System.currentTimeMillis() - lastCaptureAt > AUTO_COOLDOWN_MS
        ) {
            lastCaptureAt = System.currentTimeMillis()
            autoTrigger++
        }
    }

    fun capture() {
        if (capturing) return
        capturing = true
        val file: File = session.newRawFile()
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        val context = getApplication<Application>()
        imageCapture.takePicture(
            options,
            androidx.core.content.ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    session.addRaw(file)
                    pagesCaptured = session.rawFiles.size
                    capturing = false
                    beep()
                }

                override fun onError(exception: ImageCaptureException) {
                    file.delete()
                    capturing = false
                }
            },
        )
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
        const val AUTO_STABLE_FRAMES = 10
        const val MIN_DOC_AREA = 0.18f
        const val AUTO_COOLDOWN_MS = 2_500L
    }
}
