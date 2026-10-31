package app.ioshome.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** The dock: a static translucent rounded bar (no blur, so it is cheap) holding the favourite apps. */
class DockView(context: Context) : LinearLayout(context) {

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.setColor(Color.argb(77, 255, 255, 255))
        bg.cornerRadius = context.dpf(28f)
        bg.setStroke(context.dp(1), Color.argb(40, 255, 255, 255))
        background = bg
    }

    fun bind(
        entries: List<AppEntry>,
        slotWidth: Int,
        iconPx: Int,
        icons: IconCache,
        animatePress: Boolean,
        onClick: (AppEntry, View) -> Unit,
        onLongClick: (AppEntry, View) -> Unit
    ) {
        removeAllViews()
        for (entry in entries) {
            val v = AppIconView(context)
            v.bind(entry, iconPx, false, icons, animatePress)
            v.setOnClickListener { onClick(entry, v) }
            v.setOnLongClickListener {
                onLongClick(entry, v)
                true
            }
            addView(v, LinearLayout.LayoutParams(slotWidth, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }
}
