package com.localscanner.app.domain

/**
 * Pure text helpers shared by OCR and PDF features. Kept free of Android
 * dependencies for JVM unit testing.
 */
object TextUtils {

    private val BENGALI_RANGE = 0x0980..0x09FF

    fun containsBengali(text: String): Boolean =
        text.any { it.code in BENGALI_RANGE }

    /** Strips characters that cannot be encoded in PDF standard-14 (WinAnsi) fonts. */
    fun toWinAnsiSafe(text: String): String = text.map { c ->
        if (c.code in 32..255 && c.code !in 127..159) c else ' '
    }.joinToString("")

    /** Normalises OCR noise: trims each line and drops blank lines. */
    fun cleanOcrText(raw: String): String =
        raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()

    /** File-name-safe version of a title. */
    fun sanitizeFileName(title: String): String =
        title.replace(Regex("[^A-Za-z0-9\\-_.\\u0980-\\u09FF ]"), "_")
            .replace(Regex("\\s+"), "_")
            .take(60)
            .trimEnd('_')
            .ifBlank { "scan" }

    /** Concatenates OCR line JSON values for the document-level search index. */
    fun buildSearchIndex(pageTexts: List<String>): String? =
        pageTexts.filter { it.isNotBlank() }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
}
