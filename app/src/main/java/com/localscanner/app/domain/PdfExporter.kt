package com.localscanner.app.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.localscanner.app.data.db.DocumentEntity
import com.localscanner.app.data.db.PageEntity
import com.localscanner.app.util.ImageFiles
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Exports scanned documents to PDF, replicating CamScanner's local export:
 * page size presets (A4/Letter/Legal/A3/A5/fit), quality/compression control,
 * and a searchable text layer built from on-device OCR (no watermark).
 */
class PdfExporter(private val context: Context) {

    data class Options(
        val pageSize: PdfPage = PdfPage.A4,
        val orientation: PageOrientation = PageOrientation.AUTO,
        val qualityPct: Int = 80,
        val searchable: Boolean = true,
        val fileName: String = "scan.pdf",
    )

    suspend fun export(
        pages: List<PageEntity>,
        options: Options,
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val exportsDir = File(context.filesDir, "exports").apply { mkdirs() }
        val outFile = File(exportsDir, options.fileName)
        val bitmaps = mutableListOf<Bitmap>()
        try {
            pages.forEach { page ->
                val bmp = ImageFiles.decodeScaled(page.filePath, MAX_EXPORT_DIM)
                    ?: throw IllegalStateException("Cannot decode page image: ${page.filePath}")
                bitmaps.add(bmp)
            }
            if (options.searchable) {
                exportSearchable(bitmaps, pages, options, outFile, onProgress)
            } else {
                exportPlain(bitmaps, options, outFile, onProgress)
            }
        } finally {
            bitmaps.forEach { it.recycle() }
        }
        outFile
    }

