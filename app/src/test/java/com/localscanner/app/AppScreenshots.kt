package com.localscanner.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.localscanner.app.data.DocsRepository
import com.localscanner.app.domain.IRect
import com.localscanner.app.domain.OcrLine
import com.localscanner.app.domain.OcrLinesCodec
import com.localscanner.app.domain.Pt
import com.localscanner.app.domain.QuadF
import com.localscanner.app.ui.components.EdgeOverlay
import com.localscanner.app.ui.screens.crop.CropScreen
import com.localscanner.app.ui.screens.crop.CropViewModel
import com.localscanner.app.ui.screens.detail.DocDetailScreen
import com.localscanner.app.ui.screens.filter.FilterScreen
import com.localscanner.app.ui.screens.filter.FilterViewModel
import com.localscanner.app.ui.screens.home.HomeScreen
import com.localscanner.app.ui.screens.pdf.PdfExportScreen
import com.localscanner.app.ui.screens.settings.SettingsScreen
import com.localscanner.app.ui.screens.viewer.PageViewerScreen
import com.localscanner.app.ui.theme.LocalScannerTheme
import com.localscanner.app.ui.theme.ScanOverlayColor
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real Compose screens with seeded data and writes PNG captures to
 * app/src/test/screenshots/. The images are committed to docs/screenshots/ and
 * embedded in the README. No emulator or device is needed: Robolectric's native
 * graphics render the actual UI on the JVM.
 *
 * The camera screen is reconstructed here (Robolectric has no camera HAL); it
 * uses the real EdgeOverlay component, colors and control layout.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = ScreenshotApp::class, qualifiers = RobolectricDeviceQualifiers.Pixel6)
class AppScreenshots {

    @get:Rule
    val compose = createComposeRule()

    private val app: ScreenshotApp get() = ApplicationProvider.getApplicationContext()

    private val shotDir: File
        get() = File(System.getProperty("user.dir") ?: ".", "src/test/screenshots").apply { mkdirs() }

    private fun shot(name: String): String = File(shotDir, "$name.png").absolutePath

    private fun light(content: @Composable () -> Unit) =
        compose.setContent { LocalScannerTheme(darkTheme = false, dynamicColor = false) { content() } }

    private fun dark(content: @Composable () -> Unit) =
        compose.setContent { LocalScannerTheme(darkTheme = true, dynamicColor = false) { content() } }

    private fun capture(name: String, settleMs: Long = 2_500) {
        Thread.sleep(settleMs) // let async work (Coil decodes, flow emissions) finish
        compose.onRoot().captureRoboImage(shot(name))
    }

    private fun waitForText(text: String, timeoutMs: Long = 15_000) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Seeds a small library: 3 documents, 4 pages, OCR text for two of them. */
    private fun seedLibrary(): String {
        val repo = app.container.repository
        val tmp = File(app.cacheDir, "samples").apply { mkdirs() }
        return runBlocking {
            val d1 = repo.createDocFromFiles(
                "Rental Agreement - Flat 4B", "Home",
                listOf(
                    DocsRepository.PageResult(SampleScans.saveJpeg(SampleScans.agreementBitmap(), File(tmp, "a1.jpg"))),
                    DocsRepository.PageResult(SampleScans.saveJpeg(SampleScans.reportBitmap(), File(tmp, "a2.jpg"))),
                ),
            )
            Thread.sleep(15)
            val d2 = repo.createDocFromFiles(
                "Appliance Inspection Report", null,
                listOf(DocsRepository.PageResult(SampleScans.saveJpeg(SampleScans.reportBitmap(), File(tmp, "b1.jpg")))),
            )
            Thread.sleep(15)
            val d3 = repo.createDocFromFiles(
                "Team Meeting Notes", "Work",
                listOf(DocsRepository.PageResult(SampleScans.saveJpeg(SampleScans.notesBitmap(), File(tmp, "c1.jpg")))),
            )

            val p1 = repo.getPages(d1.id)
            val agreementLines = listOf(
                OcrLine("RENTAL AGREEMENT", IRect(84, 80, 760, 150)),
                OcrLine("Flat 4B, Dhanmondi 27, Dhaka 1209", IRect(84, 150, 660, 195)),
                OcrLine("1. Parties", IRect(84, 250, 300, 305)),
                OcrLine("Monthly rent: BDT 18,500", IRect(84, 505, 640, 570)),
                OcrLine("3. Security Deposit", IRect(84, 770, 470, 825)),
            )
            repo.setOcr(
                p1[0],
                "RENTAL AGREEMENT\nThis agreement is made on 12 March 2026 between\n" +
                    "Rahim Uddin (Landlord) and Farhana Akter (Tenant).\n" +
                    "Monthly rent: BDT 18,500, payable by the 5th of each month.\n" +
                    "Security deposit: two months' rent.",
                "tess",
                OcrLinesCodec.encode(1000, 1400, agreementLines),
            )
            val p3 = repo.getPages(d3.id)
            repo.setOcr(
                p3[0],
                "TEAM MEETING NOTES\nDate: 12 March 2026\nAttendees: Rafiq, Mona, Joy\n" +
                    "Bengali sample: জন্ম নিবন্ধন সনদ",
                "tess",
                null,
            )
            d1.id
        }
    }

