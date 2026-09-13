package io.github.holego.checkprint.escpos

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.IOException

/** Turns a picked photo into the 1-bit raster a thermal printer can draw. */
object ImageRasterizer {
    private const val MAX_SOURCE_DIM = 2048

    /** Decodes a content Uri to a software bitmap no larger than [MAX_SOURCE_DIM] on either side. */
    fun load(resolver: ContentResolver, uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(resolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val scale = maxOf(w, h).toFloat() / MAX_SOURCE_DIM
                if (scale > 1f) decoder.setTargetSize((w / scale).toInt().coerceAtLeast(1), (h / scale).toInt().coerceAtLeast(1))
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IOException("cannot open $uri")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SOURCE_DIM) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("cannot decode $uri")
    }

    /**
     * Scales to [targetWidth] dots keeping the aspect ratio, flattens transparency onto white and
     * converts to black/white, with Floyd-Steinberg error diffusion when [dither] is on.
     */
    fun toMono(src: Bitmap, targetWidth: Int, dither: Boolean, threshold: Int): MonoBitmap {
        val w = targetWidth.coerceIn(8, 2048)
        val h = maxOf(1, (src.height.toLong() * w / src.width).toInt())
        val scaled = if (src.width == w && src.height == h) src else Bitmap.createScaledBitmap(src, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== src) scaled.recycle()

        val gray = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = ((p ushr 24) and 0xFF) / 255f
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val lum = 0.299f * r + 0.587f * g + 0.114f * b
            gray[i] = lum * a + 255f * (1f - a)
        }

        val wb = (w + 7) / 8
        val rows = ByteArray(wb * h)
        val t = threshold.coerceIn(1, 254).toFloat()
        for (y in 0 until h) {
            val rowBase = y * w
            for (x in 0 until w) {
                val i = rowBase + x
                val old = gray[i]
                val black = old < t
                if (black) {
                    val idx = y * wb + (x shr 3)
                    rows[idx] = (rows[idx].toInt() or (0x80 ushr (x and 7))).toByte()
                }
                if (dither) {
                    val err = old - (if (black) 0f else 255f)
                    if (x + 1 < w) gray[i + 1] += err * 7f / 16f
                    if (y + 1 < h) {
                        if (x > 0) gray[i + w - 1] += err * 3f / 16f
                        gray[i + w] += err * 5f / 16f
                        if (x + 1 < w) gray[i + w + 1] += err * 1f / 16f
                    }
                }
            }
        }
        return MonoBitmap(w, h, rows)
    }

    /** Expands the 1-bit raster back into an ARGB bitmap for the on-screen preview. */
    fun toPreview(mono: MonoBitmap): Bitmap {
        val w = mono.width
        val h = mono.height
        val wb = mono.widthBytes
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val set = (mono.rows[y * wb + (x shr 3)].toInt() and (0x80 ushr (x and 7))) != 0
                px[y * w + x] = if (set) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}
