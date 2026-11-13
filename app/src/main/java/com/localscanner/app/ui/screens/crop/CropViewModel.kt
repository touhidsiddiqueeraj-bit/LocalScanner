package com.localscanner.app.ui.screens.crop

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localscanner.app.LocalScannerApp
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

    fun loadCurrent() {
        if (screenshotSeeded) return
        viewModelScope.launch {
            busy = true
            val file = session.rawFiles.getOrNull(index)
            if (file == null) {
                bitmap = null
                quad = null
                busy = false
                return@launch
            }
            val bmp = withContext(Dispatchers.IO) { ImageFiles.decodeScaled(file.absolutePath, 1400) }
            bitmap = bmp
            quad = withContext(Dispatchers.Default) {
                bmp?.let { ScannerVision.detectQuad(it, 900) } ?: QuadF.fullFrame()
            }
            busy = false
        }
    }

    /** Confirms the crop for the current page and advances. */
    fun applyAndAdvance(onAllDone: () -> Unit) {
        val bmp = bitmap ?: return
        val q = quad ?: return
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
        val file = session.rawFiles.getOrNull(index) ?: return
        session.addWarped(file)
        advanceOrFinish(onAllDone)
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
            withContext(Dispatchers.IO) { ImageFiles.rotateFile(file.absolutePath, 90f) }
            loadCurrent()
        }
    }

    fun resetQuad() {
        viewModelScope.launch {
            val bmp = bitmap ?: return@launch
            quad = withContext(Dispatchers.Default) {
                ScannerVision.detectQuad(bmp, 900) ?: QuadF.fullFrame()
            }
        }
    }

    fun manualFrame() {
        quad = QuadF.fullFrame()
    }

    /** Moves a corner (0..3: tl, tr, br, bl) by a normalized delta. */
    fun moveCorner(corner: Int, dx: Float, dy: Float) {
        val current = quad ?: return
        fun mv(p: Pt): Pt = Pt((p.x + dx).coerceIn(0f, 1f), (p.y + dy).coerceIn(0f, 1f))
        quad = when (corner) {
            0 -> current.copy(tl = mv(current.tl))
            1 -> current.copy(tr = mv(current.tr))
            2 -> current.copy(br = mv(current.br))
            else -> current.copy(bl = mv(current.bl))
        }
    }

    fun dismissError() {
        error = null
    }
}
