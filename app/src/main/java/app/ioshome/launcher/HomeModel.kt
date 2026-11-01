package app.ioshome.launcher

class FolderData(var name: String, val apps: MutableList<String> = ArrayList())

/**
 * Pure-Kotlin layout model (no Android dependencies, so it is unit-testable).
 *
 * - [home] is one flat, ordered list of app keys and folder keys ("folder:N"). Pages are derived from it at
 *   render time by chunking it by the current grid capacity, so rotation never loses or duplicates an icon.
 * - [dock] is the ordered list of docked apps (apps only).
 * - [folders] maps a folder key to its name and ordered apps.
 * - [known] is every app seen at the last reconcile. A known app that is placed nowhere was removed from the
 *   home screen by the user and lives only in the App Library.
 * - [hidden] apps are hidden from the home screen, the App Library and search until unhidden.
 * - [recents] is the launch history made through this launcher (local only, capped).
 *
 * Invariant after [reconcile]: every app key appears at most once across [home], [dock] and all folders,
 * only installed apps appear, and no folder is empty or orphaned.
 */
class HomeModel {
    val home: MutableList<String> = ArrayList()
    val dock: MutableList<String> = ArrayList()
    val known: MutableSet<String> = LinkedHashSet()
    val folders: MutableMap<String, FolderData> = LinkedHashMap()
    val hidden: MutableSet<String> = LinkedHashSet()
    val recents: MutableList<String> = ArrayList()

    fun isFolder(key: String): Boolean = key.startsWith(FOLDER_PREFIX)

    fun contains(key: String): Boolean {
        if (home.contains(key) || dock.contains(key)) return true
        for (folder in folders.values) {
            if (folder.apps.contains(key)) return true
        }
        return false
    }

