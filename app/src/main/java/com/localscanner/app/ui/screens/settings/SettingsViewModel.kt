package com.localscanner.app.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localscanner.app.LocalScannerApp
import com.localscanner.app.data.AppSettings
import com.localscanner.app.domain.FilterType
import com.localscanner.app.domain.OcrEngineOption
import com.localscanner.app.domain.PageOrientation
import com.localscanner.app.domain.PdfPage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = (app as LocalScannerApp).container.settings

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setTheme(mode: String) = viewModelScope.launch { settingsRepo.setThemeMode(mode) }
    fun setDefaultFilter(filter: FilterType) = viewModelScope.launch { settingsRepo.setDefaultFilter(filter) }
    fun setAutoCapture(enabled: Boolean) = viewModelScope.launch { settingsRepo.setAutoCapture(enabled) }
    fun setPageSize(size: PdfPage) = viewModelScope.launch { settingsRepo.setPdfPageSize(size) }
    fun setOrientation(o: PageOrientation) = viewModelScope.launch { settingsRepo.setPdfOrientation(o) }
    fun setQuality(q: Int) = viewModelScope.launch { settingsRepo.setPdfQuality(q) }
    fun setSearchable(s: Boolean) = viewModelScope.launch { settingsRepo.setPdfSearchable(s) }
    fun setOcrEngine(engine: OcrEngineOption) = viewModelScope.launch { settingsRepo.setOcrEngine(engine) }
}
