package scull.term

import scull.grid.CellFlags
import scull.grid.Grid
import scull.grid.Row

// The selection: cells the user marked, kept in absolute lines (Grid.evicted plus the ring index) so
// it stays on its text while output scrolls and the viewport moves. Separate from the frame, which
// only shows it; copying reads the grid here.
//
// The selection is dropped, never repaired, once the text under it may have changed: a row it covers
// was rewritten or replaced, its first or last line left the ring, the screens switched or the
// terminal was resized. Alacritty clears its selection on writes into it the same way. Ported from
// the creator's Rust scull (`crates/scull-term/src/selection.rs`).

/**
 * Characters that end a word besides blanks: Alacritty's default `semantic_escape_chars`, so paths,
 * URLs, options and `key=value` pairs select whole while brackets, quotes and pipes do not stick to them.
 */
public const val WORD_SEPARATORS: String = ",│`|:\"'()[]{}<>"

/** Cells a word selection walks each way at most. Only a program prints a longer word, and walking it must not stall the UI thread. */
public const val MAX_WORD_CELLS: Int = 4096

/** UTF-8 bytes of selected text handed out at most; more is cut at a character boundary. A full ring of wide rows would otherwise be gigabytes. */
public const val MAX_SELECTION_BYTES: Int = 16 shl 20

/** A cell that a selection or match is bounded by: an absolute line, counted like [Terminal.screenTopLine], and a column. */
public data class Point(
    public val line: Long,
    public val col: Int,
) : Comparable<Point> {
    override fun compareTo(other: Point): Int = if (line != other.line) line.compareTo(other.line) else col.compareTo(other.col)
}

/** What a selection grows by. */
public enum class SelectionKind {
    /** Cell by cell, in reading order (a drag). */
    Cell,

    /** Whole words, ended by blanks and [WORD_SEPARATORS] (a double click). */
    Word,

    /** Whole lines, soft wraps included (a triple click). */
    Line,

    /** A rectangle of columns (a drag with a modifier). */
    Block,
}

/** A selection's kind and its first and last cell, inclusive (a wide character's spacer included); for a block, the top left and bottom right corners. */
public data class SelectionRange(
    public val kind: SelectionKind,
    public val start: Point,
    public val end: Point,
)

private enum class CellClass {
    Word,
    Blank,
    Separator,

    /** The half of a wide character behind its head, or the padding before a wide character that wrapped: part of whatever surrounds it. */
    Padding,
}

internal class Selection(
    val kind: SelectionKind,
    val anchor: Point,
    val alt: Boolean,
) {
    var start = anchor
    var end = anchor

    // The rows it covers that output can still change: the screen's and both ends'. History rows in
    // between change only by leaving the ring, and the first one leaves first.
    val stampLines = ArrayList<Long>()
    val stamps = ArrayList<RowStamp>()

    /** The first column selected on [line]. */
    fun fromCol(line: Long): Int = if (kind == SelectionKind.Block || line == start.line) start.col else 0

    /** The last column selected on [line], inclusive. */
    fun toCol(
        line: Long,
        lastCol: Int,
    ): Int = if (kind == SelectionKind.Block || line == end.line) end.col else lastCol
}

/** The oldest line the ring holds. */
internal val Grid.firstLine: Long get() = evicted

/** The top screen row's line. */
internal val Grid.screenTopLine: Long get() = evicted + historyLength

/** The bottom screen row's line. */
internal val Grid.lastLine: Long get() = screenTopLine + screenRows - 1

internal val Grid.lastCol: Int get() = cols - 1

/** The row at absolute [line], if the ring still holds it. */
internal fun Grid.lineRow(line: Long): Row? {
    val at = line - evicted
    return if (at in 0..Int.MAX_VALUE.toLong()) row(at.toInt()) else null
}

/** The cell at viewport [row], [col], clamped to the viewport, as an absolute point. */
internal fun Grid.viewportPoint(
    row: Int,
    col: Int,
): Point = Point(screenTopLine - displayOffset + row.coerceIn(0, screenRows - 1), col.coerceIn(0, lastCol))

/** The viewport row showing absolute [line], or -1. */
internal fun Grid.viewportRow(line: Long): Int {
    val r = line - viewportPoint(0, 0).line
    return if (r in 0 until screenRows) r.toInt() else -1
}

private fun Grid.flagsAt(p: Point): Int = lineRow(p.line)?.takeIf { p.col < it.cols }?.cell(p.col)?.flags ?: 0

/** [p], or the wide character's head when [p] is its spacer. */
private fun Grid.head(p: Point): Point = if (p.col > 0 && flagsAt(p) and CellFlags.SPACER != 0) p.copy(col = p.col - 1) else p

/** [p], or its spacer when [p] is a wide character's head. */
private fun Grid.tail(p: Point): Point = if (p.col < lastCol && flagsAt(p) and CellFlags.WIDE != 0) p.copy(col = p.col + 1) else p

private fun Grid.classOf(p: Point): CellClass {
    val row = lineRow(p.line) ?: return CellClass.Blank
    if (p.col >= row.cols) return CellClass.Blank
    val cell = row.cell(p.col)
    return when {
        cell.flags and PADDING != 0 -> CellClass.Padding
        cell.isCluster -> CellClass.Word
        cell.isEmpty || isBlank(cell.codePoint) -> CellClass.Blank
        isSeparator(cell.codePoint) -> CellClass.Separator
        else -> CellClass.Word
    }
}

