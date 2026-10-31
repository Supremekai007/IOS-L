package app.ioshome.launcher

import android.content.Context

class LauncherSettings(context: Context) {

    private val prefs = context.getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)

    var columns: Int
        get() = prefs.getInt(KEY_COLUMNS, 4).coerceIn(3, 6)
        set(value) {
            prefs.edit().putInt(KEY_COLUMNS, value.coerceIn(3, 6)).apply()
        }

    var dockSlots: Int
        get() = prefs.getInt(KEY_DOCK, 4).coerceIn(3, 6)
        set(value) {
            prefs.edit().putInt(KEY_DOCK, value.coerceIn(3, 6)).apply()
        }

    /** 0 = small, 1 = medium, 2 = large */
    var iconSize: Int
        get() = prefs.getInt(KEY_ICON_SIZE, 1).coerceIn(0, 2)
        set(value) {
            prefs.edit().putInt(KEY_ICON_SIZE, value.coerceIn(0, 2)).apply()
        }

    var showLabels: Boolean
        get() = prefs.getBoolean(KEY_LABELS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_LABELS, value).apply()
        }

    var animations: Boolean
        get() = prefs.getBoolean(KEY_ANIMATIONS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ANIMATIONS, value).apply()
        }

    var haptics: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_HAPTICS, value).apply()
        }

    fun iconScale(): Float = when (iconSize) {
        0 -> 0.85f
        2 -> 1.15f
        else -> 1f
    }

    private companion object {
        const val KEY_COLUMNS = "columns"
        const val KEY_DOCK = "dock_slots"
        const val KEY_ICON_SIZE = "icon_size"
        const val KEY_LABELS = "show_labels"
        const val KEY_ANIMATIONS = "animations"
        const val KEY_HAPTICS = "haptics"
    }
}
