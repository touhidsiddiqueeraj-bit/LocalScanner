package com.localscanner.app.ui.screens.capture

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localscanner.app.domain.CaptureMode
import com.localscanner.app.ui.components.EdgeOverlay
import com.localscanner.app.ui.theme.ScanOverlayColor
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Composable
fun CaptureScreen(
    onDone: () -> Unit,
    onCancel: () -> Unit,
    vm: CaptureViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.beginScan() }

    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> permissionGranted = granted }

    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!permissionGranted) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Outlined.CameraAlt,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
            )
            Text(
                "Camera permission required",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                "LocalScanner uses the camera only to capture documents. Nothing is uploaded.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("Grant camera access")
            }
        }
        return
    }

    CameraContent(vm = vm, onDone = onDone, onCancel = onCancel)
}

@Composable
private fun CameraContent(vm: CaptureViewModel, onDone: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(Unit) {
        val provider = context.awaitCameraProvider()
        cameraProvider = provider
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        vm.imageAnalysis.setAnalyzer(vm.analysisExecutor, ImageAnalysis.Analyzer { vm.analyze(it) })
        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                vm.imageCapture,
                vm.imageAnalysis,
            )
        } catch (t: Throwable) {
            // Camera binding can fail on devices without a back camera.
        }
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { cameraProvider?.unbindAll() }
    }

    // Single mode: one shot is enough - move straight on to auto-crop.
    LaunchedEffect(vm.singleCaptureDone) {
        if (vm.singleCaptureDone) {
            vm.consumeSingleCaptureDone()
            onDone()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        EdgeOverlay(
            quad = vm.detectedQuad,
            frameAspect = vm.frameAspect,
            modifier = Modifier.fillMaxSize(),
        )

        // Top bar: cancel, page counter (batch only), done (batch only)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            if (vm.captureMode == CaptureMode.BATCH) {
                AssistChip(
                    onClick = {},
                    label = { Text("${vm.pagesCaptured} pages", color = Color.White) },
                    modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape),
                )
            }
            Spacer(Modifier.weight(1f))
            if (vm.captureMode == CaptureMode.BATCH) {
                IconButton(
                    onClick = { if (vm.pagesCaptured > 0) onDone() },
                    enabled = vm.pagesCaptured > 0,
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Finish capturing",
                        tint = if (vm.pagesCaptured > 0) Color.White else Color.White.copy(alpha = 0.4f),
                    )
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }

        // Left rail: flash toggle (auto capture was removed entirely).
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconButton(onClick = {
                vm.setFlash(
                    when (vm.flashMode) {
                        ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                        ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                        else -> ImageCapture.FLASH_MODE_OFF
                    },
                )
            }) {
                Icon(
                    imageVector = when (vm.flashMode) {
                        ImageCapture.FLASH_MODE_OFF -> Icons.Filled.FlashOff
                        else -> Icons.Filled.FlashOn
                    },
                    contentDescription = "Flash",
                    tint = Color.White,
                )
            }
        }

        // Bottom: shooting mode (Single / Batch) + shutter
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ModeSwitcher(
                selected = vm.captureMode,
                onSelect = { vm.updateCaptureMode(it) },
            )
            Spacer(Modifier.height(18.dp))
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(8.dp)
                    .size(68.dp)
                    .background(
                        if (vm.capturing) Color.White.copy(alpha = 0.5f) else Color.White,
                        CircleShape,
                    )
                    .clickable(enabled = !vm.capturing) { vm.capture() },
                contentAlignment = Alignment.Center,
            ) {}
        }
    }
}

/** CamScanner-style shooting mode selector: Single | Batch. */
@Composable
private fun ModeSwitcher(
    selected: CaptureMode,
    onSelect: (CaptureMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(22.dp))
            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(22.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CaptureMode.entries.forEach { mode ->
            val isSelected = mode == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (isSelected) ScanOverlayColor else Color.Transparent)
                    .clickable { onSelect(mode) }
                    .padding(horizontal = 22.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = mode.label,
                    color = if (isSelected) Color.Black else Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

private suspend fun android.content.Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    continuation.resume(future.get())
                } catch (t: Throwable) {
                    continuation.resumeWith(Result.failure(t))
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }
