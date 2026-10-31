package app.ioshome.launcher

/**
 * Pure-Kotlin layout model (no Android dependencies, so it is unit-testable).
 *
 * - [home] is one flat, ordered list. Pages are derived from it at render time by chunking it by the
 *   current grid capacity, so rotation or a grid change can never lose or duplicate an icon.
 * - [dock] is the ordered list of docked apps.
 * - [known] is every app seen at the last reconcile. An app that is known but in neither list was
 *   removed from the home screen by the user and stays only in the App Library.
 *
 * Invariant after [reconcile]: every key appears at most once across [home] and [dock], and only
 * installed keys appear.
 */
class HomeModel {
    val home: MutableList<String> = ArrayList()
    val dock: MutableList<String> = ArrayList()
    val known: MutableSet<String> = LinkedHashSet()

    fun contains(key: String): Boolean = home.contains(key) || dock.contains(key)

    /** Brings the layout in line with the installed apps. Returns true if anything changed. */
    fun reconcile(installed: List<String>, dockSlots: Int): Boolean {
        var changed = false
        val installedSet: Set<String> = installed.toHashSet()
        val seen = HashSet<String>()

        if (prune(dock, installedSet, seen)) changed = true
        if (prune(home, installedSet, seen)) changed = true

        while (dock.size > dockSlots) {
            home.add(dock.removeAt(dock.size - 1))
            changed = true
        }

        for (key in installed) {
            if (key !in known && key !in seen) {
                home.add(key)
                seen.add(key)
                changed = true
            }
        }

        if (known.size != installedSet.size || !known.containsAll(installedSet)) {
            known.clear()
            known.addAll(installed)
            changed = true
        }
        return changed
    }

    private fun prune(list: MutableList<String>, valid: Set<String>, seen: MutableSet<String>): Boolean {
        var changed = false
        val iter = list.iterator()
        while (iter.hasNext()) {
            val key = iter.next()
            if (key !in valid || !seen.add(key)) {
                iter.remove()
                changed = true
            }
        }
        return changed
    }

    /** Removes the icon from the home screen. The app stays installed and stays in the App Library. */
    fun removeFromHome(key: String): Boolean = home.remove(key)

    fun addToHome(key: String): Boolean {
        if (contains(key)) return false
        home.add(key)
        return true
    }

    fun addToDock(key: String, dockSlots: Int): Boolean {
        if (key in dock || dock.size >= dockSlots) return false
        home.remove(key)
        dock.add(key)
        return true
    }

    fun moveDockToHome(key: String): Boolean {
        if (!dock.remove(key)) return false
        home.add(key)
        return true
    }

    /** Moves an item by [delta] positions inside whichever list holds it. */
    fun shift(key: String, delta: Int): Boolean {
        val list = if (key in home) home else if (key in dock) dock else return false
        val from = list.indexOf(key)
        val to = from + delta
        if (to < 0 || to >= list.size) return false
        list.removeAt(from)
        list.add(to, key)
        return true
    }

    fun reset(installed: List<String>, preferredDock: List<String>, dockSlots: Int) {
        home.clear()
        dock.clear()
        known.clear()
        val valid = installed.toHashSet()
        for (key in preferredDock) {
            if (dock.size >= dockSlots) break
            if (key in valid && key !in dock) dock.add(key)
        }
        reconcile(installed, dockSlots)
    }
}
