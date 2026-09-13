package io.github.holego.checkprint.escpos

import java.nio.charset.Charset

/**
 * Code pages the printer can be switched to with `ESC t n`. The `escT` value is the Epson
 * numbering, which most ESC/POS clones follow; the UI lets the user override the number for
 * printers that use their own table (e.g. some Xprinter/Rongta firmwares use 73 for CP1251).
 */
enum class CodePage(val label: String, val escT: Int, val charsetName: String) {
    CP437("CP437 (Latin)", 0, "IBM437"),
    CP866("CP866 (Cyrillic)", 17, "IBM866"),
    CP1251("CP1251 (Cyrillic)", 46, "windows-1251"),
    CP1252("CP1252 (Western)", 16, "windows-1252"),
    CP852("CP852 (Central Europe)", 18, "IBM852"),
    CP858("CP858 (Latin + €)", 19, "IBM00858"),
    UTF8("UTF-8 (no ESC t)", -1, "UTF-8"),
}

object TextEncoder {
    fun encode(text: String, codePage: CodePage): ByteArray {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val charset = runCatching { Charset.forName(codePage.charsetName) }.getOrNull()
        if (charset != null) return normalized.toByteArray(charset)
        // The JVM on this device lacks the charset; fall back to hand-rolled tables for the two
        // Cyrillic pages and plain ASCII for the rest.
        return when (codePage) {
            CodePage.CP866 -> map(normalized, ::cp866)
            CodePage.CP1251 -> map(normalized, ::cp1251)
            else -> map(normalized) { if (it.code < 0x80) it.code else '?'.code }
        }
    }

    private inline fun map(s: String, f: (Char) -> Int) = ByteArray(s.length) { f(s[it]).toByte() }

    private fun cp866(c: Char): Int = when {
        c.code < 0x80 -> c.code
        c in 'А'..'Я' -> 0x80 + (c - 'А')   // А..Я
        c in 'а'..'п' -> 0xA0 + (c - 'а')   // а..п
        c in 'р'..'я' -> 0xE0 + (c - 'р')   // р..я
        c == 'Ё' -> 0xF0                              // Ё
        c == 'ё' -> 0xF1                              // ё
        c == '№' -> 0xFC                              // №
        c == '°' -> 0xF8                              // °
        else -> '?'.code
    }

    private fun cp1251(c: Char): Int = when {
        c.code < 0x80 -> c.code
        c in 'А'..'я' -> 0xC0 + (c - 'А')   // А..я
        c == 'Ё' -> 0xA8                              // Ё
        c == 'ё' -> 0xB8                              // ё
        c == '№' -> 0xB9                              // №
        c == '°' -> 0xB0                              // °
        else -> '?'.code
    }
}
