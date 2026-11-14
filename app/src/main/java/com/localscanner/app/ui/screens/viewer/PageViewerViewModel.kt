package com.localscanner.app.ui.screens.viewer

import android.app.Application
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localscanner.app.LocalScannerApp
import com.localscanner.app.data.db.PageEntity
import com.localscanner.app.domain.OcrEngineOption
import com.localscanner.app.domain.OcrLine
import com.localscanner.app.domain.OcrLinesCodec
import com.localscanner.app.domain.TextUtils
import com.localscanner.app.util.ImageFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * Powers the page viewer: the scanned page is shown full-screen with the
 * recognized text drawn ON the image (tap a line to select and copy it) and
 * a search that highlights matching lines across all pages. There is no
 * separate OCR screen any more.
 */
class PageViewerViewModel(
    app: Application,
    private val docId: String,
) : AndroidViewModel(app) {

    private val container = (app as LocalScannerApp).container
    private val repo = container.repository

    val pages: StateFlow<List<PageEntity>> = repo.observePages(docId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var pageIndex: Int by mutableStateOf(0)
    var engine: OcrEngineOption by mutableStateOf(OcrEngineOption.MLKIT_EN)
    var running: Boolean by mutableStateOf(false)
        private set
    var runAllProgress: Int? by mutableStateOf(null)
        private set
    var error: String? by mutableStateOf(null)
        private set

    // Search
    var searchActive: Boolean by mutableStateOf(false)
    var query: String by mutableStateOf("")
    var resultsVisible: Boolean by mutableStateOf(false)
        private set

    // Selected OCR line on the current page (index within that page's lines).
    var selectedLineIndex: Int? by mutableStateOf(null)
    var selectedLineText: String? by mutableStateOf(null)
        private set

    /** aspect (w/h) cache per page id, for the on-image text overlay mapping. */
    val pageAspects = mutableStateMapOf<String, Float>()

    /** Bumped after rotate/delete so image loads re-read the changed file. */
    var imageRevision: Int by mutableStateOf(0)
        private set

    private var exportedTxt: File? = null

    data class SearchHit(val pageIndex: Int, val pageLabel: Int, val text: String)

    private var _hits: List<SearchHit> = emptyList()
    val hits: List<SearchHit> get() = _hits

    init {
        viewModelScope.launch {
            engine = container.settings.settings.first().ocrEngine
        }
    }

    /** Normalized OCR line boxes for a page (null when none / legacy data). */
    fun linesFor(page: PageEntity?): List<Pair<OcrLine, OcrLinesCodec.FRect>>? =
        OcrLinesCodec.decode(page?.ocrLinesJson)?.normalizedLines()

    /** Loads the page image aspect (bounds decode only - cheap). */
    fun ensureAspect(page: PageEntity) {
        if (pageAspects.containsKey(page.id)) return
        viewModelScope.launch(Dispatchers.IO) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(page.filePath, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                pageAspects[page.id] = opts.outWidth.toFloat() / opts.outHeight.toFloat()
            }
        }
    }

    val currentText: String
        get() = pages.value.getOrNull(pageIndex.coerceIn(0, (pages.value.size - 1).coerceAtLeast(0)))?.ocrText ?: ""

    val hasCurrentText: Boolean
        get() = pages.value.getOrNull(pageIndex)?.ocrText?.isNotBlank() == true

    /** Searches the recognized text of every page; results highlight on-image. */
    fun search() {
        val q = query.trim()
        if (q.isEmpty()) {
            resultsVisible = false
            _hits = emptyList()
            return
        }
        val hits = mutableListOf<SearchHit>()
        pages.value.forEachIndexed { i, page ->
            page.ocrText?.takeIf { it.isNotBlank() }?.lines()?.forEach { line ->
                if (line.contains(q, ignoreCase = true)) {
                    hits.add(SearchHit(i, i + 1, line.trim()))
                }
            }
        }
        _hits = hits
        resultsVisible = true
    }

    fun closeResults() {
        resultsVisible = false
    }

    /** Jumps to a search hit and highlights the matching line on the image. */
    fun jumpTo(hit: SearchHit) {
        pageIndex = hit.pageIndex
        resultsVisible = false
        clearSelection()
    }

    fun selectLine(lines: List<Pair<OcrLine, OcrLinesCodec.FRect>>, index: Int) {
        if (index in lines.indices) {
            selectedLineIndex = index
            selectedLineText = lines[index].first.text
        } else {
            clearSelection()
        }
    }

    fun clearSelection() {
        selectedLineIndex = null
        selectedLineText = null
    }

    fun cycleEngine() {
        engine = if (engine == OcrEngineOption.MLKIT_EN) OcrEngineOption.TESS_BEN_EN
        else OcrEngineOption.MLKIT_EN
        viewModelScope.launch { container.settings.setOcrEngine(engine) }
    }

    /** OCR the current page; boxes are stored with the bitmap reference size. */
    fun runOcrCurrent() {
        val page = pages.value.getOrNull(pageIndex) ?: return
        runFor(listOf(page))
    }

    fun runOcrAll() {
        if (runAllProgress != null) return
        viewModelScope.launch { runFor(repo.getPages(docId)) }
    }

    private fun runFor(targets: List<PageEntity>) {
        if (running || targets.isEmpty()) return
        running = true
        error = null
        viewModelScope.launch {
            try {
                targets.forEachIndexed { i, page ->
                    val bmp = withContext(Dispatchers.IO) {
                        ImageFiles.decodeScaled(page.filePath, 1600)
                    }
                    if (bmp != null) {
                        val result = container.ocr.recognize(bmp, engine)
                        val linesJson = OcrLinesCodec.encode(bmp.width, bmp.height, result.lines)
                        bmp.recycle()
                        repo.setOcr(page, result.text, result.engine, linesJson)
                    }
                    if (targets.size > 1) runAllProgress = i + 1
                }
            } catch (t: Throwable) {
                error = "OCR failed: ${t.message ?: "unknown error"}"
            } finally {
                running = false
                runAllProgress = null
            }
        }
    }

    fun exportTxt(onReady: (File) -> Unit) {
        viewModelScope.launch {
            val list = repo.getPages(docId)
            val doc = repo.getDoc(docId)
            val combined = list.mapIndexed { i, p ->
                "---- Page ${i + 1} ----\n${p.ocrText ?: ""}"
            }.joinToString("\n\n")
            val dir = container.repository.exportsDir()
            val file = File(dir, "${TextUtils.sanitizeFileName(doc?.title ?: "scan")}_ocr.txt")
            withContext(Dispatchers.IO) {
                file.writeText(combined)
            }
            exportedTxt = file
            onReady(file)
        }
    }

    fun shareTextFile(): File? {
        val dir = container.repository.exportsDir().apply { mkdirs() }
        val file = File(dir, "${TextUtils.sanitizeFileName(docId.take(8))}_ocr.txt")
        val text = pages.value.joinToString("\n\n") { "---- Page ${it.pageIndex + 1} ----\n${it.ocrText ?: ""}" }
        if (text.isBlank()) return null
        return try {
            file.writeText(text)
            file
        } catch (t: Throwable) {
            null
        }
    }

    // ---- page management (CamScanner viewer bottom bar) ------------------

    fun rotateCurrentPage() {
        val page = pages.value.getOrNull(pageIndex) ?: return
        viewModelScope.launch {
            if (repo.rotatePage(page)) {
                invalidateAspect(page.id)
                imageRevision++
            }
        }
    }

    fun deleteCurrentPage() {
        val page = pages.value.getOrNull(pageIndex) ?: return
        viewModelScope.launch {
            repo.deletePage(page)
            imageRevision++
        }
    }

    fun moveCurrentPage(delta: Int) {
        val page = pages.value.getOrNull(pageIndex) ?: return
        viewModelScope.launch {
            repo.movePage(page, delta)
            imageRevision++
        }
    }

    /** Drops the cached aspect for a page (after rotation its aspect changed). */
    fun invalidateAspect(pageId: String) {
        pageAspects.remove(pageId)
    }

    fun dismissError() {
        error = null
    }
}
