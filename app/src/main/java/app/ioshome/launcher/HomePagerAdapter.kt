package app.ioshome.launcher

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.viewpager.widget.PagerAdapter

const val SRC_HOME = 0
const val SRC_DOCK = 1
const val SRC_FOLDER = 2

interface HomeCallbacks {
    fun onLaunch(entry: AppEntry, from: View)
    fun onOpenFolder(folderId: String)
    fun onStartDrag(itemKey: String, view: View, source: Int)
    fun onBadge(itemKey: String, inDock: Boolean)
    fun onGesture(gesture: Int)
    fun onLibraryMenu(entry: AppEntry, anchor: View)
    fun onOpenSettings()
}

/** Everything that decides how a home page looks, so unchanged pages can be kept instead of rebuilt. */
class GridParams(
    val cols: Int,
    val rows: Int,
    val iconPx: Int,
    val showLabels: Boolean,
    val labelSp: Float,
    val sidePad: Int,
    val topPad: Int,
    val bottomPad: Int,
    val animated: Boolean
)

/**
 * Pager pages, in order: an optional widgets page, the home pages, and the App Library.
 * Pages whose content did not change keep their existing views (no recreation, no icon re-binding).
 */
class HomePagerAdapter(
    private val context: Context,
    private val app: LauncherApp,
    private val library: View,
    private val callbacks: HomeCallbacks
) : PagerAdapter() {

    var homePages: Int = 1
        private set

    /** 1 when the widgets page is shown before the first home page, otherwise 0. */
    var lead: Int = 0
        private set

    var widgetsView: View? = null

    val libraryIndex: Int get() = lead + homePages

    private var items: List<String> = emptyList()
    private var params = GridParams(4, 6, 0, true, 11f, 0, 0, 0, true)
    private var editMode = false
    private val grids = HashMap<Int, HomeGridView>()

    fun update(items: List<String>, params: GridParams, lead: Int, editMode: Boolean) {
        this.items = ArrayList(items)
        this.params = params
        this.lead = lead
        this.editMode = editMode
        val capacity = (params.cols * params.rows).coerceAtLeast(1)
        homePages = ((items.size + capacity - 1) / capacity).coerceAtLeast(1)
        notifyDataSetChanged()
    }

    /** The grid view currently instantiated for a home page, if any. */
    fun gridAt(page: Int): HomeGridView? = grids[page]

    fun capacity(): Int = (params.cols * params.rows).coerceAtLeast(1)

    fun setEditMode(on: Boolean, animate: Boolean) {
        editMode = on
        for (grid in grids.values) {
            for (i in 0 until grid.childCount) {
                (grid.getChildAt(i) as? AppIconView)?.setEditMode(on, animate)
            }
        }
    }

    override fun getCount(): Int = lead + homePages + 1

    override fun isViewFromObject(view: View, obj: Any): Boolean = view === obj

    override fun getItemPosition(obj: Any): Int {
        if (obj === library) return libraryIndex
        if (obj === widgetsView) return if (lead == 1) 0 else PagerAdapter.POSITION_NONE
        if (obj is HomeGridView) {
            val page = obj.pageIndex
            if (page >= 0 && page < homePages && obj.signature == signature(page)) return lead + page
        }
        return PagerAdapter.POSITION_NONE
    }

    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        val widgets = widgetsView
        val view: View = when {
            lead == 1 && position == 0 && widgets != null -> widgets
            position < lead + homePages -> buildPage(position - lead)
            else -> library
        }
        (view.parent as? ViewGroup)?.removeView(view)
        container.addView(view)
        return view
    }

    override fun destroyItem(container: ViewGroup, position: Int, obj: Any) {
        if (obj is HomeGridView) grids.remove(obj.pageIndex)
        container.removeView(obj as View)
    }

    private fun signature(page: Int): String {
        val capacity = (params.cols * params.rows).coerceAtLeast(1)
        val start = page * capacity
        val end = minOf(items.size, start + capacity)
        val sb = StringBuilder(128)
        sb.append(params.cols).append('x').append(params.rows).append('/').append(params.iconPx)
            .append('/').append(params.showLabels).append('/').append(params.labelSp)
            .append('/').append(params.sidePad).append('/').append(params.topPad)
            .append('/').append(params.bottomPad).append('/').append(params.animated)
            .append('/').append(lead).append('/').append(app.appsVersion).append('/').append(app.icons.namespace)
        for (i in start until end) {
            val key = items[i]
            sb.append('|').append(key)
            if (key.startsWith(HomeModel.FOLDER_PREFIX)) {
                val folder = app.model.folders[key]
                if (folder != null) sb.append(folder.name).append(folder.apps.joinToString(","))
            }
            if (key == app.popKey) sb.append('!')
        }
        return sb.toString()
    }

    private fun buildPage(page: Int): View {
        val p = params
        val grid = HomeGridView(context)
        grid.columns = p.cols
        grid.rows = p.rows
        grid.pageIndex = page
        grid.signature = signature(page)
        grid.setPadding(p.sidePad, p.topPad, p.sidePad, p.bottomPad)
        grid.setOnLongClickListener {
            callbacks.onGesture(Gestures.GESTURE_LONG_PRESS)
            true
        }
        grid.doubleTapListener = { callbacks.onGesture(Gestures.GESTURE_DOUBLE_TAP) }
        val capacity = (p.cols * p.rows).coerceAtLeast(1)
        val start = page * capacity
        val end = minOf(items.size, start + capacity)
        for (i in start until end) {
            val key = items[i]
            val v = AppIconView(context)
            if (key.startsWith(HomeModel.FOLDER_PREFIX)) {
                val folder = app.model.folders[key] ?: continue
                val entries = folder.apps.mapNotNull { app.appsByKey[it] }
                v.bindFolder(key, folder.name, entries, p.iconPx, p.iconPx, p.showLabels, p.labelSp, app.icons, p.animated)
                v.setOnClickListener { callbacks.onOpenFolder(key) }
            } else {
                val entry = app.appsByKey[key] ?: continue
                v.bind(entry, p.iconPx, p.iconPx, p.showLabels, p.labelSp, app.icons, p.animated)
                v.setOnClickListener { callbacks.onLaunch(entry, v) }
            }
            v.setOnLongClickListener {
                callbacks.onStartDrag(key, v, SRC_HOME)
                true
            }
            v.onBadge = { callbacks.onBadge(key, false) }
            v.setEditMode(editMode, p.animated)
            if (p.animated && key == app.popKey) v.playPop()
            grid.addView(v)
        }
        grids[page] = grid
        return grid
    }
}
