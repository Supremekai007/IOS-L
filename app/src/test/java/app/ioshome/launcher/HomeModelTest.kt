package app.ioshome.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeModelTest {

    private fun model(vararg keys: String, dockSlots: Int = 4): HomeModel {
        val m = HomeModel()
        m.reconcile(keys.toList(), dockSlots)
        return m
    }

    // ---------------------------------------------------------------- phase 1 behaviour

    @Test
    fun firstReconcilePlacesEveryAppInOrder() {
        val m = HomeModel()
        assertTrue(m.reconcile(listOf("a", "b", "c"), 4))
        assertEquals(listOf("a", "b", "c"), m.home)
        assertTrue(m.dock.isEmpty())
    }

    @Test
    fun removedFromHomeStaysRemovedButNewAppsAreAppended() {
        val m = model("a", "b", "c")
        assertTrue(m.removeFromHome("b"))
        assertFalse(m.reconcile(listOf("a", "b", "c"), 4))
        assertEquals(listOf("a", "c"), m.home)
        assertTrue(m.reconcile(listOf("a", "b", "c", "d"), 4))
        assertEquals(listOf("a", "c", "d"), m.home)
    }

    @Test
    fun uninstalledAppsAreDroppedFromHomeAndDock() {
        val m = model("a", "b", "c")
        m.addToDock("a", 4)
        assertTrue(m.reconcile(listOf("b"), 4))
        assertEquals(listOf("b"), m.home)
        assertTrue(m.dock.isEmpty())
    }

    @Test
    fun dockRespectsSlotLimitAndRejectsDuplicates() {
        val m = model("a", "b", "c", dockSlots = 2)
        assertTrue(m.addToDock("a", 2))
        assertTrue(m.addToDock("b", 2))
        assertFalse(m.addToDock("c", 2))
        assertFalse(m.addToDock("a", 2))
        assertEquals(listOf("c"), m.home)
        assertEquals(listOf("a", "b"), m.dock)
    }

    @Test
    fun dockOverflowMovesToHomeWhenSlotsShrink() {
        val m = HomeModel()
        m.dock.addAll(listOf("a", "b", "c"))
        assertTrue(m.reconcile(listOf("a", "b", "c", "d"), 2))
        assertEquals(listOf("a", "b"), m.dock)
        assertEquals(listOf("c", "d"), m.home)
    }

    @Test
    fun shiftMovesWithinListAndStopsAtEnds() {
        val m = model("a", "b", "c")
        assertTrue(m.shift("b", 1))
        assertEquals(listOf("a", "c", "b"), m.home)
        assertFalse(m.shift("b", 1))
        assertFalse(m.shift("a", -1))
        assertFalse(m.shift("missing", 1))
    }

    @Test
    fun reconcileRepairsDuplicatesFromCorruptedData() {
        val m = HomeModel()
        m.home.addAll(listOf("a", "a", "b"))
        m.dock.add("a")
        assertTrue(m.reconcile(listOf("a", "b"), 4))
        assertEquals(listOf("a"), m.dock)
        assertEquals(listOf("b"), m.home)
    }

    @Test
    fun moveDockToHomeAndBack() {
        val m = model("a", "b")
        m.addToDock("a", 4)
        assertTrue(m.moveDockToHome("a"))
        assertEquals(listOf("b", "a"), m.home)
        assertFalse(m.moveDockToHome("a"))
    }

    @Test
    fun resetUsesPreferredDockAndIgnoresUnknownApps() {
        val m = HomeModel()
        m.reset(listOf("a", "b", "c", "d"), listOf("c", "x", "a"), 2)
        assertEquals(listOf("c", "a"), m.dock)
        assertEquals(listOf("b", "d"), m.home)
    }

    // ---------------------------------------------------------------- drag and drop

    @Test
    fun moveToHomeIndexPlacesItemAtFinalPosition() {
        val m = model("a", "b", "c", "d")
        assertTrue(m.moveToHomeIndex("a", 2))
        assertEquals(listOf("b", "c", "a", "d"), m.home)
        assertTrue(m.moveToHomeIndex("d", 0))
        assertEquals(listOf("d", "b", "c", "a"), m.home)
        assertTrue(m.moveToHomeIndex("d", 99))
        assertEquals(listOf("b", "c", "a", "d"), m.home)
        assertFalse(m.moveToHomeIndex("zzz", 0))
    }

    @Test
    fun dockToHomeDropKeepsEverythingUnique() {
        val m = model("a", "b", "c")
        m.addToDock("a", 4)
        assertTrue(m.moveToHomeIndex("a", 1))
        assertEquals(listOf("b", "a", "c"), m.home)
        assertTrue(m.dock.isEmpty())
    }

    @Test
    fun moveToDockIndexInsertsReordersAndRejectsWhenFull() {
        val m = model("a", "b", "c", dockSlots = 2)
        assertTrue(m.moveToDockIndex("a", 0, 2))
        assertTrue(m.moveToDockIndex("b", 0, 2))
        assertEquals(listOf("b", "a"), m.dock)
        assertFalse(m.moveToDockIndex("c", 0, 2))
        assertTrue(m.moveToDockIndex("b", 5, 2))
        assertEquals(listOf("a", "b"), m.dock)
        assertEquals(listOf("c"), m.home)
    }

    // ---------------------------------------------------------------- folders

    @Test
    fun droppingOnAnAppCreatesAFolderAtTheTargetPosition() {
        val m = model("a", "b", "c", "d")
        assertTrue(m.mergeIntoFolder("d", "b"))
        assertEquals(listOf("a", "folder:1", "c"), m.home)
        assertEquals(listOf("b", "d"), m.folders["folder:1"]!!.apps)
        assertEquals("Folder", m.folders["folder:1"]!!.name)
    }

    @Test
    fun droppingOnAFolderAddsTheApp() {
        val m = model("a", "b", "c", "d")
        m.mergeIntoFolder("d", "b")
        assertTrue(m.mergeIntoFolder("c", "folder:1"))
        assertEquals(listOf("a", "folder:1"), m.home)
        assertEquals(listOf("b", "d", "c"), m.folders["folder:1"]!!.apps)
        assertFalse(m.mergeIntoFolder("c", "folder:1"))
    }

    @Test
    fun foldersCannotBeNestedOrMergedIntoThemselves() {
        val m = model("a", "b", "c")
        m.mergeIntoFolder("b", "a")
        assertFalse(m.mergeIntoFolder("folder:1", "c"))
        assertFalse(m.mergeIntoFolder("c", "c"))
        assertFalse(m.moveToDockIndex("folder:1", 0, 4))
    }

    @Test
    fun emptyingAFolderRemovesIt() {
        val m = model("a", "b", "c", "d")
        m.mergeIntoFolder("d", "b")
        assertTrue(m.moveToHomeIndex("b", 0))
        assertEquals(listOf("b", "a", "folder:1", "c"), m.home)
        assertTrue(m.moveToHomeIndex("d", Int.MAX_VALUE))
        assertEquals(listOf("b", "a", "c", "d"), m.home)
        assertTrue(m.folders.isEmpty())
    }

    @Test
    fun dissolveFolderReturnsAppsInPlace() {
        val m = model("a", "b", "c", "d")
        m.mergeIntoFolder("d", "b")
        assertTrue(m.dissolveFolder("folder:1"))
        assertEquals(listOf("a", "b", "d", "c"), m.home)
        assertTrue(m.folders.isEmpty())
        assertFalse(m.dissolveFolder("folder:1"))
    }

    @Test
    fun renameFolderTrimsAndFallsBack() {
        val m = model("a", "b")
        m.mergeIntoFolder("b", "a")
        assertTrue(m.renameFolder("folder:1", "  Games  "))
        assertEquals("Games", m.folders["folder:1"]!!.name)
        assertFalse(m.renameFolder("folder:1", "Games"))
        assertTrue(m.renameFolder("folder:1", "   "))
        assertEquals("Folder", m.folders["folder:1"]!!.name)
    }

    @Test
    fun shiftInsideFolder() {
        val m = model("a", "b", "c")
        m.mergeIntoFolder("b", "a")
        m.mergeIntoFolder("c", "folder:1")
        assertEquals(listOf("a", "b", "c"), m.folders["folder:1"]!!.apps)
        assertTrue(m.shiftInFolder("folder:1", "c", -1))
        assertEquals(listOf("a", "c", "b"), m.folders["folder:1"]!!.apps)
        assertFalse(m.shiftInFolder("folder:1", "a", -1))
    }

    @Test
    fun uninstallingAppsInsideAFolderShrinksAndThenRemovesIt() {
        val m = model("a", "b", "c", "d")
        m.mergeIntoFolder("d", "b")
        assertTrue(m.reconcile(listOf("a", "b", "c"), 4))
        assertEquals(listOf("b"), m.folders["folder:1"]!!.apps)
        assertTrue(m.reconcile(listOf("a", "c"), 4))
        assertTrue(m.folders.isEmpty())
        assertEquals(listOf("a", "c"), m.home)
    }

    @Test
    fun orphanFolderReleasesItsApps() {
        val m = HomeModel()
        m.home.add("b")
        m.folders["folder:9"] = FolderData("Old", arrayListOf("a"))
        assertTrue(m.reconcile(listOf("a", "b"), 4))
        assertEquals(listOf("b", "a"), m.home)
        assertTrue(m.folders.isEmpty())
    }

    @Test
    fun appInTwoPlacesIsKeptOnlyOnce() {
        val m = HomeModel()
        m.home.addAll(listOf("a", "b"))
        m.folders["folder:1"] = FolderData("F", arrayListOf("a", "c"))
        m.home.add("folder:1")
        assertTrue(m.reconcile(listOf("a", "b", "c"), 4))
        assertEquals(listOf("b", "folder:1"), m.home)
        assertEquals(listOf("a", "c"), m.folders["folder:1"]!!.apps)
    }

    // ---------------------------------------------------------------- hidden apps and recents

    @Test
    fun hiddenAppsLeaveEveryPlaceAndStayHidden() {
        val m = model("a", "b", "c")
        m.addToDock("a", 4)
        assertTrue(m.hide("a"))
        assertTrue(m.hide("b"))
        assertTrue(m.dock.isEmpty())
        assertEquals(listOf("c"), m.home)
        assertFalse(m.reconcile(listOf("a", "b", "c"), 4))
        assertEquals(listOf("c"), m.home)
        assertFalse(m.hide("a"))
    }

    @Test
    fun unhideReturnsTheAppToTheHomeScreen() {
        val m = model("a", "b", "c")
        m.hide("b")
        assertTrue(m.unhide("b"))
        assertEquals(listOf("a", "c", "b"), m.home)
        assertFalse(m.unhide("b"))
    }

    @Test
    fun hidingAnAppInsideAFolderCanEmptyTheFolder() {
        val m = model("a", "b")
        m.mergeIntoFolder("b", "a")
        m.hide("a")
        m.hide("b")
        assertTrue(m.folders.isEmpty())
        assertTrue(m.home.isEmpty())
    }

    @Test
    fun recentsAreMostRecentFirstCappedAndPruned() {
        val keys = (1..12).map { "app" + it }
        val m = HomeModel()
        m.reconcile(keys, 4)
        for (k in keys) assertTrue(m.recordLaunch(k))
        assertEquals(HomeModel.MAX_RECENTS, m.recents.size)
        assertEquals("app12", m.recents.first())
        assertFalse(m.recordLaunch("app12"))
        assertTrue(m.recordLaunch("app5"))
        assertEquals("app5", m.recents.first())
        assertTrue(m.reconcile(keys.filter { it != "app5" }, 4))
        assertFalse(m.recents.contains("app5"))
    }
}
