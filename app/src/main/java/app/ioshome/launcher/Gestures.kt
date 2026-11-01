package app.ioshome.launcher

/** Actions that can be bound to a gesture, and the gesture slots themselves. */
object Gestures {
    const val NONE = 0
    const val SEARCH = 1
    const val LIBRARY = 2
    const val CONTROL_CENTER = 3
    const val EDIT_MODE = 4
    const val LAUNCHER_SETTINGS = 5
    const val ANDROID_SETTINGS = 6

    val NAMES = arrayOf(
        "Do nothing",
        "Open search",
        "Open App Library",
        "Open Control Center",
        "Edit home screen",
        "Launcher settings",
        "Android settings"
    )

    const val GESTURE_SWIPE_DOWN = 0
    const val GESTURE_SWIPE_UP = 1
    const val GESTURE_DOUBLE_TAP = 2
    const val GESTURE_LONG_PRESS = 3
    const val GESTURE_TOP_RIGHT = 4

    fun actionFor(settings: LauncherSettings, gesture: Int): Int {
        return when (gesture) {
            GESTURE_SWIPE_DOWN -> settings.gestureSwipeDown
            GESTURE_SWIPE_UP -> settings.gestureSwipeUp
            GESTURE_DOUBLE_TAP -> settings.gestureDoubleTap
            GESTURE_LONG_PRESS -> settings.gestureLongPress
            GESTURE_TOP_RIGHT -> settings.gestureTopRight
            else -> NONE
        }
    }
}
