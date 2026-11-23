package scull.term

import scull.grid.Cell
import scull.grid.CellFlags
import scull.grid.Grid
import scull.grid.LinkSpan
import scull.grid.Style
import scull.grid.StyleId
import scull.image.Image
import scull.unicode.appendCodePoint
import scull.unicode.firstCodePoint

// The UI-owned frame: a copy of the viewport in flat buffers that the UI reads without touching the
// terminal, so it holds the terminal's lock only while Terminal.updateFrame copies. The update copies
// only what the damage names: rows that moved unchanged are moved inside the frame, as foot replays
// its scroll damage, and only dirty rows are read from the grid. Contour rebuilds the whole page every
// refresh; a native UI behind a C ABI pays for every byte copied, so the frame keeps its last picture
// and patches it. Per row it holds fixed-size cells for backgrounds and cursor placement, plus the
// row's text cut into runs of one style and one cell width: the unit the platform shaper (CoreText,
// DirectWrite) takes, since the core decides widths and the platform only shapes.

/** Drawn for a cluster id the table no longer has: rows keep their clusters alive, so this marks a broken invariant. */
internal const val MISSING_CLUSTER: String = "\uFFFD"

private const val WIDE_COLS = 2

private const val UINT_MASK = 0xFFFF_FFFFL
private const val BYTE_MASK = 0xFFL
private const val STYLE_SHIFT = 32
private const val WIDTH_SHIFT = 48
private const val FLAGS_SHIFT = 56

/** One cell as the UI paints it. */
public data class FrameCell(
    /** The first code point of the cell's text; 0 when it shows none. */
    val codePoint: Int,
    /** The style, read through [Frame.style]. */
    val style: Int,
    /** Columns the character covers: 1, 2 for a wide head, 0 for the spacer behind it. */
    val width: Int,
    /** [CLUSTER], [SELECTED], [MATCH] and [CURRENT_MATCH] bits. */
    val flags: Int,
) {
    public companion object {
        /** The text has more code points than [codePoint]; the whole cluster is in the row's runs. */
        public const val CLUSTER: Int = 1

        /** The cell is selected. */
        public const val SELECTED: Int = 2

        /** The cell is part of a search match. */
        public const val MATCH: Int = 4

        /** The cell is part of the current search match (also [MATCH]). */
        public const val CURRENT_MATCH: Int = 8
    }
}

/** Flags laid over columns [start] until [end] of viewport row [row]: a selection or search highlight. */
internal data class Mark(
    val row: Int,
    val start: Int,
    val end: Int,
    val flags: Int,
)

/**
 * Neighbouring cells of one style and one width, with their text, for the platform to shape. Cells
 * without text (blank, erased) end a run and belong to none: they paint only a background.
 */
public data class TextRun(
    /** First column. */
    val col: Int,
    /** Columns covered, wide spacers included. */
    val cols: Int,
    /** The style, read through [Frame.style]. */
    val style: Int,
    /** Columns each character covers: 1 or 2. */
    val width: Int,
    /** Offset of the run's text in [FrameRow.text], in UTF-16 units. */
    val textStart: Int,
    /** Length of the run's text, in UTF-16 units. */
    val textLength: Int,
)

/** The text of one frame row and its runs. */
public class FrameRow internal constructor() {
    /** Every run's text, left to right. */
    public var text: String = ""
        private set

    private val runList = ArrayList<TextRun>()

    /** The runs, left to right. */
    public val runs: List<TextRun> get() = runList

    private val linkList = ArrayList<LinkSpan>()

    /** The OSC 8 link spans, by column; [Terminal.linkUri] reads their URIs. */
    public val links: List<LinkSpan> get() = linkList

    /** The text of [run], empty for a run of another row. */
    public fun runText(run: TextRun): String =
        if (run.textStart in 0..text.length && run.textLength in 0..text.length - run.textStart) {
            text.substring(run.textStart, run.textStart + run.textLength)
        } else {
            ""
        }

