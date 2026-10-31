package app.ioshome.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/** Full-screen overlay that shows one open folder: an editable name and a grid of its apps. */
class FolderView(context: Context) : FrameLayout(context) {

    var onLaunch: ((AppEntry, View) -> Unit)? = null
    var onItemMenu: ((AppEntry, View, String) -> Unit)? = null
    var onRename: ((String, String) -> Unit)? = null

    var openId: String? = null
        private set

    private val panel = LinearLayout(context)
    private val title = EditText(context)
    private val scroll = ScrollView(context)
    private val grid = HomeGridView(context)

    init {
        visibility = View.GONE
        setBackgroundColor(Color.argb(120, 0, 0, 0))
        isClickable = true
        setOnClickListener { hide(true) }

        panel.orientation = LinearLayout.VERTICAL
        panel.isClickable = true
        val bg = GradientDrawable()
        bg.setColor(Color.argb(205, 66, 66, 78))
        bg.cornerRadius = context.dpf(30f)
        panel.background = bg
        panel.setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(16))

        title.setTextColor(Color.WHITE)
        title.setHintTextColor(Color.argb(150, 255, 255, 255))
        title.hint = "Folder"
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        title.gravity = Gravity.CENTER
        title.background = null
        title.isSingleLine = true
        title.imeOptions = EditorInfo.IME_ACTION_DONE
        title.setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(12))
        title.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitName()
                hideKeyboard()
                true
            } else {
                false
            }
        }
        panel.addView(
            title,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        scroll.addView(
            grid,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, context.dp(100))
        )
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(100))
        )

        val panelParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        panelParams.leftMargin = context.dp(24)
        panelParams.rightMargin = context.dp(24)
        addView(panel, panelParams)
    }

    /** Shows (or refreshes, if [animate] is false and it is already open) the folder with the given apps. */
    fun show(id: String, name: String, entries: List<AppEntry>, iconPx: Int, animate: Boolean, animatePress: Boolean) {
        val wasOpen = openId == id
        openId = id
        if (!wasOpen || !title.hasFocus()) title.setText(name)

        grid.removeAllViews()
        val cols = 3
        grid.columns = cols
        val rows = ((entries.size + cols - 1) / cols).coerceAtLeast(1)
        grid.rows = rows
        val cellH = iconPx + context.dp(48)
        val gridH = rows * cellH
        val maxH = (resources.displayMetrics.heightPixels * 0.5f).toInt()

        val gridParams = grid.layoutParams as FrameLayout.LayoutParams
        gridParams.height = gridH
        grid.layoutParams = gridParams
        val scrollParams = scroll.layoutParams as LinearLayout.LayoutParams
        scrollParams.height = minOf(gridH, maxH)
        scroll.layoutParams = scrollParams

        for (entry in entries) {
            val v = AppIconView(context)
            v.bind(entry, iconPx, true, (context.applicationContext as LauncherApp).icons, animatePress)
            v.setOnClickListener { onLaunch?.invoke(entry, v) }
            v.setOnLongClickListener {
                onItemMenu?.invoke(entry, v, id)
                true
            }
            grid.addView(v)
        }

        panel.animate().cancel()
        this.animate().cancel()
        visibility = View.VISIBLE
        if (!wasOpen && animate) {
            panel.scaleX = 0.7f
            panel.scaleY = 0.7f
            panel.alpha = 0f
            panel.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180L).start()
            this.alpha = 0f
            this.animate().alpha(1f).setStartDelay(0L).setDuration(160L).start()
        } else {
            panel.scaleX = 1f
            panel.scaleY = 1f
            panel.alpha = 1f
            this.alpha = 1f
        }
    }

    fun hide(animate: Boolean) {
        val id = openId ?: return
        val name = title.text.toString()
        openId = null
        hideKeyboard()
        onRename?.invoke(id, name)
        panel.animate().cancel()
        this.animate().cancel()
        if (animate) {
            panel.animate().scaleX(0.8f).scaleY(0.8f).setDuration(140L).start()
            this.animate().alpha(0f).setStartDelay(0L).setDuration(140L).withEndAction {
                visibility = View.GONE
                this.alpha = 1f
                panel.scaleX = 1f
                panel.scaleY = 1f
                panel.alpha = 1f
            }.start()
        } else {
            visibility = View.GONE
            this.alpha = 1f
        }
    }

    private fun commitName() {
        val id = openId ?: return
        onRename?.invoke(id, title.text.toString())
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(title.windowToken, 0)
        title.clearFocus()
    }
}
