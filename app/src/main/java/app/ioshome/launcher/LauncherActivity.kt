package app.ioshome.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.app.role.RoleManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
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

    private class DragState(val key: String, val source: Int, val view: View, val origin: Rect, val folderId: String?) {
        var dragIdx = -1
        var insertIdx = -1
        var folderInsert = -1
        var previewZone = ZONE_NONE
        var previewPage = -1
        var hoverKey: String? = null
        var hoverView: AppIconView? = null
        var dwell: ValueAnimator? = null
        var readyRunnable: Runnable? = null
        var folderReady = false
        var edgeDir = 0
        var pendingMenu = false
        var leftFolder = false
    }

    private class GridHit(val flatIndex: Int)

    /** Drag shadow: the icon lifted slightly, enlarged, with a soft shadow underneath. */
    private class LiftShadow(v: View) : View.DragShadowBuilder(v) {
        private val pad = (v.width * 0.22f).toInt()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
            val v = view
            val w = v.width + 2 * pad
            val h = v.height + 2 * pad
            outShadowSize.set(w, h)
            outShadowTouchPoint.set(w / 2, h / 2)
        }

        override fun onDrawShadow(canvas: Canvas) {
            val v = view
            val icon = (v as? AppIconView)?.iconBounds() ?: RectF(0f, 0f, v.width.toFloat(), v.height.toFloat())
            canvas.save()
            canvas.translate(pad.toFloat(), pad.toFloat())
            canvas.scale(1.12f, 1.12f, v.width / 2f, v.height / 2f)
            paint.color = Color.argb(255, 40, 40, 40)
            paint.setShadowLayer(v.width * 0.12f, 0f, v.width * 0.07f, Color.argb(120, 0, 0, 0))
            val r = icon.width() * 0.2237f
            canvas.drawRoundRect(RectF(icon.left + 2f, icon.top + 2f, icon.right - 2f, icon.bottom - 2f), r, r, paint)
            paint.clearShadowLayer()
            v.draw(canvas)
            canvas.restore()
        }
    }

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
    private lateinit var widgetsPage: WidgetsPageView
    private lateinit var widgetHost: AppWidgetHost
    private lateinit var widgetManager: AppWidgetManager

    private val preview = ReorderPreview()
    private val s: LauncherSettings get() = app.settings

    private var applyPosted = false
    private var editMode = false
    private var drag: DragState? = null
    private var slotWidth = 0
    private var canonicalPx = 0
    private var currentLabelSp = 11f
    private var pendingWidgetId = -1

    private var downX = 0f
    private var downY = 0f
    private var swipeKind = SWIPE_NONE
    private var pinchStart = 0f
    private var pinchLast = 0f
    private var entrancePending = true
    private var lastTransitionSig = ""
    private var lastLibrarySig = ""
    private var lastDockSig = ""
    private var lastWidgetSig = ""
    private var lastDark: Boolean? = null

    private val edgeRunnable = Runnable { onEdgeTimer() }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as LauncherApp
        widgetManager = AppWidgetManager.getInstance(this)
        widgetHost = AppWidgetHost(this, WIDGET_HOST_ID)
        configureWindow()
        buildUi()
    }

    override fun onStart() {
        super.onStart()
        app.onDataChanged = { applyState() }
        try {
            widgetHost.startListening()
        } catch (e: Exception) {
            // widget updates are simply not delivered
        }
        resetPagerTransform(true)
        applyState()
    }

    override fun onStop() {
        super.onStop()
        app.onDataChanged = null
        try {
            widgetHost.stopListening()
        } catch (e: Exception) {
            // nothing to stop
        }
        controlCenter.hide(false)
        library.hideKeyboard()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pressing Home while the launcher is already showing returns to the first home page.
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            library.hideKeyboard()
            library.resetSearch()
            folderView.hide(false)
            controlCenter.hide(false)
            setEditMode(false)
            if (pager.currentItem != adapter.lead) pager.setCurrentItem(adapter.lead, s.animations)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // A launcher never finishes itself on Back; it only unwinds its own state.
        when {
            controlCenter.isOpen -> controlCenter.hide(s.animations)
            folderView.openId != null -> folderView.hide(s.animations && s.folderAnim)
            editMode -> setEditMode(false)
            pager.currentItem == adapter.libraryIndex && library.hasQuery() -> library.resetSearch()
            pager.currentItem != adapter.lead -> pager.setCurrentItem(adapter.lead, s.animations)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_WIDGET_BIND -> {
                val info = if (pendingWidgetId >= 0) widgetManager.getAppWidgetInfo(pendingWidgetId) else null
                if (resultCode == RESULT_OK && info != null) afterWidgetBound(pendingWidgetId, info) else cancelPendingWidget()
            }
            REQUEST_WIDGET_CONFIGURE -> {
                if (resultCode == RESULT_OK && pendingWidgetId >= 0) commitWidget(pendingWidgetId) else cancelPendingWidget()
            }
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

        widgetsPage = WidgetsPageView(this, this)

        adapter = HomePagerAdapter(this, app, library, this)
        adapter.widgetsView = widgetsPage
        pager = ViewPager(this)
        pager.offscreenPageLimit = 2
        pager.adapter = adapter
        pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                updateBottomBar(position + positionOffset)
            }

            override fun onPageSelected(position: Int) {
                if (position == adapter.libraryIndex) setEditMode(false)
                if (position != adapter.libraryIndex) library.hideKeyboard()
                preloadIcons()
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
            onGesture(Gestures.GESTURE_LONG_PRESS)
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

        // Edit-mode bar (Settings / Widgets / Done), shown in the reserved strip above the first row of icons.
        editBar = LinearLayout(this)
        editBar.orientation = LinearLayout.HORIZONTAL
        editBar.gravity = Gravity.CENTER_VERTICAL
        editBar.setPadding(dp(12), dp(2), dp(12), 0)
        editBar.visibility = View.GONE
        val settingsButton = pillButton("Settings")
        settingsButton.setOnClickListener { onOpenSettings() }
        val widgetsButton = pillButton("Widgets")
        widgetsButton.setOnClickListener { showWidgetPicker() }
        val doneButton = pillButton("Done")
        doneButton.setOnClickListener { setEditMode(false) }
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        editBar.addView(settingsButton, LinearLayout.LayoutParams(wrap, wrap))
        val gap = LinearLayout.LayoutParams(wrap, wrap)
        gap.leftMargin = dp(8)
        editBar.addView(widgetsButton, gap)
        editBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        editBar.addView(doneButton, LinearLayout.LayoutParams(wrap, wrap))

        folderView = FolderView(this)
        folderView.onLaunch = { entry, view -> onLaunch(entry, view) }
        folderView.onItemDrag = { entry, view, folderId -> beginDrag(entry.key, view, SRC_FOLDER, folderId) }
        folderView.onRename = { id, name -> app.edit { renameFolder(id, name) } }

        controlCenter = ControlCenterView(this, this)

        root = FrameLayout(this)
        val match = FrameLayout.LayoutParams.MATCH_PARENT
        root.addView(pager, FrameLayout.LayoutParams(match, match))
        root.addView(
            bottomBar,
            FrameLayout.LayoutParams(match, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        )
        root.addView(
            editBar,
            FrameLayout.LayoutParams(match, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        )
        root.addView(folderView, FrameLayout.LayoutParams(match, match))
        root.addView(controlCenter, FrameLayout.LayoutParams(match, match))
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
                if (pinchStart > 0f && pinchLast / pinchStart < 0.75f) enterEditMode()
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

    // ---------------------------------------------------------------- state -> views

    /** Recomputes the grid from the current size and settings and pushes data into the views that changed. */
    fun applyState() {
        val model = app.model
        val dark = s.isDark()
        if (lastDark != dark) {
            lastDark = dark
            library.applyAppearance(dark)
            folderView.applyAppearance(dark)
        }
        dock.applyStyle(s.dockOpacity, s.dockRadius, dark)

        val librarySig = app.appsVersion.toString() + "|" + app.loaded + "|" + model.hidden.joinToString(",") +
            "|" + model.recents.joinToString(",") + "|" + app.icons.namespace
        if (librarySig != lastLibrarySig) {
            lastLibrarySig = librarySig
            val visibleApps = app.apps.filter { it.key !in model.hidden }
            val recentApps = model.recents.mapNotNull { app.appsByKey[it] }
            library.setApps(visibleApps, recentApps, app.loaded)
        }

        installTransition()

        val w = pager.width
        val h = pager.height
        if (w <= 0 || h <= 0) return

        val sidePad = dp(s.iconSpacing)
        val cols = s.columns
        val cellW = (w - 2 * sidePad) / cols
        val iconPx = minOf(dpf(60f) * s.iconScale(), cellW * 0.8f).toInt().coerceAtLeast(dp(36))
        canonicalPx = iconPx
        library.canonicalPx = iconPx
        currentLabelSp = s.labelSize.toFloat()

        val slots = s.dockSlots
        val dockMargin = dp(12)
        val dockPadH = dp(10)
        val slotW = (w - 2 * dockMargin - 2 * dockPadH) / slots
        slotWidth = slotW
        val dockIcon = minOf(iconPx * s.dockIconPct / 100f, slotW * 0.8f).toInt().coerceAtLeast(dp(32))
        dock.minimumHeight = dockIcon + dp(24)
        val dockEntries = model.dock.mapNotNull { app.appsByKey[it] }
        val dockSig = model.dock.joinToString(",") + "|" + slotW + "|" + dockIcon + "|" + iconPx + "|" + editMode +
            "|" + s.animations + "|" + app.popKey + "|" + app.appsVersion + "|" + app.icons.namespace
        if (dockSig != lastDockSig) {
            lastDockSig = dockSig
            dock.bind(
                dockEntries, slotW, iconPx, dockIcon, app.icons, s.animations, editMode, app.popKey,
                { entry, view -> onLaunch(entry, view) },
                { entry, view -> beginDrag(entry.key, view, SRC_DOCK, null) },
                { entry -> onBadge(entry.key, true) }
            )
        }

        // The bottom bar height is only known after layout; a layout listener calls us again then.
        val reserve = bottomBar.height
        if (reserve <= 0) return

        val labelPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, currentLabelSp, resources.displayMetrics)
        val labelH = if (s.showLabels) (labelPx * 1.4f).toInt() + dp(5) else 0
        val cellH = iconPx + labelH + dp(12) + s.iconSpacing
        val topPad = dp(44)
        val fit = maxOf(1, (h - reserve - topPad) / cellH)
        val rows = if (s.rows > 0) minOf(s.rows, fit) else fit
        val capacity = cols * rows

        val lead = if (s.showWidgetsPage) 1 else 0
        val widgetSig = s.widgetIds.joinToString(",") + "|" + lead + "|" + app.appsVersion
        if (widgetSig != lastWidgetSig) {
            lastWidgetSig = widgetSig
            if (lead == 1) widgetsPage.refresh()
        }

        // Queue the icons of the visible page first, then neighbours, then everything else.
        preloadIcons(iconPx, capacity)

        val params = GridParams(cols, rows, iconPx, s.showLabels, currentLabelSp, sidePad, topPad, reserve, s.animations)
        adapter.update(model.home, params, lead, editMode)
        widgetsPage.setEditMode(editMode)
        dots.count = adapter.lead + adapter.homePages
        installSpeed()
        updateBottomBar(minOf(pager.currentItem, adapter.count - 1).toFloat())

        if (entrancePending && app.loaded && model.home.isNotEmpty()) {
            entrancePending = false
            if (s.animations && s.entranceAnim) playEntrance(currentHomePage())
        }

        val openId = folderView.openId
        if (openId != null) {
            val folder = model.folders[openId]
            if (folder == null || folder.apps.isEmpty()) folderView.hide(false) else showFolder(openId, false)
        }
    }

    private fun installTransition() {
        val enabled = s.animations && s.animQuality == 1
        val sig = s.transitionStyle.toString() + "|" + s.animIntensity + "|" + enabled
        if (sig == lastTransitionSig) return
        lastTransitionSig = sig
        Transitions.install(pager, s.transitionStyle, s.animIntensity / 100f) { s.animations && s.animQuality == 1 }
    }

    private fun installSpeed() {
        Transitions.installSpeed(pager, s.animSpeed / 100f)
    }

    /** Queues icon rendering in priority order so pages are ready before the user reaches them. */
    private fun preloadIcons(iconPx: Int = canonicalPx, capacity: Int = adapter.capacity()) {
        if (iconPx <= 0) return
        val model = app.model
        val home = model.home

        fun members(key: String): List<AppEntry> {
            if (key.startsWith(HomeModel.FOLDER_PREFIX)) {
                val folder = model.folders[key] ?: return emptyList()
                return folder.apps.take(9).mapNotNull { app.appsByKey[it] }
            }
            return listOfNotNull(app.appsByKey[key])
        }

        fun page(p: Int): List<AppEntry> {
            if (p < 0) return emptyList()
            val out = ArrayList<AppEntry>()
            val from = p * capacity
            val to = minOf(home.size, from + capacity)
            for (i in from until to) out.addAll(members(home[i]))
            return out
        }

        val current = maxOf(0, currentHomePage())
        app.icons.preload(page(current), iconPx, 0)
        app.icons.preload(model.dock.mapNotNull { app.appsByKey[it] }, iconPx, 0)
        val level = s.preloadLevel
        if (level >= 1) {
            app.icons.preload(page(current + 1), iconPx, 1)
            app.icons.preload(page(current - 1), iconPx, 1)
        }
        if (level >= 2) {
            val pages = (home.size + capacity - 1) / capacity
            for (p in 0 until pages) app.icons.preload(page(p), iconPx, 2)
            app.icons.preload(app.apps.filter { it.key !in model.hidden }, iconPx, 3)
        }
    }

    private fun playEntrance(page: Int) {
        if (page < 0) return
        adapterEntrance(page)
    }

    private fun adapterEntrance(page: Int) {
        val grid = adapter.gridAt(page) ?: return
        for (i in 0 until grid.childCount) {
            val v = grid.getChildAt(i)
            v.alpha = 0f
            v.scaleX = 0.8f
            v.scaleY = 0.8f
            v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay(minOf(i * 12L, 240L)).setDuration(s.dur(240L))
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun currentHomePage(): Int {
        val p = pager.currentItem - adapter.lead
        return if (p >= 0 && p < adapter.homePages) p else -1
    }

    /** Dock, search pill and dots fade out as the App Library page slides in. */
    private fun updateBottomBar(pos: Float) {
        val last = (adapter.libraryIndex - 1).toFloat()
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
        val smooth = s.animations
        pager.setCurrentItem(adapter.libraryIndex, smooth)
        pager.postDelayed({ library.focusSearch() }, if (smooth) s.dur(300L) else 50L)
    }

    // ---------------------------------------------------------------- edit mode, gestures

    private fun setEditMode(on: Boolean) {
        if (editMode == on) return
        editMode = on
        val animate = s.animations
        adapter.setEditMode(on, animate)
        dock.setEditMode(on, animate)
        widgetsPage.setEditMode(on)
        editBar.animate().cancel()
        if (on) {
            editBar.visibility = View.VISIBLE
            if (animate) {
                editBar.alpha = 0f
                editBar.animate().alpha(1f).setStartDelay(0L).setDuration(s.dur(180L)).start()
            } else {
                editBar.alpha = 1f
            }
        } else if (animate) {
            editBar.animate().alpha(0f).setStartDelay(0L).setDuration(s.dur(140L)).withEndAction {
                editBar.visibility = View.GONE
            }.start()
        } else {
            editBar.visibility = View.GONE
        }
        if (on && s.haptics) root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun enterEditMode() {
        if (pager.currentItem != adapter.libraryIndex && folderView.openId == null) setEditMode(true)
    }

    override fun onGesture(gesture: Int) {
        perform(Gestures.actionFor(s, gesture))
    }

    private fun perform(action: Int) {
        when (action) {
            Gestures.SEARCH -> openLibraryWithKeyboard()
            Gestures.LIBRARY -> pager.setCurrentItem(adapter.libraryIndex, s.animations)
            Gestures.CONTROL_CENTER -> openControlCenter()
            Gestures.EDIT_MODE -> enterEditMode()
            Gestures.LAUNCHER_SETTINGS -> onOpenSettings()
            Gestures.ANDROID_SETTINGS -> startSystem(Intent(Settings.ACTION_SETTINGS))
        }
    }

    /**
     * Observes touches without consuming them: swipes on the home screen and from the top-right corner run the
     * actions chosen in settings, and pinching in enters edit mode.
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
                        swipeKind = SWIPE_TOP_RIGHT
                    } else if (currentHomePage() >= 0 && ev.y < bottomBar.top) {
                        swipeKind = SWIPE_HOME
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> swipeKind = SWIPE_NONE
            MotionEvent.ACTION_CANCEL -> swipeKind = SWIPE_NONE
            MotionEvent.ACTION_UP -> {
                val dy = ev.y - downY
                val dx = ev.x - downX
                if (swipeKind == SWIPE_HOME && dy > dp(110) && abs(dx) < dy * 0.5f) {
                    onGesture(Gestures.GESTURE_SWIPE_DOWN)
                } else if (swipeKind == SWIPE_HOME && dy < -dp(110) && abs(dx) < -dy * 0.5f) {
                    onGesture(Gestures.GESTURE_SWIPE_UP)
                } else if (swipeKind == SWIPE_TOP_RIGHT && dy > dp(70) && abs(dx) < dy) {
                    onGesture(Gestures.GESTURE_TOP_RIGHT)
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

    private fun movementAnimated(): Boolean = s.animations && s.iconMovementAnim

    private fun tick() {
        if (s.haptics) root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    override fun onStartDrag(itemKey: String, view: View, source: Int) {
        beginDrag(itemKey, view, source, null)
    }

    private fun beginDrag(key: String, view: View, source: Int, folderId: String?) {
        if (drag != null) return
        if (s.haptics) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val state = DragState(key, source, view, rectInRoot(view), folderId)
        state.dragIdx = app.model.home.indexOf(key)
        drag = state
        val description = ClipDescription("item", arrayOf("application/x-ioshome-item"))
        val clip = ClipData(description, ClipData.Item(key))
        val started = view.startDragAndDrop(clip, LiftShadow(view), null, 0)
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
        // Dragging an app that lives in an open folder: reorder inside the panel, or leave it.
        if (d.source == SRC_FOLDER && !d.leftFolder) {
            if (rectInRoot(folderView.panelView).contains(x.toInt(), y.toInt())) {
                previewFolder(d, x, y)
                return
            }
            d.leftFolder = true
            resetPreview(d, true)
            folderView.hide(s.animations && s.folderAnim)
        }

        // Hovering at the left or right screen edge flips home pages after a short delay.
        val edge = dp(36)
        val dir = if (x < edge) -1 else if (x > root.width - edge) 1 else 0
        if (dir != d.edgeDir) {
            d.edgeDir = dir
            root.removeCallbacks(edgeRunnable)
            if (dir != 0) root.postDelayed(edgeRunnable, 650L)
        }

        if (isInDockZone(y)) {
            previewDock(d, x)
        } else {
            previewGrid(d, x, y)
        }
    }

    private fun resetPreview(d: DragState, animate: Boolean) {
        preview.reset(animate && movementAnimated())
        d.previewZone = ZONE_NONE
        d.insertIdx = -1
        d.previewPage = -1
        clearDwell(d)
    }

    private fun clearDwell(d: DragState) {
        d.dwell?.cancel()
        d.dwell = null
        val ready = d.readyRunnable
        if (ready != null) root.removeCallbacks(ready)
        d.readyRunnable = null
        d.hoverView?.releaseDwell()
        d.hoverView = null
        d.hoverKey = null
        d.folderReady = false
    }

    private fun previewFolder(d: DragState, x: Float, y: Float) {
        val folderId = d.folderId ?: return
        val folder = app.model.folders[folderId] ?: return
        val grid = folderView.gridView
        val rect = rectInRoot(grid)
        val cols = grid.columns
        val cellW = grid.cellWidth()
        val cellH = grid.cellHeight()
        if (cellW <= 0f || cellH <= 0f) return
        val col = ((x - rect.left - grid.paddingLeft) / cellW).toInt().coerceIn(0, cols - 1)
        val row = ((y - rect.top - grid.paddingTop) / cellH).toInt().coerceIn(0, grid.rows - 1)
        val idx = (row * cols + col).coerceIn(0, folder.apps.size - 1)
        if (idx != d.folderInsert) {
            d.folderInsert = idx
            preview.applyGrid(grid, 0, folder.apps, d.key, idx, movementAnimated())
            tick()
        }
    }

    private fun previewDock(d: DragState, x: Float) {
        if (d.previewZone != ZONE_DOCK) {
            resetPreview(d, true)
            d.previewZone = ZONE_DOCK
        }
        if (d.key !in app.model.dock && app.model.dock.size >= s.dockSlots) return
        val idx = dockIndexAt(x, d)
        if (idx != d.insertIdx) {
            d.insertIdx = idx
            preview.applyDock(dock, app.model.dock, d.key, idx, slotWidth, movementAnimated())
            tick()
        }
    }

    private fun previewGrid(d: DragState, x: Float, y: Float) {
        val page = currentHomePage()
        val grid = if (page >= 0) adapter.gridAt(page) else null
        if (grid == null) {
            resetPreview(d, true)
            return
        }
        if (d.previewZone != ZONE_GRID || d.previewPage != page) {
            resetPreview(d, true)
            d.previewZone = ZONE_GRID
            d.previewPage = page
        }
        val home = app.model.home
        val hit = gridHit(grid, page, x, y) ?: return

        // Lingering over the centre of another icon marks it as a folder target; the icon grows gradually.
        val candidate = if (app.model.isFolder(d.key)) null else viewUnderPointer(grid, x, y, d.key)
        updateDwell(d, candidate)
        if (candidate != null) return

        val baseCount = home.size - (if (d.dragIdx >= 0) 1 else 0)
        val idx = minOf(hit.flatIndex, baseCount)
        if (idx != d.insertIdx) {
            d.insertIdx = idx
            preview.applyGrid(grid, page, home, d.key, idx, movementAnimated())
            tick()
        }
    }

    private fun updateDwell(d: DragState, candidate: AppIconView?) {
        val key = candidate?.itemKey
        if (key == d.hoverKey) return
        clearDwell(d)
        if (candidate == null || key == null) return
        d.hoverKey = key
        d.hoverView = candidate
        val ready = Runnable {
            if (drag === d && d.hoverKey == key) {
                d.folderReady = true
                if (s.haptics) root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
        d.readyRunnable = ready
        root.postDelayed(ready, 450L)
        if (s.animations) {
            val anim = ValueAnimator.ofFloat(0f, 1f)
            anim.duration = 450L
            anim.addUpdateListener { animator -> candidate.setDwellProgress(animator.animatedFraction) }
            d.dwell = anim
            anim.start()
        } else {
            candidate.setDwellProgress(1f)
        }
    }

    private fun viewUnderPointer(grid: HomeGridView, x: Float, y: Float, dragKey: String): AppIconView? {
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i) as? AppIconView ?: continue
            if (child.itemKey == dragKey) continue
            val r = rectInRoot(child)
            if (abs(x - r.exactCenterX()) < r.width() * 0.28f && abs(y - r.exactCenterY()) < r.height() * 0.28f) return child
        }
        return null
    }

    private fun onEdgeTimer() {
        val d = drag ?: return
        if (d.edgeDir == 0) return
        val target = pager.currentItem + d.edgeDir
        if (target >= adapter.lead && target < adapter.libraryIndex) {
            pager.setCurrentItem(target, true)
            resetPreview(d, false)
        }
        root.postDelayed(edgeRunnable, 650L)
    }

    private fun isInDockZone(y: Float): Boolean {
        if (bottomBar.visibility != View.VISIBLE) return false
        return y >= rectInRoot(dock).top - dp(16)
    }

    private fun gridHit(grid: HomeGridView, page: Int, x: Float, y: Float): GridHit? {
        val rect = rectInRoot(grid)
        val cols = grid.columns
        val rows = grid.rows
        val cellW = grid.cellWidth()
        val cellH = grid.cellHeight()
        if (cellW <= 0f || cellH <= 0f) return null
        val col = ((x - rect.left - grid.paddingLeft) / cellW).toInt().coerceIn(0, cols - 1)
        val row = ((y - rect.top - grid.paddingTop) / cellH).toInt().coerceIn(0, rows - 1)
        return GridHit(page * cols * rows + row * cols + col)
    }

    /** Insertion index in the dock (its final position once the dragged app is removed and re-inserted). */
    private fun dockIndexAt(x: Float, d: DragState): Int {
        val baseCount = app.model.dock.size - (if (d.key in app.model.dock) 1 else 0)
        if (slotWidth <= 0) return baseCount
        val rect = rectInRoot(dock)
        val start = rect.exactCenterX() - (baseCount + 1) * slotWidth / 2f
        return ((x - start) / slotWidth).toInt().coerceIn(0, baseCount)
    }

    private fun onDrop(d: DragState, x: Float, y: Float) {
        root.removeCallbacks(edgeRunnable)
        val model = app.model

        // Released where it started: treat as a long-press and show the item's menu instead.
        if (d.origin.contains(x.toInt(), y.toInt()) && !d.folderReady) {
            d.pendingMenu = true
            return
        }

        // Rearranged inside its own open folder.
        if (d.source == SRC_FOLDER && !d.leftFolder) {
            val folderId = d.folderId ?: return
            if (d.folderInsert >= 0) {
                markPop(d.key)
                val index = d.folderInsert
                app.edit { moveInFolder(folderId, d.key, index) }
                if (s.haptics) root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            return
        }

        if (isInDockZone(y)) {
            val slots = s.dockSlots
            if (model.isFolder(d.key)) {
                toast("Folders can't go in the dock")
            } else if (d.key !in model.dock && model.dock.size >= slots) {
                toast("The dock is full")
            } else {
                val index = if (d.previewZone == ZONE_DOCK && d.insertIdx >= 0) d.insertIdx else dockIndexAt(x, d)
                markPop(d.key)
                app.edit { moveToDockIndex(d.key, index, slots) }
                if (s.haptics) root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            return
        }

        val page = currentHomePage()
        val grid = if (page >= 0) adapter.gridAt(page) else null
        if (grid == null) return
        val hit = gridHit(grid, page, x, y) ?: return
        val hover = d.hoverKey
        if (d.folderReady && hover != null) {
            val folderId = if (model.isFolder(hover)) hover else model.peekNextFolderId()
            markPop(folderId)
            app.edit { mergeIntoFolder(d.key, hover) }
            if (s.haptics) root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        } else {
            val baseCount = model.home.size - (if (d.dragIdx >= 0) 1 else 0)
            val index = if (d.previewZone == ZONE_GRID && d.insertIdx >= 0) d.insertIdx else minOf(hit.flatIndex, baseCount)
            markPop(d.key)
            app.edit { moveToHomeIndex(d.key, index) }
            if (s.haptics) root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun endDrag() {
        val d = drag ?: return
        drag = null
        root.removeCallbacks(edgeRunnable)
        clearDwell(d)
        preview.reset(false)
        d.view.visibility = View.VISIBLE
        if (d.pendingMenu) {
            if (d.source == SRC_FOLDER && d.folderId != null) {
                val entry = app.appsByKey[d.key]
                if (entry != null) onFolderItemMenu(entry, d.view, d.folderId)
            } else {
                showItemMenu(d.key, d.view)
            }
        }
    }

    // ---------------------------------------------------------------- HomeCallbacks

    override fun onLaunch(entry: AppEntry, from: View) {
        if (editMode) return
        library.hideKeyboard()
        try {
            val rect = Rect()
            from.getGlobalVisibleRect(rect)
            val animate = s.animations && s.launchAnim
            val options = if (animate && from.width > 0 && from.height > 0) {
                ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height).toBundle()
            } else {
                null
            }
            app.repo.launch(entry, rect, options)
            app.editQuiet { recordLaunch(entry.key) }
            if (animate) {
                // The home screen shrinks and fades as the app opens, like iOS.
                pager.animate().cancel()
                pager.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.75f).setStartDelay(0L).setDuration(s.dur(220L))
                    .setInterpolator(AccelerateDecelerateInterpolator()).start()
                pager.postDelayed({ resetPagerTransform(false) }, 1500L)
            }
        } catch (e: Exception) {
            toast("Can't open " + entry.label)
            app.reload()
        }
    }

    private fun resetPagerTransform(zoomIn: Boolean) {
        pager.animate().cancel()
        if (zoomIn && s.animations && s.launchAnim) {
            pager.scaleX = 1.05f
            pager.scaleY = 1.05f
            pager.alpha = 0.75f
            pager.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(0L).setDuration(s.dur(240L))
                .setInterpolator(DecelerateInterpolator()).start()
        } else {
            pager.scaleX = 1f
            pager.scaleY = 1f
            pager.alpha = 1f
        }
    }

    override fun onOpenFolder(folderId: String) {
        showFolder(folderId, true)
    }

    private fun showFolder(id: String, animate: Boolean) {
        val folder = app.model.folders[id] ?: return
        val entries = folder.apps.mapNotNull { app.appsByKey[it] }
        val px = if (canonicalPx > 0) canonicalPx else dp(56)
        folderView.show(
            id, folder.name, entries, px, px, currentLabelSp, app.icons,
            animate && s.animations && s.folderAnim, s.animations
        )
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

    private fun markPop(key: String) {
        app.popKey = key
        root.postDelayed({ app.popKey = null }, 700L)
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
        val slots = s.dockSlots
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
        items.add(MenuEntry("Change Icon\u2026") { openIconCustomizer(entry) })
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
        items.add(MenuEntry("Change Icon\u2026") { openIconCustomizer(entry) })
        items.add(MenuEntry("Hide App") { hideApp(entry) })
        items.add(MenuEntry("App Info") { openAppInfo(entry) })
        items.add(MenuEntry("Uninstall\u2026") { uninstall(entry) })
        showMenu(anchor, items)
    }

    override fun onLibraryMenu(entry: AppEntry, anchor: View) {
        val model = app.model
        val slots = s.dockSlots
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
        items.add(MenuEntry("Change Icon\u2026") { openIconCustomizer(entry) })
        items.add(MenuEntry("Hide App") { hideApp(entry) })
        items.add(MenuEntry("App Info") { openAppInfo(entry) })
        items.add(MenuEntry("Uninstall\u2026") { uninstall(entry) })
        showMenu(anchor, items)
    }

    override fun onOpenSettings() {
        if (s.haptics) root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun openIconCustomizer(entry: AppEntry) {
        val intent = Intent(this, SettingsActivity::class.java)
        intent.putExtra(SettingsActivity.EXTRA_CUSTOMIZE_ICON, entry.key)
        startActivity(intent)
    }

    private fun hideApp(entry: AppEntry) {
        app.edit { hide(entry.key) }
        toast("Hidden. Unhide it in Settings > Hidden apps")
    }

    private fun showMenu(anchor: View, items: List<MenuEntry>) {
        if (s.haptics) anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
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

    // ---------------------------------------------------------------- widgets

    fun currentWidgetIds(): List<Int> = s.widgetIds

    fun createWidgetView(id: Int): View? {
        val info = widgetManager.getAppWidgetInfo(id)
        if (info == null) {
            // The provider was uninstalled: forget the widget.
            widgetHost.deleteAppWidgetId(id)
            s.widgetIds = s.widgetIds.filter { it != id }
            return null
        }
        return try {
            widgetHost.createView(this, id, info)
        } catch (e: Exception) {
            null
        }
    }

    fun widgetMinHeightPx(id: Int): Int = widgetManager.getAppWidgetInfo(id)?.minHeight ?: dp(110)

    fun reportWidgetSize(view: View, widthPx: Int, heightPx: Int) {
        val hostView = view as? AppWidgetHostView ?: return
        val density = resources.displayMetrics.density
        val w = (widthPx / density).toInt()
        val h = (heightPx / density).toInt()
        try {
            hostView.updateAppWidgetSize(null, w, h, w, h)
        } catch (e: Exception) {
            // the provider keeps its default layout
        }
    }

    fun removeWidget(id: Int) {
        try {
            widgetHost.deleteAppWidgetId(id)
        } catch (e: Exception) {
            // already gone
        }
        s.widgetIds = s.widgetIds.filter { it != id }
        lastWidgetSig = ""
        applyState()
    }

    fun showWidgetPicker() {
        val providers = widgetManager.installedProviders
            .filter { (it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) != 0 }
            .sortedBy { it.loadLabel(packageManager).lowercase() }
        if (providers.isEmpty()) {
            toast("No widgets are available on this device")
            return
        }
        val labels = providers.map {
            it.loadLabel(packageManager) + "  (" + it.minWidth.toString() + " x " + it.minHeight.toString() + " px)"
        }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Add a widget")
            .setItems(labels) { _, which -> startAddWidget(providers[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startAddWidget(info: AppWidgetProviderInfo) {
        val id = widgetHost.allocateAppWidgetId()
        pendingWidgetId = id
        if (widgetManager.bindAppWidgetIdIfAllowed(id, info.provider)) {
            afterWidgetBound(id, info)
        } else {
            // Android asks the user to approve this launcher binding that widget.
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            try {
                startActivityForResult(intent, REQUEST_WIDGET_BIND)
            } catch (e: Exception) {
                cancelPendingWidget()
                toast("This widget can't be added")
            }
        }
    }

    private fun afterWidgetBound(id: Int, info: AppWidgetProviderInfo) {
        if (info.configure != null) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQUEST_WIDGET_CONFIGURE, null)
            } catch (e: Exception) {
                cancelPendingWidget()
                toast("This widget can't be configured")
            }
        } else {
            commitWidget(id)
        }
    }

    private fun commitWidget(id: Int) {
        pendingWidgetId = -1
        s.widgetIds = s.widgetIds + id
        s.showWidgetsPage = true
        lastWidgetSig = ""
        applyState()
        pager.post { pager.setCurrentItem(0, s.animations) }
    }

    private fun cancelPendingWidget() {
        if (pendingWidgetId >= 0) {
            try {
                widgetHost.deleteAppWidgetId(pendingWidgetId)
            } catch (e: Exception) {
                // nothing to release
            }
        }
        pendingWidgetId = -1
    }

    // ---------------------------------------------------------------- Control Center and system helpers

    fun openControlCenter() {
        library.hideKeyboard()
        if (s.haptics) root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        controlCenter.show(s.animations)
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
        const val REQUEST_WIDGET_BIND = 1002
        const val REQUEST_WIDGET_CONFIGURE = 1003
        const val WIDGET_HOST_ID = 1024
        const val SWIPE_NONE = 0
        const val SWIPE_HOME = 1
        const val SWIPE_TOP_RIGHT = 2
        const val ZONE_NONE = 0
        const val ZONE_GRID = 1
        const val ZONE_DOCK = 2
    }
}
