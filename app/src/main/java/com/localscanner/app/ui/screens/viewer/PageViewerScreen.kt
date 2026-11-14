package com.localscanner.app.ui.screens.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.localscanner.app.data.db.PageEntity
import com.localscanner.app.ui.theme.ScanOverlayColor
import com.localscanner.app.util.ShareUtils
import java.io.File

/**
 * Full-screen page viewer with OCR text living ON the page image: recognized
 * lines are tappable (select + copy) and searchable. Replaces the old
 * dedicated OCR screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageViewerScreen(
    docId: String,
    initialPage: Int = 0,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as android.app.Application
    val vm: PageViewerViewModel = viewModel(
        key = docId,
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                PageViewerViewModel(app, docId) as T
        },
    )

    val pages by vm.pages.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var showTextSheet by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PageEntity?>(null) }

    LaunchedEffect(pages.size) {
        pages.getOrNull(vm.pageIndex)?.let { vm.ensureAspect(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pages") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        vm.searchActive = !vm.searchActive
                        if (!vm.searchActive) {
                            vm.query = ""
                            vm.closeResults()
                        }
                    }) {
                        Icon(
                            if (vm.searchActive) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = if (vm.searchActive) "Close search" else "Search text",
                        )
                    }
                    IconButton(onClick = { showTextSheet = true }) {
                        Icon(Icons.Outlined.TextFields, contentDescription = "View all text")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (vm.searchActive) {
                SearchBar(vm = vm)
            }

            Box(modifier = Modifier.weight(1f)) {
                if (pages.isEmpty()) {
                    Text(
                        "No pages yet",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else if (vm.searchActive && vm.resultsVisible) {
                    SearchResults(vm = vm)
                } else {
                    PagesPager(vm = vm, pages = pages, initialPage = initialPage, onPendingDelete = { pendingDelete = it })
                }

                // Selection bar: the tapped line's text with a copy action.
                vm.selectedLineText?.let { sel ->
                    Card(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(12.dp)
                            .fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                sel,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 3,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { clipboard.setText(AnnotatedString(sel)) }) {
                                Icon(Icons.Filled.CopyAll, contentDescription = "Copy selected text")
                            }
                            IconButton(onClick = { vm.clearSelection() }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                            }
                        }
                    }
                }
            }

            ViewerBottomBar(vm = vm, pages = pages, onPendingDelete = { pendingDelete = it })
        }
    }

    if (showTextSheet) {
        ModalBottomSheet(onDismissRequest = { showTextSheet = false }) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Recognized text", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Select any part of the text below, or tap lines directly on the page.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                SelectionContainer {
                    Text(
                        vm.currentText.ifBlank { "No text recognized yet. Run OCR on this page." },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(vm.currentText))
                    }) {
                        Text("Copy")
                    }
                    OutlinedButton(onClick = {
                        vm.exportTxt { file -> ShareUtils.shareFile(context, file, "text/plain") }
                    }) {
                        Icon(Icons.Outlined.Description, contentDescription = null)
                        Text("TXT", modifier = Modifier.padding(start = 4.dp))
                    }
                    OutlinedButton(onClick = {
                        vm.shareTextFile()?.let { ShareUtils.shareFile(context, it, "text/plain") }
                    }) {
                        Icon(Icons.Outlined.Share, contentDescription = null)
                        Text("Share", modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
    }

    vm.error?.let { err ->
        Snackbar(
            modifier = Modifier.padding(16.dp),
            action = { TextButton(onClick = { vm.dismissError() }) { Text("OK") } },
        ) { Text(err) }
    }

    pendingDelete?.let { page ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete page?") },
            text = { Text("Page ${vm.pageIndex + 1} will be removed from this document.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    vm.deleteCurrentPage()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SearchBar(vm: PageViewerViewModel) {
    OutlinedTextField(
        value = vm.query,
        onValueChange = {
            vm.query = it
            vm.search()
        },
        placeholder = { Text("Search recognized text") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SearchResults(vm: PageViewerViewModel) {
    val hits = vm.hits
    if (hits.isEmpty()) {
        Text(
            "No matches in recognized text",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            textAlign = TextAlign.Center,
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(hits) { hit ->
            Card(onClick = { vm.jumpTo(hit) }) {
                Column(modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)) {
                    Text(
                        "Page ${hit.pageLabel}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        hit.text,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PagesPager(
    vm: PageViewerViewModel,
    pages: List<PageEntity>,
    initialPage: Int,
    onPendingDelete: (PageEntity) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { pages.size })

    // VM drives the pager (search jumps), pager drives the VM (swipes).
    LaunchedEffect(pagerState, pages.size) {
        // Open directly on the tapped page before the flow collection starts,
        // otherwise the first emission would reset the page back to 0.
        if (initialPage > 0 && pages.isNotEmpty()) {
            val target = initialPage.coerceIn(0, pages.size - 1)
            pagerState.scrollToPage(target)
            vm.pageIndex = target
        }
        snapshotFlow { pagerState.currentPage }.collect { page ->
            if (page != vm.pageIndex) {
                vm.pageIndex = page
                vm.clearSelection()
            }
            pages.getOrNull(page)?.let { vm.ensureAspect(it) }
        }
    }
    LaunchedEffect(vm.pageIndex, pages.size) {
        if (pagerState.currentPage != vm.pageIndex) {
            pagerState.scrollToPage(vm.pageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
        }
    }

    // While a page is zoomed in, swiping must pan the page, not the pager.
    var zoomActive by remember { mutableStateOf(false) }

    HorizontalPager(
        state = pagerState,
        userScrollEnabled = !zoomActive,
        modifier = Modifier.fillMaxSize(),
    ) { i ->
        val page = pages[i]
        var canvasSize by remember { mutableStateOf(IntSize.Zero) }
        var scale by remember(page.id) { mutableStateOf(1f) }
        var offset by remember(page.id) { mutableStateOf(Offset.Zero) }

        // Reset zoom when navigating to another page.
        LaunchedEffect(vm.pageIndex) {
            scale = 1f
            offset = Offset.Zero
        }
        LaunchedEffect(scale) { zoomActive = scale > 1.01f }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .clipToBounds()
                .pointerInput(page.id) {
                    detectTapGestures(
                        onDoubleTap = { tap ->
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                                offset = Offset(
                                    (size.width / 2f - tap.x) * 1.5f,
                                    (size.height / 2f - tap.y) * 1.5f,
                                )
                            }
                        },
                    )
                }
                .pointerInput(page.id) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 5f)
                        scale = newScale
                        offset = if (newScale <= 1f) Offset.Zero else offset + pan
                    }
                },
        ) {
            // Zoomed content: image + on-image OCR overlay scale together.
            // Pointer positions inside are inverse-transformed, so the tap
            // mapping below keeps working at any zoom level.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            ) {
                val request = ImageRequest.Builder(LocalContext.current)
                    .data(File(page.filePath))
                    .memoryCacheKey("${page.filePath}#${vm.imageRevision}")
                    .crossfade(false)
                    .build()
                AsyncImage(
                    model = request,
                    contentDescription = "Page ${page.pageIndex + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )

                val lines = vm.linesFor(page)
                val aspect = vm.pageAspects[page.id]
                if (lines != null && aspect != null && canvasSize != IntSize.Zero) {
                    val fit = remember(aspect, canvasSize) { computeFit(aspect, canvasSize) }
                    val query = vm.query.trim()
                    val selected = if (page.id == pages.getOrNull(vm.pageIndex)?.id) vm.selectedLineIndex else null

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(page.id, lines.size, query) {
                                detectTapGestures { tap ->
                                    if (scale > 1.01f) {
                                        vm.clearSelection()
                                        return@detectTapGestures
                                    }
                                    val nx = (tap.x - fit.x) / fit.width
                                    val ny = (tap.y - fit.y) / fit.height
                                    val slop = 0.008f
                                    val idx = lines.indices.firstOrNull { k ->
                                        val b = lines[k].second
                                        nx >= b.x - slop && nx <= b.right + slop &&
                                            ny >= b.y - slop && ny <= b.bottom + slop
                                    }
                                    if (idx != null) vm.selectLine(lines, idx) else vm.clearSelection()
                                }
                            },
                    ) {
                        // Search hits: yellow outlines directly on the image.
                        if (vm.searchActive && query.isNotBlank()) {
                            lines.forEach { (line, b) ->
                                if (line.text.contains(query, ignoreCase = true)) {
                                    drawRect(
                                        Color(0xFFFFC107),
                                        topLeft = Offset(fit.x + b.x * fit.width, fit.y + b.y * fit.height),
                                        size = androidx.compose.ui.geometry.Size(b.w * fit.width, b.h * fit.height),
                                        style = Stroke(width = 2.dp.toPx()),
                                    )
                                }
                            }
                        }
                        // Selected line: filled accent box.
                        selected?.let { si ->
                            lines.getOrNull(si)?.let { (_, b) ->
                                drawRect(
                                    ScanOverlayColor.copy(alpha = 0.30f),
                                    topLeft = Offset(fit.x + b.x * fit.width, fit.y + b.y * fit.height),
                                    size = androidx.compose.ui.geometry.Size(b.w * fit.width, b.h * fit.height),
                                )
                                drawRect(
                                    ScanOverlayColor,
                                    topLeft = Offset(fit.x + b.x * fit.width, fit.y + b.y * fit.height),
                                    size = androidx.compose.ui.geometry.Size(b.w * fit.width, b.h * fit.height),
                                    style = Stroke(width = 2.dp.toPx()),
                                )
                            }
                        }
                    }
                }
            }

            if (page.ocrText.isNullOrBlank()) {
                Card(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "Text not recognized yet",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "Run OCR to make the text on this page selectable and searchable.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                        Button(onClick = { vm.runOcrCurrent() }, enabled = !vm.running) {
                            Text("Run OCR on this page")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerBottomBar(
    vm: PageViewerViewModel,
    pages: List<PageEntity>,
    onPendingDelete: (PageEntity) -> Unit,
) {
    val pageCount = pages.size
    Column(modifier = Modifier.fillMaxWidth()) {
        if (vm.running && vm.runAllProgress != null && pageCount > 0) {
            LinearProgressIndicator(
                progress = { vm.runAllProgress!!.toFloat() / pageCount.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
            Text(
                "Running OCR... ${vm.runAllProgress}/${pageCount} pages",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // Page management: reorder, rotate, delete (CamScanner viewer bar).
        if (pageCount > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    onClick = { vm.moveCurrentPage(-1) },
                    enabled = vm.pageIndex > 0,
                ) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move page up")
                }
                IconButton(
                    onClick = { vm.moveCurrentPage(1) },
                    enabled = vm.pageIndex < pageCount - 1,
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move page down")
                }
                IconButton(onClick = { vm.rotateCurrentPage() }) {
                    Icon(Icons.Outlined.RotateRight, contentDescription = "Rotate page")
                }
                IconButton(
                    onClick = { pages.getOrNull(vm.pageIndex)?.let(onPendingDelete) },
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete page")
                }
                Text(
                    text = "Page ${vm.pageIndex + 1} of $pageCount",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistChip(
                onClick = { vm.cycleEngine() },
                label = {
                    Text(
                        if (vm.engine.id == "mlkit") "ML Kit" else "Tesseract",
                    )
                },
            )
            Spacer(Modifier.size(8.dp))
            OutlinedButton(
                onClick = { vm.runOcrCurrent() },
                enabled = pageCount > 0 && !vm.running,
            ) {
                Text(if (vm.hasCurrentText) "Re-run" else "OCR page")
            }
            OutlinedButton(
                onClick = { vm.runOcrAll() },
                enabled = pageCount > 0 && vm.runAllProgress == null,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text("All")
            }
        }
    }
}

private data class FitRect(val x: Float, val y: Float, val width: Float, val height: Float)

/** Maps a page image (given by aspect) into the canvas with ContentScale.Fit. */
private fun computeFit(aspect: Float, canvas: IntSize): FitRect {
    val iw = if (aspect >= 1f) aspect else 1f
    val ih = if (aspect >= 1f) 1f else 1f / aspect
    val scale = minOf(canvas.width / iw, canvas.height / ih)
    val w = iw * scale
    val h = ih * scale
    return FitRect((canvas.width - w) / 2f, (canvas.height - h) / 2f, w, h)
}
