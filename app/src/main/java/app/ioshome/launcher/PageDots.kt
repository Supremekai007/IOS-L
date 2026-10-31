package app.ioshome.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.abs

/** iOS-style page indicator. [position] is fractional so the dots follow the swipe. */
class PageDots(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val radius = context.dpf(3.5f)
    private val gap = context.dpf(10f)

    var count: Int = 1
        set(value) {
            field = value.coerceAtLeast(1)
            requestLayout()
            invalidate()
        }

    var position: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    init {
        paint.color = Color.WHITE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (count * 2 * radius + (count - 1) * gap).toInt() + context.dp(16)
        val h = (2 * radius).toInt() + context.dp(12)
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val total = count * 2 * radius + (count - 1) * gap
        var x = (width - total) / 2f + radius
        val cy = height / 2f
        for (i in 0 until count) {
            val closeness = 1f - minOf(1f, abs(i - position))
            paint.alpha = (90 + 165 * closeness).toInt()
            canvas.drawCircle(x, cy, radius, paint)
            x += 2 * radius + gap
        }
    }
}
