package com.localscanner.app.ui.screens.filter

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localscanner.app.domain.FilterType

@Composable
fun FilterScreen(
    onSaved: (String) -> Unit,
    vm: FilterViewModel = viewModel(),
) {
    val preview = vm.preview

    LaunchedEffect(Unit) { vm.loadCurrent() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (preview != null) {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = "Enhanced preview",
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(if (vm.busy) "Processing..." else "No pages to enhance")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterType.entries.forEach { type ->
                    FilterChip(
                        selected = vm.filter == type,
                        onClick = { vm.filter = type },
                        label = { Text(type.label) },
                    )
                }
            }

            Text(
                "Brightness  ${vm.brightness}",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Slider(
                value = vm.brightness.toFloat(),
                onValueChange = { vm.brightness = it.toInt() },
                valueRange = -50f..50f,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                "Contrast  ${vm.contrast}%",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Slider(
                value = vm.contrast.toFloat(),
                onValueChange = { vm.contrast = it.toInt() },
                valueRange = 50f..150f,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Checkbox(checked = vm.applyToAll, onCheckedChange = { vm.applyToAll = it })
                Text("Apply to all pages")
            }

            if (vm.session.targetDocId == null) {
                OutlinedTextFieldFolder(vm)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (vm.hasPages) "Page ${vm.index + 1} of ${vm.pageCount}" else "No pages",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { vm.previous() }, enabled = vm.index > 0 && !vm.busy) {
                    Text("Prev")
                }
                OutlinedButton(onClick = { vm.next() }, enabled = vm.index < vm.pageCount - 1 && !vm.busy) {
                    Text("Next")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { vm.commitCurrentEdit() },
                    enabled = !vm.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                    Text("Add pages", modifier = Modifier.padding(start = 4.dp))
                }
                Button(
                    onClick = { vm.save(onDone = onSaved) },
                    enabled = vm.hasPages && !vm.saving,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                    Text("Save document", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }

        if (vm.saving) {
            com.localscanner.app.ui.components.LoadingOverlay(
                text = "Saving document...",
                progress = vm.progress,
            )
        }
    }
}

@Composable
private fun OutlinedTextFieldFolder(vm: FilterViewModel) {
    androidx.compose.material3.OutlinedTextField(
        value = vm.folderName,
        onValueChange = { vm.folderName = it },
        label = { Text("Folder (optional)") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    )
}