    /** Brings the layout in line with the installed apps. Returns true if anything changed. */
    fun reconcile(installed: List<String>, dockSlots: Int): Boolean {
        var changed = false
        val installedSet: Set<String> = installed.toHashSet()
        val seen = HashSet<String>()

        if (prune(dock, installedSet, seen, false)) changed = true
        for (folder in folders.values) {
            if (prune(folder.apps, installedSet, seen, false)) changed = true
        }
        if (prune(home, installedSet, seen, true)) changed = true

        // A folder that is not on the home screen would hide its apps forever: release them to the home screen.
        val orphanIds = folders.keys.filter { it !in home }
        for (id in orphanIds) {
            val folder = folders.remove(id)
            if (folder != null) home.addAll(folder.apps)
            changed = true
        }
        if (cleanupFolders()) changed = true

        while (dock.size > dockSlots) {
            home.add(dock.removeAt(dock.size - 1))
            changed = true
        }

        for (key in installed) {
            if (key !in known && key !in seen && key !in hidden) {
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
        if (hidden.retainAll(installedSet)) changed = true
        if (recents.retainAll { it in installedSet && it !in hidden }) changed = true
        return changed
    }

    private fun prune(
        list: MutableList<String>,
        installed: Set<String>,
        seen: MutableSet<String>,
        allowFolders: Boolean
    ): Boolean {
        var changed = false
        val iter = list.iterator()
        while (iter.hasNext()) {
            val key = iter.next()
            val valid = if (isFolder(key)) {
                allowFolders && folders.containsKey(key)
            } else {
                key in installed && key !in hidden
            }
            if (!valid || !seen.add(key)) {
                iter.remove()
                changed = true
            }
        }
        return changed
    }

    private fun cleanupFolders(): Boolean {
        val empty = ArrayList<String>()
        for ((id, folder) in folders) {
            if (folder.apps.isEmpty()) empty.add(id)
        }
        for (id in empty) {
            folders.remove(id)
            home.remove(id)
        }
        return empty.isNotEmpty()
    }

    /** Takes the item out of the home screen, dock and every folder (it is not deleted from the model). */
    private fun removeEverywhere(key: String) {
        home.remove(key)
        dock.remove(key)
        for (folder in folders.values) folder.apps.remove(key)
    }

    private fun newFolderId(): String {
        var n = 1
        while (folders.containsKey(FOLDER_PREFIX + n)) n++
        return FOLDER_PREFIX + n
    }

    // ------------------------------------------------------------------ simple placement

    /** Removes the icon from the home screen. The app stays installed and stays in the App Library. */
    fun removeFromHome(key: String): Boolean = home.remove(key)

    fun addToHome(key: String): Boolean {
        if (contains(key)) return false
        home.add(key)
        return true
    }

    fun addToDock(key: String, dockSlots: Int): Boolean {
        if (isFolder(key) || key in dock || dock.size >= dockSlots) return false
        removeEverywhere(key)
        cleanupFolders()
        dock.add(key)
        return true
    }

    fun moveDockToHome(key: String): Boolean {
        if (!dock.remove(key)) return false
        home.add(key)
        return true
    }

    /** Moves an item by [delta] positions inside whichever of home or dock holds it. */
    fun shift(key: String, delta: Int): Boolean {
        val list = if (key in home) home else if (key in dock) dock else return false
        return shiftIn(list, key, delta)
    }

    fun shiftInFolder(folderId: String, key: String, delta: Int): Boolean {
        val folder = folders[folderId] ?: return false
        return shiftIn(folder.apps, key, delta)
    }

    private fun shiftIn(list: MutableList<String>, key: String, delta: Int): Boolean {
        val from = list.indexOf(key)
        val to = from + delta
        if (from < 0 || to < 0 || to >= list.size) return false
        list.removeAt(from)
        list.add(to, key)
        return true
    }

    // ------------------------------------------------------------------ drag and drop

    /** Places the item at [index] (its final position) on the home screen, from wherever it currently is. */
    fun moveToHomeIndex(key: String, index: Int): Boolean {
        if (isFolder(key)) {
            if (key !in home) return false
        } else if (!contains(key)) {
            return false
        }
        removeEverywhere(key)
        cleanupFolders()
        home.add(index.coerceIn(0, home.size), key)
        return true
    }

    /** Places an app at [index] (its final position) in the dock. Folders cannot be docked. */
    fun moveToDockIndex(key: String, index: Int, dockSlots: Int): Boolean {
        if (isFolder(key) || !contains(key)) return false
        if (key !in dock && dock.size >= dockSlots) return false
        removeEverywhere(key)
        cleanupFolders()
        dock.add(index.coerceIn(0, dock.size), key)
        return true
    }

    /**
     * Drops [dragKey] onto [targetKey] on the home screen. If the target is an app, a new folder replaces it
     * at its position; if the target is a folder, the app joins it. Folders cannot be nested.
     */
    fun mergeIntoFolder(dragKey: String, targetKey: String): Boolean {
        if (dragKey == targetKey || isFolder(dragKey) || !contains(dragKey)) return false
        if (home.indexOf(targetKey) < 0) return false

        if (isFolder(targetKey)) {
            val folder = folders[targetKey] ?: return false
            if (dragKey in folder.apps) return false
            removeEverywhere(dragKey)
            folder.apps.add(dragKey)
            cleanupFolders()
            return true
        }

        removeEverywhere(dragKey)
        cleanupFolders()
        val at = home.indexOf(targetKey)
        if (at < 0) return false
        val id = newFolderId()
        val apps = ArrayList<String>()
        apps.add(targetKey)
        apps.add(dragKey)
        folders[id] = FolderData("Folder", apps)
        home[at] = id
        return true
    }

    /** Places an app at [index] (its final position) inside an existing folder. */
    fun moveInFolder(folderId: String, key: String, index: Int): Boolean {
        val folder = folders[folderId] ?: return false
        if (key !in folder.apps) return false
        folder.apps.remove(key)
        folder.apps.add(index.coerceIn(0, folder.apps.size), key)
        return true
    }

    /** The id the next created folder will get (lets the UI prepare an animation before the change). */
    fun peekNextFolderId(): String = newFolderId()

    /** The folder holding [key], or null. */
    fun folderOf(key: String): String? {
        for ((id, folder) in folders) {
            if (folder.apps.contains(key)) return id
        }
        return null
    }

    /** Removes the folder and puts its apps back on the home screen where the folder was. */
    fun dissolveFolder(id: String): Boolean {
        val folder = folders.remove(id) ?: return false
        val at = home.indexOf(id)
        if (at >= 0) {
            home.removeAt(at)
            home.addAll(at, folder.apps)
        } else {
            home.addAll(folder.apps)
        }
        return true
    }

    fun renameFolder(id: String, name: String): Boolean {
        val folder = folders[id] ?: return false
        val clean = name.trim().take(24).ifEmpty { "Folder" }
        if (clean == folder.name) return false
        folder.name = clean
        return true
    }

    // ------------------------------------------------------------------ hidden apps and recents

    fun hide(key: String): Boolean {
        if (isFolder(key) || key in hidden) return false
        removeEverywhere(key)
        cleanupFolders()
        recents.remove(key)
        hidden.add(key)
        return true
    }

    fun unhide(key: String): Boolean {
        if (!hidden.remove(key)) return false
        if (!contains(key)) home.add(key)
        return true
    }

    fun recordLaunch(key: String): Boolean {
        if (isFolder(key) || key in hidden) return false
        if (recents.firstOrNull() == key) return false
        recents.remove(key)
        recents.add(0, key)
        while (recents.size > MAX_RECENTS) recents.removeAt(recents.size - 1)
        return true
    }

    /** Resets placement to defaults. Hidden apps stay hidden. */
    fun reset(installed: List<String>, preferredDock: List<String>, dockSlots: Int) {
        home.clear()
        dock.clear()
        folders.clear()
        recents.clear()
        known.clear()
        val valid = installed.toHashSet()
        for (key in preferredDock) {
            if (dock.size >= dockSlots) break
            if (key in valid && key !in hidden && key !in dock) dock.add(key)
        }
        reconcile(installed, dockSlots)
    }

    companion object {
        const val FOLDER_PREFIX = "folder:"
        const val MAX_RECENTS = 8
    }
}
