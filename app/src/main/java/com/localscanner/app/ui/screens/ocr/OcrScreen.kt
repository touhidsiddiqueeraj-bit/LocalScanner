package com.localscanner.app.ui.screens.ocr

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
import androidx.compose.material.icons.outlined.Description
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localscanner.app.domain.OcrEngineOption
import com.localscanner.app.util.ShareUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrScreen(
    docId: String,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as android.app.Application
    val vm: OcrViewModel = viewModel(
        key = docId,
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                OcrViewModel(app, docId) as T
        },
    )

    val pages by vm.pages.collectAsState()
    val runAllProgress by vm.runAllProgress.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    LaunchedEffect(pages.size) {
        // Keep the text field in sync when OCR finishes updating the selected page.
        if (!vm.dirty) vm.selectPage(vm.selectedPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OCR") },
                navigationIcon = {
                    IconButton(onClick = { vm.saveIfDirty(); onBack() }) {
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
            Text("Engine (all offline)", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OcrEngineOption.entries.forEach { option ->
                    FilterChip(
                        selected = vm.engine == option,
                        onClick = { vm.engine = option },
                        label = { Text(if (option.id == "mlkit") "English (ML Kit)" else "Bengali + English") },
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.runSelected() }, enabled = pages.isNotEmpty() && !vm.running) {
                    Text(
                        when {
                            vm.running -> "Recognizing..."
                            else -> "OCR this page"
                        },
                    )
                }
                OutlinedButton(
                    onClick = { vm.runAll() },
                    enabled = pages.isNotEmpty() && runAllProgress == null,
                ) {
                    Text(
                        if (runAllProgress != null) "All pages ${runAllProgress}/${pages.size}"
                        else "Run on all pages",
                    )
                }
            }

            if (pages.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pages.forEachIndexed { i, _ ->
                        FilterChip(
                            selected = vm.selectedPage == i,
                            onClick = { vm.selectPage(i) },
                            label = { Text("${i + 1}") },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = vm.text,
                onValueChange = { vm.editText(it) },
                label = { Text("Recognized text (editable)") },
                minLines = 8,
                maxLines = 18,
                modifier = Modifier.fillMaxWidth(),
            )
            if (vm.dirty) {
                Text(
                    "Unsaved changes",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(vm.text))
                }) {
                    Text("Copy")
                }
                OutlinedButton(onClick = {
                    vm.exportTxt { file ->
                        ShareUtils.shareFile(context, file, "text/plain")
                    }
                }) {
                    Icon(Icons.Outlined.Description, contentDescription = null)
                    Text("TXT", modifier = Modifier.padding(start = 4.dp))
                }
                OutlinedButton(onClick = {
                    if (vm.text.isNotBlank()) {
                        val f = vm.exportedTxt ?: run {
                            val dir = java.io.File(context.filesDir, "exports").apply { mkdirs() }
                            java.io.File(dir, "ocr_text.txt").apply { writeText(vm.text) }
                        }
                        if (vm.dirty || !f.exists()) f.writeText(vm.text)
                        ShareUtils.shareFile(context, f, "text/plain")
                    }
                }) {
                    Icon(Icons.Outlined.Share, contentDescription = null)
                    Text("Share", modifier = Modifier.padding(start = 4.dp))
                }
                TextButton(onClick = { vm.saveIfDirty() }, enabled = vm.dirty) {
                    Text("Save")
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
