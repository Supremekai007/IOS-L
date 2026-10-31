package app.ioshome.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.viewpager.widget.ViewPager
import kotlin.math.abs

class LauncherActivity : Activity(), HomeCallbacks {

    private class MenuEntry(val title: String, val action: () -> Unit)

    private class DragState(val key: String, val source: Int, val view: View, val origin: Rect) {
        var hoverKey: String? = null
        var hoverView: AppIconView? = null
        var folderReady = false
        var edgeDir = 0
        var pendingMenu = false
    }

    private class GridHit(val flatIndex: Int, val inCenter: Boolean, val itemKey: String?, val view: AppIconView?)

    private lateinit var app: LauncherApp
    private lateinit var root: FrameLayout
    private lateinit var pager: ViewPager
    private lateinit var bottomBar: LinearLayout
    private lateinit var dots: PageDots
    private lateinit var pill: TextView
    private lateinit var dock: DockView
    private lateinit var library: LibraryView
    private lateinit var adapter: HomePagerAdapter
    private lateinit var folderView: FolderView
    private lateinit var editBar: LinearLayout
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var controlCenter: ControlCenterView

    private var applyPosted = false
    private var editMode = false
    private var drag: DragState? = null
    private var slotWidth = 0
    private var currentIconPx = 0

    private var downX = 0f
    private var downY = 0f
    private var swipeKind = SWIPE_NONE
    private var entrancePending = true
    private var pinchStart = 0f
    private var pinchLast = 0f

