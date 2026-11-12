package com.localscanner.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localscanner.app.domain.FilterType
import com.localscanner.app.domain.OcrEngineOption
import com.localscanner.app.domain.PageOrientation
import com.localscanner.app.domain.PdfPage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val s by vm.settings.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard("Appearance") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system", "light", "dark").forEach { mode ->
                        FilterChip(
                            selected = s.themeMode == mode,
                            onClick = { vm.setTheme(mode) },
                            label = { Text(mode.replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
            }

            SectionCard("Scanning") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-capture", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Automatically shoots when a document is detected and the camera is steady.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = s.autoCapture, onCheckedChange = { vm.setAutoCapture(it) })
                }
                Text("Default filter", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterType.entries.forEach { f ->
                        FilterChip(
                            selected = s.defaultFilter == f,
                            onClick = { vm.setDefaultFilter(f) },
                            label = { Text(f.label) },
                        )
                    }
                }
            }

            SectionCard("PDF export defaults") {
                Text("Page size", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(PdfPage.FIT, PdfPage.A4, PdfPage.LETTER).forEach { size ->
                        FilterChip(
                            selected = s.pdfPageSize == size,
                            onClick = { vm.setPageSize(size) },
                            label = { Text(size.name) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(PdfPage.LEGAL, PdfPage.A3, PdfPage.A5).forEach { size ->
                        FilterChip(
                            selected = s.pdfPageSize == size,
                            onClick = { vm.setPageSize(size) },
                            label = { Text(size.name) },
                        )
                    }
                }
                Text("Orientation", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PageOrientation.entries.forEach { o ->
                        FilterChip(
                            selected = s.pdfOrientation == o,
                            onClick = { vm.setOrientation(o) },
                            label = { Text(o.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
                Text("Quality", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(90 to "High", 80 to "Medium", 60 to "Small").forEach { (q, label) ->
                        FilterChip(
                            selected = s.pdfQuality == q,
                            onClick = { vm.setQuality(q) },
                            label = { Text(label) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Searchable PDFs", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Include the invisible OCR text layer in exported PDFs.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = s.pdfSearchable, onCheckedChange = { vm.setSearchable(it) })
                }
            }

            SectionCard("OCR") {
                Text("Default engine", style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OcrEngineOption.entries.forEach { e ->
                        FilterChip(
                            selected = s.ocrEngine == e,
                            onClick = { vm.setOcrEngine(e) },
                            label = { Text(e.label) },
                        )
                    }
                }
            }

            SectionCard("About") {
                Text("LocalScanner 1.0", style = MaterialTheme.typography.titleMedium)
                Text(
                    "A 100% offline document scanner: edge detection, perspective correction, " +
                        "enhancement filters, OCR (English + Bengali) and searchable PDF export all " +
                        "run locally on this device. No data ever leaves your phone, and exports carry " +
                        "no watermark.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}
