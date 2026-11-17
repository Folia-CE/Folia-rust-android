package io.github.techuouo520.folia

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.util.LruCache
import io.github.techuouo520.folia.bridge.MonetFilteredMask
import io.github.techuouo520.folia.bridge.MonetMaskFilter
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** SalamPlayer's LyricGlyphRun uses SPText's shared NORMAL BlurMaskFilter. Use the same
 * Skia filter on the isolated alpha mask. Rust retains the result across animation frames. */
internal class MonetGlyphBlur : MonetMaskFilter {
    private val filters = LruCache<Int, BlurMaskFilter>(64)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    @Synchronized
    override fun blur(pixels: ByteArray, width: UInt, height: UInt, radius: Float): MonetFilteredMask? {
        val w = width.toInt()
        val h = height.toInt()
        if (w <= 0 || h <= 0 || w.toLong() * h != pixels.size.toLong()) return null
        val source = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        try {
            val stride = source.rowBytes
            val input = if (stride == w) pixels else ByteArray(stride*h).also { padded ->
                for (y in 0 until h) pixels.copyInto(padded, y*stride, y*w, (y+1)*w)
            }
            source.copyPixelsFromBuffer(ByteBuffer.wrap(input))
            val key = (radius.coerceAtLeast(.01f)*100f).roundToInt()
            paint.maskFilter = filters.get(key) ?: BlurMaskFilter(key/100f, BlurMaskFilter.Blur.NORMAL).also { filters.put(key,it) }
            val offsets = IntArray(2)
            val blurred = source.extractAlpha(paint,offsets)
            try {
                val bytes = ByteArray(blurred.rowBytes * blurred.height)
                blurred.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
                val bw = blurred.width
                val packed = if (blurred.rowBytes == bw) bytes else ByteArray(bw*blurred.height).also {
                    for (y in 0 until blurred.height) bytes.copyInto(it, y*bw, y*blurred.rowBytes, y*blurred.rowBytes+bw)
                }
                return MonetFilteredMask(bw.toUInt(),blurred.height.toUInt(),offsets[0],offsets[1],packed)
            } finally { blurred.recycle() }
        } finally { source.recycle() }
    }
}
