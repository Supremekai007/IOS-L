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
 * jiggle and shows a remove badge.
 */
class AppIconView(context: Context) : LinearLayout(context) {

    private val iconView = ImageView(context)
    private val labelView = TextView(context)
    private var boundKey: String? = null
    private var animated = true

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
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
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

    private fun sizeIcon(iconPx: Int) {
        val params = iconView.layoutParams as LinearLayout.LayoutParams
        params.width = iconPx
        params.height = iconPx
        iconView.layoutParams = params
    }

    fun bind(entry: AppEntry, iconPx: Int, showLabel: Boolean, icons: IconCache, animatePress: Boolean) {
        animated = animatePress
        boundKey = entry.key
        contentDescription = entry.label
        scaleX = 1f
        scaleY = 1f

        labelView.text = entry.label
        labelView.visibility = if (showLabel) View.VISIBLE else View.GONE
        sizeIcon(iconPx)

        val cached = icons.peek(entry, iconPx)
        if (cached != null) {
            iconView.setImageBitmap(cached)
        } else {
            iconView.setImageDrawable(null)
            icons.load(entry, iconPx) { bmp ->
                if (boundKey == entry.key) iconView.setImageBitmap(bmp)
            }
        }
    }

    fun bindFolder(
        folderKey: String,
        name: String,
        entries: List<AppEntry>,
        iconPx: Int,
        showLabel: Boolean,
        icons: IconCache,
        animatePress: Boolean
    ) {
        animated = animatePress
        val cacheKey = folderKey + "|" + entries.joinToString(",") { it.key }
        boundKey = cacheKey
        contentDescription = "Folder " + name
        scaleX = 1f
        scaleY = 1f

        labelView.text = name
        labelView.visibility = if (showLabel) View.VISIBLE else View.GONE
        sizeIcon(iconPx)

        val cached = icons.peekFolder(cacheKey, iconPx)
        if (cached != null) {
            iconView.setImageBitmap(cached)
        } else {
            iconView.setImageDrawable(null)
            icons.loadFolder(cacheKey, entries, iconPx) { bmp ->
                if (boundKey == cacheKey) iconView.setImageBitmap(bmp)
            }
        }
    }

    /** Highlights this icon as the target of a drop that would create or fill a folder. */
    fun setHighlighted(on: Boolean) {
        val target = if (on) 1.18f else 1f
        animate().scaleX(target).scaleY(target).setStartDelay(0L).setDuration(120L)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    /** Short settle animation for an icon that was just dropped into place. */
    fun playPop() {
        scaleX = 1.3f
        scaleY = 1.3f
        alpha = 0.5f
        animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(0L).setDuration(260L)
            .setInterpolator(DecelerateInterpolator()).start()
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
