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

/** Serialises OCR line boxes to JSON for storage and the searchable PDF layer.
 *  Hand-rolled so it works both on-device and on the JVM (unit tests). */
object OcrLinesCodec {

    private val ENTRY = Regex(
        "\\{\"t\":\"((?:[^\"\\\\]|\\\\.)*)\"," +
            "\"x\":(-?\\d+),\"y\":(-?\\d+),\"w\":(-?\\d+),\"h\":(-?\\d+)\\}",
    )

    fun encode(lines: List<OcrLine>): String? = try {
        buildString {
            append('[')
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
            append(']')
        }
    } catch (t: Throwable) {
        null
    }

    fun decode(json: String?): List<OcrLine>? {
        if (json.isNullOrBlank()) return null
        val trimmed = json.trim()
        if (!trimmed.startsWith("[")) return null
        return try {
            val out = mutableListOf<OcrLine>()
            for (m in ENTRY.findAll(trimmed)) {
                val text = unescape(m.groupValues[1])
                if (text.isBlank()) continue
                val x = m.groupValues[2].toInt()
                val y = m.groupValues[3].toInt()
                val w = m.groupValues[4].toInt()
                val h = m.groupValues[5].toInt()
                val box = if (x >= 0 && y >= 0 && w > 0 && h > 0) IRect(x, y, x + w, y + h) else null
                out.add(OcrLine(text, box))
            }
            if (out.isEmpty() && trimmed != "[]") null else out
        } catch (t: Throwable) {
            null
        }
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
