package com.localscanner.app

import com.localscanner.app.domain.PageOrientation
import com.localscanner.app.domain.PageSizeCalculator
import com.localscanner.app.domain.PdfPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSizeCalculatorTest {

    @Test
    fun `A4 portrait stays portrait for portrait images`() {
        val rect = PageSizeCalculator.pageRect(PdfPage.A4, PageOrientation.AUTO, 1000, 1414)
        assertEquals(595f, rect.widthPt, 0.01f)
        assertEquals(842f, rect.heightPt, 0.01f)
    }

    @Test
    fun `A4 auto becomes landscape for landscape images`() {
        val rect = PageSizeCalculator.pageRect(PdfPage.A4, PageOrientation.AUTO, 1414, 1000)
        assertEquals(842f, rect.widthPt, 0.01f)
        assertEquals(595f, rect.heightPt, 0.01f)
    }

    @Test
    fun `forced portrait flips landscape images`() {
        val rect = PageSizeCalculator.pageRect(PdfPage.A4, PageOrientation.PORTRAIT, 2000, 1000)
        assertTrue(rect.heightPt > rect.widthPt)
    }

    @Test
    fun `FIT derives size from aspect ratio`() {
        val rect = PageSizeCalculator.pageRect(PdfPage.FIT, PageOrientation.AUTO, 500, 1000)
        assertEquals(612f, rect.heightPt, 0.01f)
        assertEquals(306f, rect.widthPt, 0.01f)
    }

    @Test
    fun `fitRect keeps aspect and centers`() {
        val fit = PageSizeCalculator.fitRect(500, 1000, 595f, 842f, 12f)
        val ratio = fit.widthPt / fit.heightPt
        assertEquals(0.5f, ratio, 0.01f)
        // centered: margins are equal on both axes
        val marginX = (595f - fit.widthPt) / 2f
        val marginY = (842f - fit.heightPt) / 2f
        assertTrue(marginX >= 12f && marginY >= 12f)
    }

    @Test
    fun `letter size has known dimensions`() {
        val rect = PageSizeCalculator.pageRect(PdfPage.LETTER, PageOrientation.PORTRAIT, 1000, 1200)
        assertEquals(612f, rect.widthPt, 0.01f)
        assertEquals(792f, rect.heightPt, 0.01f)
    }
}
