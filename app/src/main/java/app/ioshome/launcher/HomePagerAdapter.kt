package app.ioshome.launcher

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.viewpager.widget.PagerAdapter

const val SRC_HOME = 0
const val SRC_DOCK = 1

interface HomeCallbacks {
    fun onLaunch(entry: AppEntry, from: View)
    fun onOpenFolder(folderId: String)
    fun onStartDrag(itemKey: String, view: View, source: Int)
    fun onBadge(itemKey: String, inDock: Boolean)
    fun onEnterEditMode()
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
    private var editMode = false
    private val grids = HashMap<Int, HomeGridView>()

    fun update(
        items: List<String>,
        cols: Int,
        rows: Int,
        iconPx: Int,
        showLabels: Boolean,
        sidePad: Int,
        topPad: Int,
        bottomPad: Int,
        animated: Boolean,
        editMode: Boolean
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
        this.editMode = editMode
        val capacity = (cols * rows).coerceAtLeast(1)
        homePages = ((items.size + capacity - 1) / capacity).coerceAtLeast(1)
        notifyDataSetChanged()
    }

    /** The grid view currently instantiated for a home page, if any. */
    fun gridAt(page: Int): HomeGridView? = grids[page]

    fun setEditMode(on: Boolean, animate: Boolean) {
        editMode = on
        for (grid in grids.values) {
            for (i in 0 until grid.childCount) {
                (grid.getChildAt(i) as? AppIconView)?.setEditMode(on, animate)
            }
        }
    }

    /** Icons of a page pop in one after another (used when coming back to the home screen). */
    fun playEntrance(page: Int) {
        val grid = grids[page] ?: return
        for (i in 0 until grid.childCount) {
            val v = grid.getChildAt(i)
            v.alpha = 0f
            v.scaleX = 0.8f
            v.scaleY = 0.8f
            v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay(minOf(i * 12L, 240L)).setDuration(240L)
                .setInterpolator(DecelerateInterpolator()).start()
        }
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
        grids.remove(position)
        container.removeView(obj as View)
    }

    private fun buildPage(page: Int): View {
        val grid = HomeGridView(context)
        grid.columns = cols
        grid.rows = rows
        grid.setPadding(sidePad, topPad, sidePad, bottomPad)
        grid.setOnLongClickListener {
            callbacks.onEnterEditMode()
            true
        }
        val capacity = (cols * rows).coerceAtLeast(1)
        val start = page * capacity
        val end = minOf(items.size, start + capacity)
        for (i in start until end) {
            val key = items[i]
            val v = AppIconView(context)
            if (key.startsWith(HomeModel.FOLDER_PREFIX)) {
                val folder = app.model.folders[key] ?: continue
                val entries = folder.apps.mapNotNull { app.appsByKey[it] }
                v.bindFolder(key, folder.name, entries, iconPx, showLabels, app.icons, animated)
                v.setOnClickListener { callbacks.onOpenFolder(key) }
            } else {
                val entry = app.appsByKey[key] ?: continue
                v.bind(entry, iconPx, showLabels, app.icons, animated)
                v.setOnClickListener { callbacks.onLaunch(entry, v) }
            }
            v.setOnLongClickListener {
                callbacks.onStartDrag(key, v, SRC_HOME)
                true
            }
            v.onBadge = { callbacks.onBadge(key, false) }
            v.setEditMode(editMode, animated)
            if (animated && key == app.popKey) v.playPop()
            grid.addView(v)
        }
        grids[page] = grid
        return grid
    }
}
