package app.ioshome.launcher

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewGroup

/** One home-screen page: lays children out row-major in a fixed columns x rows grid of equal cells. */
class HomeGridView(context: Context) : ViewGroup(context) {

    var columns: Int = 4
    var rows: Int = 6

    /** Which home page this view shows, and a fingerprint of what it was built from (for view reuse). */
    var pageIndex: Int = -1
    var signature: String = ""

    /** Called for a double tap on empty space. */
    var doubleTapListener: (() -> Unit)? = null

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            doubleTapListener?.invoke()
            return true
        }
    })

    fun cellWidth(): Float = (width - paddingLeft - paddingRight) / columns.coerceAtLeast(1).toFloat()

    fun cellHeight(): Float = (height - paddingTop - paddingBottom) / rows.coerceAtLeast(1).toFloat()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val cellW = (width - paddingLeft - paddingRight) / columns.coerceAtLeast(1)
        val cellH = (height - paddingTop - paddingBottom) / rows.coerceAtLeast(1)
        val ws = MeasureSpec.makeMeasureSpec(cellW.coerceAtLeast(0), MeasureSpec.EXACTLY)
        val hs = MeasureSpec.makeMeasureSpec(cellH.coerceAtLeast(0), MeasureSpec.EXACTLY)
        for (i in 0 until childCount) {
            getChildAt(i).measure(ws, hs)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cols = columns.coerceAtLeast(1)
        val cellW = (r - l - paddingLeft - paddingRight) / cols
        val cellH = (b - t - paddingTop - paddingBottom) / rows.coerceAtLeast(1)
        for (i in 0 until childCount) {
            val col = i % cols
            val row = i / cols
            val x = paddingLeft + col * cellW
            val y = paddingTop + row * cellH
            getChildAt(i).layout(x, y, x + cellW, y + cellH)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }
}