    private fun exportPlain(
        bitmaps: List<Bitmap>,
        options: Options,
        outFile: File,
        onProgress: (Float) -> Unit,
    ) {
        val document = PdfDocument()
        try {
            bitmaps.forEachIndexed { i, bmp ->
                val rect = PageSizeCalculator.pageRect(options.pageSize, options.orientation, bmp.width, bmp.height)
                val info = PdfDocument.PageInfo.Builder(
                    rect.widthPt.toInt(), rect.heightPt.toInt(), i + 1,
                ).create()
                val page = document.startPage(info)
                drawBitmapFit(page.canvas, bmp, rect.widthPt, rect.heightPt)
                document.finishPage(page)
                onProgress((i + 1) / bitmaps.size.toFloat())
            }
            FileOutputStream(outFile).use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }

    private fun exportSearchable(
        bitmaps: List<Bitmap>,
        pages: List<PageEntity>,
        options: Options,
        outFile: File,
        onProgress: (Float) -> Unit,
    ) {
        val doc = PDDocument()
        try {
            // Embedded Bengali-capable font, created lazily against this document.
            var bengaliFont: PDType0Font? = null
            fun getBengaliFont(): PDType0Font? {
                if (bengaliFont == null) {
                    bengaliFont = try {
                        context.assets.open(BENGALI_FONT).use { input ->
                            PDType0Font.load(doc, input, true)
                        }
                    } catch (t: Throwable) {
                        null
                    }
                }
                return bengaliFont
            }
            bitmaps.forEachIndexed { i, bmp ->
                val entity = pages[i]
                val rect = PageSizeCalculator.pageRect(options.pageSize, options.orientation, bmp.width, bmp.height)
                val pdPage = PDPage(PDRectangle(rect.widthPt, rect.heightPt))
                val img = try {
                    JPEGFactory.createFromImage(doc, bmp, options.qualityPct / 100f)
                } catch (t: Throwable) {
                    LosslessFactory.createFromImage(doc, bmp)
                }
                if (pageResources(pdPage) == null) pdPage.setResources(PDResources())
                val cs = PDPageContentStream(doc, pdPage)
                try {
                    cs.drawImage(img, 0f, 0f, rect.widthPt, rect.heightPt)
                    // Invisible (alpha 0) text layer -> selectable, searchable PDF.
                    val ext = PDExtendedGraphicsState()
                    ext.setNonStrokingAlphaConstant(0f)
                    cs.setGraphicsStateParameters(ext)

                    val lines = OcrLinesCodec.decode(entity.ocrLinesJson)
                        ?: pseudoLines(entity.ocrText, bmp.width, bmp.height)
                    if (lines.isNotEmpty()) {
                        drawInvisibleLines(cs, lines, bmp, rect, ::getBengaliFont)
                    }
                } finally {
                    cs.close()
                }
                doc.addPage(pdPage)
                onProgress((i + 1) / bitmaps.size.toFloat())
            }
            doc.save(outFile)
        } finally {
            doc.close()
        }
    }

    private fun drawInvisibleLines(
        cs: PDPageContentStream,
        lines: List<OcrLine>,
        bmp: Bitmap,
        pageRect: PageRect,
        getBengaliFont: () -> PDType0Font?,
    ) {
        val fit = PageSizeCalculator.fitRect(bmp.width, bmp.height, pageRect.widthPt, pageRect.heightPt, MARGIN_PT)
        val fitW = fit.widthPt
        val fitH = fit.heightPt
        val originX = (pageRect.widthPt - fitW) / 2f
        val topEdge = (pageRect.heightPt + fitH) / 2f // top of image in PDF coords
        val sx = fitW / bmp.width
        val sy = fitH / bmp.height

        for (line in lines) {
            val box = line.box ?: continue
            if (line.text.isBlank()) continue
            val xPt = originX + box.left * sx
            val wPt = box.width * sx
            val hPt = (box.height * sy).coerceAtLeast(2f)
            val baselineY = topEdge - (box.top + box.height) * sy + hPt * 0.22f
            val fontSize = (hPt * 0.95f).coerceIn(4f, 40f)
            try {
                if (TextUtils.containsBengali(line.text)) {
                    val font = getBengaliFont()
                    if (font != null) {
                        cs.beginText()
                        cs.setFont(font, fontSize)
                        cs.newLineAtOffset(xPt, baselineY)
                        cs.showText(line.text)
                        cs.endText()
                        continue
                    }
                }
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, fontSize)
                cs.newLineAtOffset(xPt, baselineY)
                cs.showText(TextUtils.toWinAnsiSafe(line.text))
                cs.endText()
            } catch (t: Throwable) {
                // Skip lines whose glyphs cannot be embedded; never fail the export.
            }
        }
    }

    private fun pageResources(page: PDPage): PDResources? = try {
        page.resources
    } catch (t: Throwable) {
        null
    }

    private fun drawBitmapFit(canvas: android.graphics.Canvas, bmp: Bitmap, pageW: Float, pageH: Float) {
        canvas.drawColor(Color.WHITE)
        val fit = PageSizeCalculator.fitRect(bmp.width, bmp.height, pageW, pageH, MARGIN_PT)
        val dst = RectF(
            (pageW - fit.widthPt) / 2f,
            (pageH - fit.heightPt) / 2f,
            (pageW + fit.widthPt) / 2f,
            (pageH + fit.heightPt) / 2f,
        )
        val paint = Paint().apply { isFilterBitmap = true }
        canvas.drawBitmap(bmp, null, dst, paint)
    }

    private fun pseudoLines(text: String?, imgW: Int, imgH: Int): List<OcrLine> {
        if (text.isNullOrBlank()) return emptyList()
        val rawLines = text.lines().filter { it.isNotBlank() }
        if (rawLines.isEmpty()) return emptyList()
        val lineH = imgH / maxOf(rawLines.size, 12)
        return rawLines.mapIndexed { i, t ->
            OcrLine(t, IRect(0, i * lineH, imgW, (i + 1) * lineH))
        }
    }

    companion object {
        const val MARGIN_PT = 12f
        const val MAX_EXPORT_DIM = 1700
        const val BENGALI_FONT = "fonts/NotoSansBengali-Regular.ttf"
    }
}
