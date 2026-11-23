package scull.term

import scull.grid.Cell
import scull.grid.CellFlags
import scull.grid.Grid
import scull.grid.Style
import scull.grid.StyleId
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
private const val MISSING_CLUSTER = "�"

private const val WIDE_COLS = 2

/** One cell as the UI paints it. */
public data class FrameCell(
    /** The first code point of the cell's text; 0 when it shows none. */
    val codePoint: Int,
    /** The style, read through [Frame.style]. */
    val style: Int,
    /** Columns the character covers: 1, 2 for a wide head, 0 for the spacer behind it. */
    val width: Int,
    /** [CLUSTER] or 0. */
    val flags: Int,
) {
    public companion object {
        /** The text has more code points than [codePoint]; the whole cluster is in the row's runs. */
        public const val CLUSTER: Int = 1
    }
}

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
    ) {
        this.text = text
        runList.clear()
        runList.addAll(runs)
    }
}

/** Where the UI draws the cursor, in viewport rows. */
public data class FrameCursor(
    val row: Int,
    val col: Int,
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
    private val styles = ArrayList<Style>()

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
        damage.compute(oldStamps, stamps, full)
        applyScrolls()
        for (r in damage.dirty) paint(grid, r)
        cursor = cursorIn(term)
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
        line.set(text.toString(), runs)
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
