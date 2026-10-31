package app.ioshome.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeModelTest {

    @Test
    fun firstReconcilePlacesEveryAppInOrder() {
        val m = HomeModel()
        assertTrue(m.reconcile(listOf("a", "b", "c"), 4))
        assertEquals(listOf("a", "b", "c"), m.home)
        assertTrue(m.dock.isEmpty())
    }

    @Test
    fun removedFromHomeStaysRemovedButNewAppsAreAppended() {
        val m = HomeModel()
        m.reconcile(listOf("a", "b", "c"), 4)
        assertTrue(m.removeFromHome("b"))
        assertFalse(m.reconcile(listOf("a", "b", "c"), 4))
        assertEquals(listOf("a", "c"), m.home)
        assertTrue(m.reconcile(listOf("a", "b", "c", "d"), 4))
        assertEquals(listOf("a", "c", "d"), m.home)
    }

    @Test
    fun uninstalledAppsAreDroppedFromHomeAndDock() {
        val m = HomeModel()
        m.reconcile(listOf("a", "b", "c"), 4)
        m.addToDock("a", 4)
        assertTrue(m.reconcile(listOf("b"), 4))
        assertEquals(listOf("b"), m.home)
        assertTrue(m.dock.isEmpty())
    }

    @Test
    fun dockRespectsSlotLimitAndRejectsDuplicates() {
        val m = HomeModel()
        m.reconcile(listOf("a", "b", "c"), 2)
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
        val m = HomeModel()
        m.reconcile(listOf("a", "b", "c"), 4)
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
        val m = HomeModel()
        m.reconcile(listOf("a", "b"), 4)
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
}