    private val edgeRunnable = Runnable { onEdgeTimer() }
    private val highlightRunnable = Runnable { onHighlightTimer() }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as LauncherApp
        configureWindow()
        buildUi()
    }

    override fun onStart() {
        super.onStart()
        app.onDataChanged = { applyState() }
        entrancePending = true
        resetPagerTransform(true)
        applyState()
    }

    override fun onStop() {
        super.onStop()
        app.onDataChanged = null
        controlCenter.hide(false)
        library.hideKeyboard()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pressing Home while the launcher is already showing returns to the first page.
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            library.hideKeyboard()
            library.resetSearch()
            folderView.hide(false)
            controlCenter.hide(false)
            setEditMode(false)
            if (pager.currentItem != 0) {
                pager.setCurrentItem(0, app.settings.animations)
            } else if (app.settings.animations) {
                adapter.playEntrance(0)
            }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // A launcher never finishes itself on Back; it only unwinds its own state.
        when {
            controlCenter.isOpen -> controlCenter.hide(app.settings.animations)
            folderView.openId != null -> folderView.hide(app.settings.animations)
            editMode -> setEditMode(false)
            pager.currentItem == adapter.homePages && library.hasQuery() -> library.resetSearch()
            pager.currentItem != 0 -> pager.setCurrentItem(0, app.settings.animations)
        }
    }

    // ---------------------------------------------------------------- window and UI

    @Suppress("DEPRECATION")
    private fun configureWindow() {
        val w = window
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false)
        } else {
            w.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        w.statusBarColor = Color.TRANSPARENT
        w.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = w.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            w.attributes = lp
        }
        if (Build.VERSION.SDK_INT >= 29) {
            w.setNavigationBarContrastEnforced(false)
        }
    }

    private fun pillButton(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(Color.WHITE)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        tv.gravity = Gravity.CENTER
        tv.setPadding(dp(20), dp(10), dp(20), dp(10))
        val bg = GradientDrawable()
        bg.setColor(Color.argb(70, 255, 255, 255))
        bg.cornerRadius = dpf(24f)
        tv.background = bg
        tv.isClickable = true
        return tv
    }

    private fun buildUi() {
        library = LibraryView(this, app)
        library.onLaunch = { entry, view -> onLaunch(entry, view) }
        library.onMenu = { entry, view -> onLibraryMenu(entry, view) }

        adapter = HomePagerAdapter(this, app, library, this)
        pager = ViewPager(this)
        pager.adapter = adapter
        pager.setPageTransformer(false) { page, position ->
            if (app.settings.animations) {
                // Pages shrink and fade slightly as they slide away, like the iOS home screen.
                val t = minOf(1f, abs(position))
                val scale = 1f - 0.10f * t
                page.scaleX = scale
                page.scaleY = scale
                page.alpha = 1f - 0.45f * t
            } else {
                page.scaleX = 1f
                page.scaleY = 1f
                page.alpha = 1f
            }
        }
        pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                updateBottomBar(position + positionOffset)
            }

            override fun onPageSelected(position: Int) {
                if (position == adapter.homePages) setEditMode(false)
                if (position != adapter.homePages) library.hideKeyboard()
            }
        })

        dots = PageDots(this)

        pill = pillButton("Search")
        pill.contentDescription = "Search apps"
        pill.setPadding(dp(32), dp(12), dp(32), dp(12))
        pill.setOnClickListener { openLibraryWithKeyboard() }

        dock = DockView(this)
        dock.setPadding(dp(10), dp(12), dp(10), dp(12))
        dock.setOnLongClickListener {
            onEnterEditMode()
            true
        }

        bottomBar = LinearLayout(this)
        bottomBar.orientation = LinearLayout.VERTICAL
        bottomBar.gravity = Gravity.CENTER_HORIZONTAL
        bottomBar.addView(
            dots,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        val pillParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        pillParams.bottomMargin = dp(10)
        bottomBar.addView(pill, pillParams)
        val dockParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        dockParams.leftMargin = dp(12)
        dockParams.rightMargin = dp(12)
        dockParams.bottomMargin = dp(8)
        bottomBar.addView(dock, dockParams)

        // Edit-mode bar (Settings / Done), shown in the reserved strip above the first row of icons.
        editBar = LinearLayout(this)
        editBar.orientation = LinearLayout.HORIZONTAL
        editBar.setPadding(dp(12), dp(2), dp(12), 0)
        editBar.visibility = View.GONE
        val settingsButton = pillButton("Settings")
        settingsButton.setOnClickListener { onOpenSettings() }
        val doneButton = pillButton("Done")
        doneButton.setOnClickListener { setEditMode(false) }
        editBar.addView(
            settingsButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        editBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        editBar.addView(
            doneButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        folderView = FolderView(this)
        folderView.onLaunch = { entry, view -> onLaunch(entry, view) }
        folderView.onItemMenu = { entry, view, folderId -> onFolderItemMenu(entry, view, folderId) }
        folderView.onRename = { id, name -> app.edit { renameFolder(id, name) } }

        controlCenter = ControlCenterView(this, this)

        root = FrameLayout(this)
        root.addView(
            pager,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        root.addView(
            bottomBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )
        root.addView(
            editBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )
        root.addView(
            folderView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        root.addView(
            controlCenter,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        root.setOnApplyWindowInsetsListener { v, insets -> applyInsets(v, insets) }
        root.setOnDragListener { _, event -> handleDrag(event) }

        pager.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) scheduleApply()
        }
        bottomBar.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) scheduleApply()
        }

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinchStart = detector.currentSpan
                pinchLast = pinchStart
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pinchLast = detector.currentSpan
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (pinchStart > 0f && pinchLast / pinchStart < 0.75f) onEnterEditMode()
            }
        })

        setContentView(root)
    }

    @Suppress("DEPRECATION")
    private fun applyInsets(v: View, insets: WindowInsets): WindowInsets {
        val left: Int
        val top: Int
        val right: Int
        val bottom: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            left = bars.left
            top = bars.top
            right = bars.right
            bottom = maxOf(bars.bottom, ime.bottom)
        } else {
            left = insets.systemWindowInsetLeft
            top = insets.systemWindowInsetTop
            right = insets.systemWindowInsetRight
            bottom = insets.systemWindowInsetBottom
        }
        v.setPadding(left, top, right, bottom)
        return insets
    }

    private fun scheduleApply() {
        if (applyPosted) return
        applyPosted = true
        root.post {
            applyPosted = false
            applyState()
        }
    }

    /** Recomputes the grid from the current size and settings and pushes data into every view. */
    fun applyState() {
        val model = app.model
        val visibleApps = app.apps.filter { it.key !in model.hidden }
        val recentApps = model.recents.mapNotNull { app.appsByKey[it] }
        library.setApps(visibleApps, recentApps, app.loaded)

        val w = pager.width
        val h = pager.height
        if (w <= 0 || h <= 0) return

        val s = app.settings
        val sidePad = dp(8)
        val cols = s.columns
        val cellW = (w - 2 * sidePad) / cols
        val iconPx = minOf(dpf(60f) * s.iconScale(), cellW * 0.78f).toInt().coerceAtLeast(dp(36))
        currentIconPx = iconPx

        val slots = s.dockSlots
        val dockMargin = dp(12)
        val dockPadH = dp(10)
        val slotW = (w - 2 * dockMargin - 2 * dockPadH) / slots
        slotWidth = slotW
        val dockIcon = minOf(iconPx.toFloat(), slotW * 0.78f).toInt().coerceAtLeast(dp(32))
        dock.minimumHeight = dockIcon + dp(24)
        val dockEntries = model.dock.mapNotNull { app.appsByKey[it] }
        dock.bind(
            dockEntries, slotW, dockIcon, app.icons, s.animations, editMode, app.popKey,
            { entry, view -> onLaunch(entry, view) },
            { entry, view -> onStartDrag(entry.key, view, SRC_DOCK) },
            { entry -> onBadge(entry.key, true) }
        )

        // The bottom bar height is only known after layout; a layout listener calls us again then.
        val reserve = bottomBar.height
        if (reserve <= 0) return

        val labelPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        val labelH = if (s.showLabels) (labelPx * 1.4f).toInt() + dp(5) else 0
        val cellH = iconPx + labelH + dp(20)
        val topPad = dp(44)
        val rows = maxOf(1, (h - reserve - topPad) / cellH)

        adapter.update(model.home, cols, rows, iconPx, s.showLabels, sidePad, topPad, reserve, s.animations, editMode)
        dots.count = adapter.homePages
        updateBottomBar(minOf(pager.currentItem, adapter.count - 1).toFloat())

        if (entrancePending && app.loaded && model.home.isNotEmpty()) {
            entrancePending = false
            if (s.animations && pager.currentItem < adapter.homePages) adapter.playEntrance(pager.currentItem)
        }

        val openId = folderView.openId
        if (openId != null) {
            val folder = model.folders[openId]
            if (folder == null || folder.apps.isEmpty()) folderView.hide(false) else showFolder(openId, false)
        }
    }

    /** Dock, search pill and dots fade out as the App Library page slides in. */
    private fun updateBottomBar(pos: Float) {
        val last = (adapter.homePages - 1).toFloat()
        val alpha = when {
            pos <= last -> 1f
            pos >= last + 1f -> 0f
            else -> 1f - (pos - last)
        }
        bottomBar.alpha = alpha
        bottomBar.visibility = if (alpha <= 0.01f) View.INVISIBLE else View.VISIBLE
        dots.position = pos
    }

    private fun openLibraryWithKeyboard() {
        val smooth = app.settings.animations
        pager.setCurrentItem(adapter.homePages, smooth)
        pager.postDelayed({ library.focusSearch() }, if (smooth) 300L else 50L)
    }

    // ---------------------------------------------------------------- edit mode, gestures

    private fun setEditMode(on: Boolean) {
        if (editMode == on) return
        editMode = on
        val animate = app.settings.animations
        adapter.setEditMode(on, animate)
        dock.setEditMode(on, animate)
        editBar.animate().cancel()
        if (on) {
            editBar.visibility = View.VISIBLE
            if (animate) {
                editBar.alpha = 0f
                editBar.animate().alpha(1f).setStartDelay(0L).setDuration(180L).start()
            } else {
                editBar.alpha = 1f
            }
        } else if (animate) {
            editBar.animate().alpha(0f).setStartDelay(0L).setDuration(140L).withEndAction {
                editBar.visibility = View.GONE
            }.start()
        } else {
            editBar.visibility = View.GONE
        }
        if (on && app.settings.haptics) root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    override fun onEnterEditMode() {
        if (pager.currentItem < adapter.homePages && folderView.openId == null) setEditMode(true)
    }

    /**
     * Observes touches without consuming them: a swipe down from the top-right opens Control Center,
     * a swipe down elsewhere on the home screen opens search, and pinching in enters edit mode.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                swipeKind = SWIPE_NONE
                val free = !editMode && folderView.openId == null && !controlCenter.isOpen && drag == null
                if (free) {
                    if (ev.x > root.width * 0.5f && ev.y < root.paddingTop + dp(96)) {
                        swipeKind = SWIPE_CONTROL
                    } else if (pager.currentItem < adapter.homePages && ev.y < bottomBar.top) {
                        swipeKind = SWIPE_SEARCH
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> swipeKind = SWIPE_NONE
            MotionEvent.ACTION_CANCEL -> swipeKind = SWIPE_NONE
            MotionEvent.ACTION_UP -> {
                val dy = ev.y - downY
                val dx = ev.x - downX
                if (swipeKind == SWIPE_SEARCH && dy > dp(110) && abs(dx) < dy * 0.5f) {
                    openLibraryWithKeyboard()
                } else if (swipeKind == SWIPE_CONTROL && dy > dp(70) && abs(dx) < dy) {
                    openControlCenter()
                }
                swipeKind = SWIPE_NONE
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // ---------------------------------------------------------------- drag and drop

    private fun rectInRoot(v: View): Rect {
        val a = IntArray(2)
        val b = IntArray(2)
        root.getLocationInWindow(a)
        v.getLocationInWindow(b)
        val left = b[0] - a[0]
        val top = b[1] - a[1]
        return Rect(left, top, left + v.width, top + v.height)
    }

    override fun onStartDrag(itemKey: String, view: View, source: Int) {
        if (drag != null) return
        if (app.settings.haptics) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val state = DragState(itemKey, source, view, rectInRoot(view))
        drag = state
        val description = ClipDescription("item", arrayOf("application/x-ioshome-item"))
        val clip = ClipData(description, ClipData.Item(itemKey))
        val started = view.startDragAndDrop(clip, View.DragShadowBuilder(view), null, 0)
        if (started) {
            view.visibility = View.INVISIBLE
        } else {
            drag = null
        }
    }

    private fun handleDrag(event: DragEvent): Boolean {
        val d = drag
        return when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> d != null
            DragEvent.ACTION_DRAG_LOCATION -> {
                if (d != null) onDragMove(d, event.x, event.y)
                true
            }
            DragEvent.ACTION_DROP -> {
                if (d != null) onDrop(d, event.x, event.y)
                true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                endDrag()
                true
            }
            else -> true
        }
    }

    private fun onDragMove(d: DragState, x: Float, y: Float) {
        // Hovering at the left or right screen edge flips pages after a short delay.
        val edge = dp(36)
        val dir = if (x < edge) -1 else if (x > root.width - edge) 1 else 0
        if (dir != d.edgeDir) {
            d.edgeDir = dir
            root.removeCallbacks(edgeRunnable)
            if (dir != 0) root.postDelayed(edgeRunnable, 650L)
        }

        // Lingering over the centre of another icon marks it as a folder target.
        val hit = if (isInDockZone(y)) null else gridHit(x, y)
        var newKey: String? = null
        if (hit != null && hit.inCenter && hit.itemKey != null && hit.itemKey != d.key && !app.model.isFolder(d.key)) {
            newKey = hit.itemKey
        }
        if (newKey != d.hoverKey) {
            d.hoverView?.setHighlighted(false)
            root.removeCallbacks(highlightRunnable)
            d.hoverKey = newKey
            d.hoverView = if (newKey != null) hit?.view else null
            d.folderReady = false
            if (newKey != null) root.postDelayed(highlightRunnable, 450L)
        }
    }

    private fun onEdgeTimer() {
        val d = drag ?: return
        if (d.edgeDir == 0) return
        val target = pager.currentItem + d.edgeDir
        if (target >= 0 && target < adapter.homePages) pager.setCurrentItem(target, true)
        root.postDelayed(edgeRunnable, 650L)
    }

    private fun onHighlightTimer() {
        val d = drag ?: return
        if (d.hoverKey != null) {
            d.folderReady = true
            d.hoverView?.setHighlighted(true)
            if (app.settings.haptics) root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun isInDockZone(y: Float): Boolean {
        if (bottomBar.visibility != View.VISIBLE) return false
        return y >= rectInRoot(dock).top - dp(16)
    }

    private fun gridHit(x: Float, y: Float): GridHit? {
        val page = pager.currentItem
        if (page >= adapter.homePages) return null
        val grid = adapter.gridAt(page) ?: return null
        val rect = rectInRoot(grid)
        val cols = grid.columns
        val rows = grid.rows
        val cellW = (grid.width - grid.paddingLeft - grid.paddingRight) / cols.toFloat()
        val cellH = (grid.height - grid.paddingTop - grid.paddingBottom) / rows.toFloat()
        if (cellW <= 0f || cellH <= 0f) return null
        val lx = x - rect.left - grid.paddingLeft
        val ly = y - rect.top - grid.paddingTop
        val col = (lx / cellW).toInt().coerceIn(0, cols - 1)
        val row = (ly / cellH).toInt().coerceIn(0, rows - 1)
        val cell = row * cols + col
        val flat = page * cols * rows + cell
        val inCenter = abs(lx - (col + 0.5f) * cellW) < cellW * 0.28f &&
            abs(ly - (row + 0.5f) * cellH) < cellH * 0.28f
        val view = if (cell < grid.childCount) grid.getChildAt(cell) as? AppIconView else null
        return GridHit(flat, inCenter, app.model.home.getOrNull(flat), view)
    }

    private fun dockIndexAt(x: Float): Int {
        val n = app.model.dock.size
        if (slotWidth <= 0) return n
        val rect = rectInRoot(dock)
        val start = rect.exactCenterX() - n * slotWidth / 2f
        return ((x - start) / slotWidth).toInt().coerceIn(0, n)
    }

    private fun onDrop(d: DragState, x: Float, y: Float) {
        root.removeCallbacks(edgeRunnable)
        root.removeCallbacks(highlightRunnable)

        // Released where it started: treat as a long-press and show the item's menu instead.
        if (d.origin.contains(x.toInt(), y.toInt()) && !d.folderReady) {
            d.pendingMenu = true
            return
        }

        val model = app.model
        if (isInDockZone(y)) {
            val slots = app.settings.dockSlots
            if (model.isFolder(d.key)) {
                toast("Folders can't go in the dock")
            } else if (d.key !in model.dock && model.dock.size >= slots) {
                toast("The dock is full")
            } else {
                val index = dockIndexAt(x)
                markPop(d.key)
                app.edit { moveToDockIndex(d.key, index, slots) }
            }
            return
        }

        val hit = gridHit(x, y) ?: return
        val hover = d.hoverKey
        if (d.folderReady && hover != null && hover == hit.itemKey) {
            app.edit { mergeIntoFolder(d.key, hover) }
        } else {
            markPop(d.key)
            app.edit { moveToHomeIndex(d.key, hit.flatIndex) }
        }
    }

    private fun endDrag() {
        val d = drag ?: return
        drag = null
        root.removeCallbacks(edgeRunnable)
        root.removeCallbacks(highlightRunnable)
        d.hoverView?.setHighlighted(false)
        d.view.visibility = View.VISIBLE
        if (d.pendingMenu) showItemMenu(d.key, d.view)
    }

    fun openControlCenter() {
        library.hideKeyboard()
        if (app.settings.haptics) root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        controlCenter.show(app.settings.animations)
    }

    /** Width available inside the system bars and cutouts. */
    fun availableWidth(): Int = root.width - root.paddingLeft - root.paddingRight

    /** Opens an Android screen (used by Control Center) and closes the overlay. */
    fun startSystem(intent: Intent) {
        controlCenter.hide(false)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("Not available on this device")
        }
    }

    private fun resetPagerTransform(zoomIn: Boolean) {
        pager.animate().cancel()
        if (zoomIn && app.settings.animations) {
            pager.scaleX = 1.06f
            pager.scaleY = 1.06f
            pager.alpha = 0.7f
            pager.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(0L).setDuration(260L)
                .setInterpolator(DecelerateInterpolator()).start()
        } else {
            pager.scaleX = 1f
            pager.scaleY = 1f
            pager.alpha = 1f
        }
    }

    /** The dropped icon plays a short settle animation when the pages are rebuilt. */
    private fun markPop(key: String) {
        app.popKey = key
        root.postDelayed({ app.popKey = null }, 700L)
    }

    // ---------------------------------------------------------------- HomeCallbacks

    override fun onLaunch(entry: AppEntry, from: View) {
        if (editMode) return
        library.hideKeyboard()
        try {
            val rect = Rect()
            from.getGlobalVisibleRect(rect)
            val options = if (app.settings.animations && from.width > 0 && from.height > 0) {
                ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height).toBundle()
            } else {
                null
            }
            app.repo.launch(entry, rect, options)
            app.editQuiet { recordLaunch(entry.key) }
            if (app.settings.animations) {
                // The home screen shrinks and fades as the app opens, like iOS.
                pager.animate().cancel()
                pager.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.75f).setStartDelay(0L).setDuration(220L)
                    .setInterpolator(AccelerateDecelerateInterpolator()).start()
                pager.postDelayed({ resetPagerTransform(false) }, 1500L)
            }
        } catch (e: Exception) {
            toast("Can't open " + entry.label)
            app.reload()
        }
    }

    override fun onOpenFolder(folderId: String) {
        showFolder(folderId, true)
    }

    private fun showFolder(id: String, animate: Boolean) {
        val folder = app.model.folders[id] ?: return
        val entries = folder.apps.mapNotNull { app.appsByKey[it] }
        val iconPx = if (currentIconPx > 0) currentIconPx else dp(56)
        folderView.show(id, folder.name, entries, iconPx, animate && app.settings.animations, app.settings.animations)
    }

    override fun onBadge(itemKey: String, inDock: Boolean) {
        // Posted so the view hierarchy is not rebuilt in the middle of the touch event.
        root.post {
            if (inDock) {
                app.edit { moveDockToHome(itemKey) }
            } else if (app.model.isFolder(itemKey)) {
                app.edit { dissolveFolder(itemKey) }
            } else {
                app.edit { removeFromHome(itemKey) }
            }
        }
    }

    private fun showItemMenu(key: String, view: View) {
        val model = app.model
        if (model.isFolder(key)) {
            val items = ArrayList<MenuEntry>()
            items.add(MenuEntry("Open Folder") { onOpenFolder(key) })
            items.add(MenuEntry("Remove Folder") { app.edit { dissolveFolder(key) } })
            showMenu(view, items)
            return
        }
        val entry = app.appsByKey[key] ?: return
        onHomeMenu(entry, view, key in model.dock)
    }

    private fun onHomeMenu(entry: AppEntry, anchor: View, inDock: Boolean) {
        val model = app.model
        val slots = app.settings.dockSlots
        val list = if (inDock) model.dock else model.home
        val index = list.indexOf(entry.key)
        val items = ArrayList<MenuEntry>()
        if (inDock) {
            items.add(MenuEntry("Move to Home Screen") { app.edit { moveDockToHome(entry.key) } })
        } else if (model.dock.size < slots) {
            items.add(MenuEntry("Add to Dock") { app.edit { addToDock(entry.key, slots) } })
        }
        if (index > 0) items.add(MenuEntry("Move Earlier") { app.edit { shift(entry.key, -1) } })
        if (index >= 0 && index < list.size - 1) {
            items.add(MenuEntry("Move Later") { app.edit { shift(entry.key, 1) } })
        }
        if (!inDock) {
            items.add(MenuEntry("Remove from Home Screen") { app.edit { removeFromHome(entry.key) } })
        }
        items.add(MenuEntry("Hide App") { hideApp(entry) })
        items.add(MenuEntry("App Info") { openAppInfo(entry) })
        items.add(MenuEntry("Uninstall\u2026") { uninstall(entry) })
        showMenu(anchor, items)
    }

    private fun onFolderItemMenu(entry: AppEntry, anchor: View, folderId: String) {
        val folder = app.model.folders[folderId] ?: return
        val index = folder.apps.indexOf(entry.key)
        val items = ArrayList<MenuEntry>()
        if (index > 0) {
            items.add(MenuEntry("Move Earlier") { app.edit { shiftInFolder(folderId, entry.key, -1) } })
        }
        if (index >= 0 && index < folder.apps.size - 1) {
            items.add(MenuEntry("Move Later") { app.edit { shiftInFolder(folderId, entry.key, 1) } })
        }
        items.add(MenuEntry("Move to Home Screen") { app.edit { moveToHomeIndex(entry.key, Int.MAX_VALUE) } })
        items.add(MenuEntry("Hide App") { hideApp(entry) })
        items.add(MenuEntry("App Info") { openAppInfo(entry) })
        items.add(MenuEntry("Uninstall\u2026") { uninstall(entry) })
        showMenu(anchor, items)
    }

    override fun onLibraryMenu(entry: AppEntry, anchor: View) {
        val model = app.model
        val slots = app.settings.dockSlots
        val items = ArrayList<MenuEntry>()
        if (!model.contains(entry.key)) {
            items.add(MenuEntry("Add to Home Screen") {
                app.edit { addToHome(entry.key) }
                toast("Added to Home Screen")
            })
            if (model.dock.size < slots) {
                items.add(MenuEntry("Add to Dock") {
                    app.edit { addToDock(entry.key, slots) }
                    toast("Added to Dock")
                })
            }
        }
        items.add(MenuEntry("Hide App") { hideApp(entry) })
        items.add(MenuEntry("App Info") { openAppInfo(entry) })
        items.add(MenuEntry("Uninstall\u2026") { uninstall(entry) })
        showMenu(anchor, items)
    }

    override fun onOpenSettings() {
        if (app.settings.haptics) root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        SettingsDialog(this, app).show()
    }

    private fun hideApp(entry: AppEntry) {
        app.edit { hide(entry.key) }
        toast("Hidden. Unhide it in Settings > Hidden apps")
    }

    private fun showMenu(anchor: View, items: List<MenuEntry>) {
        if (app.settings.haptics) anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val popup = PopupMenu(this, anchor)
        for ((i, item) in items.withIndex()) {
            popup.menu.add(0, i, i, item.title)
        }
        popup.setOnMenuItemClickListener { menuItem ->
            items.getOrNull(menuItem.itemId)?.action?.invoke()
            true
        }
        popup.show()
    }

    private fun openAppInfo(entry: AppEntry) {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", entry.component.packageName, null))
            )
        } catch (e: Exception) {
            toast("Can't open app info")
        }
    }

    /** Hands off to Android's own uninstall confirmation; the launcher never removes apps itself. */
    private fun uninstall(entry: AppEntry) {
        try {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", entry.component.packageName, null)))
        } catch (e: Exception) {
            toast("Can't uninstall this app")
        }
    }

    // ---------------------------------------------------------------- helpers used by SettingsDialog

    fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    fun openWallpaperPicker() {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Select wallpaper"))
        } catch (e: Exception) {
            toast("No wallpaper picker available")
        }
    }

    /** True only when Android itself reports this app as the current Home app. */
    fun isDefaultHome(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val info = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return info?.activityInfo?.packageName == packageName
    }

    fun requestDefaultHome() {
        if (Build.VERSION.SDK_INT >= 29) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                if (roleManager.isRoleHeld(RoleManager.ROLE_HOME)) {
                    toast("Already your Home app")
                    return
                }
                try {
                    startActivityForResult(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME), REQUEST_HOME_ROLE)
                    return
                } catch (e: Exception) {
                    // fall through to the settings screen
                }
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                toast("Open Settings > Apps > Default apps > Home app")
            }
        }
    }

    private companion object {
        const val REQUEST_HOME_ROLE = 1001
        const val SWIPE_NONE = 0
        const val SWIPE_SEARCH = 1
        const val SWIPE_CONTROL = 2
    }
}
