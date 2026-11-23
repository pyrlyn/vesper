package scull.term

import scull.grid.Row
import scull.grid.RowId

// Damage between two pictures of the viewport. No dirty bits are kept: every row has a stable id and a
// generation bumped by each visible change, so a picture that kept each row's (id, generation) finds
// its damage by comparing stamps. A row whose id shows up at another position with the same
// generation moved unchanged; runs of such rows with one offset become one scroll, as foot coalesces
// its scroll damage. Alacritty falls back to full damage while the viewport is scrolled back; with ids,
// viewport scrolling and region scrolling are the same case.

/** What a picture remembers of one row to tell whether it changed. */
public data class RowStamp(
    /** The logical line shown. */
    val id: RowId,
    /** Its generation when the picture was taken. */
    val generation: Int,
) {
    public companion object {
        /** No row: what a picture holds where the grid had none. */
        public val NONE: RowStamp = RowStamp(RowId.NONE, 0)

        /** The stamp of [row] as it is now. */
        public fun of(row: Row): RowStamp = RowStamp(row.id, row.generation)
    }
}

/**
 * Rows [start] until [end] of the new picture are, unchanged, the rows from [from] of the previous
 * one. All scrolls of one update read the previous picture, so a UI blits them from its last frame in
 * any order.
 */
public data class Scroll(
    val start: Int,
    val end: Int,
    val from: Int,
) {
    /** The rows moved into. */
    public val rows: IntRange get() = start until end
}

/** What changed between two pictures. Its buffers are kept across updates. */
public class Damage {
    /** Whether everything must be repainted: first picture, other screen, other size. Every row is then also dirty. */
    public var isFull: Boolean = false
        private set

    private val scrollList = ArrayList<Scroll>()
    private val dirtyList = ArrayList<Int>()

    // Previous row of each id: a scratch index.
    private val index = HashMap<RowId, Int>()

    /** Rows moved unchanged, to apply before repainting the dirty rows. */
    public val scrolls: List<Scroll> get() = scrollList

    /** Rows to repaint, top to bottom. */
    public val dirty: List<Int> get() = dirtyList

    public val isEmpty: Boolean get() = !isFull && scrollList.isEmpty() && dirtyList.isEmpty()

    /** No damage. */
    public fun clear() {
        isFull = false
        scrollList.clear()
        dirtyList.clear()
    }

    /** The damage that turns picture [old] into [new]. [full], or a change in row count, repaints everything. */
    public fun compute(
        old: List<RowStamp>,
        new: List<RowStamp>,
        full: Boolean,
    ): Unit = compute(old, new, full) { _, _ -> true }

    /** As [compute], with row [from] of [old] standing for row `to` of [new] only when [sameMarks] says their highlights match. */
    internal fun compute(
        old: List<RowStamp>,
        new: List<RowStamp>,
        full: Boolean,
        sameMarks: (from: Int, to: Int) -> Boolean,
    ) {
        clear()
        if (full || old.size != new.size) {
            isFull = true
            dirtyList.addAll(new.indices)
            return
        }
        index.clear()
        old.forEachIndexed { row, stamp -> index[stamp.id] = row }
        new.forEachIndexed { row, stamp ->
            if (old[row] == stamp && sameMarks(row, row)) return@forEachIndexed
            val from = index[stamp.id]?.takeIf { old[it] == stamp && sameMarks(it, row) }
            if (from == null) dirtyList += row else pushScroll(row, from)
        }
    }

    /** Extends the last scroll when [row] continues it with the same offset. */
    private fun pushScroll(
        row: Int,
        from: Int,
    ) {
        val last = scrollList.lastOrNull()
        if (last != null && last.end == row && last.from + (row - last.start) == from) {
            scrollList[scrollList.lastIndex] = last.copy(end = row + 1)
        } else {
            scrollList += Scroll(row, row + 1, from)
        }
    }
}
