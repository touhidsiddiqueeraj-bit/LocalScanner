package com.localscanner.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.localscanner.app.domain.Pt
import com.localscanner.app.domain.QuadF
import com.localscanner.app.ui.theme.ScanOverlayColor
import kotlin.math.max

/**
 * Live document edge overlay drawn on top of the camera preview.
 * The quad is in normalized frame coordinates; mapping reproduces the
 * PreviewView FILL_CENTER transform so edges line up with the camera image.
 */
@Composable
fun EdgeOverlay(
    quad: QuadF?,
    frameAspect: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val q = quad ?: return@Canvas
        val frameW = if (frameAspect >= 1f) frameAspect else 1f
        val frameH = if (frameAspect >= 1f) 1f else 1f / frameAspect
        val scale = max(size.width / frameW, size.height / frameH)
        val dx = (size.width - frameW * scale) / 2f
        val dy = (size.height - frameH * scale) / 2f

        fun map(p: Pt): Offset = Offset(dx + p.x * frameW * scale, dy + p.y * frameH * scale)

        val path = Path().apply {
            val pts = q.points().map { map(it) }
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
            close()
        }
        // Dim everything outside the detected document.
        val framePath = Path().apply {
            addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
        }
        val outside = Path()
        outside.op(framePath, path, androidx.compose.ui.graphics.PathOperation.Difference)
        drawPath(outside, Color(0x66000000))

        drawPath(
            path,
            ScanOverlayColor,
            style = Stroke(width = 3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))),
        )
        q.points().forEach { p ->
            val o = map(p)
            drawCircle(ScanOverlayColor, radius = 7.dp.toPx(), center = o)
            drawCircle(Color.White, radius = 3.dp.toPx(), center = o)
        }
    }
}
