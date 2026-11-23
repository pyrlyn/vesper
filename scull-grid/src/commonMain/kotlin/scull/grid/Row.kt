package scull.grid

// One row of cells with its identity, generation and out-of-line extras. Separate from the ring so
// storage tricks (uniform rows, trimmed tails, wide-character fix-ups) are tested without scrolling.
//
// Storage is a written prefix plus a fill cell standing for every column past it, so a blank or
// uniformly erased row (Contour's "trivial line", docs/research/foot-contour.md §2.4) has no cell
// array at all, and a history row keeps only the columns up to its last non-fill cell.
//
// For damage (T9) a row carries a stable RowId, fixed while the same logical line scrolls through
// screen and history and replaced when the slot is recycled, and a generation bumped by every
// visible change. A frame that cached (id, generation) per row repaints exactly the rows whose pair
// differs, and reads a scroll as known ids at new positions.

/** Columns a row may have: link spans and the C ABI count them in 16 bits. */
public const val MAX_COLS: Int = 0xFFFF

private val NO_CELLS = LongArray(0)
private const val ASCII_MASK = 0x7FL

/** Identity of a logical row, unique within one grid. */
public value class RowId(
    public val value: Long,
) {
    public companion object {
        /** The id of a slot that holds no logical row. */
        public val NONE: RowId = RowId(0)
    }
}

/** An OSC 8 hyperlink as the terminal layer numbers it; the grid only keeps which columns carry which id. */
public value class LinkId(
    public val value: Int,
)

/** Columns [start] until [end] of a row carry [link]. */
public data class LinkSpan(
    public val start: Int,
    public val end: Int,
    public val link: LinkId,
)