// Every Unicode white space is in the BMP.
private fun isBlank(codePoint: Int): Boolean = codePoint <= Char.MAX_VALUE.code && codePoint.toChar().isWhitespace()

private fun isSeparator(codePoint: Int): Boolean = codePoint <= Char.MAX_VALUE.code && codePoint.toChar() in WORD_SEPARATORS

/** The next cell in reading order, across a soft wrap but not a hard line end, or null. */
private fun Grid.step(
    p: Point,
    forward: Boolean,
): Point? =
    if (forward) {
        when {
            p.col < lastCol -> p.copy(col = p.col + 1)
            lineRow(p.line)?.wrapped == true && lineRow(p.line + 1) != null -> Point(p.line + 1, 0)
            else -> null
        }
    } else {
        when {
            p.col > 0 -> p.copy(col = p.col - 1)
            lineRow(p.line - 1)?.wrapped == true -> Point(p.line - 1, lastCol)
            else -> null
        }
    }

/** The first (or, [forward], the last) cell of the word at [p]. A separator is a word of its own; a run of blanks is one word. */
private fun Grid.wordEdge(
    p: Point,
    forward: Boolean,
): Point {
    val at = head(p)
    val cls = classOf(at).let { if (it == CellClass.Padding) CellClass.Blank else it }
    var edge = at
    if (cls != CellClass.Separator) {
        for (i in 0 until MAX_WORD_CELLS) {
            val next = step(edge, forward) ?: break
            val c = classOf(next)
            if (c != cls && c != CellClass.Padding) break
            edge = next
        }
    }
    return if (forward) tail(edge) else edge
}

/** The first (or, [forward], the last) line of the soft-wrapped line through [line]. */
internal fun Grid.logicalEdge(
    line: Long,
    forward: Boolean,
): Long {
    var at = line
    while (true) {
        val next = if (forward) at + 1 else at - 1
        val wraps = lineRow(if (forward) at else next)?.wrapped == true
        if (!wraps || lineRow(next) == null) return at
        at = next
    }
}

/** Sets [sel]'s bounds from its anchor and [focus], and stamps the rows that could change under it. */
internal fun Grid.resolve(
    sel: Selection,
    focus: Point,
) {
    val a = minOf(sel.anchor, focus)
    val b = maxOf(sel.anchor, focus)
    when (sel.kind) {
        SelectionKind.Cell -> {
            sel.start = head(a)
            sel.end = tail(b)
        }

        SelectionKind.Word -> {
            sel.start = wordEdge(a, false)
            sel.end = wordEdge(b, true)
        }

        SelectionKind.Line -> {
            sel.start = Point(logicalEdge(a.line, false), 0)
            sel.end = Point(logicalEdge(b.line, true), lastCol)
        }

        SelectionKind.Block -> {
            sel.start = Point(a.line, minOf(a.col, b.col))
            sel.end = Point(b.line, maxOf(a.col, b.col))
        }
    }
    sel.stampLines.clear()
    sel.stamps.clear()

    fun stamp(line: Long) {
        val row = lineRow(line) ?: return
        sel.stampLines += line
        sel.stamps += RowStamp.of(row)
    }
    stamp(sel.start.line)
    stamp(sel.end.line)
    for (line in maxOf(sel.start.line, screenTopLine)..sel.end.line) stamp(line)
}

/** Whether nothing under [sel] can have changed since it was resolved. */
internal fun Grid.holds(
    sel: Selection,
    alt: Boolean,
): Boolean = sel.alt == alt && sel.stampLines.indices.all { i -> lineRow(sel.stampLines[i])?.let(RowStamp::of) == sel.stamps[i] }

/**
 * The selected text: soft-wrapped rows joined, hard line ends (and block rows) as `\n`, wide characters
 * once, blank and concealed cells as spaces, trailing spaces trimmed per line, at most
 * [MAX_SELECTION_BYTES] UTF-8 bytes.
 */
internal fun Grid.selectedText(sel: Selection): String {
    val out = StringBuilder()
    var line = sel.start.line
    while (line <= sel.end.line && out.length <= MAX_SELECTION_BYTES) {
        val row = lineRow(line)
        if (row != null) {
            val start = out.length
            for (col in sel.fromCol(line)..minOf(sel.toCol(line, lastCol), row.cols - 1)) {
                val cell = row.cell(col)
                if (cell.flags and PADDING == 0) out.appendCell(this, cell)
            }
            // A soft-wrapped row's trailing blanks are inside its line, so they stay.
            val lineEnds = sel.kind == SelectionKind.Block || !row.wrapped
            if (line == sel.end.line || lineEnds) out.trimLine(start)
            if (line != sel.end.line && lineEnds) out.append('\n')
        }
        line++
    }
    return capUtf8(out.toString(), MAX_SELECTION_BYTES)
}

/** Lays [sel] over the viewport rows it covers. */
internal fun Grid.selectionMarks(
    sel: Selection,
    out: MutableList<Mark>,
) {
    val top = viewportPoint(0, 0).line
    for (row in 0 until screenRows) {
        val line = top + row
        if (line in sel.start.line..sel.end.line) out += Mark(row, sel.fromCol(line), sel.toCol(line, lastCol) + 1, FrameCell.SELECTED)
    }
}
