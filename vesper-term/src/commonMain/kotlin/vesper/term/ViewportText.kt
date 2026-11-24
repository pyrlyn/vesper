package vesper.term

import vesper.grid.CellFlags
import vesper.grid.Grid

// The viewport as plain text, for readers that are not renderers: screen readers (NSAccessibility,
// UI Automation) index one string, a line per row, in UTF-16 offsets. Read from the grid on demand, in
// the shape a reader wants, while the frame is cut for drawing. It keeps where each character sits,
// so offsets map to cells and back without asking the grid again. Ported from the earlier Rust core's
// `read_text` and the macOS host's `ScreenText`.

/** A stretch of a [ViewportText] from UTF-16 offset [start] until [end]. */
public data class TextRange(
    val start: Int,
    val end: Int,
) {
    val length: Int get() = end - start
}

/** A character's place in the viewport: [cols] columns of row [row] from column [col]. */
public data class TextCell(
    val row: Int,
    val col: Int,
    val cols: Int,
)

/**
 * The viewport's text: line N is viewport row N, lines joined by `\n`. Wide characters read once,
 * blank and concealed cells as spaces, trailing spaces are trimmed. [selected] is the selection, one
 * range per row it covers, empty without one.
 */
public class ViewportText internal constructor(
    public val text: String,
    // Per line, where each character starts (a UTF-16 offset and a column), then the line's end.
    private val indices: List<IntArray>,
    private val columns: List<IntArray>,
    public val selected: List<TextRange>,
) {
    public val length: Int get() = text.length

    public val lineCount: Int get() = indices.size

    /** The line holding [index]; before the start it is the first, past the end the last. */
    public fun lineOf(index: Int): Int {
        var low = 0
        var high = indices.size
        // The first line starting after index, minus one.
        while (low < high) {
            val mid = (low + high) / 2
            if (indices[mid][0] > index) high = mid else low = mid + 1
        }
        return (low - 1).coerceAtLeast(0)
    }

    /** Line [line]'s text with its newline, as NSTextView answers it; null past the lines. */
    public fun lineRange(line: Int): TextRange? {
        if (line !in indices.indices) return null
        return TextRange(indices[line][0], indices.getOrNull(line + 1)?.get(0) ?: text.length)
    }

    /** The offset of the character at [col] of [row] (the one it is part of, for a wide character), or the line's end past its text. */
    public fun indexAt(
        row: Int,
        col: Int,
    ): Int {
        val line = row.coerceIn(0, indices.size - 1)
        return indices[line][lastAtOrBefore(columns[line], col).coerceAtLeast(0)]
    }

    /** The cell the character holding [index] starts in, with the columns it covers; null on a line's end or outside the text. */
    public fun cellAt(index: Int): TextCell? {
        if (index !in 0 until text.length) return null
        val line = lineOf(index)
        val k = lastAtOrBefore(indices[line], index)
        if (k < 0 || k + 1 >= indices[line].size) return null
        return TextCell(line, columns[line][k], columns[line][k + 1] - columns[line][k])
    }

    /** The whole character (a cluster, as the terminal drew it) holding [index]; null on a line's end or outside the text. */
    public fun characterAt(index: Int): TextRange? {
        if (index !in 0 until text.length) return null
        val line = lineOf(index)
        val k = lastAtOrBefore(indices[line], index)
        if (k < 0 || k + 1 >= indices[line].size) return null
        return TextRange(indices[line][k], indices[line][k + 1])
    }

    /** The columns of [line] that the characters in [range] cover, from the first touched to the last; at least one column. */
    public fun cellsOf(
        line: Int,
        range: TextRange,
    ): TextCell {
        val l = line.coerceIn(0, indices.size - 1)
        val start = columns[l][lastAtOrBefore(indices[l], range.start).coerceAtLeast(0)]
        val after = indices[l].indexOfFirst { it >= range.end }
        val end = if (after < 0) columns[l].last() else columns[l][after]
        return TextCell(l, start, maxOf(1, end - start))
    }

    /**
     * The cells a reader's [range] selects, as a selection takes them: from the first character in it
     * to the last column of the last one, newlines skipped. Null when it holds no character.
     */
    public fun cellsFor(range: TextRange): Pair<TextCell, TextCell>? {
        if (range.start !in 0 until text.length) return null
        val end = minOf(range.end, text.length)
        val first = (range.start until end).firstOrNull { text[it] != '\n' } ?: return null
        val last = (range.start until end).last { text[it] != '\n' }
        return (cellAt(first) ?: return null) to (cellAt(last) ?: return null)
    }

    /**
     * The range of row [row]'s text that columns [col] until [endCol] cover: from the character the
     * first column belongs to, until after the one holding the last, so a wide character is never
     * cut. Cells past the trimmed text add nothing; null when no text is covered.
     */
    internal fun rangeOf(
        row: Int,
        col: Int,
        endCol: Int,
    ): TextRange? {
        if (row !in indices.indices) return null
        val k = lastAtOrBefore(columns[row], col)
        if (k < 0) return null
        val after = columns[row].indexOfFirst { it >= endCol }
        val end = if (after < 0) indices[row].last() else indices[row][after]
        return if (end > indices[row][k]) TextRange(indices[row][k], end) else null
    }

    public companion object {
        /** No text: one empty line. */
        public val EMPTY: ViewportText = ViewportText("", listOf(intArrayOf(0)), listOf(intArrayOf(0)), emptyList())
    }
}

/** The last position in the sorted [values] at or before [value], or -1. */
private fun lastAtOrBefore(
    values: IntArray,
    value: Int,
): Int {
    var k = -1
    for (i in values.indices) if (values[i] <= value) k = i else break
    return k
}

/** Reads the viewport as [ViewportText], with [marks] (the selection's, by row) as its selected ranges. */
internal fun Grid.viewportText(marks: List<Mark>): ViewportText {
    val out = StringBuilder()
    val indices = ArrayList<IntArray>(screenRows)
    val columns = ArrayList<IntArray>(screenRows)
    val lineIndices = ArrayList<Int>()
    val lineCols = ArrayList<Int>()
    for (r in 0 until screenRows) {
        if (r > 0) out.append('\n')
        val start = out.length
        lineIndices.clear()
        lineCols.clear()
        var col = 0
        visibleRow(r)?.let { row ->
            for (c in 0 until row.cols) {
                val cell = row.cell(c)
                if (cell.flags and PADDING != 0) continue
                lineIndices += out.length
                lineCols += c
                out.appendCell(this, cell)
                col = c + if (cell.hasFlags(CellFlags.WIDE)) 2 else 1
            }
        }
        out.trimLine(start)
        // The trimmed blanks leave with their stops; the line ends where the first of them began.
        var kept = lineIndices.size
        while (kept > 0 && lineIndices[kept - 1] >= out.length) kept--
        val end = if (kept < lineIndices.size) lineCols[kept] else col
        indices += IntArray(kept + 1) { if (it < kept) lineIndices[it] else out.length }
        columns += IntArray(kept + 1) { if (it < kept) lineCols[it] else end }
    }
    val text = ViewportText(out.toString(), indices, columns, emptyList())
    val selected = marks.sortedBy { it.row }.mapNotNull { text.rangeOf(it.row, it.start, it.end) }
    return if (selected.isEmpty()) text else ViewportText(text.text, indices, columns, selected)
}