    internal fun set(
        text: String,
        runs: List<TextRun>,
        links: List<LinkSpan>,
    ) {
        this.text = text
        runList.clear()
        runList.addAll(runs)
        linkList.clear()
        linkList.addAll(links)
    }
}

/** Where the UI draws the cursor, in viewport rows. */
public data class FrameCursor(
    val row: Int,
    val col: Int,
)

/**
 * One image in the viewport: [sourceWidth] by [sourceHeight] pixels of [image] from ([sourceX], [sourceY]),
 * scaled to [cols] by [rows] cells from viewport [row] (negative above the viewport) and [col], moved right
 * and down by [offsetX] and [offsetY] pixels. A negative [z] draws below the text. [image] keeps its pixels
 * however long the UI holds it, and its id names them for good, so it keys a texture cache.
 */
public data class FramePlacement(
    val image: Image,
    val row: Int,
    val col: Int,
    val cols: Int,
    val rows: Int,
    val sourceX: Int,
    val sourceY: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val z: Int,
    val offsetX: Int,
    val offsetY: Int,
)

/** The UI's copy of the viewport. Create one per view and keep it: every update reuses its buffers and copies only what changed. */
public class Frame {
    public var cols: Int = 0
        private set
    public var rows: Int = 0
        private set
    private var alt = false
    private var filled = false

    // Flat cell buffers, row after row: what a C ABI copies out without walking objects.
    private var codePoints = IntArray(0)
    private var styleIds = IntArray(0)
    private var widths = ByteArray(0)
    private var cellFlags = ByteArray(0)
    private var lines = ArrayList<FrameRow>()
    private var stamps = ArrayList<RowStamp>()
    private var oldStamps = ArrayList<RowStamp>()

    // Selection and search highlights by row. A row whose marks changed is repainted even when its
    // text did not, and one that moved keeps its scroll only if its marks moved with it.
    private var marks: List<List<Mark>> = emptyList()
    private var oldMarks: List<List<Mark>> = emptyList()
    private val collected = ArrayList<Mark>()
    private val styles = ArrayList<Style>()

    // The images in the viewport, and those of the last update: a change repaints the rows under both.
    private var placementList = ArrayList<FramePlacement>()
    private var oldPlacements = ArrayList<FramePlacement>()

    /** The images in the viewport, lowest z first; the rows under any that changed are in the damage's dirty rows. */
    public val placements: List<FramePlacement> get() = placementList

    /** The cursor; null when hidden or outside the viewport. */
    public var cursor: FrameCursor? = null
        private set

    /** What the last update changed: blit the scrolls from the previous picture, then repaint the dirty rows. */
    public val damage: Damage = Damage()

    // Scratch for one row's text and runs.
    private val text = StringBuilder()
    private val runs = ArrayList<TextRun>()

    /** The cell at [row], [col]. */
    public fun cell(
        row: Int,
        col: Int,
    ): FrameCell {
        require(row in 0 until rows && col in 0 until cols) { "cell $row,$col of a $cols by $rows frame" }
        val at = row * cols + col
        return FrameCell(codePoints[at], styleIds[at], widths[at].toInt(), cellFlags[at].toInt())
    }

    /**
     * The cell at [row], [col] packed into one Long: code point in bits 0-31, style 32-47, width
     * 48-55, flags 56-63. Read without an object per cell; it is also the byte layout of the C ABI's
     * `tt_cell` on a little-endian machine, so a copy out is one store per cell.
     */
    public fun packedCell(
        row: Int,
        col: Int,
    ): Long {
        require(row in 0 until rows && col in 0 until cols) { "cell $row,$col of a $cols by $rows frame" }
        val at = row * cols + col
        return (codePoints[at].toLong() and UINT_MASK) or
            (styleIds[at].toLong() shl STYLE_SHIFT) or
            ((widths[at].toLong() and BYTE_MASK) shl WIDTH_SHIFT) or
            ((cellFlags[at].toLong() and BYTE_MASK) shl FLAGS_SHIFT)
    }

    /** Entries in the style table that cell and run style ids index. */
    public val styleCount: Int get() = styles.size

