package app.ioshome.launcher

import org.json.JSONObject

/** Export and import of the whole launcher configuration as one JSON document. */
object Backup {
    const val FORMAT = "ios-home-launcher-backup"
    const val VERSION = 1

    fun export(app: LauncherApp): String {
        val o = JSONObject()
        o.put("format", FORMAT)
        o.put("version", VERSION)
        o.put("layout", JSONObject(LayoutStore.toJson(app.model)))
        o.put("settings", app.settings.exportJson())
        return o.toString(2)
    }

    /** Result of an import: null error means success. */
    class Result(val error: String?, val message: String)

    /**
     * Validates [text] fully before changing anything, so a bad or incompatible file can never leave the
     * launcher half-restored.
     */
    fun restore(app: LauncherApp, text: String): Result {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            return Result("not-json", "That file is not a launcher backup.")
        }
        if (root.optString("format") != FORMAT) return Result("format", "That file is not a launcher backup.")
        val version = root.optInt("version", -1)
        if (version < 1) return Result("version", "The backup has no valid version.")
        if (version > VERSION) {
            return Result("newer", "This backup was made by a newer launcher version and can't be restored safely.")
        }
        val layoutObject = root.optJSONObject("layout")
            ?: return Result("layout", "The backup contains no layout.")
        val model = try {
            LayoutStore.fromJson(layoutObject.toString())
        } catch (e: Exception) {
            null
        } ?: return Result("layout", "The layout in the backup is damaged.")

        val settingsObject = root.optJSONObject("settings")
        val applied = if (settingsObject != null) app.settings.importJson(settingsObject) else 0
        app.replaceModel(model)
        app.applyIconConfig()
        return Result(null, "Restored the layout and " + applied + " settings.")
    }
}
