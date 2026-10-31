package app.ioshome.launcher

import android.app.AlertDialog
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Settings are shown as a simple dialog; each tap cycles or toggles a value and applies it immediately. */
class SettingsDialog(private val activity: LauncherActivity, private val app: LauncherApp) {

    fun show() {
        val s = app.settings
        val column = LinearLayout(activity)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(activity.dp(20), activity.dp(4), activity.dp(20), activity.dp(4))

        addRow(column, "Columns", { s.columns.toString() }) {
            s.columns = if (s.columns >= 6) 3 else s.columns + 1
            activity.applyState()
        }
        addRow(column, "Icon size", { SIZE_NAMES[s.iconSize] }) {
            s.iconSize = (s.iconSize + 1) % 3
            activity.applyState()
        }
        addRow(column, "Dock icons", { s.dockSlots.toString() }) {
            s.dockSlots = if (s.dockSlots >= 6) 3 else s.dockSlots + 1
            app.reconcileNow()
            activity.applyState()
        }
        addRow(column, "App labels", { onOff(s.showLabels) }) {
            s.showLabels = !s.showLabels
            activity.applyState()
        }
        addRow(column, "Animations", { onOff(s.animations) }) {
            s.animations = !s.animations
            activity.applyState()
        }
        addRow(column, "Haptic feedback", { onOff(s.haptics) }) {
            s.haptics = !s.haptics
        }
        addRow(column, "Change wallpaper", { "" }) {
            activity.openWallpaperPicker()
        }
        addRow(column, "Default home app", { if (activity.isDefaultHome()) "Yes" else "Set" }) {
            activity.requestDefaultHome()
        }
        addRow(column, "Reset home screen layout", { "" }) {
            AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Reset layout?")
                .setMessage("Apps return to their default positions. Nothing is uninstalled.")
                .setPositiveButton("Reset") { _, _ -> app.resetLayout() }
                .setNegativeButton("Cancel", null)
                .show()
        }
        addRow(column, "About", { versionName() }) {
            AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("iOS Home Launcher")
                .setMessage(
                    "Version " + versionName() + "\n\n" +
                        "Works fully offline. No ads, no analytics and no network permission. " +
                        "The only extra permission is used to show Android's own uninstall prompt.\n\n" +
                        "An independent, iOS-inspired launcher. Not affiliated with Apple."
                )
                .setPositiveButton("OK", null)
                .show()
        }

        val scroll = ScrollView(activity)
        scroll.addView(column)
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Home Screen Settings")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun addRow(parent: LinearLayout, title: String, value: () -> String, onClick: () -> Unit) {
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = activity.dp(52)
        row.isClickable = true
        row.setBackgroundResource(android.R.drawable.list_selector_background)

        val titleView = TextView(activity)
        titleView.text = title
        titleView.setTextColor(Color.WHITE)
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)

        val valueView = TextView(activity)
        valueView.text = value()
        valueView.setTextColor(Color.argb(170, 255, 255, 255))
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        valueView.gravity = Gravity.END

        row.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            valueView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        row.setOnClickListener {
            onClick()
            valueView.text = value()
        }
        parent.addView(
            row,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
    }

    private fun onOff(value: Boolean): String = if (value) "On" else "Off"

    @Suppress("DEPRECATION")
    private fun versionName(): String {
        return try {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private companion object {
        val SIZE_NAMES = arrayOf("Small", "Medium", "Large")
    }
}
