package app.ioshome.launcher

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Persists [HomeModel] as versioned JSON using AtomicFile, so a crash mid-write cannot corrupt it. */
class LayoutStore(context: Context) {

    private val file = AtomicFile(File(context.filesDir, "layout_v1.json"))

    /** Returns null if there is no saved layout or it is unreadable (caller then builds a default). */
    fun load(): HomeModel? {
        try {
            if (!file.baseFile.exists()) return null
            val text = String(file.readFully(), Charsets.UTF_8)
            return fromJson(text)
        } catch (e: Exception) {
            return null
        }
    }

    fun save(json: String) {
        var out: FileOutputStream? = null
        try {
            out = file.startWrite()
            out.write(json.toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: IOException) {
            if (out != null) file.failWrite(out)
        }
    }

    companion object {
        private const val VERSION = 1

        fun toJson(model: HomeModel): String {
            val o = JSONObject()
            o.put("v", VERSION)
            o.put("home", JSONArray(model.home))
            o.put("dock", JSONArray(model.dock))
            o.put("known", JSONArray(model.known))
            return o.toString()
        }

        fun fromJson(text: String): HomeModel? {
            val o = JSONObject(text)
            if (o.optInt("v", -1) != VERSION) return null
            val m = HomeModel()
            m.home.addAll(readList(o, "home"))
            m.dock.addAll(readList(o, "dock"))
            m.known.addAll(readList(o, "known"))
            return m
        }

        private fun readList(o: JSONObject, name: String): List<String> {
            val a = o.optJSONArray(name) ?: return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until a.length()) {
                val s = a.optString(i, "")
                if (s.isNotEmpty()) out.add(s)
            }
            return out
        }
    }
}
