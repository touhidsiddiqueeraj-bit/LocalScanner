package com.localscanner.app.ui.screens.ocr

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localscanner.app.LocalScannerApp
import com.localscanner.app.data.db.PageEntity
import com.localscanner.app.domain.OcrEngineOption
import com.localscanner.app.domain.OcrLinesCodec
import com.localscanner.app.domain.TextUtils
import com.localscanner.app.util.ImageFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

class OcrViewModel(
    app: Application,
    private val docId: String,
) : AndroidViewModel(app) {

    private val container = (app as LocalScannerApp).container
    private val repo = container.repository

    val pages: StateFlow<List<PageEntity>> = repo.observePages(docId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var selectedPage: Int by mutableStateOf(0)
    var engine: OcrEngineOption by mutableStateOf(OcrEngineOption.MLKIT_EN)

    /** Edited text for the selected page (local edits until saved). */
    var text: String by mutableStateOf("")
        private set
    var dirty: Boolean by mutableStateOf(false)
        private set
    var running: Boolean by mutableStateOf(false)
        private set
    var runAllProgress: StateFlow<Int?> = MutableStateFlow(null)
        private set

    private val _runAllProgress = MutableStateFlow<Int?>(null)
    var error: String? by mutableStateOf(null)
        private set
    var exportedTxt: File? by mutableStateOf(null)
        private set

    init {
        runAllProgress = _runAllProgress
        viewModelScope.launch {
            engine = container.settings.settings.first().ocrEngine
        }
        viewModelScope.launch {
            pages.collect { list ->
                val page = list.getOrNull(selectedPage)
                if (!dirty) text = page?.ocrText ?: ""
            }
        }
    }

    fun selectPage(index: Int) {
        saveIfDirty()
        selectedPage = index
        dirty = false
        text = pages.value.getOrNull(index)?.ocrText ?: ""
    }

    fun editText(value: String) {
        text = value
        dirty = true
    }

    fun saveIfDirty() {
        if (!dirty) return
        val page = pages.value.getOrNull(selectedPage) ?: return
        viewModelScope.launch {
            repo.setOcr(page, text, page.ocrEngine, page.ocrLinesJson)
            dirty = false
        }
    }

    /** OCR the selected page with the chosen engine. */
    fun runSelected() {
        val page = pages.value.getOrNull(selectedPage) ?: return
        if (running) return
        running = true
        error = null
        viewModelScope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) {
                    ImageFiles.decodeScaled(page.filePath, 1600)
                }
                if (bmp != null) {
                    val result = container.ocr.recognize(bmp, engine)
                    bmp.recycle()
                    text = result.text
                    dirty = true
                    saveIfDirty()
                    repo.setOcr(page, result.text, result.engine, OcrLinesCodec.encode(result.lines))
                }
            } catch (t: Throwable) {
                error = "OCR failed: ${t.message ?: "unknown error"}"
            } finally {
                running = false
            }
        }
    }

    fun runAll() {
        if (_runAllProgress.value != null) return
        viewModelScope.launch {
            _runAllProgress.value = 0
            val list = repo.getPages(docId)
            try {
                list.forEachIndexed { i, page ->
                    val bmp = withContext(Dispatchers.IO) {
                        ImageFiles.decodeScaled(page.filePath, 1600)
                    }
                    if (bmp != null) {
                        val result = container.ocr.recognize(bmp, engine)
                        bmp.recycle()
                        repo.setOcr(page, result.text, result.engine, OcrLinesCodec.encode(result.lines))
                    }
                    _runAllProgress.value = i + 1
                }
            } catch (t: Throwable) {
                error = "OCR failed: ${t.message ?: "unknown error"}"
            } finally {
                _runAllProgress.value = null
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

    fun dismissError() {
        error = null
    }
}
