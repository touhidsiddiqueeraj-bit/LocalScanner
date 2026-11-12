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
    fun `ocr line codec round trip`() {
        val lines = listOf(
            OcrLine("Invoice #123", IRect(10, 20, 300, 60)),
            OcrLine("বিল নম্বর", IRect(10, 70, 300, 110)),
        )
        val json = OcrLinesCodec.encode(lines)
        assertTrue(json!!.contains("Invoice"))
        val decoded = OcrLinesCodec.decode(json)!!
        assertEquals(2, decoded.size)
        assertEquals("Invoice #123", decoded[0].text)
        assertEquals(IRect(10, 20, 300, 60), decoded[0].box)
    }

    @Test
    fun `ocr codec handles garbage input`() {
        assertNull(OcrLinesCodec.decode(null))
        assertNull(OcrLinesCodec.decode("not json"))
    }
}
