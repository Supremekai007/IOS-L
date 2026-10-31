package app.ioshome.launcher

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
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

    private fun keyOf(entry: AppEntry, size: Int): String = entry.key + "@" + size

    fun peek(entry: AppEntry, size: Int): Bitmap? = cache.get(keyOf(entry, size))

    fun peekFolder(cacheKey: String, size: Int): Bitmap? = cache.get(cacheKey + "@" + size)

    /** Renders a 3x3 preview of the folder's apps on a translucent rounded square, off the main thread. */
    fun loadFolder(cacheKey: String, entries: List<AppEntry>, size: Int, callback: (Bitmap) -> Unit) {
        worker.execute {
            val k = cacheKey + "@" + size
            val cached = cache.get(k)
            val result: Bitmap = cached ?: run {
                val rendered = renderFolder(entries, size)
                cache.put(k, rendered)
                rendered
            }
            main.post { callback(result) }
        }
    }

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
        return roundCorners(bmp)
    }

    private fun renderFolder(entries: List<AppEntry>, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.argb(110, 235, 235, 245))
        val pad = size * 0.10f
        val cell = (size - 2 * pad) / 3f
        val mini = (cell * 0.84f).toInt().coerceAtLeast(4)
        var index = 0
        for (entry in entries) {
            if (index >= 9) break
            val key = keyOf(entry, mini)
            var icon: Bitmap? = cache.get(key)
            if (icon == null) {
                icon = try {
                    render(entry, mini)
                } catch (t: Throwable) {
                    null
                }
                if (icon != null) cache.put(key, icon)
            }
            if (icon != null) {
                val left = pad + (index % 3) * cell + (cell - mini) / 2f
                val top = pad + (index / 3) * cell + (cell - mini) / 2f
                canvas.drawBitmap(icon, left, top, null)
            }
            index++
        }
        return roundCorners(bmp)
    }

    private fun fallback(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(200, 200, 206))
        return roundCorners(bmp)
    }

    /**
     * Returns a new bitmap showing [src] clipped to an anti-aliased rounded square (iOS corner radius,
     * about 22% of the width). Drawing the source through a BitmapShader is what actually removes the
     * corners; an alpha-mask xfermode on drawRoundRect only affects pixels inside the shape.
     */
    private fun roundCorners(src: Bitmap): Bitmap {
        val size = src.width
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val radius = size * 0.2237f
        Canvas(out).drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, paint)
        return out
    }

    private companion object {
        fun maxBytes(): Int {
            val budget = (Runtime.getRuntime().maxMemory() / 16L).toInt()
            return budget.coerceIn(4 * 1024 * 1024, 24 * 1024 * 1024)
        }
    }
}