    @Test
    fun homeLight() {
        seedLibrary()
        light {
            HomeScreen(onScan = {}, onOpenDoc = {}, onOpenSettings = {}, onImportReady = {})
        }
        waitForText("LocalScanner")
        capture("home_light", settleMs = 3_000)
    }

    @Test
    fun homeDark() {
        seedLibrary()
        dark {
            HomeScreen(onScan = {}, onOpenDoc = {}, onOpenSettings = {}, onImportReady = {})
        }
        waitForText("LocalScanner")
        capture("home_dark", settleMs = 3_000)
    }

    @Test
    fun captureScreen() {
        light { CaptureShotSim(pages = 2, batchMode = true) }
        waitForText("2 pages")
        capture("capture", settleMs = 1_500)
    }

    @Test
    fun cropScreen() {
        seedLibrary()
        val vm = CropViewModel(app)
        val (bmp, quad) = SampleScans.deskPhotoBitmap()
        vm.seedForScreenshot(bmp, quad)
        light { CropScreen(onDone = {}, vm = vm) }
        waitForText("Crop")
        capture("crop", settleMs = 1_500)
    }

    @Test
    fun filterScreen() {
        seedLibrary()
        val vm = FilterViewModel(app)
        vm.seedForScreenshot(SampleScans.agreementBitmap())
        light { FilterScreen(onSaved = {}, vm = vm) }
        waitForText("Magic Color")
        capture("filter", settleMs = 1_500)
    }

    @Test
    fun docDetailScreen() {
        val docId = seedLibrary()
        light {
            DocDetailScreen(docId, onBack = {}, onExportPdf = {}, onOpenViewer = {}, onAddPages = {})
        }
        waitForText("Rental Agreement - Flat 4B")
        capture("doc_detail", settleMs = 3_000)
    }

    @Test
    fun viewerScreen() {
        val docId = seedLibrary()
        light { PageViewerScreen(docId, onBack = {}) }
        waitForText("Page 1 of 2")
        capture("viewer", settleMs = 3_000)
    }

    @Test
    fun pdfExportScreen() {
        val docId = seedLibrary()
        light { PdfExportScreen(docId, onBack = {}) }
        waitForText("Export PDF")
        capture("pdf", settleMs = 2_000)
    }

    @Test
    fun settingsDark() {
        dark { SettingsScreen(onBack = {}) }
        waitForText("Settings")
        capture("settings_dark", settleMs = 1_500)
    }
}

/**
 * Stand-in for the live camera screen used by the screenshot suite. Robolectric
 * has no camera HAL, so this renders a simulated desk scene with the REAL
 * EdgeOverlay component and the same control layout as CaptureScreen
 * (Single / Batch mode pills, flash, shutter).
 */
@Composable
private fun CaptureShotSim(pages: Int, batchMode: Boolean) {
    val quad = QuadF(
        tl = Pt(0.14f, 0.10f),
        tr = Pt(0.86f, 0.08f),
        br = Pt(0.90f, 0.90f),
        bl = Pt(0.10f, 0.94f),
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Simulated camera preview: a document on a desk, FILL_CENTER framed at 4:3.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val aspect = 4f / 3f
            val frameW = if (aspect >= 1f) aspect else 1f
            val frameH = if (aspect >= 1f) 1f else 1f / aspect
            val scale = maxOf(size.width / frameW, size.height / frameH)
            val dx = (size.width - frameW * scale) / 2f
            val dy = (size.height - frameH * scale) / 2f
            fun map(p: Pt) = androidx.compose.ui.geometry.Offset(dx + p.x * frameW * scale, dy + p.y * frameH * scale)

            // Desk + window light
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color(0xFF6E5847), Color(0xFF4E3E32)),
                ),
            )
            // Paper sheet aligned with the overlay quad
            val pts = quad.points().map { map(it) }
            val page = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                close()
            }
            drawPath(page, Color(0xF2, 0xF1, 0xE8))
            drawPath(
                page,
                Color.Black.copy(alpha = 0.10f),
                style = Stroke(width = 6f),
            )
        }
        EdgeOverlay(quad = quad, frameAspect = 4f / 3f, modifier = Modifier.fillMaxSize())

        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {}) {
                Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            AssistChip(
                onClick = {},
                label = { Text("$pages pages", color = Color.White) },
                modifier = Modifier
                    .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape),
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {}) {
                Icon(Icons.Filled.Check, contentDescription = "Finish", tint = Color.White)
            }
        }

        // Left rail: flash only (auto capture was removed entirely)
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconButton(onClick = {}) {
                Icon(Icons.Filled.FlashOn, contentDescription = "Flash", tint = Color.White)
            }
        }

        // Bottom: mode pills + shutter
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.55f), androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
                    .padding(4.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
            ) {
                listOf("Single" to !batchMode, "Batch" to batchMode).forEach { (label, selected) ->
                    Box(
                        modifier = Modifier
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                            .background(if (selected) ScanOverlayColor else Color.Transparent)
                            .padding(horizontal = 22.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = label,
                            color = if (selected) Color.Black else Color.White,
                            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(8.dp)
                    .size(68.dp)
                    .background(Color.White, CircleShape),
            ) {}
        }
    }
}
