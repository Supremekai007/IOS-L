package app.ioshome.launcher

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.viewpager.widget.PagerAdapter

interface HomeCallbacks {
    fun onLaunch(entry: AppEntry, from: View)
    fun onHomeMenu(entry: AppEntry, anchor: View, inDock: Boolean)
    fun onLibraryMenu(entry: AppEntry, anchor: View)
    fun onOpenSettings()
}

/**
 * Pages 0 until [homePages] are home-screen grids built from the flat item list;
 * the final page is the App Library.
 */
class HomePagerAdapter(
    private val context: Context,
    private val app: LauncherApp,
    private val library: LibraryView,
    private val callbacks: HomeCallbacks
) : PagerAdapter() {

    var homePages: Int = 1
        private set

    private var items: List<String> = emptyList()
    private var cols = 4
    private var rows = 6
    private var iconPx = 0
    private var showLabels = true
    private var sidePad = 0
    private var topPad = 0
    private var bottomPad = 0
    private var animated = true

    fun update(
        items: List<String>,
        cols: Int,
        rows: Int,
        iconPx: Int,
        showLabels: Boolean,
        sidePad: Int,
        topPad: Int,
        bottomPad: Int,
        animated: Boolean
    ) {
        this.items = ArrayList(items)
        this.cols = cols
        this.rows = rows
        this.iconPx = iconPx
        this.showLabels = showLabels
        this.sidePad = sidePad
        this.topPad = topPad
        this.bottomPad = bottomPad
        this.animated = animated
        val capacity = (cols * rows).coerceAtLeast(1)
        homePages = ((items.size + capacity - 1) / capacity).coerceAtLeast(1)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = homePages + 1

    override fun isViewFromObject(view: View, obj: Any): Boolean = view === obj

    override fun getItemPosition(obj: Any): Int = PagerAdapter.POSITION_NONE

    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        val view: View = if (position < homePages) buildPage(position) else library
        (view.parent as? ViewGroup)?.removeView(view)
        container.addView(view)
        return view
    }

    override fun destroyItem(container: ViewGroup, position: Int, obj: Any) {
        container.removeView(obj as View)
    }

    private fun buildPage(page: Int): View {
        val grid = HomeGridView(context)
        grid.columns = cols
        grid.rows = rows
        grid.setPadding(sidePad, topPad, sidePad, bottomPad)
        grid.setOnLongClickListener {
            callbacks.onOpenSettings()
            true
        }
        val capacity = (cols * rows).coerceAtLeast(1)
        val start = page * capacity
        val end = minOf(items.size, start + capacity)
        for (i in start until end) {
            val entry = app.appsByKey[items[i]] ?: continue
            val v = AppIconView(context)
            v.bind(entry, iconPx, showLabels, app.icons, animated)
            v.setOnClickListener { callbacks.onLaunch(entry, v) }
            v.setOnLongClickListener {
                callbacks.onHomeMenu(entry, v, false)
                true
            }
            grid.addView(v)
        }
        return grid
    }
}
