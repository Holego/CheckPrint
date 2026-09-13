package io.github.holego.checkprint.escpos

import java.io.ByteArrayOutputStream

/** 1-bit image, rows packed MSB-first, 1 = black. Row stride is [widthBytes]. */
class MonoBitmap(val width: Int, val height: Int, val rows: ByteArray) {
    val widthBytes: Int get() = (width + 7) / 8
}

/** The handful of ESC/POS commands the app needs. Every function returns raw bytes to stream. */
object EscPos {
    private const val ESC = 0x1B
    private const val GS = 0x1D
    const val LF = 0x0A

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** ESC @ : reset fonts, alignment and modes. */
    fun init() = bytes(ESC, '@'.code)

    /** ESC t n : select character code page. */
    fun codePage(n: Int) = bytes(ESC, 't'.code, n.coerceIn(0, 255))

    /** ESC a n : 0 = left, 1 = center, 2 = right. */
    fun align(a: Int) = bytes(ESC, 'a'.code, a.coerceIn(0, 2))

    /** ESC E n : emphasized on/off. */
    fun bold(on: Boolean) = bytes(ESC, 'E'.code, if (on) 1 else 0)

    /** ESC - n : underline on/off. */
    fun underline(on: Boolean) = bytes(ESC, '-'.code, if (on) 1 else 0)

    /** GS ! n : width and height multipliers 1..8 (high nibble = width, low nibble = height). */
    fun charSize(width: Int, height: Int): ByteArray {
        val w = width.coerceIn(1, 8) - 1
        val h = height.coerceIn(1, 8) - 1
        return bytes(GS, '!'.code, (w shl 4) or h)
    }

    /** ESC d n : print buffer and feed n lines. */
    fun feed(lines: Int) = bytes(ESC, 'd'.code, lines.coerceIn(0, 255))

    /** GS V 66 0 / GS V 65 0 : feed to the cutter position and cut (partial or full). */
    fun cut(partial: Boolean = true) = bytes(GS, 'V'.code, if (partial) 66 else 65, 0)

    val lineFeed = bytes(LF)

    /**
     * GS v 0 : raster bit image. Printers have small receive buffers, so a tall image is sent as a
     * series of bands; the printer draws them back to back with no gap.
     */
    fun raster(image: MonoBitmap, bandRows: Int = 128): ByteArray {
        val out = ByteArrayOutputStream(image.rows.size + 64)
        val wb = image.widthBytes
        var y = 0
        while (y < image.height) {
            val h = minOf(bandRows, image.height - y)
            out.write(bytes(GS, 'v'.code, '0'.code, 0, wb and 0xFF, (wb shr 8) and 0xFF, h and 0xFF, (h shr 8) and 0xFF))
            out.write(image.rows, y * wb, h * wb)
            y += h
        }
        return out.toByteArray()
    }
}
