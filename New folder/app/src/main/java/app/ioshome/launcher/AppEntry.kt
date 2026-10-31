package app.ioshome.launcher

import android.content.ComponentName
import android.content.pm.LauncherActivityInfo

/** One launchable activity. [key] is the flattened component name and is the stable id used in saved layouts. */
class AppEntry(
    val key: String,
    val label: String,
    val component: ComponentName,
    val info: LauncherActivityInfo
)
