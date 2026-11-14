package com.localscanner.app.ui.screens.filter

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localscanner.app.domain.FilterType
import com.localscanner.app.ui.theme.ScanOverlayColor

/**
 * CamScanner-style live effects editor: the preview re-renders while filters
 * and tone sliders move, a thumbnail strip previews all modes, and pressing
 * and holding the image shows the original for before/after comparison.
 */
@Composable
fun FilterScreen(
    onSaved: (String) -> Unit,
    vm: FilterViewModel = viewModel(),
) {
    val preview = vm.preview
    val base = vm.baseBitmap
    var showOriginal by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.loadCurrent() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // Live preview (press & hold = show original)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                val shown = if (showOriginal) base else preview
                if (shown != null) {
                    Image(
                        bitmap = shown.asImageBitmap(),
                        contentDescription = if (showOriginal) "Original scan" else "Enhanced preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        showOriginal = true
                                        tryAwaitRelease()
                                        showOriginal = false
                                    },
                                )
                            },
                    )
                } else {
                    Text(if (vm.busy) "Processing..." else "No pages to enhance")
                }
                if (showOriginal) {
                    Text(
                        "Original",
                        style = MaterialTheme.typography.labelMedium,
                        color = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp),
                    )
                }
            }

            // Filter thumbnails: live previews of every mode.
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(FilterType.entries.toList()) { type ->
                    FilterThumb(
                        label = type.label,
                        bitmap = vm.filterThumbs[type],
                        selected = vm.filter == type,
                        onClick = { vm.updateFilter(type) },
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
                onValueChange = { vm.updateBrightness(it.toInt()) },
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
                onValueChange = { vm.updateContrast(it.toInt()) },
                valueRange = 50f..150f,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Checkbox(checked = vm.applyToAll, onCheckedChange = { vm.updateApplyToAll(it) })
                Text("Apply to all pages")
                androidx.compose.material3.TextButton(
                    onClick = { vm.resetAdjustments() },
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.width(18.dp))
                    Text("Reset tone", modifier = Modifier.padding(start = 4.dp))
                }
            }

            if (vm.session.targetDocId == null) {
                OutlinedTextField(
                    value = vm.folderName,
                    onValueChange = { vm.folderName = it },
                    label = { Text("Folder (optional)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
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
private fun FilterThumb(
    label: String,
    bitmap: android.graphics.Bitmap?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(32.dp)
                        .height(32.dp)
                        .then(
                            if (selected) {
                                Modifier.border(2.dp, ScanOverlayColor, RoundedCornerShape(6.dp))
                            } else {
                                Modifier
                            },
                        ),
                )
            } else {
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
            }
        },
    )
}
