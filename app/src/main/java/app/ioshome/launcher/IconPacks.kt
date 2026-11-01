package app.ioshome.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import kotlin.math.abs

/** One parsed third-party icon pack (ADW / Nova / GO / Apex style, driven by appfilter.xml). */
class IconPack(val packageName: String, val label: String, private val res: Resources) {

    val byComponent = HashMap<String, String>()
    val byPackage = HashMap<String, String>()
    val backs = ArrayList<String>()
    val listed = ArrayList<String>()
    var maskName: String? = null
    var uponName: String? = null
    var scale = 1f

    /** Every drawable name the pack offers (used by the per-app icon picker and the preview). */
    val allNames: List<String> by lazy {
        val set = java.util.TreeSet<String>()
        set.addAll(listed)
        set.addAll(byComponent.values)
        ArrayList(set)
    }

    fun load(name: String): Drawable? = loadDrawable(res, packageName, name)

    /** The pack's themed icon for this component, or null if the pack does not cover the app. */
    fun drawableFor(component: ComponentName): Drawable? {
        val name = byComponent[component.flattenToString()] ?: byPackage[component.packageName] ?: return null
        return load(name)
    }

    fun hasBack(): Boolean = backs.isNotEmpty()

    /** Builds an icon for an app the pack does not cover: pack background + scaled app icon + mask + overlay. */
    fun compose(base: Drawable, key: String, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        if (backs.isNotEmpty()) {
            val back = load(backs[abs(key.hashCode()) % backs.size])
            if (back != null) {
                back.setBounds(0, 0, size, size)
                back.draw(canvas)
            }
        }
        val inner = (size * scale.coerceIn(0.3f, 1f)).toInt().coerceAtLeast(1)
        val offset = (size - inner) / 2
        val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val layerCanvas = Canvas(layer)
        base.setBounds(offset, offset, offset + inner, offset + inner)
        base.draw(layerCanvas)
        val mask = maskName?.let { load(it) }
        if (mask != null) {
            val maskBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            mask.setBounds(0, 0, size, size)
            mask.draw(Canvas(maskBitmap))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            layerCanvas.drawBitmap(maskBitmap, 0f, 0f, paint)
        }
        canvas.drawBitmap(layer, 0f, 0f, null)
        val upon = uponName?.let { load(it) }
        if (upon != null) {
            upon.setBounds(0, 0, size, size)
            upon.draw(canvas)
        }
        return out
    }

    companion object {
        fun loadDrawable(res: Resources, pkg: String, name: String): Drawable? {
            var id = res.getIdentifier(name, "drawable", pkg)
            if (id == 0) id = res.getIdentifier(name, "mipmap", pkg)
            if (id == 0) return null
            return try {
                res.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null)
            } catch (e: Exception) {
                try {
                    res.getDrawable(id, null)
                } catch (e2: Exception) {
                    null
                }
            }
        }
    }
}

/** Finds installed icon packs, parses their appfilter.xml and resolves per-app custom icons. */
class IconPackManager(private val context: Context) {

    class PackInfo(val packageName: String, val label: String)

    @Volatile
    private var current: IconPack? = null
    private val parsed = HashMap<String, IconPack>()

    fun current(): IconPack? = current

