package vesper.grid

// Rewrapping rows to a new width in one pass, carrying tracking points. Separate from the ring so the
// line arithmetic is tested on plain row lists; the grid decides afterwards which rows become screen
// and history.
//
// The approach follows foot (docs/research/foot-contour.md §1.7, ideas only, nothing ported): rows
// joined by their soft-wrap flag form logical lines, and every line is cut again at the new width
// while the points, sorted by position, are translated as the copy passes them. Cost is one visit per
// carried cell plus sorting the points.

private const val NONE = -1
private const val ROW_SHIFT = 32
private const val COL_SHIFT = 1
private const val COL_MASK = 0xFFFF
private const val EXACT = 1L

/** A position in a list of rows, moved by a resize; [lost] says the cell that was there may have changed or vanished. */
internal class Spot(
    var row: Int,
    var col: Int,
    var lost: Boolean = false,
)

/** Where one fed cell ended up, packed so the per-cell path allocates nothing. */
private value class Landing(
    val bits: Long,
) {
    constructor(row: Int, col: Int, exact: Boolean) :
        this((row.toLong() shl ROW_SHIFT) or (col.toLong() shl COL_SHIFT) or (if (exact) EXACT else 0L))

    val row: Int get() = (bits ushr ROW_SHIFT).toInt()
    val col: Int get() = (bits.toInt() ushr COL_SHIFT) and COL_MASK
    val exact: Boolean get() = bits and EXACT != 0L

    fun inexact(): Landing = Landing(bits and EXACT.inv())
}

/**
 * Rewraps [old] (oldest first) to [cols] columns and moves every spot in [spots] (old coordinates in,
 * new ones out) with its cell. Old rows are dropped as their line is consumed, so peak memory is one
 * extra line. Rows that start no old row get ids from [freshId].
 */
internal fun rewrap(
    old: Array<Row?>,
    cols: Int,
    spots: List<Spot>,
    freshId: () -> RowId,
): ArrayList<Row> {
    // Sorted copies of the old positions: the spots themselves are overwritten as they land.
    val order = spots.indices.sortedWith(compareBy({ spots[it].row }, { spots[it].col }))
    val keyRow = IntArray(order.size) { spots[order[it]].row }
    val keyCol = IntArray(order.size) { spots[order[it]].col }
    var next = 0
    val out = Out(cols, old, freshId)
    var start = 0
    while (start < old.size) {
        val end = lineEnd(old, start)
        val fill = lineFill(checkNotNull(old[end]))
        var fed = 0
        for (r in start..end) {
            val row = checkNotNull(old[r])
            var pointEnd = 0
            var k = next
            while (k < order.size && keyRow[k] <= r) {
                if (keyRow[k] == r) pointEnd = maxOf(pointEnd, keyCol[k] + 1)
                k++
            }
            val kept = keptLength(row, r == end, pointEnd)
            out.candidate = r
            val links = row.links
            var span = 0
            for (c in 0 until kept) {
                while (span < links.size && links[span].end <= c) span++
                val link = links.getOrNull(span)?.takeIf { it.start <= c }?.link
                val at = out.feed(row.cell(c), link, fill)
                fed++
                while (next < order.size && (keyRow[next] < r || (keyRow[next] == r && keyCol[next] <= c))) {
                    val exact = at.exact && keyRow[next] == r && keyCol[next] == c
                    spots[order[next]].land(at.row, at.col, exact)
                    next++
                }
            }
        }
        val tail = out.endLine(start, fed, fill)
        // Points left in this line sat on a dropped leading spacer with nothing after it; they stay at the line's end.
        while (next < order.size && keyRow[next] <= end) {
            spots[order[next]].land(tail.row, tail.col, false)
            next++
        }
        for (r in start..end) old[r] = null
        start = end + 1
    }
    return out.rows
}

private fun Spot.land(
    row: Int,
    col: Int,
    exact: Boolean,
) {
    this.row = row
    this.col = col
    lost = lost || !exact
}

/** The last row of the logical line that starts at [start]. */
private fun lineEnd(
    old: Array<Row?>,
    start: Int,
): Int {
    var end = start
    while (end + 1 < old.size && old[end]?.wrapped == true) end++
    return end
}

