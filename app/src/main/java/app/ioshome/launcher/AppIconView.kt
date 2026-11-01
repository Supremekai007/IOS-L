package app.ioshome.launcher

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Random

/**
 * An app or folder icon with its label, an iOS-style press-scale effect, and an edit mode that makes it
 * jiggle and shows a remove badge. Shows a placeholder at once and swaps in the real icon when ready.
 */
class AppIconView(context: Context) : LinearLayout(context) {

    private val iconView = ImageView(context)
    private val labelView = TextView(context)
    private var boundKey: String? = null
    private var animated = true

    /** The model key this view represents (app key or folder id). */
    var itemKey: String? = null

    private var editing = false
    private var jiggle: ObjectAnimator? = null
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeMark = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeRadius = context.dpf(10f)
    private val badgeHit = RectF()

    /** Called when the remove badge is tapped in edit mode. */
    var onBadge: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER

        iconView.scaleType = ImageView.ScaleType.FIT_CENTER

        labelView.setTextColor(Color.WHITE)
        labelView.maxLines = 1
        labelView.ellipsize = TextUtils.TruncateAt.END
        labelView.gravity = Gravity.CENTER
        labelView.setShadowLayer(context.dpf(2f), 0f, context.dpf(1f), Color.argb(160, 0, 0, 0))

        addView(iconView, LinearLayout.LayoutParams(0, 0))
        val labelParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        labelParams.topMargin = context.dp(5)
        addView(labelView, labelParams)

        badgeFill.color = Color.argb(235, 120, 120, 128)
        badgeMark.color = Color.WHITE
    }

    private fun prepare(displayPx: Int, showLabel: Boolean, labelSp: Float, text: String) {
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSp)
        labelView.text = text
        labelView.visibility = if (showLabel) View.VISIBLE else View.GONE
        val params = iconView.layoutParams as LinearLayout.LayoutParams
        params.width = displayPx
        params.height = displayPx
        iconView.layoutParams = params
        Springs.cancelAll(this)
        scaleX = 1f
        scaleY = 1f
        translationX = 0f
        translationY = 0f
        iconView.animate().cancel()
        iconView.alpha = 1f
    }

    private fun swapIn(bmp: android.graphics.Bitmap, fade: Boolean) {
        iconView.setImageBitmap(bmp)
        if (fade) {
            iconView.alpha = 0.35f
            iconView.animate().alpha(1f).setStartDelay(0L).setDuration(140L)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    /**
     * [canonicalPx] is the size the icon bitmap is cached at; [displayPx] is the size the view shows it at
     * (equal or a little smaller, so scaling is always a mild reduction).
     */
    fun bind(
        entry: AppEntry,
        canonicalPx: Int,
        displayPx: Int,
        showLabel: Boolean,
        labelSp: Float,
        icons: IconCache,
        animatePress: Boolean
    ) {
        animated = animatePress
        boundKey = entry.key
        itemKey = entry.key
        contentDescription = entry.label
        prepare(displayPx, showLabel, labelSp, entry.label)

        val cached = icons.peek(entry, canonicalPx)
        if (cached != null) {
            iconView.setImageBitmap(cached)
        } else {
            iconView.setImageBitmap(icons.placeholder(canonicalPx))
            icons.request(entry, canonicalPx, 1) { bmp ->
                if (boundKey == entry.key) swapIn(bmp, animatePress)
            }
        }
    }

    fun bindFolder(
        folderKey: String,
        name: String,
        entries: List<AppEntry>,
        canonicalPx: Int,
        displayPx: Int,
        showLabel: Boolean,
        labelSp: Float,
        icons: IconCache,
        animatePress: Boolean
    ) {
        animated = animatePress
        val cacheKey = folderKey + "|" + entries.joinToString(",") { it.key }
        boundKey = cacheKey
        itemKey = folderKey
        contentDescription = "Folder " + name
        prepare(displayPx, showLabel, labelSp, name)

        val cached = icons.peekFolder(cacheKey, canonicalPx)
        if (cached != null) {
            iconView.setImageBitmap(cached)
        } else {
            iconView.setImageBitmap(icons.placeholder(canonicalPx))
            icons.loadFolder(cacheKey, entries, canonicalPx) { bmp ->
                if (boundKey == cacheKey) swapIn(bmp, animatePress)
            }
        }
    }

    /** Bounds of the icon image inside this view (used to draw the lifted drag shadow). */
    fun iconBounds(): RectF {
        return RectF(iconView.left.toFloat(), iconView.top.toFloat(), iconView.right.toFloat(), iconView.bottom.toFloat())
    }

    /** Grows the icon gradually while another icon lingers over it (0 = normal, 1 = folder-ready). */
    fun setDwellProgress(fraction: Float) {
        val scale = 1f + 0.18f * fraction.coerceIn(0f, 1f)
        scaleX = scale
        scaleY = scale
    }

    /** Springs the icon back to normal size after a dwell was abandoned. */
    fun releaseDwell() {
        Springs.scaleTo(this, 1f)
    }

    /** Short spring settle for an icon that was just dropped into place. */
    fun playPop() {
        Springs.settle(this, 1.28f)
    }

    fun setEditMode(on: Boolean, animate: Boolean) {
        editing = on
        jiggle?.cancel()
        jiggle = null
        rotation = 0f
        if (on && animate) {
            val a = ObjectAnimator.ofFloat(this, View.ROTATION, -1.8f, 1.8f)
            a.duration = 110L + Random().nextInt(40)
            a.repeatCount = ValueAnimator.INFINITE
            a.repeatMode = ValueAnimator.REVERSE
            a.start()
            jiggle = a
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        jiggle?.cancel()
        jiggle = null
        super.onDetachedFromWindow()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!editing) return
        val cx = iconView.left + badgeRadius
        val cy = iconView.top + badgeRadius
        canvas.drawCircle(cx, cy, badgeRadius, badgeFill)
        canvas.drawRect(
            cx - badgeRadius * 0.5f,
            cy - badgeRadius * 0.11f,
            cx + badgeRadius * 0.5f,
            cy + badgeRadius * 0.11f,
            badgeMark
        )
        badgeHit.set(cx - badgeRadius * 1.5f, cy - badgeRadius * 1.5f, cx + badgeRadius * 1.5f, cy + badgeRadius * 1.5f)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (editing && ev.action == MotionEvent.ACTION_UP && badgeHit.contains(ev.x, ev.y)) {
            onBadge?.invoke()
        }
        return super.onTouchEvent(ev)
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        val target = if (pressed) 0.9f else 1f
        if (animated) {
            animate().scaleX(target).scaleY(target).setStartDelay(0L).setDuration(110L)
                .setInterpolator(DecelerateInterpolator()).start()
        } else {
            scaleX = target
            scaleY = target
        }
    }
}