    /** Lists installed packs (blocking, call from a background thread). */
    fun discover(): List<PackInfo> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, PackInfo>()
        for (action in ACTIONS) {
            val matches = try {
                pm.queryIntentActivities(Intent(action), 0)
            } catch (e: Exception) {
                emptyList()
            }
            for (match in matches) {
                val pkg = match.activityInfo.packageName
                if (found.containsKey(pkg) || pkg == context.packageName) continue
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (e: Exception) {
                    pkg
                }
                found[pkg] = PackInfo(pkg, label)
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    /** Makes [pkg] the active pack (blocking; parses on first use). Null or empty means "no pack". */
    fun select(pkg: String?): IconPack? {
        if (pkg.isNullOrEmpty()) {
            current = null
            return null
        }
        val pack = pack(pkg)
        current = pack
        return pack
    }

    /** Parses (or returns the cached) pack, without making it active. Blocking. */
    fun pack(pkg: String): IconPack? {
        synchronized(this) {
            val cached = parsed[pkg]
            if (cached != null) return cached
        }
        val fresh = parse(pkg) ?: return null
        synchronized(this) { parsed[pkg] = fresh }
        return fresh
    }

    /** Forgets cached parses so an updated pack is re-read. */
    fun forget(pkg: String) {
        synchronized(this) { parsed.remove(pkg) }
        if (current?.packageName == pkg) current = null
    }

    /** Resolves a per-app override of the form "packagename|drawablename". */
    fun loadOverride(spec: String): Drawable? {
        val bar = spec.indexOf('|')
        if (bar <= 0 || bar == spec.length - 1) return null
        val pkg = spec.substring(0, bar)
        val name = spec.substring(bar + 1)
        return try {
            IconPack.loadDrawable(context.packageManager.getResourcesForApplication(pkg), pkg, name)
        } catch (e: Exception) {
            null
        }
    }

    private fun parse(pkg: String): IconPack? {
        try {
            val pm = context.packageManager
            val res = pm.getResourcesForApplication(pkg)
            val label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            val pack = IconPack(pkg, label, res)
            val filter = openXml(res, pkg, "appfilter") ?: return null
            readAppFilter(filter, pack)
            val list = openXml(res, pkg, "drawable")
            if (list != null) readDrawableList(list, pack)
            return pack
        } catch (e: Throwable) {
            return null
        }
    }

    private fun openXml(res: Resources, pkg: String, name: String): XmlPullParser? {
        val id = res.getIdentifier(name, "xml", pkg)
        if (id != 0) {
            return try {
                res.getXml(id)
            } catch (e: Exception) {
                null
            }
        }
        return try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(res.assets.open(name + ".xml"), null)
            parser
        } catch (e: Exception) {
            null
        }
    }

    private fun readAppFilter(parser: XmlPullParser, pack: IconPack) {
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        val component = parser.getAttributeValue(null, "component")
                        val drawable = parser.getAttributeValue(null, "drawable")
                        if (component != null && drawable != null) addItem(pack, component, drawable)
                    }
                    "iconback" -> {
                        for (i in 0 until parser.attributeCount) {
                            if (parser.getAttributeName(i).startsWith("img")) pack.backs.add(parser.getAttributeValue(i))
                        }
                    }
                    "iconmask" -> pack.maskName = firstImage(parser)
                    "iconupon" -> pack.uponName = firstImage(parser)
                    "scale" -> {
                        val factor = parser.getAttributeValue(null, "factor")?.toFloatOrNull()
                        if (factor != null) pack.scale = factor
                    }
                }
            }
            event = parser.next()
        }
    }

    private fun firstImage(parser: XmlPullParser): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i).startsWith("img")) return parser.getAttributeValue(i)
        }
        return null
    }

    /** Entries look like component="ComponentInfo{com.pkg/com.pkg.Activity}" drawable="name". */
    private fun addItem(pack: IconPack, componentAttr: String, drawable: String) {
        var text = componentAttr.trim()
        if (text.startsWith("ComponentInfo{")) text = text.removePrefix("ComponentInfo{").removeSuffix("}")
        val slash = text.indexOf('/')
        if (slash <= 0 || slash == text.length - 1) return
        val pkg = text.substring(0, slash)
        var cls = text.substring(slash + 1)
        if (cls.startsWith(".")) cls = pkg + cls
        pack.byComponent.putIfAbsent(pkg + "/" + cls, drawable)
        pack.byPackage.putIfAbsent(pkg, drawable)
    }

    private fun readDrawableList(parser: XmlPullParser, pack: IconPack) {
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "item") {
                val name = parser.getAttributeValue(null, "drawable")
                if (name != null) pack.listed.add(name)
            }
            event = parser.next()
        }
    }

    companion object {
        val ACTIONS = arrayOf(
            "com.novalauncher.THEME",
            "org.adw.launcher.THEMES",
            "com.gau.go.launcherex.theme",
            "com.anddoes.launcher.THEME",
            "org.adw.launcher.icons.ACTION_PICK_ICON",
            "com.fede.launcher.THEME_ICONPACK"
        )
    }
}
