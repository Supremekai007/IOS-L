package app.ioshome.launcher

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle

/** Discovers launchable apps through LauncherApps (no QUERY_ALL_PACKAGES needed) and watches for changes. */
class AppRepository(context: Context) {

    private val appContext = context.applicationContext
    private val launcherApps: LauncherApps = appContext.getSystemService(LauncherApps::class.java)
    private val user: UserHandle = Process.myUserHandle()
    private val main = Handler(Looper.getMainLooper())
    private var onChanged: ((Set<String>) -> Unit)? = null
    private val pendingPackages = LinkedHashSet<String>()
    private val notifyRunnable = Runnable {
        val changed = HashSet(pendingPackages)
        pendingPackages.clear()
        onChanged?.invoke(changed)
    }

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String?, user: UserHandle?) {
            schedule(packageName)
        }

        override fun onPackageAdded(packageName: String?, user: UserHandle?) {
            schedule(packageName)
        }

        override fun onPackageChanged(packageName: String?, user: UserHandle?) {
            schedule(packageName)
        }

        override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) {
            if (packageNames != null) for (name in packageNames) schedule(name)
        }

        override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) {
            if (packageNames != null) for (name in packageNames) schedule(name)
        }
    }

    fun start(listener: (Set<String>) -> Unit) {
        onChanged = listener
        launcherApps.registerCallback(callback, main)
    }

    /** Debounced so an app update (remove + add) produces one refresh after things settle. */
    private fun schedule(packageName: String?) {
        if (packageName != null) pendingPackages.add(packageName)
        main.removeCallbacks(notifyRunnable)
        main.postDelayed(notifyRunnable, 800L)
    }

    /** Blocking; call from a background thread. */
    fun loadApps(): List<AppEntry> {
        val out = ArrayList<AppEntry>()
        val seen = HashSet<String>()
        for (info in launcherApps.getActivityList(null, user)) {
            val component = info.componentName
            if (component.packageName == appContext.packageName) continue
            val key = component.flattenToString()
            if (!seen.add(key)) continue
            val label = info.label?.toString() ?: component.packageName
            out.add(AppEntry(key, label, component, info))
        }
        out.sortWith(Comparator { a, b -> a.label.compareTo(b.label, ignoreCase = true) })
        return out
    }

    fun launch(entry: AppEntry, bounds: Rect?, options: Bundle?) {
        launcherApps.startMainActivity(entry.component, user, bounds, options)
    }
}
