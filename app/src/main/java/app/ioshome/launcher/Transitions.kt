package app.ioshome.launcher

import android.content.Context
import android.view.View
import android.view.animation.Interpolator
import android.widget.Scroller
import androidx.viewpager.widget.ViewPager
import kotlin.math.abs

/** The seven selectable page transition styles. Used by the real pager and by the settings preview. */
object Transitions {
    const val SLIDE = 0
    const val CUBE = 1
    const val PARALLAX = 2
    const val ZOOM = 3
    const val FADE = 4
    const val ROTATION = 5
    const val DEPTH = 6

    val NAMES = arrayOf("Classic Slide", "3D Cube", "Parallax", "Zoom", "Fade", "Rotation", "Depth")

    /** Depth needs the outgoing page drawn on top of the incoming one. */
    fun needsReverseOrder(style: Int): Boolean = style == DEPTH

    fun reset(page: View) {
        page.scaleX = 1f
        page.scaleY = 1f
        page.alpha = 1f
        page.rotation = 0f
        page.rotationY = 0f
        page.translationX = 0f
        page.pivotX = page.width / 2f
        page.pivotY = page.height / 2f
        if (page.visibility == View.INVISIBLE) page.visibility = View.VISIBLE
    }

    /**
     * [position] is 0 for the page in the centre, -1 one page to the left, +1 one page to the right.
     * [k] is the intensity from about 0.3 (subtle) to 1.0 (strong).
     */
    fun apply(style: Int, page: View, position: Float, k: Float) {
        reset(page)
        val w = page.width.toFloat()
        val h = page.height.toFloat()
        val a = abs(position)
        when (style) {
            CUBE -> if (a < 1f) {
                page.cameraDistance = page.resources.displayMetrics.density * 9000f
                page.pivotX = if (position < 0f) w else 0f
                page.pivotY = h * 0.5f
                page.rotationY = 90f * position * k
            }
            PARALLAX -> if (position < 0f && position > -1f) {
                page.translationX = -position * w * 0.55f * k
                page.alpha = 1f - 0.35f * a * k
            }
            ZOOM -> if (a < 1f) {
                val scale = 1f - 0.35f * k * a
                page.scaleX = scale
                page.scaleY = scale
                page.alpha = 1f - 0.6f * k * a
            }
            FADE -> {
                page.translationX = -position * w
                page.alpha = (1f - a).coerceIn(0f, 1f)
                if (a >= 1f) page.visibility = View.INVISIBLE
            }
            ROTATION -> if (a < 1f) {
                page.pivotX = w * 0.5f
                page.pivotY = h
                page.rotation = -position * 22f * k
                page.alpha = 1f - 0.5f * a * k
            }
            DEPTH -> {
                if (position > 0f && position < 1f) {
                    page.translationX = -position * w
                    val scale = (1f - 0.4f * k) + 0.4f * k * (1f - position)
                    page.scaleX = scale
                    page.scaleY = scale
                    page.alpha = 1f - position
                } else if (position >= 1f) {
                    page.visibility = View.INVISIBLE
                }
            }
            else -> Unit
        }
    }

    fun transformer(style: Int, intensity: Float, enabled: () -> Boolean): ViewPager.PageTransformer {
        return ViewPager.PageTransformer { page, position ->
            if (enabled()) apply(style, page, position, intensity) else reset(page)
        }
    }

    /** Installs [style] on [pager] and clears any transform left over from the previous style. */
    fun install(pager: ViewPager, style: Int, intensity: Float, enabled: () -> Boolean) {
        pager.setPageTransformer(needsReverseOrder(style), transformer(style, intensity, enabled))
        for (i in 0 until pager.childCount) reset(pager.getChildAt(i))
    }

    /** Replaces ViewPager's internal scroller (reflection on the library class, not a platform hidden API). */
    fun installSpeed(pager: ViewPager, speed: Float) {
        try {
            val field = ViewPager::class.java.getDeclaredField("mScroller")
            field.isAccessible = true
            val current = field.get(pager)
            if (current is PagerScroller) {
                current.speed = speed
            } else {
                field.set(pager, PagerScroller(pager.context, speed))
            }
        } catch (e: Exception) {
            // Speed adjustment is simply unavailable if the field is not found.
        }
    }

    class PagerScroller(context: Context, var speed: Float) : Scroller(context, QuinticInterpolator) {
        override fun startScroll(startX: Int, startY: Int, dx: Int, dy: Int, duration: Int) {
            super.startScroll(startX, startY, dx, dy, (duration / speed).toInt().coerceAtLeast(1))
        }
    }

    object QuinticInterpolator : Interpolator {
        override fun getInterpolation(input: Float): Float {
            val t = input - 1f
            return t * t * t * t * t + 1f
        }
    }
}
