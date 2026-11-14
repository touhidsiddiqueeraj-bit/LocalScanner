package com.localscanner.app.ui.screens.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.localscanner.app.data.db.PageEntity
import com.localscanner.app.ui.components.ConfirmDialog
import com.localscanner.app.ui.screens.home.TextEntryDialog
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocDetailScreen(
    docId: String,
    onBack: () -> Unit,
    onExportPdf: () -> Unit,
    onOpenViewer: () -> Unit,
    onAddPages: () -> Unit,
    onViewPage: (Int) -> Unit = {},
) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
    val vm: DocDetailViewModel = viewModel(
        key = docId,
        factory = docDetailViewModelFactory(app, docId),
    )

    val doc by vm.doc.collectAsState()
    val pages by vm.pages.collectAsState()

    var showRename by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    var showDeleteDoc by remember { mutableStateOf(false) }
    var pageMenuFor by remember { mutableStateOf<PageEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(doc?.title ?: "Document", maxLines = 1)
                        Text(
                            text = buildString {
                                append("${pages.size} page")
                                if (pages.size != 1) append("s")
                                doc?.folder?.let { append(" - $it") }
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showRename = true }) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Rename")
                    }
                    IconButton(onClick = { showDeleteDoc = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete document")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onExportPdf, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.PictureAsPdf, contentDescription = null)
                    Text("Export PDF", modifier = Modifier.padding(start = 4.dp))
                }
                OutlinedButton(onClick = onOpenViewer, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.TextFields, contentDescription = null)
                    Text("Text", modifier = Modifier.padding(start = 4.dp))
                }
                OutlinedButton(onClick = onAddPages, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("Add pages", modifier = Modifier.padding(start = 4.dp))
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(pages, key = { it.id }) { page ->
                    PageCard(
                        page = page,
                        // Tap: view the image full-screen (CamScanner behaviour).
                        onClick = { onViewPage(page.pageIndex) },
                        // Long-press: quick actions without leaving the grid.
                        onLongClick = { pageMenuFor = page },
                    )
                }
            }
        }
    }

    if (showRename) {
        TextEntryDialog(
            title = "Rename document",
            initial = doc?.title ?: "",
            confirmLabel = "Rename",
            onConfirm = { vm.rename(it) },
            onDismiss = { showRename = false },
        )
    }
    if (showMove) {
        TextEntryDialog(
            title = "Move to folder",
            initial = doc?.folder ?: "",
            confirmLabel = "Move",
            hint = "Folder name (empty removes the folder)",
            onConfirm = { folder -> vm.moveToFolder(folder.takeIf { it.isNotBlank() }) },
            onDismiss = { showMove = false },
        )
    }
    if (showDeleteDoc && doc != null) {
        ConfirmDialog(
            title = "Delete document?",
            text = "\"${doc!!.title}\" and all pages will be removed from this device.",
            confirmLabel = "Delete",
            onConfirm = { vm.deleteDoc(onDone = onBack) },
            onDismiss = { showDeleteDoc = false },
        )
    }
    pageMenuFor?.let { page ->
        PageMenuDialog(
            page = page,
            pageCount = pages.size,
            onDismiss = { pageMenuFor = null },
            onRotate = { vm.rotatePage(page) },
            onDelete = { vm.deletePage(page) },
            onMove = { delta -> vm.movePage(page, delta) },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageCard(
    page: PageEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AsyncImage(
                model = File(page.thumbPath),
                contentDescription = "Page ${page.pageIndex + 1}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                "${page.pageIndex + 1}",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp),
            )
        }
    }
}

@Composable
private fun PageMenuDialog(
    page: PageEntity,
    pageCount: Int,
    onDismiss: () -> Unit,
    onRotate: () -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Page ${page.pageIndex + 1}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onMove(-1); onDismiss() },
                        enabled = page.pageIndex > 0,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.ChevronLeft, contentDescription = null)
                        Text("Up")
                    }
                    OutlinedButton(
                        onClick = { onMove(1); onDismiss() },
                        enabled = page.pageIndex < pageCount - 1,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Down")
                        Icon(Icons.Filled.ChevronRight, contentDescription = null)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onRotate(); onDismiss() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.RotateRight, contentDescription = null)
                        Text("Rotate")
                    }
                    OutlinedButton(onClick = { onDismiss(); onDelete() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Text("Delete")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
