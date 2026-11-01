package app.ioshome.launcher

import android.view.View

/**
 * Live rearrangement preview shown while an icon is being dragged. It only moves the views that are
 * already on screen (springs on translation); the saved layout is changed once, when the drop succeeds.
 */
class ReorderPreview {
    private val moved = ArrayList<View>()

    /** Springs every moved view back to its real position. */
    fun reset(animate: Boolean) {
        for (v in moved) Springs.move(v, 0f, 0f, animate)
        moved.clear()
    }

    /**
     * [items] is the full home list, [dragKey] the dragged item and [insertIdx] where it would land in the list
     * once removed from its old place (its final index). Views whose slot changes spring to their new cell,
     * or slide off the page if they would flow onto the next or previous page.
     */
    fun applyGrid(grid: HomeGridView, page: Int, items: List<String>, dragKey: String, insertIdx: Int, animate: Boolean) {
        val cols = grid.columns
        val capacity = (grid.columns * grid.rows).coerceAtLeast(1)
        val dragIdx = items.indexOf(dragKey)
        val cw = grid.cellWidth()
        val ch = grid.cellHeight()
        for (c in 0 until grid.childCount) {
            val v = grid.getChildAt(c)
            val global = page * capacity + c
            val key = items.getOrNull(global) ?: continue
            if (key == dragKey) continue
            val baseIdx = if (dragIdx in 0 until global) global - 1 else global
            val newIdx = if (baseIdx >= insertIdx) baseIdx + 1 else baseIdx
            val newPage = newIdx / capacity
            val newCell = newIdx % capacity
            val dx = (newPage - page) * grid.width + ((newCell % cols) - (c % cols)) * cw
            val dy = ((newCell / cols) - (c / cols)) * ch
            Springs.move(v, dx, dy, animate)
            if (!moved.contains(v)) moved.add(v)
        }
    }

    /** Dock version: the row stays centred, so items also shift by half a slot when the count changes. */
    fun applyDock(dock: DockView, keys: List<String>, dragKey: String, insertIdx: Int, slotWidth: Int, animate: Boolean) {
        val n = dock.childCount
        val dragIdx = keys.indexOf(dragKey)
        val total = n - (if (dragIdx >= 0) 1 else 0) + 1
        for (i in 0 until n) {
            val v = dock.getChildAt(i)
            val key = keys.getOrNull(i) ?: continue
            if (key == dragKey) continue
            val baseIdx = if (dragIdx in 0 until i) i - 1 else i
            val newIdx = if (baseIdx >= insertIdx) baseIdx + 1 else baseIdx
            val dx = (n - total) * slotWidth / 2f + (newIdx - i) * slotWidth
            Springs.move(v, dx, 0f, animate)
            if (!moved.contains(v)) moved.add(v)
        }
    }
}
