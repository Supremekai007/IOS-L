package app.ioshome.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** The dock: a static translucent rounded bar (no blur, so it is cheap) holding the favourite apps. */
class DockView(context: Context) : LinearLayout(context) {

    private val bgDrawable = GradientDrawable()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        this.setBackground(bgDrawable)
        applyStyle(30, 28, true)
    }

    /** [opacityPct] 0..100, [radiusDp] corner radius, [dark] picks a dark or light tint. */
    fun applyStyle(opacityPct: Int, radiusDp: Int, dark: Boolean) {
        val alpha = (opacityPct.coerceIn(0, 100) * 255) / 100
        if (dark) {
            bgDrawable.setColor(Color.argb(alpha, 28, 28, 34))
            bgDrawable.setStroke(context.dp(1), Color.argb((alpha / 3).coerceAtMost(90), 255, 255, 255))
        } else {
            bgDrawable.setColor(Color.argb(alpha, 255, 255, 255))
            bgDrawable.setStroke(context.dp(1), Color.argb((alpha / 3).coerceAtMost(90), 255, 255, 255))
        }
        bgDrawable.cornerRadius = context.dpf(radiusDp.toFloat())
        invalidate()
    }

    fun bind(
        entries: List<AppEntry>,
        slotWidth: Int,
        canonicalPx: Int,
        displayPx: Int,
        icons: IconCache,
        animatePress: Boolean,
        editMode: Boolean,
        popKey: String?,
        onClick: (AppEntry, View) -> Unit,
        onLongClick: (AppEntry, View) -> Unit,
        onBadge: (AppEntry) -> Unit
    ) {
        removeAllViews()
        for (entry in entries) {
            val v = AppIconView(context)
            v.bind(entry, canonicalPx, displayPx, false, 11f, icons, animatePress)
            v.setOnClickListener { onClick(entry, v) }
            v.setOnLongClickListener {
                onLongClick(entry, v)
                true
            }
            v.onBadge = { onBadge(entry) }
            v.setEditMode(editMode, animatePress)
            if (animatePress && entry.key == popKey) v.playPop()
            addView(v, LinearLayout.LayoutParams(slotWidth, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    fun setEditMode(on: Boolean, animate: Boolean) {
        for (i in 0 until childCount) {
            (getChildAt(i) as? AppIconView)?.setEditMode(on, animate)
        }
    }
}
