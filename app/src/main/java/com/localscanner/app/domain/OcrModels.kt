package com.localscanner.app.domain

/** OCR result models. Kept Android-free (except nothing) for testability. */

data class IRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

data class OcrLine(
    val text: String,
    val box: IRect?,
)

data class OcrResult(
    val text: String,
    val lines: List<OcrLine>,
    val engine: String,
)

enum class OcrEngineOption(val id: String, val label: String) {
    MLKIT_EN("mlkit", "English - ML Kit (fast, offline)"),
    TESS_BEN_EN("tess", "Bengali + English - Tesseract (offline)");

    companion object {
        fun byId(id: String): OcrEngineOption? = entries.firstOrNull { it.id == id }
    }
}

/** Serialises OCR line boxes to JSON for storage, the on-image text overlay
 *  and the searchable PDF layer. Hand-rolled so it works both on-device and
 *  on the JVM (unit tests).
 *
 *  Format v2 keeps the reference bitmap size so box pixels can be normalized
 *  to any displayed size:  {"v":2,"bw":W,"bh":H,"lines":[...]}.
 *  Legacy v1 (bare array) is still decoded; its boxes have unknown reference
 *  size (bw = bh = -1) and cannot be drawn on the image.
 */
object OcrLinesCodec {

    private val ENTRY = Regex(
        "\\{\"t\":\"((?:[^\"\\\\]|\\\\.)*)\"," +
            "\"x\":(-?\\d+),\"y\":(-?\\d+),\"w\":(-?\\d+),\"h\":(-?\\d+)\\}",
    )
    private val HEADER = Regex("\\{\"v\":2,\"bw\":(-?\\d+),\"bh\":(-?\\d+),\"lines\":\\[")

    /** Decoded OCR payload: reference bitmap size + recognized lines. */
    data class OcrLinesDoc(val bmpW: Int, val bmpH: Int, val lines: List<OcrLine>) {
        /** Line boxes normalized to [0..1]; null when reference size is unknown. */
        fun normalizedLines(): List<Pair<OcrLine, FRect>>? {
            if (bmpW <= 0 || bmpH <= 0) return null
            return lines.mapNotNull { line ->
                val b = line.box ?: return@mapNotNull null
                line to FRect(
                    b.left / bmpW.toFloat(),
                    b.top / bmpH.toFloat(),
                    b.width / bmpW.toFloat(),
                    b.height / bmpH.toFloat(),
                )
            }
        }
    }

    data class FRect(val x: Float, val y: Float, val w: Float, val h: Float) {
        val right: Float get() = x + w
        val bottom: Float get() = y + h
    }

    fun encode(bmpW: Int, bmpH: Int, lines: List<OcrLine>): String? = try {
        buildString {
            append("{\"v\":2,\"bw\":").append(bmpW).append(",\"bh\":").append(bmpH)
            append(",\"lines\":[")
            lines.forEachIndexed { i, line ->
                if (i > 0) append(',')
                append("{\"t\":\"")
                append(escape(line.text))
                append("\",")
                val b = line.box
                append("\"x\":").append(b?.left ?: -1)
                append(",\"y\":").append(b?.top ?: -1)
                append(",\"w\":").append(b?.width ?: -1)
                append(",\"h\":").append(b?.height ?: -1)
                append('}')
            }
            append("]}")
        }
    } catch (t: Throwable) {
        null
    }

    /** Convenience overload for callers without a known reference size (legacy). */
    fun encodeLines(lines: List<OcrLine>): String? = encode(-1, -1, lines)

    fun decode(json: String?): OcrLinesDoc? {
        if (json.isNullOrBlank()) return null
        val trimmed = json.trim()
        return try {
            val isV2 = trimmed.startsWith("{")
            val lines = parseEntries(trimmed) ?: return null
            if (isV2) {
                val header = HEADER.find(trimmed)
                val bw = header?.groupValues?.get(1)?.toInt() ?: -1
                val bh = header?.groupValues?.get(2)?.toInt() ?: -1
                OcrLinesDoc(bw, bh, lines)
            } else {
                OcrLinesDoc(-1, -1, lines)
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun parseEntries(text: String): List<OcrLine>? {
        val out = mutableListOf<OcrLine>()
        for (m in ENTRY.findAll(text)) {
            val lineText = unescape(m.groupValues[1])
            if (lineText.isBlank()) continue
            val x = m.groupValues[2].toInt()
            val y = m.groupValues[3].toInt()
            val w = m.groupValues[4].toInt()
            val h = m.groupValues[5].toInt()
            val box = if (x >= 0 && y >= 0 && w > 0 && h > 0) IRect(x, y, x + w, y + h) else null
            out.add(OcrLine(lineText, box))
        }
        return if (out.isEmpty() && !text.contains("\"t\"")) null else out
    }

    private fun escape(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }

    private fun unescape(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    '\\' -> append('\\')
                    '"' -> append('"')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    else -> append(n)
                }
                i += 2
            } else {
                append(c)
                i += 1
            }
        }
    }
}
