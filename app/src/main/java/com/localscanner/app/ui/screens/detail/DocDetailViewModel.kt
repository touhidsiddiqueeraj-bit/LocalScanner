package com.localscanner.app.ui.screens.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localscanner.app.LocalScannerApp
import com.localscanner.app.data.db.DocumentEntity
import com.localscanner.app.data.db.PageEntity
import com.localscanner.app.domain.OcrEngineOption
import com.localscanner.app.domain.OcrLinesCodec
import com.localscanner.app.domain.OcrEngine
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

class DocDetailViewModel(
    app: Application,
    private val docId: String,
) : AndroidViewModel(app) {

    private val container = (app as LocalScannerApp).container
    private val repo = container.repository

    // Accessed lazily: OCR engines own JNI objects and must not be created
    // until a bulk OCR run actually needs them (keeps JVM tests clean).
    private val ocrEngine: com.localscanner.app.domain.OcrEngine get() = container.ocr

    val doc: StateFlow<DocumentEntity?> = repo.observeDoc(docId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val pages: StateFlow<List<PageEntity>> = repo.observePages(docId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Number of pages already processed during a bulk OCR run; null when idle. */
    private val _ocrProgress = MutableStateFlow<Int?>(null)
    val ocrProgress: StateFlow<Int?> = _ocrProgress

    var ocrError: String? by mutableStateOf(null)
        private set

    fun rename(title: String) = viewModelScope.launch {
        if (title.isNotBlank()) repo.rename(docId, title)
    }

    fun moveToFolder(folder: String?) = viewModelScope.launch {
        repo.setFolder(docId, folder)
    }

    fun deleteDoc(onDone: () -> Unit) = viewModelScope.launch {
        repo.deleteDoc(docId)
        onDone()
    }

    fun deletePage(page: PageEntity) = viewModelScope.launch {
        repo.deletePage(page)
    }

    fun movePage(page: PageEntity, delta: Int) = viewModelScope.launch {
        repo.movePage(page, delta)
    }

    fun rotatePage(page: PageEntity) = viewModelScope.launch {
        repo.rotatePage(page)
    }

    /** Runs on-device OCR over every page of the document, then refreshes search index. */
    fun runOcrAll() {
        if (_ocrProgress.value != null) return
        viewModelScope.launch {
            _ocrProgress.value = 0
            val engineOption = container.settings.settings.first().ocrEngine
            val list = repo.getPages(docId)
            try {
                list.forEachIndexed { i, page ->
                    val bmp = withContext(Dispatchers.IO) {
                        ImageFiles.decodeScaled(page.filePath, 1600)
                    }
                    if (bmp != null) {
                        val result = ocrEngine.recognize(bmp, engineOption)
                        bmp.recycle()
                        repo.setOcr(page, result.text, result.engine, OcrLinesCodec.encode(result.lines))
                    }
                    _ocrProgress.value = i + 1
                }
            } catch (t: Throwable) {
                ocrError = "OCR failed: ${t.message ?: "unknown error"}"
            } finally {
                _ocrProgress.value = null
            }
        }
    }

    fun dismissOcrError() {
        ocrError = null
    }
}
