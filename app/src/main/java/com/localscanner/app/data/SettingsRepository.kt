package com.localscanner.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.localscanner.app.domain.CaptureMode
import com.localscanner.app.domain.FilterType
import com.localscanner.app.domain.OcrEngineOption
import com.localscanner.app.domain.PageOrientation
import com.localscanner.app.domain.PdfPage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val themeMode: String = "system",       // system | light | dark
    val defaultFilter: FilterType = FilterType.MAGIC_COLOR,
    val captureMode: CaptureMode = CaptureMode.SINGLE,
    val pdfPageSize: PdfPage = PdfPage.A4,
    val pdfOrientation: PageOrientation = PageOrientation.AUTO,
    val pdfQuality: Int = 80,
    val pdfSearchable: Boolean = true,
    val ocrEngine: OcrEngineOption = OcrEngineOption.MLKIT_EN,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val DEFAULT_FILTER = stringPreferencesKey("default_filter")
        val CAPTURE_MODE = stringPreferencesKey("capture_mode")
        val PDF_PAGE_SIZE = stringPreferencesKey("pdf_page_size")
        val PDF_ORIENTATION = stringPreferencesKey("pdf_orientation")
        val PDF_QUALITY = stringPreferencesKey("pdf_quality")
        val PDF_SEARCHABLE = booleanPreferencesKey("pdf_searchable")
        val OCR_ENGINE = stringPreferencesKey("ocr_engine")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            themeMode = p[Keys.THEME] ?: "system",
            defaultFilter = p[Keys.DEFAULT_FILTER]?.let { runCatching { FilterType.valueOf(it) }.getOrNull() }
                ?: FilterType.MAGIC_COLOR,
            captureMode = p[Keys.CAPTURE_MODE]?.let { CaptureMode.byId(it) } ?: CaptureMode.SINGLE,
            pdfPageSize = p[Keys.PDF_PAGE_SIZE]?.let { runCatching { PdfPage.valueOf(it) }.getOrNull() }
                ?: PdfPage.A4,
            pdfOrientation = p[Keys.PDF_ORIENTATION]?.let { runCatching { PageOrientation.valueOf(it) }.getOrNull() }
                ?: PageOrientation.AUTO,
            pdfQuality = p[Keys.PDF_QUALITY]?.toIntOrNull() ?: 80,
            pdfSearchable = p[Keys.PDF_SEARCHABLE] ?: true,
            ocrEngine = p[Keys.OCR_ENGINE]?.let { runCatching { OcrEngineOption.byId(it) }.getOrNull() }
                ?: OcrEngineOption.MLKIT_EN,
        )
    }

    suspend fun setThemeMode(mode: String) = context.dataStore.edit { it[Keys.THEME] = mode }
    suspend fun setDefaultFilter(filter: FilterType) = context.dataStore.edit { it[Keys.DEFAULT_FILTER] = filter.name }
    suspend fun setCaptureMode(mode: CaptureMode) = context.dataStore.edit { it[Keys.CAPTURE_MODE] = mode.id }
    suspend fun setPdfPageSize(size: PdfPage) = context.dataStore.edit { it[Keys.PDF_PAGE_SIZE] = size.name }
    suspend fun setPdfOrientation(o: PageOrientation) = context.dataStore.edit { it[Keys.PDF_ORIENTATION] = o.name }
    suspend fun setPdfQuality(q: Int) = context.dataStore.edit { it[Keys.PDF_QUALITY] = q.toString() }
    suspend fun setPdfSearchable(s: Boolean) = context.dataStore.edit { it[Keys.PDF_SEARCHABLE] = s }
    suspend fun setOcrEngine(e: OcrEngineOption) = context.dataStore.edit { it[Keys.OCR_ENGINE] = e.id }
}
