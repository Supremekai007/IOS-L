package app.ioshome.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Loads, themes and caches app icons.
 *
 * - One canonical bitmap per app (key = component + size + icon configuration); views scale it slightly.
 * - Two low-priority worker threads draw from a priority queue: visible page, then neighbouring pages,
 *   then everything else. Duplicate requests are merged.
 * - Placeholders are returned immediately so no icon slot is ever blank.
 * - Only the entries of a changed package are invalidated; the whole cache is dropped only when the
 *   icon pack, shape or per-app overrides change, or when Android asks for memory.
 */
class IconCache(
    private val context: Context,
    private val settings: LauncherSettings,
    private val packs: IconPackManager
) {
    private class Job(val priority: Int, val order: Long, val work: Runnable) : Runnable, Comparable<Job> {
        override fun run() = work.run()
        override fun compareTo(other: Job): Int {
            return if (priority != other.priority) priority.compareTo(other.priority) else order.compareTo(other.order)
        }
    }

    @Volatile
    private var cache: LruCache<String, Bitmap> = newCache()

    @Volatile
    var namespace: String = computeNamespace()
        private set

    private val executor = ThreadPoolExecutor(
        2, 2, 30L, TimeUnit.SECONDS, PriorityBlockingQueue<Runnable>()
    ) { r ->
        val t = Thread(r, "icon-loader")
        t.priority = Thread.NORM_PRIORITY - 1
        t
    }
    private val main = Handler(Looper.getMainLooper())
    private val waiting = HashMap<String, ArrayList<(Bitmap) -> Unit>>()
    private val placeholders = HashMap<Int, Bitmap>()
    private var order = 0L

    private fun newCache(): LruCache<String, Bitmap> {
        val bytes = if (settings.cacheMb > 0) {
            settings.cacheMb * 1024 * 1024
        } else {
            (Runtime.getRuntime().maxMemory() / 12L).toInt().coerceIn(8 * 1024 * 1024, 40 * 1024 * 1024)
        }
        return object : LruCache<String, Bitmap>(bytes) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        }
    }

    private fun computeNamespace(): String {
        return settings.iconPack + "|" + settings.iconShape + "|" + settings.overridesStamp
    }

    private fun keyOf(entry: AppEntry, size: Int): String = entry.key + "@" + size + "#" + namespace

    // ------------------------------------------------------------------ main-thread API

    fun peek(entry: AppEntry, size: Int): Bitmap? = cache.get(keyOf(entry, size))

    /** A rounded translucent square shown while the real icon is still being prepared. */
    fun placeholder(size: Int): Bitmap {
        var bmp = placeholders[size]
        if (bmp == null) {
            bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.argb(60, 255, 255, 255)
            val r = size * CORNER
            canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), r, r, paint)
            placeholders[size] = bmp
        }
        return bmp!!
    }

    /**
     * Delivers the icon to [callback] on the main thread (immediately if cached). [priority]: 0 visible,
     * 1 neighbouring page, 2 background, 3 least urgent.
     */
    fun request(entry: AppEntry, size: Int, priority: Int, callback: ((Bitmap) -> Unit)?) {
        val key = keyOf(entry, size)
        val hit = cache.get(key)
        if (hit != null) {
            callback?.invoke(hit)
            return
        }
        val list = waiting[key]
        if (list != null) {
            if (callback != null) list.add(callback)
            if (priority < 2) submit(entry, size, priority, key)
            return
        }
        val fresh = ArrayList<(Bitmap) -> Unit>()
        if (callback != null) fresh.add(callback)
        waiting[key] = fresh
        submit(entry, size, priority, key)
    }

    fun preload(entries: List<AppEntry>, size: Int, priority: Int) {
        for (entry in entries) {
            if (cache.get(keyOf(entry, size)) == null) request(entry, size, priority, null)
        }
    }

    fun peekFolder(cacheKey: String, size: Int): Bitmap? = cache.get(folderKey(cacheKey, size))

    private fun folderKey(cacheKey: String, size: Int): String = cacheKey + "@" + size + "#" + namespace

    /** Renders a 3x3 preview of the folder's apps on a translucent rounded square. */
    fun loadFolder(cacheKey: String, entries: List<AppEntry>, size: Int, callback: (Bitmap) -> Unit) {
        val key = folderKey(cacheKey, size)
        val hit = cache.get(key)
        if (hit != null) {
            callback(hit)
            return
        }
        val list = waiting[key]
        if (list != null) {
            list.add(callback)
            return
        }
        val fresh = ArrayList<(Bitmap) -> Unit>()
        fresh.add(callback)
        waiting[key] = fresh
        executor.execute(Job(0, order++, Runnable {
            val bmp = try {
                renderFolder(entries, size)
            } catch (t: Throwable) {
                null
            }
            if (bmp != null) cache.put(key, bmp)
            main.post { deliver(key, bmp ?: placeholderFor(size)) }
        }))
    }

    private fun placeholderFor(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.argb(60, 255, 255, 255))
        return roundCorners(bmp)
    }

    private fun submit(entry: AppEntry, size: Int, priority: Int, key: String) {
        executor.execute(Job(priority, order++, Runnable {
            val existing = cache.get(key)
            val bmp: Bitmap
            if (existing != null) {
                bmp = existing
            } else {
                val rendered = try {
                    render(entry, size)
                } catch (t: Throwable) {
                    null
                }
                if (rendered != null) {
                    cache.put(key, rendered)
                    bmp = rendered
                } else {
                    bmp = fallback(size)
                }
            }
            main.post { deliver(key, bmp) }
        }))
    }

    private fun deliver(key: String, bmp: Bitmap) {
        val list = waiting.remove(key) ?: return
        for (callback in list) callback(bmp)
    }

    // ------------------------------------------------------------------ invalidation

    /** Drops only the icons of one package (and folder previews, which may contain it). */
    fun invalidatePackage(pkg: String) {
        val prefix = "$pkg/"
        val current = cache
        for (key in current.snapshot().keys) {
            if (key.startsWith(prefix) || key.startsWith("folder:")) current.remove(key)
        }
    }

    /**
     * Call after the icon pack, shape or per-app overrides changed. Returns true if the configuration
     * really changed (the cache is dropped in that case).
     */
    fun reconfigure(): Boolean {
        val ns = computeNamespace()
        if (ns == namespace) return false
        namespace = ns
        cache.evictAll()
        return true
    }

    /** Drops everything (Settings > Performance > Clear icon cache) and rebuilds with the current size. */
    fun clearAll() {
        cache = newCache()
        placeholders.clear()
    }

    fun trim(level: Int) {
        val current = cache
        when {
            level >= 60 -> current.evictAll()
            level >= 15 -> current.trimToSize(current.maxSize() / 2)
        }
    }

    // ------------------------------------------------------------------ rendering (worker threads)

    private fun render(entry: AppEntry, size: Int): Bitmap? {
        val round = settings.iconShape == 0
        val override = settings.getOverride(entry.key)
        if (override != null) {
            val drawable = packs.loadOverride(override)
            if (drawable != null) return fromDrawable(drawable, size, round)
        }
        val pack = packs.current()
        if (pack != null) {
            val themed = pack.drawableFor(entry.component)
            if (themed != null) return fromDrawable(themed, size, round)
        }
        val base = entry.info.getBadgedIcon(0) ?: return null
        if (pack != null && pack.hasBack()) {
            val composed = pack.compose(base, entry.key, size)
            return if (round) roundCorners(composed) else composed
        }
        return systemIcon(base, size, round)
    }

    private fun fromDrawable(drawable: Drawable, size: Int, round: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bmp))
        return if (round) roundCorners(bmp) else bmp
    }

    private fun systemIcon(drawable: Drawable, size: Int, round: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (!round) {
            // Keep the icon's own shape: adaptive icons are masked by the system, legacy icons are drawn as is.
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            return bmp
        }
        if (drawable is AdaptiveIconDrawable) {
            canvas.drawColor(Color.WHITE)
            // Adaptive layers are 108dp with the visible area being the central 72dp, so 1.5x.
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
            var icon: Bitmap? = cache.get(keyOf(entry, size))
            if (icon == null) {
                icon = try {
                    render(entry, size)
                } catch (t: Throwable) {
                    null
                }
                if (icon != null) cache.put(keyOf(entry, size), icon)
            }
            if (icon != null) {
                val small = scaleDown(icon, mini)
                val left = pad + (index % 3) * cell + (cell - mini) / 2f
                val top = pad + (index / 3) * cell + (cell - mini) / 2f
                canvas.drawBitmap(small, left, top, null)
            }
            index++
        }
        return roundCorners(bmp)
    }

    /** Halves repeatedly before the final filtered scale so large reductions stay sharp. */
    private fun scaleDown(src: Bitmap, target: Int): Bitmap {
        var current = src
        while (current.width / 2 >= target) {
            current = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
        }
        return if (current.width == target) current else Bitmap.createScaledBitmap(current, target, target, true)
    }

    private fun fallback(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.rgb(200, 200, 206))
        return roundCorners(bmp)
    }

    /**
     * Returns a new bitmap showing [src] clipped to an anti-aliased rounded square (iOS corner radius,
     * about 22% of the width). Drawing through a BitmapShader is what actually removes the corners.
     */
    private fun roundCorners(src: Bitmap): Bitmap {
        val size = src.width
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val radius = size * CORNER
        Canvas(out).drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, paint)
        return out
    }

    private companion object {
        const val CORNER = 0.2237f
    }
}