    /** The text and runs of row [row]. */
    public fun row(row: Int): FrameRow? = lines.getOrNull(row)

    /** The id and generation of the line [row] shows. */
    public fun stamp(row: Int): RowStamp? = stamps.getOrNull(row)

    /** The style behind a cell's or run's style id. */
    public fun style(id: Int): Style? = styles.getOrNull(id)

    /** Copies what changed in [term]'s viewport since the last update. */
    internal fun copyFrom(term: Terminal) {
        val grid = term.grid
        val resized = grid.cols != cols || grid.screenRows != rows
        // Row ids are unique per grid only, so ids on the other screen say nothing about this one.
        val full = !filled || resized || alt != term.isAltScreen
        if (resized) {
            val size = grid.cols * grid.screenRows
            codePoints = IntArray(size)
            styleIds = IntArray(size)
            widths = ByteArray(size)
            cellFlags = ByteArray(size)
            lines = ArrayList(List(grid.screenRows) { FrameRow() })
        }
        cols = grid.cols
        rows = grid.screenRows
        alt = term.isAltScreen
        filled = true
        oldStamps = stamps.also { stamps = oldStamps }
        stamps.clear()
        for (r in 0 until rows) stamps += grid.visibleRow(r)?.let(RowStamp::of) ?: RowStamp.NONE
        oldMarks = marks
        marks = collectMarks(term)
        damage.compute(oldStamps, stamps, full) { from, to -> sameSpans(oldMarks[from], marks[to]) }
        collectPlacements(term, full)
        applyScrolls()
        for (r in damage.dirty) {
            paint(grid, r)
            applyMarks(r)
        }
        cursor = cursorIn(term)
    }

    private fun collectPlacements(
        term: Terminal,
        full: Boolean,
    ) {
        oldPlacements = placementList.also { placementList = oldPlacements }
        placementList.clear()
        term.placements(placementList)
        if (full || placementList == oldPlacements) return
        val under = BooleanArray(rows)
        for (p in oldPlacements + placementList) {
            for (r in p.row.coerceAtLeast(0) until (p.row.toLong() + p.rows).coerceAtMost(rows.toLong()).toInt()) under[r] = true
        }
        damage.addDirty(under)
    }

    private fun collectMarks(term: Terminal): List<List<Mark>> {
        collected.clear()
        term.marks(collected)
        if (collected.isEmpty()) return List(rows) { emptyList() }
        val byRow = arrayOfNulls<ArrayList<Mark>>(rows)
        for (m in collected) {
            if (m.row in 0 until rows) (byRow[m.row] ?: ArrayList<Mark>().also { byRow[m.row] = it }) += m
        }
        return byRow.map { it ?: emptyList() }
    }

    /** Whether two rows carry the same highlights, ignoring the row each mark names: a row that moved keeps them. */
    private fun sameSpans(
        a: List<Mark>,
        b: List<Mark>,
    ): Boolean = a.size == b.size && a.indices.all { a[it].start == b[it].start && a[it].end == b[it].end && a[it].flags == b[it].flags }

    private fun applyMarks(r: Int) {
        for (m in marks[r]) {
            for (col in m.start.coerceAtLeast(0) until minOf(m.end, cols)) {
                val at = r * cols + col
                cellFlags[at] = (cellFlags[at].toInt() or m.flags).toByte()
            }
        }
    }

    /** Moves the rows the damage says moved. Every scroll reads the previous picture, so all sources are copied before any is written. */
    private fun applyScrolls() {
        val scrolls = damage.scrolls
        if (scrolls.isEmpty()) return
        val sources = scrolls.map { s -> Moved(s, this) }
        sources.forEach { it.writeInto(this) }
    }

