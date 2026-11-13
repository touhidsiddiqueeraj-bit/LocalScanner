package com.localscanner.app.ui.screens.crop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localscanner.app.ui.theme.ScanOverlayColor
import kotlin.math.hypot

@Composable
fun CropScreen(
    onDone: () -> Unit,
    vm: CropViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.loadCurrent() }

    val bitmap = vm.bitmap
    val quad = vm.quad
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var activeCorner by remember { mutableStateOf(-1) }

    // CamScanner-style auto-crop: when a confident quad was detected, apply it
    // automatically after a beat. Any touch on the quad cancels the timer.
    LaunchedEffect(vm.autoCropPending, vm.index) {
        if (vm.autoCropPending) {
            kotlinx.coroutines.delay(700)
            if (vm.autoCropPending && !vm.busy) vm.applyAndAdvance(onAllDone = onDone)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { canvasSize = it },
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null && quad != null && canvasSize != IntSize.Zero) {
                val fit = remember(bitmap, canvasSize) { computeFit(bitmap.width, bitmap.height, canvasSize) }

                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Page to crop",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(bitmap) {
                            detectDragGestures(
                                onDragStart = { touch ->
                                    vm.cancelAutoCrop()
                                    val currentQuad = vm.quad ?: return@detectDragGestures
                                    val corners = currentQuad.points().map { toDisplay(it, fit) }
                                    activeCorner = corners.indexOfFirst {
                                        hypot(it.x - touch.x, it.y - touch.y) < 80f
                                    }
                                    if (activeCorner == -1) {
                                        activeCorner = corners.indices.minByOrNull { i ->
                                            val c = corners[i]
                                            hypot(c.x - touch.x, c.y - touch.y)
                                        } ?: -1
                                    }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    if (activeCorner >= 0) {
                                        val ndx = dragAmount.x / fit.width
                                        val ndy = dragAmount.y / fit.height
                                        vm.moveCorner(activeCorner, ndx, ndy)
                                    }
                                },
                                onDragEnd = { activeCorner = -1 },
                            )
                        },
                ) {
                    val pts = quad.points().map { toDisplay(it, fit) }
                    val path = Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                        close()
                    }
                    val framePath = Path().apply {
                        addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                    }
                    val outside = Path()
                    outside.op(framePath, path, androidx.compose.ui.graphics.PathOperation.Difference)
                    drawPath(outside, Color(0x66000000))
                    drawPath(
                        path,
                        ScanOverlayColor,
                        style = Stroke(width = 3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 10f))),
                    )
                    pts.forEachIndexed { i, p ->
                        drawCircle(if (i == activeCorner) Color.White else ScanOverlayColor, 8.dp.toPx(), p)
                        drawCircle(Color.White, 3.dp.toPx(), p)
                    }
                }
            } else if (vm.busy) {
                Text("Preparing page...")
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.rotate() }, enabled = !vm.busy) {
                Icon(Icons.Outlined.RotateRight, contentDescription = "Rotate 90 degrees")
            }
            OutlinedButton(onClick = { vm.resetQuad() }, enabled = !vm.busy) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Re-detect", modifier = Modifier.padding(start = 4.dp))
            }
            OutlinedButton(onClick = { vm.manualFrame() }, enabled = !vm.busy) {
                Icon(Icons.Outlined.CropFree, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Full page", modifier = Modifier.padding(start = 4.dp))
            }
        }

        if (vm.pageCount > 1) {
            OutlinedButton(
                onClick = { vm.autoCropAll(onAllDone = onDone) },
                enabled = !vm.busy,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 4.dp),
            ) {
                Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Auto-crop all ${vm.pageCount - vm.index} remaining pages", modifier = Modifier.padding(start = 4.dp))
            }
        }

        if (vm.autoCropPending) {
            Text(
                text = "Auto-crop applied - adjusting automatically...",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 4.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (vm.hasPages) "Page ${vm.index + 1} of ${vm.pageCount}" else "No pages",
                style = MaterialTheme.typography.titleMedium,
            )
            Button(
                onClick = { vm.applyAndAdvance(onAllDone = onDone) },
                enabled = bitmap != null && quad != null && !vm.busy,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Crop", modifier = Modifier.padding(start = 4.dp))
            }
            OutlinedButton(
                onClick = { vm.skipAndAdvance(onAllDone = onDone) },
                enabled = bitmap != null && !vm.busy,
            ) {
                Text("Skip")
            }
        }
    }

    if (vm.busy) {
        androidx.compose.material3.Surface(
            color = Color.Black.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxSize(),
        ) {}
    }
    vm.error?.let { message ->
        androidx.compose.material3.Snackbar(
            modifier = Modifier.padding(16.dp),
            action = {
                androidx.compose.material3.TextButton(onClick = { vm.dismissError() }) { Text("OK") }
            },
        ) { Text(message) }
    }
    vm.message?.let { msg ->
        androidx.compose.material3.Snackbar(
            modifier = Modifier.padding(16.dp),
            action = {
                androidx.compose.material3.TextButton(onClick = { vm.dismissMessage() }) { Text("OK") }
            },
        ) { Text(msg) }
    }
}

private data class FitRect(val x: Float, val y: Float, val width: Float, val height: Float)

private fun computeFit(imgW: Int, imgH: Int, canvas: IntSize): FitRect {
    val scale = minOf(canvas.width.toFloat() / imgW, canvas.height.toFloat() / imgH)
    val w = imgW * scale
    val h = imgH * scale
    return FitRect((canvas.width - w) / 2f, (canvas.height - h) / 2f, w, h)
}

private fun toDisplay(p: com.localscanner.app.domain.Pt, fit: FitRect): Offset =
    Offset(fit.x + p.x * fit.width, fit.y + p.y * fit.height)
