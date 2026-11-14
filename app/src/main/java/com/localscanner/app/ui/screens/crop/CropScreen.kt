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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localscanner.app.ui.theme.ScanOverlayColor
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * CamScanner-style crop editor ("perspective shift"):
 *  - four draggable corner handles,
 *  - four draggable edge handles that slide a whole side,
 *  - a magnifier loupe that follows the dragged handle for pixel-accurate
 *    placement,
 *  - the confirmed quad is perspective-warped into a straightened page.
 */
@Composable
fun CropScreen(
    onDone: () -> Unit,
    vm: CropViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.loadCurrent() }

    val bitmap = vm.bitmap
    val quad = vm.quad
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // -1 = none; 0..3 = corner; 100+e = edge e
    var activeHandle by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val haptics = LocalHapticFeedback.current

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
                val image = remember(bitmap) { bitmap.asImageBitmap() }

                Image(
                    bitmap = image,
                    contentDescription = "Page to crop",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(bitmap, canvasSize) {
                            detectDragGestures(
                                onDragStart = { touch ->
                                    vm.cancelAutoCrop()
                                    val currentQuad = vm.quad ?: return@detectDragGestures
                                    val corners = currentQuad.points().map { toDisplay(it, fit) }
                                    val edges = (0..3).map { toDisplay(currentQuad.edgeMidpoint(it), fit) }
                                    val hit = 34.dp.toPx()
                                    activeHandle = -1
                                    dragOffset = touch
                                    // Corners win over edges when both are close.
                                    val cornerIdx = corners.indices
                                        .map { i -> i to hypot(corners[i].x - touch.x, corners[i].y - touch.y) }
                                        .filter { (_, d) -> d < hit }
                                        .minByOrNull { (_, d) -> d }?.first
                                    if (cornerIdx != null) {
                                        activeHandle = cornerIdx
                                    } else {
                                        val edgeIdx = edges.indices
                                            .map { i -> i to hypot(edges[i].x - touch.x, edges[i].y - touch.y) }
                                            .filter { (_, d) -> d < hit }
                                            .minByOrNull { (_, d) -> d }?.first
                                        if (edgeIdx != null) activeHandle = 100 + edgeIdx
                                    }
                                    if (activeHandle != -1) {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffset = dragOffset + dragAmount
                                    when (val h = activeHandle) {
                                        in 0..3 -> {
                                            vm.moveCorner(h, dragAmount.x / fit.width, dragAmount.y / fit.height)
                                        }
                                        in 100..103 -> {
                                            vm.moveEdge(h - 100, dragAmount.x / fit.width, dragAmount.y / fit.height)
                                        }
                                    }
                                },
                                onDragEnd = {
                                    activeHandle = -1
                                    dragOffset = Offset.Zero
                                },
                                onDragCancel = {
                                    activeHandle = -1
                                    dragOffset = Offset.Zero
                                },
                            )
                        },
                ) {
                    val pts = quad.points().map { toDisplay(it, fit) }
                    val edgeMids = (0..3).map { toDisplay(quad.edgeMidpoint(it), fit) }
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

                    // Edge handles: pill-shaped side grips.
                    edgeMids.forEachIndexed { i, p ->
                        val active = activeHandle == 100 + i
                        drawCircle(
                            if (active) Color.White else ScanOverlayColor,
                            radius = if (active) 11.dp.toPx() else 9.dp.toPx(),
                            center = p,
                        )
                        drawCircle(Color.White, 3.dp.toPx(), p)
                    }
                    // Corner handles: larger, with white ring for visibility.
                    pts.forEachIndexed { i, p ->
                        val active = activeHandle == i
                        drawCircle(ScanOverlayColor, radius = if (active) 15.dp.toPx() else 12.dp.toPx(), center = p)
                        drawCircle(Color.White, 5.dp.toPx(), p)
                    }

                    // Magnifier loupe following the active handle.
                    val handlePos = when (val h = activeHandle) {
                        in 0..3 -> pts[h]
                        in 100..103 -> edgeMids[h - 100]
                        else -> Offset.Zero
                    }
                    if (activeHandle != -1 && bitmap.width > 0) {
                        drawLoupe(image, handlePos, fit, size.width, size.height)
                    }
                }

                Text(
                    text = "Drag corners or edges to adjust - the page is straightened on crop",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp),
                )
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

/**
 * Circular magnifier: shows the bitmap region under [handlePos] magnified
 * relative to the fitted display scale, clamped to stay on screen.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLoupe(
    image: ImageBitmap,
    handlePos: Offset,
    fit: FitRect,
    canvasW: Float,
    canvasH: Float,
) {
    val zoom = 2.6f
    val radius = 52.dp.toPx()
    val displayScale = fit.width / image.width // display px per bitmap px
    if (displayScale <= 0f) return
    val srcRadius = (radius / (displayScale * zoom)).roundToInt().coerceAtLeast(8)

    val bx = ((handlePos.x - fit.x) / displayScale).roundToInt()
        .coerceIn(srcRadius, (image.width - srcRadius).coerceAtLeast(srcRadius + 1))
    val by = ((handlePos.y - fit.y) / displayScale).roundToInt()
        .coerceIn(srcRadius, (image.height - srcRadius).coerceAtLeast(srcRadius + 1))

    // Loupe floats above the finger, clamped inside the canvas.
    val cx = handlePos.x.coerceIn(radius, (canvasW - radius).coerceAtLeast(radius))
    val cy = (handlePos.y - radius * 1.6f).coerceIn(radius, (canvasH - radius).coerceAtLeast(radius))

    val circle = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(cx - radius, cy - radius, cx + radius, cy + radius))
    }
    drawCircle(Color.Black.copy(alpha = 0.35f), radius + 3.dp.toPx(), Offset(cx, cy))
    clipPath(circle) {
        drawImage(
            image = image,
            srcOffset = IntOffset(bx - srcRadius, by - srcRadius),
            srcSize = IntSize(srcRadius * 2, srcRadius * 2),
            dstOffset = IntOffset((cx - radius).roundToInt(), (cy - radius).roundToInt()),
            dstSize = IntSize((radius * 2).roundToInt(), (radius * 2).roundToInt()),
        )
    }
    drawCircle(Color.White, radius = radius, center = Offset(cx, cy), style = Stroke(width = 2.dp.toPx()))
    // Crosshair so the exact grab point is visible.
    drawLine(
        Color.White.copy(alpha = 0.8f),
        start = Offset(cx - 10.dp.toPx(), cy),
        end = Offset(cx + 10.dp.toPx(), cy),
        strokeWidth = 1.5.dp.toPx(),
    )
    drawLine(
        Color.White.copy(alpha = 0.8f),
        start = Offset(cx, cy - 10.dp.toPx()),
        end = Offset(cx, cy + 10.dp.toPx()),
        strokeWidth = 1.5.dp.toPx(),
    )
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
