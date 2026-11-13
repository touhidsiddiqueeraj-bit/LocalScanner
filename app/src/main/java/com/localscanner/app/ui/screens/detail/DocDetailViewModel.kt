package com.localscanner.app.ui.screens.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localscanner.app.LocalScannerApp
import com.localscanner.app.data.db.DocumentEntity
import com.localscanner.app.data.db.PageEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DocDetailViewModel(
    app: Application,
    private val docId: String,
) : AndroidViewModel(app) {

    private val container = (app as LocalScannerApp).container
    private val repo = container.repository

    val doc: StateFlow<DocumentEntity?> = repo.observeDoc(docId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val pages: StateFlow<List<PageEntity>> = repo.observePages(docId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
}
