package app.ioshome.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors

/**
 * Renders each app icon once into a rounded-square bitmap (iOS-style mask), caches it in a
 * memory-bounded LRU cache and loads it off the main thread.
 */
class IconCache {

    private val cache = object : LruCache<String, Bitmap>(maxBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "icon-loader") }
    private val main = Handler(Looper.getMainLooper())
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    private fun keyOf(entry: AppEntry, size: Int): String = entry.key + "@" + size

    fun peek(entry: AppEntry, size: Int): Bitmap? = cache.get(keyOf(entry, size))

    /** Always calls [callback] on the main thread, with a placeholder if the real icon cannot be loaded. */
    fun load(entry: AppEntry, size: Int, callback: (Bitmap) -> Unit) {
        worker.execute {
            val k = keyOf(entry, size)
            val cached = cache.get(k)
            val result: Bitmap = cached ?: run {
                val rendered = try {
                    render(entry, size)
                } catch (t: Throwable) {
                    null
                }
                if (rendered != null) {
                    cache.put(k, rendered)
                    rendered
                } else {
                    fallback(size)
                }
            }
            main.post { callback(result) }
        }
    }

    /** Called whenever packages change so updated icons are never served stale. */
    fun clear() {
        cache.evictAll()
    }

    private fun render(entry: AppEntry, size: Int): Bitmap? {
        val drawable = entry.info.getBadgedIcon(0) ?: return null
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (drawable is AdaptiveIconDrawable) {
            canvas.drawColor(Color.WHITE)
            // Adaptive layers are 108dp with the visible area being the central 72dp => 1.5x.
            val pad = (size * 0.25f).toInt()
            val bg = drawable.background
            val fg = drawable.foreground
            if (bg != null) {
                bg.setBounds(-pad, -pad, size + pad, size + pad)
                bg.draw(canvas)
            }
            if (fg != null) {
                fg.setBounds(-pad, -pad, size + pad, size + pad)
                fg.draw(canvas)
            }
        } else {
            canvas.drawColor(Color.rgb(242, 242, 247))
            val inset = (size * 0.14f).toInt()
            drawable.setBounds(inset, inset, size - inset, size - inset)
            drawable.draw(canvas)
        }
        applyRoundedMask(canvas, size)
        return bmp
    }

    private fun fallback(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(200, 200, 206))
        applyRoundedMask(canvas, size)
        return bmp
    }

    private fun applyRoundedMask(canvas: Canvas, size: Int) {
        val radius = size * 0.2237f
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, maskPaint)
    }

    private companion object {
        fun maxBytes(): Int {
            val budget = (Runtime.getRuntime().maxMemory() / 16L).toInt()
            return budget.coerceIn(4 * 1024 * 1024, 24 * 1024 * 1024)
        }
    }
}
