package vesper.term

import vesper.grid.Grid
import vesper.grid.MAX_COLS
import vesper.grid.Reflow
import vesper.grid.TrackPoint

// Resizing the terminal: both screens take the new size, the cursor and the saved cursors follow their
// cells, and what is sized to the screen (margins, tab stops) is rebuilt. Separate from Screen.kt
// because it drives the grid's reflow instead of switching screens.
//
// The primary screen and its scrollback are rewrapped; the alternate screen is cut or padded, because
// its application redraws on SIGWINCH anyway (kitty and WezTerm do the same). The grid moves every
// tracked point with its cell (Grid.resize); this file only says which points those are and brings
// them back onto the screen.

/** Resizes both screens to [cols] by [rows]; the same size is a no-op. */
internal fun State.resize(
    cols: Int,
    rows: Int,
) {
    require(cols in 1..MAX_COLS && rows in 1..MAX_ROWS) { "a terminal of $cols x $rows" }
    if (cols == this.cols && rows == this.rows) return
    val sameWidth = cols == this.cols
    val main = if (altActive) alt else grid
    val other = if (altActive) grid else alt
    val shown = Cursor(row, col, pendingWrap)
    // A hidden screen has no live cursor. The main screen's is what DECSC kept on the way to the
    // alternate screen (?1049); without one its bottom row stands in, so shrinking pushes its top into
    // history instead of cutting rows off below.
    val mainCursor = if (altActive) saved[MAIN_SLOT]?.cursor ?: Cursor(row = main.screenRows - 1) else shown
    val altCursor = if (altActive) shown else saved[ALT_SLOT]?.cursor ?: Cursor()
    val (mainAt, mainSaved) =
        main.resizeScreen(
            cols,
            rows,
            Reflow.REWRAP,
            mainCursor,
            saved[MAIN_SLOT],
            if (altActive) hiddenImages else images,
            prompts,
        )
    val (altAt, altSaved) =
        other.resizeScreen(
            cols,
            rows,
            Reflow.TRUNCATE,
            altCursor,
            saved[ALT_SLOT],
            if (altActive) images else hiddenImages,
        )
    saved[MAIN_SLOT] = mainSaved
    saved[ALT_SLOT] = altSaved
    val at = if (altActive) altAt else mainAt
    row = at.row
    col = at.col
    // A wrap pending at the old right edge means nothing at another width.
    pendingWrap = at.pendingWrap && sameWidth
    this.cols = cols
    this.rows = rows
    lastCol = cols - 1
    lastRow = rows - 1
    resetMargins()
    tabs.resize(cols)
    endCluster()
}

private val SavedCursor.cursor: Cursor get() = Cursor(row, col, pendingWrap)

/**
 * Resizes this screen and moves [cursor] and [saved] (screen positions) with their cells, then clamps both onto the
 * screen. [images]' placements move with their top-left cells, and [prompts] with their lines; one whose cell is lost goes.
 */
private fun Grid.resizeScreen(
    cols: Int,
    rows: Int,
    reflow: Reflow,
    cursor: Cursor,
    saved: SavedCursor?,
    images: ScreenImages,
    prompts: PromptMarks? = null,
): Pair<Cursor, SavedCursor?> {
    val base = historyLength
    val anchors = images.anchors(this)
    val marks = prompts?.points(this).orEmpty()
    val points = listOfNotNull(saved?.let { TrackPoint(base + it.row, it.col) }) + anchors.points + marks
    val tracked = resize(cols, rows, reflow, TrackPoint(base + cursor.row, cursor.col), points)
    val savedPoints = if (saved == null) 0 else 1
    val markPoints = savedPoints + anchors.points.size
    images.reanchor(this, anchors, tracked.points.subList(savedPoints, markPoints))
    prompts?.reanchor(this, tracked.points.subList(markPoints, tracked.points.size))
    images.prune(firstLine)
    val top = historyLength
    val onScreen = { p: TrackPoint -> (p.row - top).coerceIn(0, rows - 1) to p.col.coerceAtMost(cols - 1) }
    val (r, c) = onScreen(tracked.cursor)
    val moved =
        saved?.let { s ->
            val (sr, sc) = onScreen(tracked.points[0])
            SavedCursor(sr, sc, s.pendingWrap, s.style, s.origin, s.charsets)
        }
    return Cursor(r, c, cursor.pendingWrap) to moved
}
