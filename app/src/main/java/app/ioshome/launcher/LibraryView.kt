package app.ioshome.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** App Library page: search field plus an alphabetical list of every launchable app. */
class LibraryView(context: Context, private val app: LauncherApp) : LinearLayout(context) {

    var onLaunch: ((AppEntry, View) -> Unit)? = null
    var onMenu: ((AppEntry, View) -> Unit)? = null

    private class Row(val header: String?, val app: AppEntry?)

    private class RowHolder(val icon: ImageView, val label: TextView) {
        var boundKey: String? = null
    }

    private val search = EditText(context)
    private val clearButton = TextView(context)
    private val listView = ListView(context)
    private val emptyView = TextView(context)
    private val rowAdapter = RowAdapter()
    private var allApps: List<AppEntry> = emptyList()
    private var recentApps: List<AppEntry> = emptyList()
    private var loaded = false
    private var rows: List<Row> = emptyList()
    private val iconPx = context.dp(44)

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.argb(115, 0, 0, 0))
        setPadding(context.dp(16), context.dp(12), context.dp(16), 0)

        val bar = LinearLayout(context)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        val barBg = GradientDrawable()
        barBg.setColor(Color.argb(60, 255, 255, 255))
        barBg.cornerRadius = context.dpf(12f)
        bar.background = barBg
        bar.setPadding(context.dp(12), 0, context.dp(4), 0)

        search.hint = "App Library"
        search.setHintTextColor(Color.argb(170, 255, 255, 255))
        search.setTextColor(Color.WHITE)
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        search.background = null
        search.isSingleLine = true
        search.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        search.imeOptions = EditorInfo.IME_ACTION_SEARCH
        search.setPadding(context.dp(4), context.dp(12), context.dp(4), context.dp(12))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                clearButton.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                refilter()
            }
        })
        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val first = rows.firstOrNull { it.app != null }?.app
                hideKeyboard()
                if (first != null) onLaunch?.invoke(first, search)
                true
            } else {
                false
            }
        }
        bar.addView(search, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        clearButton.text = "\u2715"
        clearButton.contentDescription = "Clear search"
        clearButton.setTextColor(Color.argb(200, 255, 255, 255))
        clearButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        clearButton.setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
        clearButton.visibility = View.GONE
        clearButton.setOnClickListener { search.setText("") }
        bar.addView(
            clearButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        val barParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        barParams.bottomMargin = context.dp(8)
        addView(bar, barParams)

        val frame = FrameLayout(context)
        listView.divider = null
        listView.dividerHeight = 0
        listView.selector = ColorDrawable(Color.argb(40, 255, 255, 255))
        listView.adapter = rowAdapter
        listView.setOnItemClickListener { _, v, position, _ ->
            val entry = rows.getOrNull(position)?.app
            if (entry != null) onLaunch?.invoke(entry, v)
        }
        listView.setOnItemLongClickListener { _, v, position, _ ->
            val entry = rows.getOrNull(position)?.app
            if (entry != null) {
                onMenu?.invoke(entry, v)
                true
            } else {
                false
            }
        }
        listView.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {
                if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) hideKeyboard()
            }

            override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {}
        })
        frame.addView(
            listView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        emptyView.setTextColor(Color.argb(200, 255, 255, 255))
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        emptyView.gravity = Gravity.CENTER
        emptyView.setPadding(context.dp(24), context.dp(48), context.dp(24), 0)
        emptyView.visibility = View.GONE
        frame.addView(
            emptyView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        )

        addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun setApps(apps: List<AppEntry>, recents: List<AppEntry>, isLoaded: Boolean) {
        allApps = apps
        recentApps = recents
        loaded = isLoaded
        refilter()
    }

    fun hasQuery(): Boolean = search.text.isNotEmpty()

    fun resetSearch() {
        search.setText("")
    }

    fun focusSearch() {
        search.requestFocus()
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }

    private fun refilter() {
        val query = search.text.toString().trim()
        if (query.isEmpty()) {
            val out = ArrayList<Row>()
            if (recentApps.isNotEmpty()) {
                out.add(Row("Recent", null))
                for (recent in recentApps) out.add(Row(null, recent))
            }
            out.addAll(withHeaders(allApps))
            rows = out
        } else {
            val lower = query.lowercase()
            val results = allApps
                .filter { it.label.lowercase().contains(lower) }
                .sortedBy { if (it.label.lowercase().startsWith(lower)) 0 else 1 }
            rows = results.map { Row(null, it) }
        }
        rowAdapter.notifyDataSetChanged()
        emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        emptyView.text = when {
            !loaded -> "Loading\u2026"
            query.isEmpty() -> "No apps found"
            else -> "No results for \"" + query + "\""
        }
    }

    private fun withHeaders(apps: List<AppEntry>): List<Row> {
        val out = ArrayList<Row>(apps.size + 28)
        var last = ""
        for (a in apps) {
            val ch = a.label.trim().firstOrNull()
            val header = if (ch != null && ch.isLetter()) ch.uppercaseChar().toString() else "#"
            if (header != last) {
                out.add(Row(header, null))
                last = header
            }
            out.add(Row(null, a))
        }
        return out
    }

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount(): Int = rows.size

        override fun getItem(position: Int): Any = rows[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getViewTypeCount(): Int = 2

        override fun getItemViewType(position: Int): Int = if (rows[position].header != null) 0 else 1

        override fun isEnabled(position: Int): Boolean = rows[position].app != null

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = rows[position]
            if (row.header != null) {
                val tv = (convertView as? TextView) ?: TextView(context).apply {
                    setTextColor(Color.argb(200, 255, 255, 255))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(context.dp(8), context.dp(14), context.dp(8), context.dp(4))
                }
                tv.text = row.header
                return tv
            }

            val entry = row.app!!
            val holder: RowHolder
            val view: View
            if (convertView == null) {
                val line = LinearLayout(context)
                line.orientation = LinearLayout.HORIZONTAL
                line.gravity = Gravity.CENTER_VERTICAL
                line.minimumHeight = context.dp(60)
                line.setPadding(context.dp(8), context.dp(6), context.dp(8), context.dp(6))

                val icon = ImageView(context)
                line.addView(icon, LinearLayout.LayoutParams(iconPx, iconPx))

                val label = TextView(context)
                label.setTextColor(Color.WHITE)
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                label.maxLines = 1
                label.ellipsize = android.text.TextUtils.TruncateAt.END
                val labelParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                labelParams.leftMargin = context.dp(14)
                line.addView(label, labelParams)

                holder = RowHolder(icon, label)
                line.tag = holder
                view = line
            } else {
                view = convertView
                holder = convertView.tag as RowHolder
            }

            holder.boundKey = entry.key
            holder.label.text = entry.label
            val cached = app.icons.peek(entry, iconPx)
            if (cached != null) {
                holder.icon.setImageBitmap(cached)
            } else {
                holder.icon.setImageDrawable(null)
                app.icons.load(entry, iconPx) { bmp ->
                    if (holder.boundKey == entry.key) holder.icon.setImageBitmap(bmp)
                }
            }
            return view
        }
    }
}