    /** One scroll's rows from the previous picture. */
    private class Moved(
        private val scroll: Scroll,
        frame: Frame,
    ) {
        private val from = scroll.from * frame.cols
        private val to = scroll.end - scroll.start + scroll.from
        private val count = (scroll.end - scroll.start) * frame.cols
        private val codePoints = frame.codePoints.copyOfRange(from, from + count)
        private val styleIds = frame.styleIds.copyOfRange(from, from + count)
        private val widths = frame.widths.copyOfRange(from, from + count)
        private val flags = frame.cellFlags.copyOfRange(from, from + count)
        private val lines = frame.lines.subList(scroll.from, to).toList()

        fun writeInto(frame: Frame) {
            val at = scroll.start * frame.cols
            codePoints.copyInto(frame.codePoints, at)
            styleIds.copyInto(frame.styleIds, at)
            widths.copyInto(frame.widths, at)
            flags.copyInto(frame.cellFlags, at)
            lines.forEachIndexed { i, line -> frame.lines[scroll.start + i] = line }
        }
    }

    /** Copies viewport row [r] from the grid. */
    private fun paint(
        grid: Grid,
        r: Int,
    ) {
        // A moved row may still sit at its old place too; this row gets its own object.
        val line = FrameRow()
        lines[r] = line
        text.clear()
        runs.clear()
        val row = grid.visibleRow(r)
        var seen = -1
        for (col in 0 until cols) {
            val at = r * cols + col
            if (row == null) {
                setCell(at, 0, 0, 0, 0)
                continue
            }
            val cell = row.cell(col)
            if (cell.style.value != seen) {
                seen = cell.style.value
                refreshStyle(grid, cell.style)
            }
            push(at, col, cell, grid)
        }
        line.set(text.toString(), runs, row?.links.orEmpty())
    }

    private fun setCell(
        at: Int,
        codePoint: Int,
        style: Int,
        width: Int,
        flags: Int,
    ) {
        codePoints[at] = codePoint
        styleIds[at] = style
        widths[at] = width.toByte()
        cellFlags[at] = flags.toByte()
    }

    /** Writes [cell] at [at] and adds its text, if any, to the row's runs. */
    private fun push(
        at: Int,
        col: Int,
        cell: Cell,
        grid: Grid,
    ) {
        val style = cell.style.value
        if (cell.hasFlags(CellFlags.SPACER)) return setCell(at, 0, style, 0, 0)
        val width = if (cell.hasFlags(CellFlags.WIDE)) WIDE_COLS else 1
        val start = text.length
        val codePoint: Int
        val flags: Int
        when {
            cell.isCluster -> {
                val cluster = grid.clusters[cell.clusterId] ?: MISSING_CLUSTER
                text.append(cluster)
                codePoint = cluster.firstCodePoint()
                flags = FrameCell.CLUSTER
            }

            cell.isEmpty -> {
                return setCell(at, 0, style, width, 0)
            }

            else -> {
                text.appendCodePoint(cell.codePoint)
                codePoint = cell.codePoint
                flags = 0
            }
        }
        extendRun(col, style, width, start)
        setCell(at, codePoint, style, width, flags)
    }

    /** Adds the text from [start] to the last run, or opens a run. */
    private fun extendRun(
        col: Int,
        style: Int,
        width: Int,
        start: Int,
    ) {
        val length = text.length - start
        val last = runs.lastOrNull()
        if (last != null && last.style == style && last.width == width && last.col + last.cols == col) {
            runs[runs.lastIndex] = last.copy(cols = last.cols + width, textLength = last.textLength + length)
        } else {
            runs += TextRun(col, width, style, width, start, length)
        }
    }

    /**
     * Copies style [id] into the frame's table. Ids of unchanged rows stay valid: the grid only
     * reclaims ids no row uses, so refreshing the ids of the rows copied keeps the whole table right.
     */
    private fun refreshStyle(
        grid: Grid,
        id: StyleId,
    ) {
        while (styles.size <= id.value) styles += Style.DEFAULT
        styles[id.value] = grid.styles[id] ?: Style.DEFAULT
    }

    /** The cursor in viewport rows, when it is shown and the viewport is not scrolled back past it. */
    private fun cursorIn(term: Terminal): FrameCursor? {
        if (!term.modes.cursorVisible) return null
        val row = term.cursor.row + term.grid.displayOffset
        return if (row < term.grid.screenRows) FrameCursor(row, term.cursor.col) else null
    }
}
