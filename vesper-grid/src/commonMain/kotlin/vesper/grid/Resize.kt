package vesper.grid

// The public side of a resize, and the alt screen's truncation. The ring rebuild lives in Grid, which
// owns the ring; the per-line work lives in Reflow so it is tested without a ring.

/** How rows meet a new width. */
public enum class Reflow {
    /** Join soft-wrapped rows into lines and cut them at the new width: the primary screen and its scrollback. */
    REWRAP,

    /** Keep every row as it is, cutting or padding its tail: the alt screen, whose application redraws on SIGWINCH anyway. */
    TRUNCATE,
}

/**
 * A position that must follow its cell through a resize: the cursor, the saved cursor, selection ends,
 * a search match.
 *
 * [row] is a logical grid row (0 is the oldest history row, [Grid.historyLength] the top of the
 * screen), so points in scrollback and on the screen are expressed alike: a screen position (r, c) is
 * `TrackPoint(grid.historyLength + r, c)` before the resize and `point.row - grid.historyLength` after.
 */
public data class TrackPoint(
    val row: Int,
    val col: Int,
    /**
     * Set by the resize when the point's cell did not survive (history evicted it, truncation cut it,
     * the row it was on was dropped) and the point was clamped to the nearest place still in the
     * grid. A selection whose end is clamped should be cleared.
     */
    val clamped: Boolean = false,
)

/** Where a resize moved the cursor and the caller's points, in the order they were given. */
public data class Tracked(
    val cursor: TrackPoint,
    val points: List<TrackPoint>,
)

/** A caller's point moved inside a grid of [total] rows and [cols] columns. */
internal fun TrackPoint.clampIn(
    total: Int,
    cols: Int,
): Spot {
    val r = row.coerceIn(0, total - 1)
    val c = col.coerceIn(0, cols - 1)
    return Spot(r, c, lost = r != row || c != col)
}

/** Every row cut or padded to [cols]; a spot whose cell changed is lost. */
internal fun truncate(
    rows: Array<Row?>,
    cols: Int,
    spots: List<Spot>,
): ArrayList<Row> {
    val kept = ArrayList<Row>(rows.size)
    rows.mapTo(kept) { checkNotNull(it) }
    val before = spots.map { kept[it.row].cell(it.col) }
    for (row in kept) row.setCols(cols)
    spots.forEachIndexed { i, s ->
        val col = s.col.coerceAtMost(cols - 1)
        s.lost = s.lost || col != s.col || kept[s.row].cell(col) != before[i]
        s.col = col
    }
    return kept
}
