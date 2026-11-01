package app.ioshome.launcher

import android.content.Context
import android.provider.Settings
import org.json.JSONObject
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** All launcher preferences. Every value is validated (clamped) on read and write. */
class LauncherSettings(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs = context.getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)

    /** Bumped on every write so UI code can cheaply tell that something changed. */
    @Volatile
    var revision = 0
        private set

    private fun intPref(key: String, def: Int, lo: Int, hi: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Int = prefs.getInt(key, def).coerceIn(lo, hi)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            prefs.edit().putInt(key, value.coerceIn(lo, hi)).apply()
            revision++
        }
    }

    private fun boolPref(key: String, def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = prefs.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            prefs.edit().putBoolean(key, value).apply()
            revision++
        }
    }

    private fun stringPref(key: String, def: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String = prefs.getString(key, def) ?: def
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            prefs.edit().putString(key, value).apply()
            revision++
        }
    }

    // ---- home screen
    var columns by intPref("columns", 4, 3, 6)
    /** 0 = automatic, otherwise a fixed number of rows (reduced if they would not fit). */
    var rows by intPref("rows", 0, 0, 8)
    var iconSpacing by intPref("icon_spacing", 8, 0, 24)
    var showLabels by boolPref("show_labels", true)
    var labelSize by intPref("label_size", 11, 9, 14)

    /** Icon size in percent of the default. Migrates the old three-step setting from v0.1 to v0.3. */
    var iconScalePct: Int
        get() = if (prefs.contains("icon_scale_pct")) {
            prefs.getInt("icon_scale_pct", 100).coerceIn(70, 130)
        } else {
            when (prefs.getInt("icon_size", 1)) {
                0 -> 85
                2 -> 115
                else -> 100
            }
        }
        set(value) {
            prefs.edit().putInt("icon_scale_pct", value.coerceIn(70, 130)).apply()
            revision++
        }

    fun iconScale(): Float = iconScalePct / 100f

    // ---- dock
    var dockSlots by intPref("dock_slots", 4, 3, 6)
    var dockIconPct by intPref("dock_icon_pct", 100, 70, 120)
    var dockOpacity by intPref("dock_opacity", 30, 0, 100)
    var dockRadius by intPref("dock_radius", 28, 0, 40)

    // ---- animations
    /** The user's own switch. Use [animations] for the effective value. */
    var animationsPref by boolPref("animations", true)
    var transitionStyle by intPref("transition_style", Transitions.ZOOM, 0, Transitions.NAMES.size - 1)
    var animSpeed by intPref("anim_speed", 100, 50, 200)
    var animIntensity by intPref("anim_intensity", 60, 30, 100)
    var iconMovementAnim by boolPref("anim_icon_movement", true)
    var folderAnim by boolPref("anim_folder", true)
    var launchAnim by boolPref("anim_launch", true)
    var entranceAnim by boolPref("anim_entrance", true)

    /** Effective: off when the user turned it off or Android's animation scale is 0 (reduced motion). */
    val animations: Boolean
        get() = animationsPref && systemMotionEnabled()

    fun systemMotionEnabled(): Boolean {
        return try {
            Settings.Global.getFloat(appContext.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        } catch (e: Exception) {
            true
        }
    }

    /** Scales a base duration by the user's speed setting (200% = twice as fast). */
    fun dur(base: Long): Long = (base * 100f / animSpeed).toLong().coerceAtLeast(1L)

    // ---- feedback and gestures
    var haptics by boolPref("haptics", true)
    var gestureSwipeDown by intPref("gesture_swipe_down", Gestures.SEARCH, 0, Gestures.NAMES.size - 1)
    var gestureSwipeUp by intPref("gesture_swipe_up", Gestures.LIBRARY, 0, Gestures.NAMES.size - 1)
    var gestureDoubleTap by intPref("gesture_double_tap", Gestures.NONE, 0, Gestures.NAMES.size - 1)
    var gestureLongPress by intPref("gesture_long_press", Gestures.EDIT_MODE, 0, Gestures.NAMES.size - 1)
    var gestureTopRight by intPref("gesture_top_right", Gestures.CONTROL_CENTER, 0, Gestures.NAMES.size - 1)

    // ---- appearance (0 = follow system, 1 = light, 2 = dark)
    var appearance by intPref("appearance", 0, 0, 2)

    fun isDark(): Boolean {
        return when (appearance) {
            1 -> false
            2 -> true
            else -> {
                val mode = appContext.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    // ---- performance
    /** 0 = only what is visible, 1 = visible and neighbouring pages, 2 = everything in the background. */
    var preloadLevel by intPref("preload_level", 2, 0, 2)
    /** 0 = lighter (no page transform, simpler effects), 1 = full. */
    var animQuality by intPref("anim_quality", 1, 0, 1)
    /** 0 = automatic, otherwise megabytes for the icon cache. Applied when the cache is rebuilt. */
    var cacheMb by intPref("cache_mb", 0, 0, 96)

    // ---- icons
    var iconPack by stringPref("icon_pack", "")
    /** 0 = iOS rounded square for every icon, 1 = keep each icon's own shape. */
    var iconShape by intPref("icon_shape", 0, 0, 1)

    // ---- widgets
    var showWidgetsPage by boolPref("widgets_page", false)

    var widgetIds: List<Int>
        get() = (prefs.getString("widget_ids", "") ?: "").split(',').mapNotNull { it.trim().toIntOrNull() }
        set(value) {
            prefs.edit().putString("widget_ids", value.joinToString(",")).apply()
            revision++
        }

    // ---- per-app icon overrides ("packagename|drawablename")
    @Volatile
    var overridesStamp = 0
        private set

    fun getOverride(componentKey: String): String? = prefs.getString(OVERRIDE_PREFIX + componentKey, null)

    fun setOverride(componentKey: String, spec: String?) {
        val editor = prefs.edit()
        if (spec == null) editor.remove(OVERRIDE_PREFIX + componentKey) else editor.putString(OVERRIDE_PREFIX + componentKey, spec)
        editor.apply()
        overridesStamp++
        revision++
    }

    fun overrideKeys(): List<String> {
        return prefs.all.keys.filter { it.startsWith(OVERRIDE_PREFIX) }.map { it.removePrefix(OVERRIDE_PREFIX) }
    }

    fun clearOverrides() {
        val editor = prefs.edit()
        for (key in prefs.all.keys) {
            if (key.startsWith(OVERRIDE_PREFIX)) editor.remove(key)
        }
        editor.apply()
        overridesStamp++
        revision++
    }

    // ---- backup
    fun exportJson(): JSONObject {
        val o = JSONObject()
        for ((key, value) in prefs.all) {
            when (value) {
                is Int -> o.put(key, value)
                is Boolean -> o.put(key, value)
                is String -> o.put(key, value)
            }
        }
        return o
    }

    /** Applies exported settings; unknown keys and wrong types are ignored. Returns the number applied. */
    fun importJson(o: JSONObject): Int {
        var applied = 0
        val editor = prefs.edit()
        val keys = o.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = o.opt(key)
            when (value) {
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
                else -> continue
            }
            applied++
        }
        editor.apply()
        overridesStamp++
        revision++
        return applied
    }

    private companion object {
        const val OVERRIDE_PREFIX = "ov:"
    }
}
