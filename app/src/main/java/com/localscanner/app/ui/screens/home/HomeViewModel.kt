package com.localscanner.app.ui.screens.home

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localscanner.app.LocalScannerApp
import com.localscanner.app.data.db.DocumentSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as LocalScannerApp).container
    private val repo = container.repository
    private val session = container.scanSession

    private val queryFlow = MutableStateFlow("")
    private val folderFlow = MutableStateFlow<String?>(null)

    val folderState: StateFlow<String?> = folderFlow

    val docs: StateFlow<List<DocumentSummary>> = combine(
        repo.documents, queryFlow, folderFlow,
    ) { list, query, folder ->
        list.filter { item ->
            val folderOk = folder == null || item.doc.folder == folder
            val queryOk = query.isBlank() ||
                item.doc.title.contains(query, ignoreCase = true) ||
                (item.doc.searchText?.contains(query, ignoreCase = true) ?: false)
            folderOk && queryOk
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val folders: StateFlow<List<String>> = repo.documents
        .map { list -> list.mapNotNull { it.doc.folder }.distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val importDone = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val importReady: SharedFlow<Unit> = importDone

    fun setQuery(query: String) {
        queryFlow.value = query
    }

    fun setFolder(folder: String?) {
        folderFlow.value = folder
    }

    fun rename(docId: String, title: String) = viewModelScope.launch {
        repo.rename(docId, title)
    }

    fun moveToFolder(docId: String, folder: String?) = viewModelScope.launch {
        repo.setFolder(docId, folder)
    }

    fun delete(docId: String) = viewModelScope.launch {
        repo.deleteDoc(docId)
    }

    /** Gallery import: copies the picked images into the scan session. */
    fun importImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            session.reset()
            session.importMode = true
            val copied = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri -> copyUri(uri) }
            }
            copied.forEach { session.addWarped(it) }
            if (copied.isNotEmpty()) importDone.tryEmit(Unit)
        }
    }

    private fun copyUri(uri: Uri): File? = runCatching {
        val out = session.newWarpedFile()
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        out
    }.getOrNull()
}
