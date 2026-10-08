package com.sonalika.chassisscan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build

/** Bitmap helpers for reading stamped / cast numbers on metal. */
object ImageTools {

    fun rotate(src: Bitmap, deg: Int): Bitmap =
        if (deg % 360 == 0) src
        else Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(deg.toFloat()) }, true)

    /** Characters need to be a reasonable pixel height for OCR: upscale small crops, downscale huge ones. */
    fun sizeForOcr(src: Bitmap): Bitmap {
        val short = minOf(src.width, src.height).toFloat()
        val long = maxOf(src.width, src.height).toFloat()
        var s = 1f
        if (short < MIN_SHORT) s = (MIN_SHORT / short).coerceAtMost(3f)
        if (long * s > MAX_LONG) s = MAX_LONG / long
        if (s in 0.97f..1.03f) return src
        return Bitmap.createScaledBitmap(src, (src.width * s).toInt().coerceAtLeast(1), (src.height * s).toInt().coerceAtLeast(1), true)
    }

    /**
     * Grayscale + contrast stretch. Cast and punched numbers on metal are grey on grey;
     * stretching the middle of the histogram makes the edges of the characters stand out.
     */
    fun enhance(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val lum = IntArray(px.size)
        val hist = IntArray(256)
        for (i in px.indices) {
            val c = px[i]
            val l = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            lum[i] = l; hist[l]++
        }
        val lo = percentile(hist, px.size, 0.02)
        val hi = percentile(hist, px.size, 0.98)
        val range = (hi - lo).coerceAtLeast(1)
        for (i in px.indices) {
            val v = ((lum[i] - lo) * 255 / range).coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun percentile(hist: IntArray, total: Int, p: Double): Int {
        val target = (total * p).toLong()
        var acc = 0L
        for (i in 0..255) { acc += hist[i]; if (acc >= target) return i }
        return 255
    }

    /** Loads a gallery / camera photo as a software bitmap, upright, at most ~2400 px. */
    fun load(ctx: Context, uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val l = maxOf(info.size.width, info.size.height)
                if (l > MAX_LONG) {
                    val s = MAX_LONG / l
                    dec.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_LONG * 1.5f) sample *= 2
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    } catch (_: Exception) { null }

    private const val MIN_SHORT = 420f
    private const val MAX_LONG = 2400f
}
