package app.ioshome.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** The launcher's settings: organised sections with controls that apply to the home screen immediately. */
class SettingsActivity : Activity() {

    private class Screen(val title: String, val build: () -> Unit)

    private class PackRow(val info: IconPackManager.PackInfo, val iconCount: Int, val thumbs: List<Drawable>)

    private lateinit var app: LauncherApp
    private lateinit var titleView: TextView
    private lateinit var backView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private val stack = ArrayList<Screen>()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var packRows: List<PackRow>? = null
    private var packsLoading = false
    private var preview: TransitionPreview? = null

    private val s: LauncherSettings get() = app.settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as LauncherApp

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(8), dp(8), dp(16), dp(8))
        backView = TextView(this)
        backView.text = "\u2039"
        backView.setTextColor(ACCENT)
        backView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
        backView.gravity = Gravity.CENTER
        backView.contentDescription = "Back"
        backView.setOnClickListener { goBack() }
        bar.addView(backView, LinearLayout.LayoutParams(dp(48), dp(48)))
        titleView = TextView(this)
        titleView.setTextColor(Color.WHITE)
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        bar.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        scroll = ScrollView(this)
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(12), dp(4), dp(12), dp(32))
        scroll.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        push("Settings") { screenMain() }

        val customize = intent.getStringExtra(EXTRA_CUSTOMIZE_ICON)
        if (customize != null && savedInstanceState == null) {
            val entry = app.appsByKey[customize]
            if (entry != null) {
                push("Individual icons") { screenIconList() }
                showIconOptions(entry)
            }
        }
    }

    override fun onDestroy() {
        preview?.stop()
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        goBack()
    }

    // ---------------------------------------------------------------- navigation

    private fun push(title: String, build: () -> Unit) {
        stack.add(Screen(title, build))
        render(true)
    }

    private fun goBack() {
        if (stack.size > 1) {
            stack.removeAt(stack.size - 1)
            render(true)
        } else {
            finish()
        }
    }

    /** Rebuilds the visible screen; keeps the scroll position when only a value changed. */
    private fun render(resetScroll: Boolean = false) {
        val top = stack.lastOrNull() ?: return
        val y = scroll.scrollY
        preview?.stop()
        preview = null
        content.removeAllViews()
        titleView.text = top.title
        backView.visibility = if (stack.size > 1) View.VISIBLE else View.INVISIBLE
        top.build()
        scroll.post { scroll.scrollTo(0, if (resetScroll) 0 else y) }
    }

    // ---------------------------------------------------------------- building blocks

    private fun label(text: String, sp: Float, color: Int): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(color)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        return tv
    }

    private fun spaced(top: Int = 0): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(top)
        lp.bottomMargin = dp(1)
        return lp
    }

    private fun addHeader(text: String) {
        val tv = label(text.uppercase(), 12f, SUB)
        tv.setPadding(dp(8), dp(20), dp(8), dp(6))
        content.addView(tv)
    }

    private fun addInfo(text: String) {
        val tv = label(text, 13f, SUB)
        tv.setPadding(dp(8), dp(8), dp(8), dp(8))
        content.addView(tv)
    }

    private fun addRow(title: String, sub: String?, trailing: View?, onClick: (() -> Unit)?): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setBackgroundColor(CARD)
        row.setPadding(dp(16), dp(12), dp(16), dp(12))
        row.minimumHeight = dp(52)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(label(title, 16f, Color.WHITE))
        if (sub != null) col.addView(label(sub, 13f, SUB))
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) row.addView(trailing)
        if (onClick != null) {
            row.isClickable = true
            row.setOnClickListener { onClick() }
        }
        content.addView(row, spaced())
        return row
    }

    private fun addNav(title: String, sub: String?, build: () -> Unit) {
        val arrow = label("\u203A", 24f, SUB)
        addRow(title, sub, arrow) { push(title, build) }
    }

    private fun addAction(title: String, sub: String?, onClick: () -> Unit) {
        addRow(title, sub, null, onClick)
    }

    private fun addSwitch(title: String, sub: String?, get: () -> Boolean, set: (Boolean) -> Unit) {
        val sw = Switch(this)
        sw.isChecked = get()
        sw.setOnCheckedChangeListener { _, checked -> set(checked) }
        addRow(title, sub, sw) { sw.toggle() }
    }

    private fun addChoice(title: String, options: Array<String>, get: () -> Int, set: (Int) -> Unit) {
        val current = get().coerceIn(0, options.size - 1)
        addRow(title, options[current], label("\u203A", 24f, SUB)) {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setSingleChoiceItems(options, current) { dialog, which ->
                    set(which)
                    dialog.dismiss()
                    render()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun addSlider(title: String, min: Int, max: Int, get: () -> Int, set: (Int) -> Unit, format: (Int) -> String) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setBackgroundColor(CARD)
        box.setPadding(dp(16), dp(12), dp(16), dp(8))
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.addView(label(title, 16f, Color.WHITE), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val valueView = label(format(get()), 15f, SUB)
        head.addView(valueView)
        box.addView(head)
        val bar = SeekBar(this)
        bar.max = max - min
        bar.progress = get().coerceIn(min, max) - min
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = min + progress
                valueView.text = format(v)
                if (fromUser) set(v)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        box.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))
        content.addView(box, spaced())
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- screens

    private fun screenMain() {
        addHeader("Launcher")
        addNav("Home Screen", s.columns.toString() + " columns, " + (if (s.rows == 0) "auto rows" else s.rows.toString() + " rows")) { screenHome() }
        addNav("Icons and Themes", iconSummary()) { screenIcons() }
        addNav("Animations", if (s.animations) Transitions.NAMES[s.transitionStyle] else "Off") { screenAnimations() }
        addNav("Gestures", null) { screenGestures() }
        addNav("Dock", s.dockSlots.toString() + " icons") { screenDock() }
        addNav("Appearance", arrayOf("System", "Light", "Dark")[s.appearance]) { screenAppearance() }
        addNav("Performance", null) { screenPerformance() }
        addNav("Widgets", if (s.showWidgetsPage) "Widgets page on" else "Widgets page off") { screenWidgets() }
        addNav("Backup and Restore", null) { screenBackup() }
        addHeader("System")
        addAction("Default home app", if (isDefaultHome()) "This launcher is the default" else "Tap to make this launcher the default") {
            requestDefaultHome()
        }
        addAction("Hidden apps", app.model.hidden.size.toString() + " hidden") { showHiddenApps() }
        addNav("About", null) { screenAbout() }
    }

    private fun iconSummary(): String {
        val pack = s.iconPack
        if (pack.isEmpty()) return "System icons"
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pack, 0)).toString()
        } catch (e: Exception) {
            "System icons"
        }
    }

    private fun screenHome() {
        addHeader("Layout")
        addChoice(
            "Page layout",
            arrayOf("Keep current", "4 x 5", "4 x 6", "5 x 5", "5 x 6"),
            { 0 },
            { which ->
                when (which) {
                    1 -> { s.columns = 4; s.rows = 5 }
                    2 -> { s.columns = 4; s.rows = 6 }
                    3 -> { s.columns = 5; s.rows = 5 }
                    4 -> { s.columns = 5; s.rows = 6 }
                    else -> Unit
                }
            }
        )
        addSlider("Grid columns", 3, 6, { s.columns }, { s.columns = it }) { it.toString() }
        addSlider("Grid rows (0 = automatic)", 0, 8, { s.rows }, { s.rows = it }) { if (it == 0) "Auto" else it.toString() }
        addHeader("Icons")
        addSlider("Icon size", 70, 130, { s.iconScalePct }, { s.iconScalePct = it }) { it.toString() + "%" }
        addSlider("Icon spacing", 0, 24, { s.iconSpacing }, { s.iconSpacing = it }) { it.toString() + " dp" }
        addSwitch("Show icon labels", null, { s.showLabels }, { s.showLabels = it })
        addSlider("Label size", 9, 14, { s.labelSize }, { s.labelSize = it }) { it.toString() + " sp" }
        addInfo("Changes appear on the home screen as soon as you go back to it.")
    }

    private fun screenIcons() {
        addHeader("Icon shape")
        addChoice(
            "Shape",
            arrayOf("iOS rounded square", "Keep each icon's own shape"),
            { s.iconShape },
            {
                s.iconShape = it
                app.applyIconConfig()
            }
        )
        addHeader("Icon packs")
        val noPack = addRow("System icons", "No icon pack", if (s.iconPack.isEmpty()) label("\u2713", 20f, ACCENT) else null) {
            s.iconPack = ""
            app.applyIconConfig()
            render()
        }
        noPack.minimumHeight = dp(52)
        val rows = packRows
        if (rows == null) {
            addInfo("Looking for installed icon packs...")
            loadPacks()
        } else if (rows.isEmpty()) {
            addInfo("No compatible icon packs are installed. Install an ADW, Nova, GO or Apex icon pack from your app store and it will appear here.")
        } else {
            for (row in rows) addPackRow(row)
        }
        addHeader("Customize")
        addNav("Individual icons", s.overrideKeys().size.toString() + " customized") { screenIconList() }
        if (s.overrideKeys().isNotEmpty()) {
            addAction("Restore all original icons", null) {
                s.clearOverrides()
                app.applyIconConfig()
                render()
                toast("Original icons restored")
            }
        }
    }

    private fun loadPacks() {
        if (packsLoading) return
        packsLoading = true
        io.execute {
            val found = app.packs.discover()
            val rows = ArrayList<PackRow>()
            for (info in found) {
                val pack = app.packs.pack(info.packageName)
                val names = pack?.allNames ?: emptyList()
                val thumbs = ArrayList<Drawable>()
                if (pack != null) {
                    for (name in names) {
                        if (thumbs.size >= 4) break
                        val d = pack.load(name)
                        if (d != null) thumbs.add(d)
                    }
                }
                if (pack != null) rows.add(PackRow(info, names.size, thumbs))
            }
            main.post {
                packRows = rows
                packsLoading = false
                if (!isFinishing) render()
            }
        }
    }

    private fun addPackRow(row: PackRow) {
        val thumbsBox = LinearLayout(this)
        thumbsBox.orientation = LinearLayout.HORIZONTAL
        thumbsBox.gravity = Gravity.CENTER_VERTICAL
        for (d in row.thumbs) {
            val iv = ImageView(this)
            iv.setImageDrawable(d)
            val lp = LinearLayout.LayoutParams(dp(30), dp(30))
            lp.leftMargin = dp(6)
            thumbsBox.addView(iv, lp)
        }
        if (s.iconPack == row.info.packageName) thumbsBox.addView(label("  \u2713", 20f, ACCENT))
        val line = addRow(row.info.label, row.iconCount.toString() + " icons", thumbsBox) {
            s.iconPack = row.info.packageName
            app.applyIconConfig()
            render()
            toast("Applying " + row.info.label)
        }
        val appIcon = ImageView(this)
        try {
            appIcon.setImageDrawable(packageManager.getApplicationIcon(row.info.packageName))
        } catch (e: Exception) {
            // no app icon to show
        }
        val lp = LinearLayout.LayoutParams(dp(40), dp(40))
        lp.rightMargin = dp(12)
        line.addView(appIcon, 0, lp)
    }

    private fun screenIconList() {
        addHeader("Tap an app to change its icon")
        val size = dp(44)
        for (entry in app.apps.filter { it.key !in app.model.hidden }) {
            val img = ImageView(this)
            val cached = app.icons.peek(entry, size)
            if (cached != null) {
                img.setImageBitmap(cached)
            } else {
                img.setImageBitmap(app.icons.placeholder(size))
                app.icons.request(entry, size, 3) { bmp -> img.setImageBitmap(bmp) }
            }
            val custom = s.getOverride(entry.key) != null
            val row = addRow(entry.label, if (custom) "Custom icon" else null, null) { showIconOptions(entry) }
            val iconParams = LinearLayout.LayoutParams(size, size)
            iconParams.rightMargin = dp(12)
            row.addView(img, 0, iconParams)
        }
    }

    private fun showIconOptions(entry: AppEntry) {
        val options = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (s.iconPack.isNotEmpty()) {
            options.add("Choose from the icon pack")
            actions.add { chooseFromPack(entry) }
        } else {
            options.add("Choose from an icon pack (select a pack first)")
            actions.add { toast("Select an icon pack under Icons and Themes first") }
        }
        if (s.getOverride(entry.key) != null) {
            options.add("Restore original icon")
            actions.add {
                s.setOverride(entry.key, null)
                app.applyIconConfig()
                toast("Original icon restored")
                render()
            }
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(entry.label)
            .setItems(options.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseFromPack(entry: AppEntry) {
        val packName = s.iconPack
        io.execute {
            val pack = app.packs.pack(packName)
            main.post {
                if (pack == null || isFinishing) {
                    toast("That icon pack could not be read")
                } else {
                    showPackGrid(entry, pack)
                }
            }
        }
    }

    private fun showPackGrid(entry: AppEntry, pack: IconPack) {
        val names = pack.allNames
        if (names.isEmpty()) {
            toast("This icon pack lists no icons")
            return
        }
        val grid = GridView(this)
        grid.numColumns = 4
        grid.setPadding(dp(8), dp(8), dp(8), dp(8))
        grid.adapter = object : BaseAdapter() {
            override fun getCount(): Int = names.size
            override fun getItem(position: Int): Any = names[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val iv = (convertView as? ImageView) ?: ImageView(this@SettingsActivity)
                iv.layoutParams = android.widget.AbsListView.LayoutParams(dp(64), dp(64))
                iv.setPadding(dp(8), dp(8), dp(8), dp(8))
                iv.setImageDrawable(pack.load(names[position]))
                return iv
            }
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Icons from " + pack.label)
            .setView(grid)
            .setNegativeButton("Cancel", null)
            .create()
        grid.setOnItemClickListener { _, _, position, _ ->
            s.setOverride(entry.key, pack.packageName + "|" + names[position])
            app.applyIconConfig()
            dialog.dismiss()
            toast("Icon changed")
            render()
        }
        dialog.show()
    }

    private fun screenAnimations() {
        val p = TransitionPreview(this)
        content.addView(p, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150)))
        preview = p
        p.configure(s.transitionStyle, s.animIntensity / 100f, s.animSpeed / 100f)
        p.start()

        addHeader("Page transitions")
        addSwitch("Animations", if (s.systemMotionEnabled()) null else "Android's animation scale is off, so animations stay off", { s.animationsPref }, {
            s.animationsPref = it
        })
        addChoice(
            "Transition style",
            Transitions.NAMES,
            { s.transitionStyle },
            {
                s.transitionStyle = it
            }
        )
        addSlider("Animation speed", 50, 200, { s.animSpeed }, {
            s.animSpeed = it
            p.configure(s.transitionStyle, s.animIntensity / 100f, s.animSpeed / 100f)
        }) { it.toString() + "%" }
        addSlider("Animation intensity", 30, 100, { s.animIntensity }, {
            s.animIntensity = it
            p.configure(s.transitionStyle, s.animIntensity / 100f, s.animSpeed / 100f)
        }) { it.toString() + "%" }
        addHeader("Other animations")
        addSwitch("Icon movement while dragging", null, { s.iconMovementAnim }, { s.iconMovementAnim = it })
        addSwitch("Folder open and close", null, { s.folderAnim }, { s.folderAnim = it })
        addSwitch("App launch and return", null, { s.launchAnim }, { s.launchAnim = it })
        addSwitch("Icons pop in at start", null, { s.entranceAnim }, { s.entranceAnim = it })
    }

    private fun screenGestures() {
        addHeader("Choose what each gesture does")
        val names = Gestures.NAMES
        addChoice("Swipe down on the home screen", names, { s.gestureSwipeDown }, { s.gestureSwipeDown = it })
        addChoice("Swipe up on the home screen", names, { s.gestureSwipeUp }, { s.gestureSwipeUp = it })
        addChoice("Double tap on empty space", names, { s.gestureDoubleTap }, { s.gestureDoubleTap = it })
        addChoice("Long press on empty space", names, { s.gestureLongPress }, { s.gestureLongPress = it })
        addChoice("Swipe down from the top-right corner", names, { s.gestureTopRight }, { s.gestureTopRight = it })
        addInfo("Pinching in always enters edit mode. Long-pressing an icon starts dragging it.")
    }

    private fun screenDock() {
        addSlider("Icons in the dock", 3, 6, { s.dockSlots }, {
            s.dockSlots = it
            app.reconcileNow()
        }) { it.toString() }
        addSlider("Dock icon size", 70, 120, { s.dockIconPct }, { s.dockIconPct = it }) { it.toString() + "%" }
        addSlider("Background opacity", 0, 100, { s.dockOpacity }, { s.dockOpacity = it }) { it.toString() + "%" }
        addSlider("Corner radius", 0, 40, { s.dockRadius }, { s.dockRadius = it }) { it.toString() + " dp" }
        addInfo("The dock uses a simple translucent tint instead of a live blur, which keeps scrolling smooth.")
    }

    private fun screenAppearance() {
        addChoice("Appearance", arrayOf("Follow system", "Light", "Dark"), { s.appearance }, { s.appearance = it })
        addAction("Change wallpaper", "Opens Android's wallpaper picker") {
            try {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Select wallpaper"))
            } catch (e: Exception) {
                toast("No wallpaper picker available")
            }
        }
        addSwitch("Show icon labels", null, { s.showLabels }, { s.showLabels = it })
        addNav("Layout customization", null) { screenHome() }
        addInfo("Light and dark change the dock, folders and App Library. Icon labels stay white so they read on any wallpaper.")
    }

    private fun screenPerformance() {
        addChoice(
            "Icon preloading",
            arrayOf("Visible page only", "Visible and neighbouring pages", "Everything, in the background"),
            { s.preloadLevel },
            { s.preloadLevel = it }
        )
        addChoice("Animation quality", arrayOf("Lighter (no page effects)", "Full"), { s.animQuality }, { s.animQuality = it })
        addChoice(
            "Icon cache size",
            arrayOf("Automatic", "16 MB", "32 MB", "64 MB"),
            { when (s.cacheMb) { 16 -> 1; 32 -> 2; 64 -> 3; else -> 0 } },
            {
                s.cacheMb = arrayOf(0, 16, 32, 64)[it]
                app.icons.clearAll()
                app.applyIconConfig()
            }
        )
        addAction("Clear icon cache", "Icons are drawn again the next time they are needed") {
            app.icons.clearAll()
            app.applyIconConfig()
            toast("Icon cache cleared")
        }
        addInfo(
            if (s.systemMotionEnabled()) {
                "Reduced motion: when Android's animation scale is set to off, all launcher animations turn off automatically."
            } else {
                "Android's animation scale is off, so launcher animations are disabled."
            }
        )
    }

    private fun screenWidgets() {
        addSwitch("Show widgets page", "A page before the first home page that holds Android widgets", { s.showWidgetsPage }, {
            s.showWidgetsPage = it
        })
        addInfo("To add a widget: go to the home screen, long-press empty space, and tap Widgets. In edit mode each widget shows a remove button.")
        addInfo(s.widgetIds.size.toString() + " widgets are on the page.")
    }

    private fun screenBackup() {
        addAction("Export backup", "Saves the layout, folders, hidden apps, recents, icon choices and settings to a file") {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "application/json"
            intent.putExtra(Intent.EXTRA_TITLE, "ios-home-launcher-backup.json")
            try {
                startActivityForResult(intent, REQUEST_EXPORT)
            } catch (e: Exception) {
                toast("No file manager available")
            }
        }
        addAction("Import backup", "Restores a backup file; apps that are not installed here are skipped") {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*"
            try {
                startActivityForResult(intent, REQUEST_IMPORT)
            } catch (e: Exception) {
                toast("No file manager available")
            }
        }
        addInfo("Backups are plain JSON files. A backup from a newer launcher version is refused instead of being half-restored.")
    }

    @Suppress("DEPRECATION")
    private fun screenAbout() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
        addRow("iOS Home Launcher", "Version " + version, null, null)
        addInfo(
            "Works fully offline: no ads, no analytics and no network permission. Extra permissions: one to show " +
                "Android's own uninstall prompt, and \"Modify system settings\", used only if you grant it and use the " +
                "brightness or rotation-lock controls in Control Center."
        )
        addInfo("An independent, iOS-inspired launcher. Not affiliated with Apple.")
    }

    private fun showHiddenApps() {
        val entries = app.model.hidden.mapNotNull { app.appsByKey[it] }
        val builder = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
        if (entries.isEmpty()) {
            builder.setTitle("Hidden apps")
                .setMessage("No hidden apps. Long-press an app and choose Hide App to hide it.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val labels = entries.map { it.label }.toTypedArray()
        builder.setTitle("Tap an app to unhide it")
            .setItems(labels) { _, which ->
                app.edit { unhide(entries[which].key) }
                toast("Unhidden")
                render()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---------------------------------------------------------------- default home app

    private fun isDefaultHome(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val info = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return info?.activityInfo?.packageName == packageName
    }

    private fun requestDefaultHome() {
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

    // ---------------------------------------------------------------- backup file results

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_HOME_ROLE) {
            render()
            return
        }
        val uri: Uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQUEST_EXPORT -> {
                val text = Backup.export(app)
                io.execute {
                    val ok = try {
                        contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
                    } catch (e: Exception) {
                        false
                    }
                    main.post { toast(if (ok) "Backup saved" else "Could not save the backup") }
                }
            }
            REQUEST_IMPORT -> {
                io.execute {
                    val text = try {
                        contentResolver.openInputStream(uri)?.use { stream ->
                            val bytes = stream.readBytes()
                            if (bytes.size > MAX_BACKUP_BYTES) null else String(bytes, Charsets.UTF_8)
                        }
                    } catch (e: Exception) {
                        null
                    }
                    main.post {
                        if (text == null) {
                            toast("Could not read that file")
                        } else {
                            val result = Backup.restore(app, text)
                            toast(result.message)
                            render()
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- live transition preview

    /** Two mini pages that repeatedly swap using the selected transition, speed and intensity. */
    private class TransitionPreview(context: Context) : FrameLayout(context) {
        private val pageA = View(context)
        private val pageB = View(context)
        private val colorsA = intArrayOf(Color.rgb(10, 132, 255), Color.rgb(255, 159, 10))
        private var style = Transitions.ZOOM
        private var intensity = 0.6f
        private var speed = 1f
        private var swapped = false
        private var animator: ValueAnimator? = null

        init {
            val a = GradientDrawable()
            a.cornerRadius = context.dpf(18f)
            a.setColor(colorsA[0])
            val b = GradientDrawable()
            b.cornerRadius = context.dpf(18f)
            b.setColor(colorsA[1])
            pageA.background = a
            pageB.background = b
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            lp.leftMargin = context.dp(40)
            lp.rightMargin = context.dp(40)
            lp.topMargin = context.dp(8)
            lp.bottomMargin = context.dp(8)
            addView(pageA, lp)
            addView(pageB, FrameLayout.LayoutParams(lp))
            clipChildren = true
            clipToPadding = true
        }

        fun configure(style: Int, intensity: Float, speed: Float) {
            this.style = style
            this.intensity = intensity
            this.speed = speed
            if (style == Transitions.DEPTH) pageA.bringToFront() else pageB.bringToFront()
            animator?.duration = (2200L / speed).toLong().coerceAtLeast(300L)
            place(0f)
        }

        fun start() {
            stop()
            val anim = ValueAnimator.ofFloat(0f, 1f)
            anim.duration = (2200L / speed).toLong().coerceAtLeast(300L)
            anim.interpolator = LinearInterpolator()
            anim.repeatCount = ValueAnimator.INFINITE
            anim.addUpdateListener { animator -> place(animator.animatedFraction) }
            anim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationRepeat(animation: Animator) {
                    swapped = !swapped
                    val bgA = pageA.background
                    pageA.background = pageB.background
                    pageB.background = bgA
                }
            })
            animator = anim
            anim.start()
        }

        fun stop() {
            animator?.cancel()
            animator = null
        }

        /** Hold, move with the pager's easing, hold. */
        private fun place(fraction: Float) {
            val moving = ((fraction - 0.2f) / 0.5f).coerceIn(0f, 1f)
            val t = Transitions.QuinticInterpolator.getInterpolation(moving)
            show(pageA, -t)
            show(pageB, 1f - t)
        }

        private fun show(page: View, position: Float) {
            Transitions.apply(style, page, position, intensity)
            page.translationX = page.translationX + position * page.width
        }
    }

    companion object {
        const val EXTRA_CUSTOMIZE_ICON = "customize_icon"
        private const val REQUEST_HOME_ROLE = 2001
        private const val REQUEST_EXPORT = 2002
        private const val REQUEST_IMPORT = 2003
        private const val MAX_BACKUP_BYTES = 5 * 1024 * 1024
        private val BG = Color.rgb(18, 18, 22)
        private val CARD = Color.rgb(34, 34, 40)
        private val SUB = Color.argb(165, 255, 255, 255)
        private val ACCENT = Color.rgb(10, 132, 255)
    }
}
