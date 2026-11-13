package com.localscanner.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.localscanner.app.domain.Pt
import com.localscanner.app.domain.QuadF
import com.localscanner.app.util.ImageFiles
import java.io.File

/**
 * Synthetic "scanned" pages drawn with Canvas so the screenshot tests exercise
 * the real image pipeline (JPEG files on disk -> Coil/BitmapFactory) and show
 * realistic document content instead of blank placeholders.
 */
object SampleScans {

    private const val W = 1000
    private const val H = 1400

    private val INK = Color.rgb(0x2A, 0x2A, 0x2A)
    private val INK_SOFT = Color.rgb(0x6E, 0x6E, 0x6E)
    private val PAPER_WHITE = Color.rgb(0xFD, 0xFD, 0xF9)
    private val PAPER_SHADE = Color.rgb(0xF2, 0xF1, 0xEA)

    private class Pens {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            textSize = 52f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK_SOFT
            textSize = 30f
            typeface = Typeface.SANS_SERIF
        }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x9C, 0x9C, 0x9C)
            textSize = 26f
        }
    }

    private fun newCanvas(top: Int = PAPER_WHITE, bottom: Int = PAPER_SHADE): Pair<Bitmap, Canvas> {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, H.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), bg)
        return bmp to c
    }

    private fun bodyLines(
        c: Canvas,
        p: Pens,
        x: Float,
        yStart: Float,
        widths: List<Float>,
        lineGap: Float = 44f,
        height: Float = 13f,
        color: Int = Color.rgb(0xB4, 0xB4, 0xB4),
    ) {
        val paint = Paint(p.body).apply { this.color = color }
        var y = yStart
        for (w in widths) {
            c.drawRoundRect(RectF(x, y, x + W * w - 2 * x, y + height), 6f, 6f, paint)
            y += lineGap
        }
    }

    private fun heading(c: Canvas, p: Pens, text: String, x: Float, y: Float) {
        c.drawText(text, x, y, Paint(p.sub).apply {
            color = INK
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        })
    }

    private fun title(c: Canvas, p: Pens, text: String, y: Float) {
        c.drawText(text, 84f, y, p.title)
    }

    private fun rule(c: Canvas, y: Float) {
        c.drawRect(RectF(84f, y, W - 84f, y + 3f), Paint().apply { color = Color.rgb(0xCC, 0xCC, 0xC4) })
    }

    /** Rental agreement page (Magic Color style: clean white, crisp ink). */
    fun agreementBitmap(): Bitmap {
        val (bmp, c) = newCanvas()
        val p = Pens()
        title(c, p, "RENTAL AGREEMENT", 130f)
        c.drawText("Flat 4B, Dhanmondi 27, Dhaka 1209", 84f, 180f, p.sub)
        rule(c, 210f)

        heading(c, p, "1. Parties", 84f, 290f)
        bodyLines(c, p, 84f, 330f, listOf(0.86f, 0.92f, 0.80f))

        heading(c, p, "2. Term and Rent", 84f, 520f)
        bodyLines(c, p, 84f, 560f, listOf(0.88f, 0.90f, 0.72f, 0.85f))

        heading(c, p, "3. Security Deposit", 84f, 810f)
        bodyLines(c, p, 84f, 850f, listOf(0.84f, 0.78f))

        // Signature block
        c.drawText("Signed on 12 March 2026", 84f, 1060f, p.sub)
        val line = Paint(p.stroke).apply { strokeWidth = 3f; color = INK }
        c.drawLine(84f, 1160f, 460f, 1160f, line)
        c.drawText("Tenant signature", 84f, 1205f, Paint(p.sub).apply { textSize = 24f })
        c.drawLine(540f, 1160f, 916f, 1160f, line)
        c.drawText("Landlord signature", 540f, 1205f, Paint(p.sub).apply { textSize = 24f })

        c.drawText("Page 1 of 2", W / 2f - 70f, 1330f, Paint(p.sub).apply { textSize = 24f })
        return bmp
    }

    /** Inspection report page with a status table. */
    fun reportBitmap(): Bitmap {
        val (bmp, c) = newCanvas()
        val p = Pens()
        title(c, p, "INSPECTION REPORT", 130f)
        c.drawText("Prepared by LocalScanner - 12 Mar 2026", 84f, 180f, p.sub)
        rule(c, 210f)

        val left = 84f
        val top = 280f
        val colW = arrayOf(400f, 280f, 148f)
        val rowH = 90f
        val rows = listOf(
            "Refrigerator" to "Working",
            "Air conditioner" to "Service due",
            "Washing machine" to "Working",
            "Water heater" to "Working",
            "Kitchen cooktop" to "Replaced",
            "Ceiling fans" to "Working",
        )
        val grid = Paint(p.stroke).apply { color = Color.rgb(0xB8, 0xB8, 0xB0); strokeWidth = 2f }
        var y = top
        for (r in 0..rows.size) {
            c.drawLine(left, y, left + colW.sum(), y, grid)
            y += rowH
        }
        var x = left
        for (w in colW) {
            c.drawLine(x, top, x, top + rowH * rows.size, grid)
            x += w
        }
        c.drawLine(left + colW.sum(), top, left + colW.sum(), top + rowH * rows.size, grid)

        val label = Paint(p.sub).apply { color = INK }
        val ok = Paint(p.fill).apply { color = Color.rgb(0x2E, 0x8B, 0x57) }
        val warn = Paint(p.fill).apply { color = Color.rgb(0xD4, 0x8A, 0x1E) }
        rows.forEachIndexed { i, (name, status) ->
            val cy = top + rowH * i + rowH / 2f + 10f
            c.drawText(name, left + 24f, cy, label)
            c.drawText(status, left + colW[0] + 24f, cy, label)
            val dot = if (status == "Working") ok else warn
            c.drawCircle(left + colW[0] + colW[1] + 74f, cy - 9f, 13f, dot)
        }

        bodyLines(c, p, 84f, 1120f, listOf(0.86f, 0.74f), color = Color.rgb(0xC0, 0xC0, 0xB8))
        return bmp
    }

    /** Meeting notes page with bullets and a sticky note. */
    fun notesBitmap(): Bitmap {
        val (bmp, c) = newCanvas(top = PAPER_SHADE, bottom = PAPER_WHITE)
        val p = Pens()
        title(c, p, "TEAM MEETING NOTES", 130f)
        c.drawText("12 March 2026 - 10:30 AM", 84f, 180f, p.sub)
        rule(c, 210f)

        val bullet = Paint(p.fill).apply { color = INK }
        val items = listOf(
            "Sprint 42 scope confirmed", "OCR Bengali accuracy at 94%",
            "PDF export size reduced 30%", "Auto-capture tuned for A4 sheets",
            "Next review: 19 March",
        )
        var y = 330f
        for (t in items) {
            c.drawCircle(100f, y - 9f, 10f, bullet)
            c.drawText(t, 140f, y, Paint(p.sub).apply { color = INK })
            y += 110f
        }
        bodyLines(c, p, 84f, y + 40f, listOf(0.84f, 0.88f, 0.62f), color = Color.rgb(0xC0, 0xC0, 0xB8))

        // Sticky note
        val sticky = Paint(p.fill).apply { color = Color.rgb(0xFF, 0xE0, 0x82) }
        val shadow = Paint(p.fill).apply { color = 0x33000000 }
        c.save()
        c.rotate(-3f, 700f, 1180f)
        c.drawRoundRect(RectF(560f, 1040f, 900f, 1300f), 10f, 10f, shadow)
        c.drawRoundRect(RectF(552f, 1032f, 892f, 1292f), 10f, 10f, sticky)
        val ink = Paint(p.sub).apply { color = Color.rgb(0x5A, 0x46, 0x00); textSize = 28f }
        c.drawText("Follow up with", 590f, 1100f, ink)
        c.drawText("landlord about", 590f, 1150f, ink)
        c.drawText("parking slot", 590f, 1200f, ink)
        c.restore()
        return bmp
    }

    /**
     * A camera-style photo of a document lying on a desk, used by the crop
     * screenshot. Returns the bitmap and the matching document quad (normalized
     * coordinates) so the crop overlay lines up with the page.
     */
    fun deskPhotoBitmap(): Pair<Bitmap, QuadF> {
        val quad = QuadF(
            tl = Pt(0.115f, 0.055f),
            tr = Pt(0.905f, 0.075f),
            br = Pt(0.875f, 0.945f),
            bl = Pt(0.085f, 0.925f),
        )
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        // Desk
        val desk = Paint().apply {
            shader = LinearGradient(0f, 0f, W.toFloat(), H.toFloat(),
                Color.rgb(0x7C, 0x5E, 0x46), Color.rgb(0x5B, 0x44, 0x34), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), desk)

        fun px(pt: Pt) = android.graphics.PointF(pt.x * W, pt.y * H)
        val pts = quad.points().map { px(it) }
        val page = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
            close()
        }

        // Soft shadow, then paper
        val shadow = Path(page)
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x51000000; style = Paint.Style.FILL }
        c.save()
        c.translate(10f, 14f)
        c.drawPath(shadow, shadowPaint)
        c.restore()
        c.drawPath(page, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0xFC, 0xFB, 0xF4)
            style = Paint.Style.FILL
        })

        // Page content clipped to the sheet
        c.save()
        c.clipPath(page)
        val p = Pens()
        c.drawText("RENTAL AGREEMENT", 130f, 220f, Paint(p.title).apply { textSize = 64f })
        bodyLines(c, p, 130f, 300f, listOf(0.80f, 0.84f, 0.72f, 0.82f, 0.66f, 0.78f, 0.60f, 0.74f))
        bodyLines(c, p, 130f, 760f, listOf(0.82f, 0.70f, 0.78f), color = Color.rgb(0xC4, 0xC4, 0xBC))
        c.restore()
        return bmp to quad
    }

    /** Saves a bitmap as a JPEG file suitable for the repository seeding. */
    fun saveJpeg(bmp: Bitmap, out: File): File {
        out.parentFile?.mkdirs()
        ImageFiles.saveJpeg(bmp, out, 92)
        return out
    }
}
