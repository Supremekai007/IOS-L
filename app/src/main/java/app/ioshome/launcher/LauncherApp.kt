package app.ioshome.launcher

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.util.concurrent.Executors

/**
 * Process-wide launcher state, so the activity can be recreated (rotation, theme change) without
 * reloading anything. Everything except the background loads is touched on the main thread only.
 */
class LauncherApp : Application() {

    lateinit var settings: LauncherSettings
        private set
    lateinit var icons: IconCache
        private set
    lateinit var repo: AppRepository
        private set

    var model: HomeModel = HomeModel()
        private set
    var apps: List<AppEntry> = emptyList()
        private set
    var appsByKey: Map<String, AppEntry> = emptyMap()
        private set
    var loaded: Boolean = false
        private set

    /** Set by the visible activity; called on the main thread whenever apps or layout change. */
    var onDataChanged: (() -> Unit)? = null

    private lateinit var store: LayoutStore
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var modelReady = false

    override fun onCreate() {
        super.onCreate()
        settings = LauncherSettings(this)
        icons = IconCache()
        store = LayoutStore(this)
        repo = AppRepository(this)
        repo.start { reload() }
        reload()
    }

    /** Re-reads the installed apps in the background and reconciles the saved layout with them. */
    fun reload() {
        io.execute {
            val list: List<AppEntry>? = try {
                repo.loadApps()
            } catch (e: Exception) {
                null
            }
            val stored = if (!modelReady) store.load() else null
            main.post { applyLoaded(list, stored) }
        }
    }

    private fun applyLoaded(list: List<AppEntry>?, stored: HomeModel?) {
        val firstRun = !modelReady && stored == null
        if (!modelReady) {
            model = stored ?: HomeModel()
            modelReady = true
        }
        if (list != null) {
            apps = list
            appsByKey = list.associateBy { it.key }
            icons.clear()
            // An empty result is treated as a failed query: never prune the saved layout against it.
            if (list.isNotEmpty()) {
                val keys = list.map { it.key }
                val changed = if (firstRun) {
                    model.reset(keys, defaultDock(list), settings.dockSlots)
                    true
                } else {
                    model.reconcile(keys, settings.dockSlots)
                }
                if (changed) save()
            }
        }
        loaded = true
        onDataChanged?.invoke()
    }

    /** Applies a layout change on the model; persists and notifies only if it actually changed something. */
    fun edit(change: HomeModel.() -> Boolean) {
        if (model.change()) {
            save()
            onDataChanged?.invoke()
        }
    }

    fun reconcileNow() {
        if (apps.isEmpty()) return
        if (model.reconcile(apps.map { it.key }, settings.dockSlots)) save()
    }

    fun resetLayout() {
        if (apps.isEmpty()) return
        model.reset(apps.map { it.key }, defaultDock(apps), settings.dockSlots)
        save()
        onDataChanged?.invoke()
    }

    private fun save() {
        val json = LayoutStore.toJson(model)
        io.execute { store.save(json) }
    }

    /** Phone, messages, browser and camera if the device has default handlers for them. */
    private fun defaultDock(list: List<AppEntry>): List<String> {
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("sms:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        )
        val out = ArrayList<String>()
        for (intent in intents) {
            val pkg = try {
                packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                    ?.activityInfo?.packageName
            } catch (e: Exception) {
                null
            } ?: continue
            val entry = list.firstOrNull { it.component.packageName == pkg } ?: continue
            if (entry.key !in out) out.add(entry.key)
        }
        return out
    }
}
