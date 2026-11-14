package com.localscanner.app.ui.screens.filter

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localscanner.app.LocalScannerApp
import com.localscanner.app.data.DocsRepository
import com.localscanner.app.data.ScanSession
import com.localscanner.app.domain.FilterType
import com.localscanner.app.domain.ScannerVision
import com.localscanner.app.util.ImageFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * CamScanner-style live effects editing: filter / brightness / contrast
 * changes re-render the preview immediately (sliders are debounced), and a
 * row of filter thumbnails previews every mode at a glance.
 */
class FilterViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as LocalScannerApp).container
    val session: ScanSession = container.scanSession
    private val repo = container.repository
    private val settingsRepo = container.settings

    var index: Int by mutableStateOf(0)
        private set
    var preview: Bitmap? by mutableStateOf(null)
        private set

    /** The current page unfiltered, for press-and-hold before/after compare. */
    var baseBitmap: Bitmap? by mutableStateOf(null)
        private set

    /** Small live thumbnails (one per filter) rendered from the current page. */
    var filterThumbs: Map<FilterType, Bitmap> by mutableStateOf(emptyMap())
        private set

    var busy: Boolean by mutableStateOf(false)
        private set
    var saving: Boolean by mutableStateOf(false)
        private set
    var progress: Float by mutableStateOf(0f)
        private set

    var filter: FilterType by mutableStateOf(FilterType.MAGIC_COLOR)
        private set
    var brightness: Int by mutableStateOf(0)
        private set
    var contrast: Int by mutableStateOf(100)
        private set
    var applyToAll: Boolean by mutableStateOf(false)
    var folderName: String by mutableStateOf("")

    private val edits = mutableListOf<PageEdit>()
    val pageCount: Int get() = session.warpedFiles.size
    val hasPages: Boolean get() = session.warpedFiles.isNotEmpty()

    /**
     * Screenshot-test hook: Robolectric has no OpenCV native library, so tests
     * preload the preview directly and loadCurrent() becomes a no-op.
     */
    internal var screenshotSeeded = false

    internal fun seedForScreenshot(bmp: Bitmap) {
        screenshotSeeded = true
        preview = bmp
        busy = false
    }

    data class PageEdit(val filter: FilterType, val brightness: Int, val contrast: Int)

    private var renderJob: Job? = null
    private var thumbJob: Job? = null

    init {
        viewModelScope.launch {
            val s = settingsRepo.settings.first()
            filter = s.defaultFilter
            edits.addAll(session.warpedFiles.map { PageEdit(filter, 0, 100) })
            loadCurrent()
        }
    }

    private fun editFor(i: Int): PageEdit = edits.getOrNull(i) ?: PageEdit(filter, brightness, contrast)

    fun loadCurrent() {
        if (screenshotSeeded) return
        renderJob?.cancel()
        thumbJob?.cancel()
        viewModelScope.launch {
            busy = true
            val file = session.warpedFiles.getOrNull(index)
            if (file == null) {
                busy = false
                return@launch
            }
            val edit = editFor(index)
            filter = edit.filter
            brightness = edit.brightness
            contrast = edit.contrast
            val bmp = withContext(Dispatchers.IO) { ImageFiles.decodeScaled(file.absolutePath, 1000) }
            val oldBase = baseBitmap
            baseBitmap = bmp
            if (oldBase != null && oldBase !== bmp) oldBase.recycle()
            renderPreview(debounceMs = 0L)
            renderThumbs()
            busy = false
        }
    }

    // ---- live editing entry points -------------------------------------

    /** Filter chip tapped: re-render at once. */
    fun updateFilter(f: FilterType) {
        if (filter == f) return
        filter = f
        commitEdit()
        renderPreview(debounceMs = 0L)
    }

    /** Slider drag: debounced re-render keeps it responsive. */
    fun updateBrightness(v: Int) {
        brightness = v
        commitEdit()
        renderPreview(debounceMs = TONE_DEBOUNCE_MS)
    }

    fun updateContrast(v: Int) {
        contrast = v
        commitEdit()
        renderPreview(debounceMs = TONE_DEBOUNCE_MS)
    }

    fun resetAdjustments() {
        brightness = 0
        contrast = 100
        commitEdit()
        renderPreview(debounceMs = 0L)
    }

    fun updateApplyToAll(v: Boolean) {
        applyToAll = v
        if (v) commitEdit()
    }

    /** Stores the current page's edit (and optionally applies it to all pages). */
    fun commitCurrentEdit() {
        commitEdit()
    }

    private fun commitEdit() {
        val edit = PageEdit(filter, brightness, contrast)
        while (edits.size < session.warpedFiles.size) edits.add(edit)
        edits[index] = edit
        if (applyToAll) {
            for (i in edits.indices) edits[i] = edit
        }
    }

    private fun renderPreview(debounceMs: Long) {
        if (screenshotSeeded) return
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val src = baseBitmap ?: return@launch
            val rendered = withContext(Dispatchers.Default) {
                ScannerVision.applyFilter(src, filter, brightness, contrast)
            }
            if (!isActive) {
                rendered.recycle()
                return@launch
            }
            val old = preview
            preview = rendered
            if (old != null && old !== rendered && old !== baseBitmap) old.recycle()
        }
    }

    private fun renderThumbs() {
        if (screenshotSeeded) return
        thumbJob?.cancel()
        thumbJob = viewModelScope.launch {
            val src = baseBitmap ?: return@launch
            val small = withContext(Dispatchers.Default) { ImageFiles.downscale(src, 144) }
            val out = mutableMapOf<FilterType, Bitmap>()
            FilterType.entries.forEach { f ->
                val thumb = withContext(Dispatchers.Default) {
                    ScannerVision.applyFilter(small, f, 0, 100)
                }
                if (!isActive) {
                    thumb.recycle()
                    return@launch
                }
                out[f] = thumb
            }
            if (small !== baseBitmap && small !== src) small.recycle()
            val oldThumbs = filterThumbs
            filterThumbs = out
            oldThumbs.values.forEach { it.recycle() }
        }
    }

    fun next() {
        if (index < pageCount - 1) {
            commitEdit()
            index++
            loadCurrent()
        }
    }

    fun previous() {
        if (index > 0) {
            commitEdit()
            index--
            loadCurrent()
        }
    }

    /** Applies filters at full resolution, persists the document, resets the session. */
    fun save(onDone: (String) -> Unit) {
        if (saving || session.warpedFiles.isEmpty()) return
        commitEdit()
        saving = true
        progress = 0f
        viewModelScope.launch {
            try {
                val targetDocId = session.targetDocId
                val results = mutableListOf<DocsRepository.PageResult>()
                val total = session.warpedFiles.size
                session.warpedFiles.forEachIndexed { i, file ->
                    val edit = editFor(i)
                    val final = withContext(Dispatchers.Default) {
                        val bmp = ImageFiles.decodeScaled(file.absolutePath, 2048)
                        if (bmp == null) {
                            null
                        } else {
                            val out = ScannerVision.applyFilter(bmp, edit.filter, edit.brightness, edit.contrast)
                            bmp.recycle()
                            out
                        }
                    }
                    if (final != null) {
                        val f = session.newWarpedFile()
                        withContext(Dispatchers.IO) { ImageFiles.saveJpeg(final, f, 92) }
                        final.recycle()
                        results.add(DocsRepository.PageResult(file = f))
                    }
                    progress = (i + 1) / total.toFloat()
                }

                val docId: String = if (targetDocId != null) {
                    repo.appendPages(targetDocId, results)
                    targetDocId
                } else {
                    repo.createDocFromFiles(
                        title = null,
                        folder = folderName.takeIf { it.isNotBlank() },
                        finalPages = results,
                    ).id
                }
                session.reset()
                saving = false
                onDone(docId)
            } catch (t: Throwable) {
                saving = false
            }
        }
    }

    fun discardRemaining() {
        renderJob?.cancel()
        thumbJob?.cancel()
        preview?.recycle()
        preview = null
        baseBitmap?.recycle()
        baseBitmap = null
        filterThumbs.values.forEach { it.recycle() }
        filterThumbs = emptyMap()
    }

    companion object {
        const val TONE_DEBOUNCE_MS = 220L
    }
}
