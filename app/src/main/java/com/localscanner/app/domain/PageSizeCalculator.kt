package com.localscanner.app.domain

/** Supported PDF page sizes. FIT derives the page size from the image aspect. */
enum class PdfPage(val widthPt: Float, val heightPt: Float) {
    FIT(0f, 0f),
    A4(595f, 842f),
    LETTER(612f, 792f),
    LEGAL(612f, 1008f),
    A3(842f, 1191f),
    A5(420f, 595f),
}

enum class PageOrientation { AUTO, PORTRAIT, LANDSCAPE }

/** Page rectangle in PDF points (72 dpi). */
data class PageRect(val widthPt: Float, val heightPt: Float)

object PageSizeCalculator {

    /**
     * Computes the output page rect for a page image.
     *
     * @param size chosen page size (FIT derives from the image)
     * @param orientation portrait/landscape/auto
     * @param imgW image width in pixels
     * @param imgH image height in pixels
     */
    fun pageRect(size: PdfPage, orientation: PageOrientation, imgW: Int, imgH: Int): PageRect {
        require(imgW > 0 && imgH > 0) { "image must have positive dimensions" }
        return when (size) {
            PdfPage.FIT -> {
                val maxDimPt = 612f // roughly Letter width
                val ratio = imgW.toFloat() / imgH.toFloat()
                if (imgW >= imgH) {
                    PageRect(maxDimPt, maxDimPt / ratio)
                } else {
                    PageRect(maxDimPt * ratio, maxDimPt)
                }
            }
            else -> {
                var w = size.widthPt
                var h = size.heightPt
                val landscape = when (orientation) {
                    PageOrientation.LANDSCAPE -> true
                    PageOrientation.PORTRAIT -> false
                    PageOrientation.AUTO -> imgW > imgH
                }
                if (landscape && w < h) {
                    val t = w; w = h; h = t
                }
                PageRect(w, h)
            }
        }
    }

    /** Destination rect for drawing an image inside a page, preserving aspect, centered. */
    fun fitRect(
        imgW: Int,
        imgH: Int,
        pageW: Float,
        pageH: Float,
        marginPt: Float,
    ): PageRect {
        val availW = (pageW - 2 * marginPt).coerceAtLeast(10f)
        val availH = (pageH - 2 * marginPt).coerceAtLeast(10f)
        val scale = minOf(availW / imgW, availH / imgH)
        val w = imgW * scale
        val h = imgH * scale
        val x = (pageW - w) / 2f
        val y = (pageH - h) / 2f
        return PageRect(w, h) // width/height reused as fit size; origin computed by caller
    }
}