/** The fill every row of a line is padded with: the last row's when it is erased space, else plain blank, since a text fill only spoke for the old width. */
internal fun lineFill(last: Row): Cell = if (last.fillIsBlank) last.fillCell else Cell.EMPTY

/**
 * Cells of [row] that belong to its line. A wrapped row drops the placeholder a wide character left at
 * its edge; the last row stops after its content, or after the furthest point on it, so a cursor past
 * the text keeps its column.
 */
internal fun keptLength(
    row: Row,
    last: Boolean,
    pointEnd: Int,
): Int {
    if (last) return maxOf(row.contentLength, pointEnd).coerceAtMost(row.cols)
    val edge = row.cols - 1
    return if (edge >= 0 && row.cell(edge).hasFlags(CellFlags.LEADING_SPACER)) edge else row.cols
}

/** The row being assembled and everything already finished. */
private class Out(
    private val cols: Int,
    private val old: Array<Row?>,
    private val freshId: () -> RowId,
) {
    val rows = ArrayList<Row>(old.size)
    private val cells = LongArray(cols)
    private var used = 0
    private var links = ArrayList<LinkSpan>()

    /** The old row whose first cell starts the row being built: its id lives on. */
    private var origin = NONE

    /** The old row whose first cell has not been placed yet. */
    var candidate = NONE

    /** A wide head was laid down and its spacer is due next. */
    private var afterHead = false

    /** A wide head was blanked (a one-column grid) and its spacer is dropped. */
    private var headDropped = false
    private var last = Landing(0, 0, false)

    /** Lays one cell of a line down and says where it went. */
    fun feed(
        cell: Cell,
        link: LinkId?,
        fill: Cell,
    ): Landing {
        if (cell.hasFlags(CellFlags.SPACER)) {
            if (headDropped) {
                headDropped = false
                return last.inexact()
            }
            if (afterHead) {
                afterHead = false
                return push(cell, link)
            }
        }
        afterHead = false
        var laid = cell
        var width = 1
        var exact = true
        if (cell.hasFlags(CellFlags.WIDE)) {
            if (cols < 2) {
                // A wide character wider than the grid: nothing can show it.
                laid = Cell.blank(cell.style)
                headDropped = true
                exact = false
            } else {
                width = 2
                afterHead = true
            }
        }
        if (used + width > cols) {
            if (width == 2 && used + 1 == cols) cells[used++] = Cell.EMPTY.withFlags(CellFlags.LEADING_SPACER).bits
            finishRow(wrapped = true, fill)
        }
        val at = push(laid, link)
        return if (exact) at else at.inexact()
    }

    private fun push(
        cell: Cell,
        link: LinkId?,
    ): Landing {
        val first = candidate
        candidate = NONE
        if (used == 0) origin = first
        val col = used
        cells[used++] = cell.bits
        if (link != null) {
            val span = links.lastOrNull()
            if (span != null && span.link == link && span.end == col) {
                links[links.lastIndex] = span.copy(end = col + 1)
            } else {
                links += LinkSpan(col, col + 1, link)
            }
        }
        last = Landing(rows.size, col, true)
        return last
    }

    /** Ends a line; an empty one still yields a row, keeping its first old row's id. Returns the spot just past the line's last cell. */
    fun endLine(
        first: Int,
        fed: Int,
        fill: Cell,
    ): Landing {
        if (fed == 0) origin = first
        candidate = NONE
        afterHead = false
        headDropped = false
        val at = Landing(rows.size, used.coerceAtMost(cols - 1), false)
        finishRow(wrapped = false, fill)
        return at
    }

    private fun finishRow(
        wrapped: Boolean,
        fill: Cell,
    ) {
        val source = if (origin == NONE) null else old[origin]
        origin = NONE
        val row =
            Row.rebuilt(
                id = source?.id ?: freshId(),
                generation = source?.generation ?: 0,
                cols = cols,
                wrapped = wrapped,
                fill = fill,
                cells = cells.copyOf(used),
                length = used,
                links = links,
            )
        if (source != null && !row.sameContent(source)) row.markChanged()
        rows += row
        used = 0
        links = ArrayList()
    }
}
