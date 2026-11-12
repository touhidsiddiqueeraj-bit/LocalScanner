package com.localscanner.app.ui.screens.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.localscanner.app.data.db.DocumentSummary
import com.localscanner.app.ui.components.ConfirmDialog
import com.localscanner.app.ui.components.EmptyState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onScan: () -> Unit,
    onOpenDoc: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onImportReady: () -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val docs by vm.docs.collectAsState()
    val folders by vm.folders.collectAsState()
    val selectedFolder by vm.folderState.collectAsState()
    var query by remember { mutableStateOf("") }

    LaunchedEffect(query) { vm.setQuery(query) }

    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20),
    ) { uris ->
        if (uris.isNotEmpty()) vm.importImages(uris)
    }

    LaunchedEffect(Unit) {
        vm.importReady.collect { onImportReady() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LocalScanner") },
                actions = {
                    IconButton(onClick = {
                        galleryPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    }) {
                        Icon(Icons.Outlined.PhotoLibrary, contentDescription = "Import images")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScan,
                icon = { Icon(Icons.Filled.CameraAlt, contentDescription = null) },
                text = { Text("New scan") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search titles and OCR text") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            if (folders.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 8.dp),
                ) {
                    items(listOf<String?>(null) + folders) { folder ->
                        FilterChip(
                            selected = selectedFolder == folder,
                            onClick = { vm.setFolder(folder) },
                            label = { Text(folder ?: "All") },
                            leadingIcon = if (folder != null) {
                                { Icon(Icons.Filled.Folder, contentDescription = null) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            if (docs.isEmpty()) {
                EmptyState(
                    title = if (query.isBlank()) "No documents yet" else "No matches",
                    subtitle = if (query.isBlank()) {
                        "Tap \"New scan\" to capture pages with automatic edge detection, or import from the gallery."
                    } else {
                        "Search covers document titles and recognized OCR text."
                    },
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    items(docs, key = { it.doc.id }) { item ->
                        DocumentCard(
                            item = item,
                            onOpen = { onOpenDoc(item.doc.id) },
                            onRename = { title -> vm.rename(item.doc.id, title) },
                            onMove = { folder -> vm.moveToFolder(item.doc.id, folder) },
                            onDelete = { vm.delete(item.doc.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentCard(
    item: DocumentSummary,
    onOpen: () -> Unit,
    onRename: (String) -> Unit,
    onMove: (String?) -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    val dateText = remember(item.doc.updatedAt) {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(item.doc.updatedAt))
    }

    Card(onClick = onOpen) {
        Column {
            if (item.coverPath != null) {
                AsyncImage(
                    model = File(item.coverPath),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Outlined.DocumentScanner, contentDescription = null)
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.doc.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append("${item.pageCount} page")
                            if (item.pageCount != 1) append("s")
                            append(" - ")
                            append(dateText)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = { menuOpen = false; showRename = true },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text("Move to folder") },
                        onClick = { menuOpen = false; showMove = true },
                        leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = { menuOpen = false; showDelete = true },
                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                    )
                }
            }
        }
    }

    if (showRename) {
        TextEntryDialog(
            title = "Rename document",
            initial = item.doc.title,
            confirmLabel = "Rename",
            onConfirm = onRename,
            onDismiss = { showRename = false },
        )
    }
    if (showMove) {
        TextEntryDialog(
            title = "Move to folder",
            initial = item.doc.folder ?: "",
            confirmLabel = "Move",
            hint = "Folder name (empty removes the folder)",
            onConfirm = { folder -> onMove(folder.takeIf { it.isNotBlank() }) },
            onDismiss = { showMove = false },
        )
    }
    if (showDelete) {
        ConfirmDialog(
            title = "Delete document?",
            text = "\"${item.doc.title}\" and its pages will be removed from this device.",
            confirmLabel = "Delete",
            onConfirm = onDelete,
            onDismiss = { showDelete = false },
        )
    }
}

@Composable
fun TextEntryDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    hint: String? = null,
) {
    var value by remember(title) { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hint != null) {
                    Spacer(Modifier.width(4.dp))
                    Text(hint, style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onConfirm(value.trim()); onDismiss() },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
