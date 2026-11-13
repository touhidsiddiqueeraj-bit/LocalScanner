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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

class FilterViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as LocalScannerApp).container
    val session: ScanSession = container.scanSession
    private val repo = container.repository
    private val settingsRepo = container.settings

    var index: Int by mutableStateOf(0)
        private set
    var preview: Bitmap? by mutableStateOf(null)
        private set
    var busy: Boolean by mutableStateOf(false)
        private set
    var saving: Boolean by mutableStateOf(false)
        private set
    var progress: Float by mutableStateOf(0f)
        private set

    var filter: FilterType by mutableStateOf(FilterType.MAGIC_COLOR)
    var brightness: Int by mutableStateOf(0)
    var contrast: Int by mutableStateOf(100)
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

    init {
        viewModelScope.launch {
            val s = settingsRepo.settings.first()
            filter = s.defaultFilter
            edits.addAll(session.warpedFiles.map { PageEdit(filter, 0, 100) })
            loadCurrent()
        }
    }

    fun loadCurrent() {
        if (screenshotSeeded) return
        viewModelScope.launch {
            busy = true
            val file = session.warpedFiles.getOrNull(index) ?: return@launch
            val edit = editFor(index)
            val bmp = withContext(Dispatchers.IO) { ImageFiles.decodeScaled(file.absolutePath, 1000) }
            preview?.recycle()
            preview = bmp?.let {
                withContext(Dispatchers.Default) {
                    ScannerVision.applyFilter(it, edit.filter, edit.brightness, edit.contrast)
                }.also { if (it !== bmp) bmp.recycle() }
            }
            busy = false
        }
    }

    private fun editFor(i: Int): PageEdit = edits.getOrNull(i) ?: PageEdit(filter, brightness, contrast)

    /** Stores the current page's edit (and optionally applies it to all pages). */
    fun commitCurrentEdit() {
        val edit = PageEdit(filter, brightness, contrast)
        while (edits.size < session.warpedFiles.size) edits.add(edit)
        edits[index] = edit
        if (applyToAll) {
            for (i in edits.indices) edits[i] = edit
        }
    }

    fun next() {
        if (index < pageCount - 1) {
            commitCurrentEdit()
            index++
            loadCurrent()
        }
    }

    fun previous() {
        if (index > 0) {
            commitCurrentEdit()
            index--
            loadCurrent()
        }
    }

    /** Applies filters at full resolution, persists the document, resets the session. */
    fun save(onDone: (String) -> Unit) {
        if (saving || session.warpedFiles.isEmpty()) return
        commitCurrentEdit()
        saving = true
        progress = 0f
        viewModelScope.launch {
            try {
                val targetDocId = session.targetDocId
                val results = mutableListOf<DocsRepository.PageResult>()
                val finalFiles = mutableListOf<File>()
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
                        finalFiles.add(f)
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
        preview?.recycle()
        preview = null
    }
}
