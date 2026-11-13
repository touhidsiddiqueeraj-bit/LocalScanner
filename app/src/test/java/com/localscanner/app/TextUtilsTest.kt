package com.localscanner.app

import com.localscanner.app.domain.OcrLinesCodec
import com.localscanner.app.domain.IRect
import com.localscanner.app.domain.OcrLine
import com.localscanner.app.domain.TextUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextUtilsTest {

    @Test
    fun `bengali script is detected`() {
        assertTrue(TextUtils.containsBengali("আমার সোনার বাংলা"))
        assertFalse(TextUtils.containsBengali("Hello world"))
    }

    @Test
    fun `winansi sanitization strips non-latin glyphs`() {
        val safe = TextUtils.toWinAnsiSafe("Invoice #123 — বাংলা test")
        assertFalse(safe.contains('ব'))
        assertTrue(safe.contains("Invoice"))
    }

    @Test
    fun `ocr text cleanup removes empty line runs`() {
        val cleaned = TextUtils.cleanOcrText("line one\n\n\n\n\nline two")
        assertEquals("line one\nline two", cleaned)
    }

    @Test
    fun `file names are sanitized`() {
        assertEquals("My_Invoice_2024", TextUtils.sanitizeFileName("My/Invoice 2024?"))
        assertEquals("scan", TextUtils.sanitizeFileName("///"))
    }

    @Test
    fun `search index is null when no text`() {
        assertNull(TextUtils.buildSearchIndex(listOf("", " ")))
    }

    @Test
    fun `ocr line codec round trip v2 with bitmap size`() {
        val lines = listOf(
            OcrLine("Invoice #123", IRect(10, 20, 300, 60)),
            OcrLine("বিল নম্বর", IRect(10, 70, 300, 110)),
        )
        val json = OcrLinesCodec.encode(1240, 1754, lines)
        assertTrue(json!!.contains("Invoice"))
        val decoded = OcrLinesCodec.decode(json)!!
        assertEquals(2, decoded.lines.size)
        assertEquals(1240, decoded.bmpW)
        assertEquals(1754, decoded.bmpH)
        assertEquals("Invoice #123", decoded.lines[0].text)
        assertEquals(IRect(10, 20, 300, 60), decoded.lines[0].box)
        // Normalized boxes are derived from the stored reference size.
        val norm = decoded.normalizedLines()!!
        assertEquals(10f / 1240f, norm[0].second.x, 1e-6f)
    }

    @Test
    fun `ocr codec still reads legacy v1 arrays`() {
        val legacy = "[{\"t\":\"Hello\",\"x\":5,\"y\":6,\"w\":100,\"h\":20}]"
        val decoded = OcrLinesCodec.decode(legacy)!!
        assertEquals(1, decoded.lines.size)
        assertEquals(-1, decoded.bmpW) // unknown reference size
        assertNull(decoded.normalizedLines())
    }

    @Test
    fun `ocr codec handles garbage input`() {
        assertNull(OcrLinesCodec.decode(null))
        assertNull(OcrLinesCodec.decode("not json"))
    }
}
