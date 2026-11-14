package com.localscanner.app.ui.screens.crop

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localscanner.app.LocalScannerApp
import com.localscanner.app.data.ScanSession
import com.localscanner.app.domain.Pt
import com.localscanner.app.domain.QuadF
import com.localscanner.app.domain.ScannerVision
import com.localscanner.app.util.ImageFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

class CropViewModel(app: Application) : AndroidViewModel(app) {

    val session = (app as LocalScannerApp).container.scanSession

    var index: Int by mutableStateOf(0)
        private set
    var bitmap: Bitmap? by mutableStateOf(null)
        private set
    var quad: QuadF? by mutableStateOf(null)
    var busy: Boolean by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set
    var message: String? by mutableStateOf(null)
        private set

    /** Where the current quad came from: FULL (fallback), HINT (live view), DETECTED, INSET (safe default). */
    enum class QuadSource { FULL, HINT, DETECTED, INSET }

    var quadSource: QuadSource by mutableStateOf(QuadSource.FULL)
        private set

    /**
     * CamScanner-style auto-crop: armed when the current page was cropped
     * automatically and the screen should advance on its own (single-shot
     * flow). Cancelled the moment the user touches a corner.
     */
    var autoCropPending: Boolean by mutableStateOf(false)
        private set

    val pageCount: Int get() = session.rawFiles.size
    val hasPages: Boolean get() = session.rawFiles.isNotEmpty()

    /**
     * Screenshot-test hook: Robolectric has no OpenCV native library, so tests
     * preload the state directly and loadCurrent() becomes a no-op.
     */
    internal var screenshotSeeded = false

    internal fun seedForScreenshot(bmp: Bitmap, q: QuadF) {
        screenshotSeeded = true
        bitmap = bmp
        quad = q
        busy = false
    }

    init {
        if (session.rawFiles.isNotEmpty() && session.warpedFiles.isNotEmpty() && session.importMode) {
            // Imported images skip cropping entirely.
        }
    }

    /** True in the single-shot flow: one page, nothing processed yet. */
    private val isSingleShotFlow: Boolean
        get() = session.rawFiles.size == 1 && session.warpedFiles.isEmpty()

    fun loadCurrent() {
        if (screenshotSeeded) return
        viewModelScope.launch {
            busy = true
            autoCropPending = false
            val file = session.rawFiles.getOrNull(index)
            if (file == null) {
                bitmap = null
                quad = null
                busy = false
                return@launch
            }
            val bmp = withContext(Dispatchers.IO) { ImageFiles.decodeScaled(file.absolutePath, 2000) }
            bitmap = bmp
            val detected = bmp?.let { aspect ->
                withContext(Dispatchers.Default) {
                    ScannerVision.detectQuad(aspect, 1000)?.let { ScannerVision.refineQuad(aspect, it) }
                }
            }
            val hint = session.rawHints.getOrNull(index)
            when {
                detected != null -> {
                    quad = detected
                    quadSource = QuadSource.DETECTED
                }
                hint != null && bmp != null && aspectClose(bmp, hint) -> {
                    // Still-photo detection failed; reuse the quad the live
                    // view detected right before the shutter fired.
                    quad = hint.quad
                    quadSource = QuadSource.HINT
                }
                bmp != null && withContext(Dispatchers.Default) { ScannerVision.looksPaperFilled(bmp) } -> {
                    // Nothing detected, but the frame is already paper: the
                    // full page IS the correct selection.
                    quad = QuadF.fullFrame()
                    quadSource = QuadSource.FULL
                }
                else -> {
                    // Never silently select the whole frame: start from an
                    // obvious inset rectangle the user can drag.
                    quad = QuadF.insetFrame(FALLBACK_INSET)
                    quadSource = QuadSource.INSET
                }
            }
            busy = false
            // Auto-crop like CamScanner: in the single-shot flow a confident
            // quad is applied immediately without a manual "Crop" tap.
            if (isSingleShotFlow && (quadSource == QuadSource.DETECTED || quadSource == QuadSource.HINT)) {
                autoCropPending = true
            }
        }
    }

    /** Hint coordinates are only valid when the still photo has ~the same aspect. */
    private fun aspectClose(bmp: Bitmap, hint: ScanSession.RawHint): Boolean {
        val photoAspect = bmp.width.toFloat() / bmp.height.toFloat()
        if (hint.aspect <= 0f) return false
        val relDiff = kotlin.math.abs(photoAspect - hint.aspect) / hint.aspect
        return relDiff <= 0.12f
    }

    fun cancelAutoCrop() {
        autoCropPending = false
    }

