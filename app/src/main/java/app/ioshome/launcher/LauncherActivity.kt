package app.ioshome.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.app.role.RoleManager
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
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.viewpager.widget.ViewPager

class LauncherActivity : Activity(), HomeCallbacks {

    private class MenuEntry(val title: String, val action: () -> Unit)

    private lateinit var app: LauncherApp
    private lateinit var root: FrameLayout
    private lateinit var pager: ViewPager
    private lateinit var bottomBar: LinearLayout
    private lateinit var dots: PageDots
    private lateinit var pill: TextView
    private lateinit var dock: DockView
    private lateinit var library: LibraryView
    private lateinit var adapter: HomePagerAdapter
    private var applyPosted = false

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
        applyState()
    }

    override fun onStop() {
        super.onStop()
        app.onDataChanged = null
        library.hideKeyboard()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pressing Home while the launcher is already showing returns to the first page.
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            library.hideKeyboard()
            library.resetSearch()
            if (pager.currentItem != 0) pager.setCurrentItem(0, app.settings.animations)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // A launcher never finishes itself on Back; it only unwinds its own state.
        if (pager.currentItem == adapter.homePages && library.hasQuery()) {
            library.resetSearch()
        } else if (pager.currentItem != 0) {
            pager.setCurrentItem(0, app.settings.animations)
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

    private fun buildUi() {
        library = LibraryView(this, app)
        library.onLaunch = { entry, view -> onLaunch(entry, view) }
        library.onMenu = { entry, view -> onLibraryMenu(entry, view) }

        adapter = HomePagerAdapter(this, app, library, this)
        pager = ViewPager(this)
        pager.adapter = adapter
        pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                updateBottomBar(position + positionOffset)
            }

            override fun onPageSelected(position: Int) {
                if (position != adapter.homePages) library.hideKeyboard()
            }
        })

        dots = PageDots(this)

        pill = TextView(this)
        pill.text = "Search"
        pill.contentDescription = "Search apps"
        pill.setTextColor(Color.WHITE)
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        pill.gravity = Gravity.CENTER
        pill.setPadding(dp(32), dp(12), dp(32), dp(12))
        val pillBg = GradientDrawable()
        pillBg.setColor(Color.argb(60, 255, 255, 255))
        pillBg.cornerRadius = dpf(24f)
        pill.background = pillBg
        pill.isClickable = true
        pill.setOnClickListener { openLibraryWithKeyboard() }

        dock = DockView(this)
        dock.setPadding(dp(10), dp(12), dp(10), dp(12))
        dock.setOnLongClickListener {
            onOpenSettings()
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
        root.setOnApplyWindowInsetsListener { v, insets -> applyInsets(v, insets) }

        pager.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) scheduleApply()
        }
        bottomBar.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) scheduleApply()
        }

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
        library.setApps(app.apps, app.loaded)

        val w = pager.width
        val h = pager.height
        if (w <= 0 || h <= 0) return

        val s = app.settings
        val sidePad = dp(8)
        val cols = s.columns
        val cellW = (w - 2 * sidePad) / cols
        val iconPx = minOf(dpf(60f) * s.iconScale(), cellW * 0.78f).toInt().coerceAtLeast(dp(36))

        val slots = s.dockSlots
        val dockMargin = dp(12)
        val dockPadH = dp(10)
        val slotW = (w - 2 * dockMargin - 2 * dockPadH) / slots
        val dockIcon = minOf(iconPx.toFloat(), slotW * 0.78f).toInt().coerceAtLeast(dp(32))
        dock.minimumHeight = dockIcon + dp(24)
        val dockEntries = app.model.dock.mapNotNull { app.appsByKey[it] }
        dock.bind(
            dockEntries, slotW, dockIcon, app.icons, s.animations,
            { entry, view -> onLaunch(entry, view) },
            { entry, view -> onHomeMenu(entry, view, true) }
        )

        // The bottom bar height is only known after layout; a layout listener calls us again then.
        val reserve = bottomBar.height
        if (reserve <= 0) return

        val labelPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        val labelH = if (s.showLabels) (labelPx * 1.4f).toInt() + dp(5) else 0
        val cellH = iconPx + labelH + dp(20)
        val topPad = dp(10)
        val rows = maxOf(1, (h - reserve - topPad) / cellH)

        adapter.update(app.model.home, cols, rows, iconPx, s.showLabels, sidePad, topPad, reserve, s.animations)
        dots.count = adapter.homePages
        updateBottomBar(minOf(pager.currentItem, adapter.count - 1).toFloat())
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

    // ---------------------------------------------------------------- HomeCallbacks

    override fun onLaunch(entry: AppEntry, from: View) {
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
        } catch (e: Exception) {
            toast("Can't open " + entry.label)
            app.reload()
        }
    }

    override fun onHomeMenu(entry: AppEntry, anchor: View, inDock: Boolean) {
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
        items.add(MenuEntry("App Info") { openAppInfo(entry) })
        items.add(MenuEntry("Uninstall\u2026") { uninstall(entry) })
        showMenu(anchor, items)
    }

    override fun onOpenSettings() {
        if (app.settings.haptics) root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        SettingsDialog(this, app).show()
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
    }
}
