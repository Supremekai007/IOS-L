package app.ioshome.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The widgets page (like the iOS Today view): a vertical stack of real Android app widgets hosted with
 * AppWidgetHost, plus an "Add widget" button. Widget hosting itself lives in [LauncherActivity].
 */
class WidgetsPageView(context: Context, private val host: LauncherActivity) : ScrollView(context) {

    private val column = LinearLayout(context)
    private var editMode = false
    private val removeButtons = ArrayList<View>()

    init {
        isFillViewport = true
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(context.dp(16), context.dp(40), context.dp(16), context.dp(160))
        addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }

    fun setEditMode(on: Boolean) {
        editMode = on
        for (b in removeButtons) b.visibility = if (on) View.VISIBLE else View.GONE
    }

    /** Rebuilds the stack from the saved widget ids. */
    fun refresh() {
        column.removeAllViews()
        removeButtons.clear()

        val title = TextView(context)
        title.text = "Widgets"
        title.setTextColor(Color.WHITE)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        title.setShadowLayer(context.dpf(3f), 0f, context.dpf(1f), Color.argb(150, 0, 0, 0))
        column.addView(title, spaced(12))

        for (id in host.currentWidgetIds()) {
            val widget = host.createWidgetView(id) ?: continue
            column.addView(card(id, widget), spaced(12))
        }

        val add = TextView(context)
        add.text = "+  Add widget"
        add.setTextColor(Color.WHITE)
        add.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        add.gravity = Gravity.CENTER
        add.setPadding(context.dp(20), context.dp(16), context.dp(20), context.dp(16))
        val bg = GradientDrawable()
        bg.setColor(Color.argb(70, 255, 255, 255))
        bg.cornerRadius = context.dpf(22f)
        add.background = bg
        add.isClickable = true
        add.setOnClickListener { host.showWidgetPicker() }
        column.addView(add, spaced(0))
    }

    private fun spaced(bottomDp: Int): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = context.dp(bottomDp)
        return lp
    }

    private fun card(id: Int, widget: View): FrameLayout {
        val card = FrameLayout(context)
        val bg = GradientDrawable()
        bg.setColor(Color.argb(60, 255, 255, 255))
        bg.cornerRadius = context.dpf(22f)
        card.background = bg
        card.clipToOutline = true
        card.outlineProvider = ViewOutlineProvider.BACKGROUND
        val minHeight = host.widgetMinHeightPx(id).coerceIn(context.dp(110), context.dp(420))
        card.addView(widget, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, minHeight))

        val remove = TextView(context)
        remove.text = "\u2715"
        remove.setTextColor(Color.WHITE)
        remove.gravity = Gravity.CENTER
        remove.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        val rbg = GradientDrawable()
        rbg.setColor(Color.argb(210, 120, 120, 128))
        rbg.shape = GradientDrawable.OVAL
        remove.background = rbg
        remove.visibility = if (editMode) View.VISIBLE else View.GONE
        remove.setOnClickListener { host.removeWidget(id) }
        val size = context.dp(32)
        val lp = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START)
        lp.leftMargin = context.dp(8)
        lp.topMargin = context.dp(8)
        card.addView(remove, lp)
        removeButtons.add(remove)

        // Report the real size to the widget provider so it can pick a matching layout.
        card.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            host.reportWidgetSize(widget, (r - l), (b - t))
        }
        return card
    }
}
