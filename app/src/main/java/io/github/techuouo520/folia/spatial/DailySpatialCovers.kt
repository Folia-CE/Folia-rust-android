package io.github.techuouo520.folia.spatial

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.LruCache
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Bounded IO/decode and staging storage; neither Compose nor GL decodes artwork. */
internal class DailySpatialCovers(private val source: (String) -> ByteArray?, private val sharePreviews: Boolean=false) : AutoCloseable {
    companion object {
        const val TILE = 64
        const val SIZE = 768
        const val PREVIEWS = 2048
        const val INITIAL_PREVIEWS = 1024
        const val ATLAS_SIDE = 32
        private val sharedPreviews=object: LruCache<String,Preview>(8*1024*1024) {
            override fun sizeOf(key: String,value: Preview)=value.bitmap.allocationByteCount
        }
        fun trimShared() { sharedPreviews.evictAll() }
        private const val FULL_BYTES = SIZE * SIZE * 4
        private const val STAGING_BYTES = 48 * 1024 * 1024
    }
    class Preview(val bitmap: Bitmap, val color: Int)
    private val main = Handler(Looper.getMainLooper())
    private val workers = (Executors.newFixedThreadPool(4) { task ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "Folia-WallCovers")
    } as ThreadPoolExecutor).apply {
        setKeepAliveTime(10, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
    }
    private val palette = ThreadLocal.withInitial { IntArray(TILE * TILE) }
    private val previews = LruCache<String, Preview>(1536)
    private val pixels = DailySpatialPixels(SIZE, STAGING_BYTES)
    private val pending = mutableSetOf<String>()
    private val pendingFull = mutableSetOf<String>()
    private val failed = mutableMapOf<String, Long>()
    private val revision = AtomicInteger()
    private val warmEpoch = AtomicInteger()
    private val dirtyPosted = AtomicBoolean()
    @Volatile private var closed = false
    @Volatile private var highActive = true
    private var dirty: () -> Unit = {}
    private val notifyDirty = Runnable { dirtyPosted.set(false); if (!closed) dirty() }

    fun setDirty(callback: () -> Unit) { dirty = callback }
    fun contentRevision() = revision.get()
    fun preview(url: String?) = url?.let { previews.get(it) ?: if(sharePreviews) sharedPreviews.get(it) else null }
    fun full(url: String?) = url?.let(pixels::ready)
    fun uploaded(url: String?) { if (url != null) pixels.uploaded(url) }
    fun highPreparation(active: Boolean) = Unit

    fun resumeHigh() = synchronized(pending) { highActive = true }

    fun releaseHigh() = synchronized(pending) {
        highActive = false
        for (task in workers.queue.toTypedArray()) {
            if (task is DecodeJob && task.high && workers.remove(task)) pendingFull.remove(task.url)
        }
        pixels.trim()
    }

    fun retainHigh(wanted: Set<String?>) = synchronized(pending) {
        // Cancel queued work, never recycle a buffer still borrowed by the GL upload.
        for (task in workers.queue.toTypedArray()) {
            if (task is DecodeJob && task.high && task.url !in wanted && workers.remove(task)) {
                pendingFull.remove(task.url)
            }
        }
        pixels.retain(wanted)
    }

    fun unavailable(url: String?): Boolean = synchronized(pending) {
        url != null && failed[url]?.let { SystemClock.uptimeMillis() - it < 30_000 } == true
    }

    fun request(url: String?, high: Boolean) {
        if (url.isNullOrEmpty() || closed) return
        if(!high&&sharePreviews) sharedPreviews.get(url)?.let {previews.put(url,it);return}
        if (if (high) pixels.ready(url) != null else previews.get(url) != null) return
        val work = if (high) pendingFull else pending
        synchronized(pending) {
            // Recheck publication under the admission lock: GL may already borrow it.
            if (closed || (high && !highActive) || (if (high) pixels.ready(url) != null else previews.get(url) != null)) return
            if (url in work || unavailable(url)) return
            if (high && (pixels.readyCount() + pendingFull.size + 1) * FULL_BYTES > STAGING_BYTES) return
            if (high && pixels.promote(url)) { changed(); return }
            if (pending.size + pendingFull.size >= 12) return
            work.add(url)
            try { workers.execute(DecodeJob(url, high)) }
            catch (_: RejectedExecutionException) { work.remove(url) }
        }
    }

    private fun changed() {
        revision.incrementAndGet()
        if (!closed && dirtyPosted.compareAndSet(false, true)) main.post(notifyDirty)
    }

    private inner class DecodeJob(val url: String, val high: Boolean) : Runnable {
        override fun run() {
            var staging: ByteBuffer? = null
            try {
                if (closed || (high && !highActive)) return
                // Reserve pooled upload storage before spending CPU on an HD decode.
                if (high) staging = pixels.acquire() ?: return
                val image = decode(url, if (high) SIZE else TILE) ?: return
                if (high) {
                    val buffer = checkNotNull(staging)
                    try { image.copyPixelsToBuffer(buffer); buffer.flip() } finally { image.recycle() }
                    synchronized(pending) {
                        if (closed || !highActive) return
                        pixels.publish(url, buffer)
                        staging = null
                    }
                } else {
                    val preview = coloredPreview(image)
                    if(sharePreviews) sharedPreviews.put(url,preview)
                    synchronized(pending) {
                        if (!closed) previews.put(url, preview)
                    }
                }
            } finally {
                synchronized(pending) {
                    staging?.let(pixels::release)
                    (if (high) pendingFull else pending).remove(url)
                    if (!highActive) pixels.trim()
                }
                // Failed jobs also invalidate demand so the renderer can leave warm-up.
                changed()
            }
        }
    }

    private fun coloredPreview(image: Bitmap): Preview {
        val scratch = checkNotNull(palette.get())
        image.getPixels(scratch, 0, TILE, 0, 0, TILE, TILE)
        val color = SpatialNative.coverAccent(scratch)
        return Preview(image, if (color != 0) color else image.getPixel(TILE / 2, TILE / 2))
    }

    private fun decode(url: String, size: Int): Bitmap? {
        return try {
            val bytes = source(url)
            if (closed) return null
            val image = bytes?.let { if (Build.VERSION.SDK_INT >= 28) decodeScaled(it, size) else decodeLegacy(it, size) }
            if (image == null) markFailed(url)
            image
        } catch (_: Exception) { markFailed(url); null }
    }

    private fun decodeScaled(bytes: ByteArray, size: Int): Bitmap? {
        val image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val side = minOf(info.size.width, info.size.height)
            val width = maxOf(size, Math.round(info.size.width.toDouble() * size / side).toInt())
            val height = maxOf(size, Math.round(info.size.height.toDouble() * size / side).toInt())
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            decoder.setTargetSize(width, height)
            val left = (width - size) / 2
            val top = (height - size) / 2
            decoder.crop = Rect(left, top, left + size, top + size)
        }
        // The upload ABI is exactly RGBA8 even for high-bit-depth source images.
        if (image.config == Bitmap.Config.ARGB_8888) return image
        return try { image.copy(Bitmap.Config.ARGB_8888, false) } finally { image.recycle() }
    }

    /** Folia also supports API 26/27, where ImageDecoder is unavailable. */
    private fun decodeLegacy(bytes: ByteArray, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        try {
            val image = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val side = minOf(decoded.width, decoded.height)
            val x = (decoded.width - side) / 2
            val y = (decoded.height - side) / 2
            Canvas(image).drawBitmap(decoded, Rect(x, y, x + side, y + side), Rect(0, 0, size, size), Paint(Paint.FILTER_BITMAP_FLAG))
            return image
        } finally { decoded.recycle() }
    }

    private fun markFailed(url: String) = synchronized(pending) {
        if (!closed) failed[url] = SystemClock.uptimeMillis()
    }

    fun warm(items: List<SpatialItem>, limit: Int=INITIAL_PREVIEWS, progress: (Int, Int) -> Unit, ready: () -> Unit) {
        val epoch = warmEpoch.incrementAndGet()
        val urls = items.take(limit.coerceIn(1,INITIAL_PREVIEWS)).map { it.coverUrl }.filter { it.isNotEmpty() }.distinct()
        val cursor = AtomicInteger()
        val done = AtomicInteger()
        val active = AtomicInteger(4)
        progress(0, urls.size)
        repeat(4) { workers.execute {
            try {
                while (!closed && epoch == warmEpoch.get()) {
                    val i = cursor.getAndIncrement()
                    if (i >= urls.size) break
                    if(sharePreviews) sharedPreviews.get(urls[i])?.let {previews.put(urls[i],it)}
                    if (previews.get(urls[i]) == null && !unavailable(urls[i])) {
                        decode(urls[i], TILE)?.let { image ->
                            val preview = coloredPreview(image)
                            if(sharePreviews) sharedPreviews.put(urls[i],preview)
                            synchronized(pending) {
                                if (!closed) { previews.put(urls[i], preview); revision.incrementAndGet() }
                            }
                        }
                    }
                    val n = done.incrementAndGet()
                    if (n % 16 == 0 || n == urls.size) main.post {
                        if (!closed && epoch == warmEpoch.get()) progress(n, urls.size)
                    }
                }
            } finally { if (active.decrementAndGet() == 0) main.post {
                // Missing local artwork is legitimate; preserve placeholder cards.
                if (!closed && epoch == warmEpoch.get()) ready()
            } }
        } }
    }

    fun cancelWarm() { warmEpoch.incrementAndGet() }
    override fun close() {
        closed = true
        cancelWarm()
        workers.shutdownNow()
        main.removeCallbacksAndMessages(null)
        synchronized(pending) {
            pixels.close(); previews.evictAll(); pending.clear(); pendingFull.clear(); failed.clear()
        }
    }
}
