package com.localscanner.app.ui.screens.pdf

import android.os.Build
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
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localscanner.app.domain.PageOrientation
import com.localscanner.app.domain.PdfPage
import com.localscanner.app.util.ShareUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfExportScreen(
    docId: String,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as android.app.Application
    val vm: PdfExportViewModel = viewModel(
        key = docId,
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                PdfExportViewModel(app, docId) as T
        },
    )

    val pages by vm.pages.collectAsState()
    val exported by vm.exported.collectAsState()
    val progress by vm.progress.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export PDF") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("${pages.size} pages", style = MaterialTheme.typography.titleMedium)

            Text("Page size", style = MaterialTheme.typography.labelLarge)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                listOf(PdfPage.FIT, PdfPage.A4, PdfPage.LETTER, PdfPage.LEGAL, PdfPage.A3, PdfPage.A5)
                    .take(4)
                    .forEach { size ->
                        FilterChip(
                            selected = vm.pageSize == size,
                            onClick = { vm.pageSize = size },
                            label = { Text(size.name) },
                        )
                    }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                listOf(PdfPage.LEGAL, PdfPage.A3, PdfPage.A5).forEach { size ->
                    FilterChip(
                        selected = vm.pageSize == size,
                        onClick = { vm.pageSize = size },
                        label = { Text(size.name) },
                    )
                }
            }

            Text("Orientation", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PageOrientation.entries.forEach { o ->
                    FilterChip(
                        selected = vm.orientation == o,
                        onClick = { vm.orientation = o },
                        label = { Text(o.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }

            Text("Quality / compression", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple("High", 90, "Best quality"),
                    Triple("Medium", 80, "Balanced"),
                    Triple("Small", 60, "Smallest file"),
                ).forEach { (label, q, _) ->
                    FilterChip(
                        selected = vm.quality == q,
                        onClick = { vm.quality = q },
                        label = { Text(label) },
                    )
                }
            }

            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Searchable PDF", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Embeds the OCR text as an invisible layer so the PDF is selectable and searchable.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = vm.searchable, onCheckedChange = { vm.searchable = it })
            }

            OutlinedTextField(
                value = vm.fileName,
                onValueChange = { vm.fileName = it },
                label = { Text("File name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            val p = progress
            Button(
                onClick = { vm.export() },
                enabled = pages.isNotEmpty() && p == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.PictureAsPdf, contentDescription = null)
                Text(
                    if (p != null) "Exporting ${(p * 100).toInt()}%" else "Create PDF",
                    modifier = Modifier.padding(start = 6.dp),
                )
            }

            exported?.let { file ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Created ${file.name} (${file.length() / 1024} KB)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            ShareUtils.shareFile(context, file, "application/pdf")
                        }) {
                            Icon(Icons.Outlined.Share, contentDescription = null)
                            Text("Share", modifier = Modifier.padding(start = 4.dp))
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            OutlinedButton(onClick = {
                                val ok = ShareUtils.saveToDownloads(context, file, "application/pdf")
                            }) {
                                Icon(Icons.Outlined.Download, contentDescription = null)
                                Text("Downloads", modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                        OutlinedButton(onClick = {
                            ShareUtils.printPdf(context, file, file.nameWithoutExtension)
                        }) {
                            Icon(Icons.Outlined.Print, contentDescription = null)
                            Text("Print", modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }

            vm.error?.let { err ->
                Snackbar(
                    action = { TextButton(onClick = { vm.dismissError() }) { Text("OK") } },
                ) { Text(err) }
            }
        }
    }
}