    /** Confirms the crop for the current page and advances. */
    fun applyAndAdvance(onAllDone: () -> Unit) {
        val bmp = bitmap ?: return
        val q = quad ?: return
        autoCropPending = false
        viewModelScope.launch {
            busy = true
            error = null
            val warped = withContext(Dispatchers.Default) { ScannerVision.warpPerspective(bmp, q, 2048) }
            val file = session.newWarpedFile()
            val ok = warped != null && withContext(Dispatchers.IO) { ImageFiles.saveJpeg(warped, file, 92) }
            warped?.recycle()
            if (!ok) {
                busy = false
                error = "Could not crop this page"
                return@launch
            }
            session.addWarped(file)
            advanceOrFinish(onAllDone)
        }
    }

    /** Adds the raw, uncropped image as-is. */
    fun skipAndAdvance(onAllDone: () -> Unit) {
        autoCropPending = false
        val file = session.rawFiles.getOrNull(index) ?: return
        session.addWarped(file)
        advanceOrFinish(onAllDone)
    }

    /**
     * CamScanner-style "auto-crop all": detects + warps every remaining page
     * without individual review. Pages where detection fails keep the full
     * frame so nothing is lost.
     */
    fun autoCropAll(onAllDone: () -> Unit) {
        if (busy) return
        autoCropPending = false
        viewModelScope.launch {
            busy = true
            var cropped = 0
            var processed = 0
            while (index < session.rawFiles.size) {
                val file = session.rawFiles[index]
                val bmp = withContext(Dispatchers.IO) { ImageFiles.decodeScaled(file.absolutePath, 2048) }
                if (bmp == null) {
                    session.addWarped(file)
                } else {
                    val detected = withContext(Dispatchers.Default) {
                        ScannerVision.detectQuad(bmp, 1000)?.let { ScannerVision.refineQuad(bmp, it) }
                    }
                    val warped = detected?.let {
                        withContext(Dispatchers.Default) { ScannerVision.warpPerspective(bmp, it, 2048) }
                    }
                    val out = warped ?: bmp
                    if (warped != null) cropped++
                    processed++
                    val f = session.newWarpedFile()
                    val ok = withContext(Dispatchers.IO) { ImageFiles.saveJpeg(out, f, 92) }
                    if (ok) session.addWarped(f)
                    if (warped != null && warped !== bmp) warped.recycle()
                    bmp.recycle()
                }
                index++
            }
            bitmap?.recycle()
            bitmap = null
            busy = false
            message = "Auto-cropped $cropped of $processed pages"
            onAllDone()
        }
    }

    private fun advanceOrFinish(onAllDone: () -> Unit) {
        val next = index + 1
        if (next >= session.rawFiles.size) {
            busy = false
            bitmap?.recycle()
            bitmap = null
            onAllDone()
        } else {
            index = next
            loadCurrent()
        }
    }

    fun rotate() {
        val file = session.rawFiles.getOrNull(index) ?: return
        viewModelScope.launch {
            busy = true
            // A rotated photo invalidates the live-view hint coordinates.
            while (session.rawHints.size <= index) session.rawHints.add(null)
            session.rawHints[index] = null
            withContext(Dispatchers.IO) { ImageFiles.rotateFile(file.absolutePath, 90f) }
            loadCurrent()
        }
    }

    fun resetQuad() {
        viewModelScope.launch {
            val bmp = bitmap ?: return@launch
            autoCropPending = false
            val detected = withContext(Dispatchers.Default) {
                ScannerVision.detectQuad(bmp, 1000)?.let { ScannerVision.refineQuad(bmp, it) }
            }
            when {
                detected != null -> {
                    quad = detected
                    quadSource = QuadSource.DETECTED
                }
                withContext(Dispatchers.Default) { ScannerVision.looksPaperFilled(bmp) } -> {
                    quad = QuadF.fullFrame()
                    quadSource = QuadSource.FULL
                }
                else -> {
                    quad = QuadF.insetFrame(FALLBACK_INSET)
                    quadSource = QuadSource.INSET
                }
            }
        }
    }

    fun manualFrame() {
        autoCropPending = false
        quad = QuadF.fullFrame()
        quadSource = QuadSource.FULL
    }

    /** Moves a corner (0..3: tl, tr, br, bl) by a normalized delta.
     *  Guards against collapsing or twisting the quad (perspective shift). */
    fun moveCorner(corner: Int, dx: Float, dy: Float) {
        quad = quad?.movedCorner(corner, dx, dy) ?: return
    }

    /**
     * CamScanner-style edge drag (0..3: top, right, bottom, left): the two
     * corners of the edge translate along the edge normal. Rejected moves
     * (inverted / collapsed quad) keep the previous quad.
     */
    fun moveEdge(edge: Int, dx: Float, dy: Float) {
        quad = quad?.movedEdge(edge, dx, dy) ?: return
    }

    fun dismissError() {
        error = null
    }

    fun dismissMessage() {
        message = null
    }

    companion object {
        /** Margin of the default selection rectangle when detection fails. */
        const val FALLBACK_INSET = 0.08f
    }
}
