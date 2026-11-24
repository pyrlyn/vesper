package vesper.macos

import vesper.term.Damage

/** In [RowSources.sources]: the row must be repainted. */
internal const val REPAINT = -1

/**
 * The damage of every frame update since the last draw, composed. The core reports damage per update,
 * but the view may update several times (output, a key, a resize) before it draws once.
 */
internal class RowSources {
    /**
     * For each row of the latest frame, the row of the last drawn picture it still shows unchanged, or
     * [REPAINT]. Empty until the first update: everything is to be painted.
     */
    var sources = IntArray(0)
        private set

    fun absorb(
        damage: Damage,
        rows: Int,
    ) {
        if (damage.isFull || rows != sources.size) {
            sources = IntArray(rows) { REPAINT }
            return
        }
        // Every scroll of one update reads the picture as it was before that update.
        val before = sources.copyOf()
        for (scroll in damage.scrolls) {
            for (row in scroll.start until minOf(scroll.end, rows)) {
                sources[row] = before.getOrElse(scroll.from + row - scroll.start) { REPAINT }
            }
        }
        for (row in damage.dirty) if (row in 0 until rows) sources[row] = REPAINT
    }

    /** The picture of [rows] rows was drawn: nothing has changed since. */
    fun drawn(rows: Int) {
        sources = IntArray(rows) { it }
    }
}

/**
 * Which buffer slot holds each row's quads. A scroll only reorders this table: rows that moved keep
 * their slots, and only rows that lost theirs are built again.
 */
internal class RowSlots(
    rows: Int,
) {
    var slotOfRow = IntArray(rows) { it }
        private set

    /** Moves reused rows' slots to their new rows; answers the rows that got a free slot. */
    fun remap(sources: IntArray): List<Int> {
        val taken = BooleanArray(slotOfRow.size)
        val next = IntArray(sources.size) { REPAINT }
        for ((row, source) in sources.withIndex()) {
            if (source !in slotOfRow.indices || taken[slotOfRow[source]]) continue
            next[row] = slotOfRow[source]
            taken[slotOfRow[source]] = true
        }
        val free = taken.indices.filter { !taken[it] }.toMutableList()
        val dirty = ArrayList<Int>()
        for (row in next.indices) {
            if (next[row] != REPAINT) continue
            next[row] = free.removeAt(free.size - 1)
            dirty.add(row)
        }
        slotOfRow = next
        return dirty
    }
}
