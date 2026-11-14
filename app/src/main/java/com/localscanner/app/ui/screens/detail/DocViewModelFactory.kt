package com.localscanner.app.ui.screens.detail

import androidx.lifecycle.ViewModelProvider

/** Shared factory so screens that show one document reuse DocDetailViewModel. */
fun docDetailViewModelFactory(app: android.app.Application, docId: String): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return DocDetailViewModel(app, docId) as T
        }
    }
