package vesper.macos

import vesper.term.FrameCursor
import vesper.term.TextRange
import vesper.term.ViewportText

// What a screen reader asks of the terminal view, answered from the viewport's text. Kept apart from
// the view so tests check the range arithmetic without AppKit. Ported from the removed Swift host's
// accessibility extension. Line N is viewport row N; the insertion point is the cursor.

/** [rows] rows of cells from [row], [cols] columns from [col]. */
internal data class CellBox(
    val row: Int,
    val col: Int,
    val cols: Int,
    val rows: Int = 1,
)

/** The empty range at the cursor; at the start while the program hides it. */
internal fun ViewportText.cursorRange(cursor: FrameCursor?): TextRange {
    val at = cursor?.let { indexAt(it.row, it.col) } ?: 0
    return TextRange(at, at)
}

/** The selection as one range, from its first character to its last; the cursor's empty range without one. */
internal fun ViewportText.selectedSpan(cursor: FrameCursor?): TextRange =
    if (selected.isEmpty()) cursorRange(cursor) else TextRange(selected.first().start, selected.last().end)

/** The ranges a reader lists as selected: one per row, or the cursor's empty range. */
internal fun ViewportText.selectedRanges(cursor: FrameCursor?): List<TextRange> = selected.ifEmpty { listOf(cursorRange(cursor)) }

/** The selected text, a line per selected row. */
internal val ViewportText.selectedString: String get() = selected.joinToString("\n") { text.substring(it.start, it.end) }

/** The text in [range], cut to the text; null when it starts outside it. */
internal fun ViewportText.substring(range: TextRange): String? {
    if (range.start !in 0..length) return null
    return text.substring(range.start, minOf(range.end, length))
}

/** The cells [range] covers: its columns when it stays on one line, whole rows of [cols] columns otherwise. */
internal fun ViewportText.boxOf(
    range: TextRange,
    cols: Int,
): CellBox? {
    if (range.start !in 0..length) return null
    val first = lineOf(range.start)
    val last = lineOf(maxOf(range.start, range.end - 1))
    if (first != last) return CellBox(first, 0, cols, last - first + 1)
    val cells = cellsOf(first, range)
    return CellBox(cells.row, cells.col, cells.cols)
}

/** The character a reader points at in cell [row], [col]: the whole cluster, or the empty range at a line's end. */
internal fun ViewportText.rangeAt(
    row: Int,
    col: Int,
): TextRange {
    val index = indexAt(row, col)
    return characterAt(index) ?: TextRange(index, index)
}
