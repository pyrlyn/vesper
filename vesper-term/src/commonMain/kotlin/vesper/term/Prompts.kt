package vesper.term

import vesper.grid.Grid
import vesper.grid.TrackPoint

// Where shells said their prompts start (OSC 133 `A`), so the viewport can jump between them.
// Separate from the OSC dispatch because the marks outlive the sequence: like image placements they
// are absolute lines (Grid.firstLine plus the ring index), so scrolling the whole screen moves
// nothing, and only eviction from the ring, ED 2, ED 3, RIS and reflow touch them. Only the primary
// screen keeps them: a full-screen program on the alternate screen has no prompts to walk, and its
// own rows would shadow the shell's. The count needs no cap of its own: one mark per line at most,
// and every line the ring has dropped is pruned.

/** The primary screen's prompt lines, sorted and without duplicates. */
internal class PromptMarks {
    private val lines = ArrayList<Long>()

    /** The marks, oldest first; for tests. */
    fun toList(): List<Long> = lines.toList()

    fun record(line: Long) {
        val at = lines.binarySearch(line)
        if (at < 0) lines.add(-at - 1, line)
    }

    /** Drops the marks on lines from [from] until [to]. */
    fun removeRange(
        from: Long,
        to: Long,
    ) {
        val lo = indexOf(from)
        val hi = indexOf(to)
        if (lo < hi) lines.subList(lo, hi).clear()
    }

    /** Drops the marks above [firstLine], which the ring no longer holds. */
    fun prune(firstLine: Long): Unit = removeRange(Long.MIN_VALUE, firstLine)

    /** The nearest mark above [line], or null. */
    fun before(line: Long): Long? = indexOf(line).let { if (it > 0) lines[it - 1] else null }

    /** The nearest mark below [line], or null. */
    fun after(line: Long): Long? = lines.getOrNull(indexOf(line + 1))

    /** Each mark as a point at the start of its line on [grid], whose rows are about to be reflowed. */
    fun points(grid: Grid): List<TrackPoint> {
        prune(grid.firstLine)
        val first = grid.firstLine
        return lines.map { TrackPoint((it - first).toInt(), 0) }
    }

    /** Puts the marks where the reflow took their [moved] points; one whose line was lost goes, and two rewrapped onto one line become one. */
    fun reanchor(
        grid: Grid,
        moved: List<TrackPoint>,
    ) {
        lines.clear()
        for (p in moved) if (!p.clamped) record(grid.firstLine + p.row)
    }

    /** Where [line] is or would go. */
    private fun indexOf(line: Long): Int = lines.binarySearch(line).let { if (it < 0) -it - 1 else it }
}

/** The grid the primary screen's text is in, whichever screen is shown. */
internal val State.mainGrid: Grid get() = if (altActive) alt else grid

/** OSC 133 `A` at the cursor; the alternate screen records nothing. */
internal fun State.markPrompt() {
    if (!altActive) prompts.record(grid.screenTopLine + row)
}

/** Drops the marks on the primary screen's rows, which ED 2 and RIS have just blanked. */
internal fun State.erasePrompts() {
    if (altActive) return
    val top = grid.screenTopLine
    prompts.removeRange(top, top + rows)
}

/**
 * Scrolls [grid] so that the prompt before (or, [forward], after) the viewport's top row is its top
 * row, as far as history allows; whether the viewport moved.
 */
internal fun PromptMarks.step(
    grid: Grid,
    forward: Boolean,
): Boolean {
    val top = grid.screenTopLine - grid.displayOffset
    val line = (if (forward) after(top) else before(top)) ?: return false
    // A prompt on the screen below its top row cannot become the top: the viewport goes as low as it can.
    val offset = (grid.screenTopLine - line).coerceIn(0, grid.historyLength.toLong()).toInt()
    if (offset == grid.displayOffset) return false
    grid.scrollDisplay(offset - grid.displayOffset)
    return true
}
