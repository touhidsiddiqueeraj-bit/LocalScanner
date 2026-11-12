package com.localscanner.app.ui.screens.pdf

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localscanner.app.LocalScannerApp
import com.localscanner.app.data.db.PageEntity
import com.localscanner.app.domain.PageOrientation
import com.localscanner.app.domain.PdfExporter
import com.localscanner.app.domain.PdfPage
import com.localscanner.app.domain.TextUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class PdfExportViewModel(
    app: Application,
    private val docId: String,
) : AndroidViewModel(app) {

    private val container = (app as LocalScannerApp).container
    private val repo = container.repository

    var pageSize by mutableStateOf(PdfPage.A4)
    var orientation by mutableStateOf(PageOrientation.AUTO)
    var quality by mutableStateOf(80)
    var searchable by mutableStateOf(true)
    var fileName by mutableStateOf("scan.pdf")

    val pages: StateFlow<List<PageEntity>> = repo.observePages(docId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _exported = MutableStateFlow<File?>(null)
    val exported: StateFlow<File?> = _exported

    private val _progress = MutableStateFlow<Float?>(null)
    val progress: StateFlow<Float?> = _progress

    var error: String? by mutableStateOf(null)
        private set

    init {
        viewModelScope.launch {
            val s = container.settings.settings.first()
            pageSize = s.pdfPageSize
            orientation = s.pdfOrientation
            quality = s.pdfQuality
            searchable = s.pdfSearchable
            val doc = repo.getDoc(docId)
            fileName = "${TextUtils.sanitizeFileName(doc?.title ?: "scan")}.pdf"
        }
    }

    fun export() {
        if (_progress.value != null) return
        _progress.value = 0f
        viewModelScope.launch {
            try {
                val options = PdfExporter.Options(
                    pageSize = pageSize,
                    orientation = orientation,
                    qualityPct = quality,
                    searchable = searchable,
                    fileName = fileName,
                )
                val file = container.pdfExporter.export(repo.getPages(docId), options) { p ->
                    _progress.value = p
                }
                _exported.value = file
            } catch (t: Throwable) {
                error = "Export failed: ${t.message ?: "unknown error"}"
            } finally {
                _progress.value = null
            }
        }
    }

    fun dismissError() {
        error = null
    }
}