/** One row of the grid: [cols] cells, of which only a prefix is stored. */
public class Row(
    id: RowId,
    cols: Int,
    fill: Cell = Cell.EMPTY,
) {
    init {
        require(cols in 0..MAX_COLS) { "a row of $cols columns" }
    }

    /** Stable identity of the logical line in this row. */
    public var id: RowId = id
        private set

    /** Bumped by every change a frame must repaint. It wraps after 2^32 changes; T9 may not rely on more. */
    public var generation: Int = 0
        private set

    public val cols: Int = cols

    /** Whether the line continues in the next row (soft wrap), for reflow and copy. */
    public var wrapped: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                touch()
            }
        }

    private var fill: Cell = fill
    private var cells: LongArray = NO_CELLS
    private var len = 0

    /** Rare per-row data, null for rows without links. Spans are sorted and disjoint, so never more than [cols]. */
    private var spans: ArrayList<LinkSpan>? = null

    /** Whether every column holds the same cell, with no cell array. */
    public val isUniform: Boolean get() = len == 0

    /** Cells the storage has room for, so tests can see compaction. */
    internal val cellCapacity: Int get() = cells.size

    /** The cell at [col]. */
    public fun cell(col: Int): Cell {
        checkColumn(col)
        return if (col < len) Cell(cells[col]) else fill
    }

    /**
     * Stores [cell] at [col] as is. No wide-character fix-up: for cells the caller lays out itself,
     * such as a [CellFlags.LEADING_SPACER].
     */
    public fun set(
        col: Int,
        cell: Cell,
    ) {
        if (cell(col) == cell) return
        materialize(col + 1)
        cells[col] = cell.bits
        touch()
    }

    /**
     * Writes a character at [col]; a [wide] one also writes the spacer to its right. A wide character
     * half overwritten here loses its other half, so no head is left without a spacer or spacer
     * without a head.
     */
    public fun put(
        col: Int,
        cell: Cell,
        wide: Boolean = false,
    ) {
        checkColumn(col)
        val end = col + if (wide) 2 else 1
        if (end > cols) throw IndexOutOfBoundsException("a wide character at column $col of a row of $cols columns")
        breakWideEdges(col, end)
        materialize(end)
        cells[col] = (if (wide) cell.withFlags(CellFlags.WIDE) else cell.withoutFlags(CellFlags.WIDE)).bits
        if (wide) cells[col + 1] = Cell.spacer(cell.style).bits
        touch()
    }

    /**
     * Writes [count] one-cell characters at [col], one per byte of [bytes] from [from], all drawn with
     * [style]: what [put] does for each, in one call, because the terminal prints text in runs and a
     * call per cell costs more than the write (docs/benchmarks/grid.md). Only the low seven bits of a
     * byte are stored, so a byte that is not ASCII cannot reach the style or the flags.
     */
    public fun putAscii(
        col: Int,
        bytes: ByteArray,
        from: Int,
        count: Int,
        style: StyleId,
    ) {
        if (count <= 0) return
        checkColumn(col)
        val end = col + count
        if (end > cols) throw IndexOutOfBoundsException("$count characters at column $col of a row of $cols columns")
        if (from < 0 || from + count > bytes.size) throw IndexOutOfBoundsException("bytes $from until ${from + count} of ${bytes.size}")
        breakWideEdges(col, end)
        materialize(end)
        val styled = Cell.blank(style).bits
        for (i in 0 until count) cells[col + i] = styled or (bytes[from + i].toLong() and ASCII_MASK)
        touch()
    }

    /** Sets columns [start] until [end] to [cell] (EL, ECH) and drops their links. Covering the whole row makes it uniform again. */
    public fun fillRange(
        start: Int,
        end: Int,
        cell: Cell,
    ) {
        val to = end.coerceAtMost(cols)
        val from = start.coerceIn(0, to.coerceAtLeast(0))
        if (from >= to) return
        if (from == 0 && to == cols) return clear(cell)
        breakWideEdges(from, to)
        if (cell == fill && to >= len) {
            len = len.coerceAtMost(from)
        } else {
            materialize(to)
            cells.fill(cell.bits, from, to)
        }
        dropLinks(from, to)
        touch()
    }

    /** ICH: moves columns [start] until [end] right by [n]; cells pushed past [end] are dropped and [n] copies of [fill] open the gap. */
    public fun insertCells(
        start: Int,
        end: Int,
        n: Int,
        fill: Cell,
    ): Unit = shift(start, end, n, fill, right = true)

    /** DCH: deletes [n] columns at [start]; the rest of the range moves left and [n] copies of [fill] close it at [end]. */
    public fun deleteCells(
        start: Int,
        end: Int,
        n: Int,
        fill: Cell,
    ): Unit = shift(start, end, n, fill, right = false)

    /**
     * Copies [count] columns of [source] from [from] into this row at [at], dropping what runs past
     * the row (scrolling inside left and right margins). A wide character cut by either edge of the
     * copy is blanked, so no half survives.
     */
    public fun writeCells(
        at: Int,
        source: Row,
        from: Int,
        count: Int,
    ) {
        val start = at.coerceIn(0, cols)
        val end = (start + count.coerceAtMost(source.cols - from)).coerceAtMost(cols)
        if (start >= end) return
        materialize(end)
        for (i in start until end) cells[i] = source.cell(from + i - start).bits
        repairWide((start - 1).coerceAtLeast(0), end + 1)
        dropLinks(start, end)
        touch()
    }

    /** Every column becomes [cell] in O(1), and the links go too. The array is kept: a screen row is usually rewritten right away. */
    public fun clear(cell: Cell) {
        fill = cell
        len = 0
        spans = null
        touch()
    }

    /** The link at [col], if any. */
    public fun linkAt(col: Int): LinkId? = spans?.firstOrNull { it.start <= col && col < it.end }?.link

    /** The link spans, sorted by column. */
    public val links: List<LinkSpan> get() = spans ?: emptyList()

    /** Columns [start] until [end] carry [link] from now on. */
    public fun setLink(
        start: Int,
        end: Int,
        link: LinkId,
    ) {
        val to = end.coerceAtMost(cols)
        val from = start.coerceIn(0, to.coerceAtLeast(0))
        if (from >= to) return
        dropLinks(from, to)
        val list = spans ?: ArrayList<LinkSpan>().also { spans = it }
        var at = list.indexOfFirst { it.start >= from }
        if (at < 0) at = list.size
        list.add(at, LinkSpan(from, to, link))
        var i = 1
        while (i < list.size) {
            val prev = list[i - 1]
            val next = list[i]
            if (prev.end == next.start && prev.link == next.link) {
                list[i - 1] = prev.copy(end = next.end)
                list.removeAt(i)
            } else {
                i++
            }
        }
        touch()
    }

    /** Columns [start] until [end] carry no link from now on. A range that held none leaves the generation as it was. */
    public fun clearLinks(
        start: Int,
        end: Int,
    ) {
        val to = end.coerceAtMost(cols)
        val from = start.coerceIn(0, to.coerceAtLeast(0))
        // Removing nothing leaves the frame as it was, but a bump would still read as damage to T9,
        // which repaints every row whose generation moved.
        if (from < to && dropLinks(from, to)) touch()
    }

    /** Starts a new logical line in this slot: new id, no content. The array is kept for the next line. */
    public fun recycle(
        id: RowId,
        fill: Cell,
    ) {
        this.id = id
        // After the wrap flag, whose setter counts as a change.
        wrapped = false
        generation = 0
        this.fill = fill
        len = 0
        spans = null
    }

    /** Empties the slot and frees its storage, for a slot no logical row uses. */
    internal fun release() {
        recycle(RowId.NONE, Cell.EMPTY)
        cells = NO_CELLS
    }

    /** Shrinks the storage to what the content needs, for a row leaving the screen. Content and generation are unchanged. */
    public fun compact() {
        len = storedLength()
        if (len == cols && len > 0 && (1 until len).all { cells[it] == cells[0] }) {
            fill = Cell(cells[0])
            len = 0
        }
        // A row whose array already fits is left alone: a full-width line, or a recycled slot that
        // took a line as long as its last one, would be copied for nothing on every scrolled line.
        if (cells.size != len) cells = if (len == 0) NO_CELLS else cells.copyOf(len)
        spans?.trimToSize()
    }

    /** Marks every style and cluster this row refers to, for a sweep. */
    public fun mark(
        styles: Marks,
        clusters: Marks,
    ) {
        for (i in 0 until len) markCell(Cell(cells[i]), styles, clusters)
        markCell(fill, styles, clusters)
    }

    private fun markCell(
        cell: Cell,
        styles: Marks,
        clusters: Marks,
    ) {
        styles.mark(cell.style.value)
        if (cell.isCluster) clusters.mark(cell.clusterId.value)
    }

    private fun shift(
        start: Int,
        end: Int,
        count: Int,
        fill: Cell,
        right: Boolean,
    ) {
        val to = end.coerceAtMost(cols)
        val from = start.coerceIn(0, to.coerceAtLeast(0))
        val n = count.coerceIn(0, (to - from).coerceAtLeast(0))
        if (n == 0) return
        materialize(to)
        if (right) {
            cells.copyInto(cells, from + n, from, to - n)
            cells.fill(fill.bits, from, from + n)
        } else {
            cells.copyInto(cells, from, from + n, to)
            cells.fill(fill.bits, to - n, to)
        }
        repairWide((from - 1).coerceAtLeast(0), to + 1)
        dropLinks(from, to)
        touch()
    }

    /** Blanks every half of a wide character in [start] until [end] whose other half a shift moved away or overwrote. */
    private fun repairWide(
        start: Int,
        end: Int,
    ) {
        for (i in start until end.coerceAtMost(len)) {
            val cell = Cell(cells[i])
            val next = if (i + 1 < len) Cell(cells[i + 1]) else fill
            val prevWide = i >= 1 && Cell(cells[i - 1]).hasFlags(CellFlags.WIDE)
            val orphanHead = cell.hasFlags(CellFlags.WIDE) && !next.hasFlags(CellFlags.SPACER)
            val orphanSpacer = cell.hasFlags(CellFlags.SPACER) && !prevWide
            if (orphanHead || orphanSpacer) cells[i] = Cell.blank(cell.style).bits
        }
    }

    /** Stored cells up to the last one that is not the fill. */
    private fun storedLength(): Int {
        var i = len
        while (i > 0 && cells[i - 1] == fill.bits) i--
        return i
    }

    private fun checkColumn(col: Int) {
        if (col !in 0 until cols) throw IndexOutOfBoundsException("column $col outside a row of $cols columns")
    }

    private fun touch() {
        generation++
    }

    /** Ensures the first [length] columns are stored. The first write reserves the whole row, so typing a line allocates once. */
    private fun materialize(length: Int) {
        val target = length.coerceAtMost(cols)
        if (len >= target) return
        if (cells.size < target) cells = cells.copyOf(cols)
        cells.fill(fill.bits, len, target)
        len = target
    }

    /** Blanks the outer half of a wide character cut by [start] until [end]. */
    private fun breakWideEdges(
        start: Int,
        end: Int,
    ) {
        if (start in 1 until len && Cell(cells[start]).hasFlags(CellFlags.SPACER)) {
            cells[start - 1] = Cell.blank(Cell(cells[start - 1]).style).bits
        }
        if (end in 1 until len && Cell(cells[end - 1]).hasFlags(CellFlags.WIDE)) {
            cells[end] = Cell.blank(Cell(cells[end]).style).bits
        }
    }

    /** Removes link coverage from [start] until [end], splitting spans at the edges; returns whether any was there. */
    private fun dropLinks(
        start: Int,
        end: Int,
    ): Boolean {
        val list = spans ?: return false
        var dropped = false
        val kept = ArrayList<LinkSpan>(list.size + 1)
        for (s in list) {
            if (s.end <= start || s.start >= end) {
                kept += s
                continue
            }
            dropped = true
            if (s.start < start) kept += s.copy(end = start)
            if (s.end > end) kept += s.copy(start = end)
        }
        spans = kept.takeIf { it.isNotEmpty() }
        return dropped
    }
}
