package app.ioshome.launcher

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** An app icon with its label underneath, with an iOS-style press-scale effect. */
class AppIconView(context: Context) : LinearLayout(context) {

    private val iconView = ImageView(context)
    private val labelView = TextView(context)
    private var boundKey: String? = null
    private var animated = true

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
    }

    fun bind(entry: AppEntry, iconPx: Int, showLabel: Boolean, icons: IconCache, animatePress: Boolean) {
        animated = animatePress
        boundKey = entry.key
        contentDescription = entry.label
        scaleX = 1f
        scaleY = 1f

        labelView.text = entry.label
        labelView.visibility = if (showLabel) View.VISIBLE else View.GONE

        val params = iconView.layoutParams as LinearLayout.LayoutParams
        params.width = iconPx
        params.height = iconPx
        iconView.layoutParams = params

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

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        val target = if (pressed) 0.9f else 1f
        if (animated) {
            animate().scaleX(target).scaleY(target).setDuration(110L).start()
        } else {
            scaleX = target
            scaleY = target
        }
    }
}
