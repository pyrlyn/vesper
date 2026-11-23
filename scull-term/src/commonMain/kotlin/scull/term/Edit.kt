package scull.term

import scull.grid.Cell
import scull.grid.StyleId

// Editing the screen: erase (ED, EL, ECH), insert and delete (ICH, DCH, IL, DL), region scrolling
// (SU, SD and the scrolls of IND and RI), and DECALN. Separate from motion because these change
// cells.
//
// Erased cells keep the pen's background (xterm's background colour erase). Every edit clears the
// pending wrap, as xterm does. Rules for the margins follow xterm's ctlseqs and DEC STD 070: ECH,
// ED and EL ignore them; ICH, DCH, IL and DL do nothing when the cursor is outside them.

/** DECALN fills the screen with this letter. */
private const val ALIGNMENT_CHAR = 'E'

/** `Ps` of ED and EL: from the cursor to the end. */
internal const val ERASE_TO_END = 0

/** From the start to the cursor, inclusive. */
private const val ERASE_TO_START = 1
private const val ERASE_ALL = 2

/** ED 3 (xterm): the scrollback, leaving the screen alone. */
private const val ERASE_SCROLLBACK = 3

/** EL. An unknown [which] is ignored. */
internal fun State.eraseLine(which: Int) {
    if (which !in ERASE_TO_END..ERASE_ALL) return
    pendingWrap = false
    val from = if (which == ERASE_TO_END) col else 0
    val to = if (which == ERASE_TO_START) col + 1 else cols
    cursorRow().fillRange(from, to, blank())
}

/** ED. An unknown [which] is ignored. */
internal fun State.eraseDisplay(which: Int) {
    when (which) {
        ERASE_TO_END -> {
            eraseLine(which)
            clearRows(row + 1, rows)
        }

        ERASE_TO_START -> {
            eraseLine(which)
            clearRows(0, row)
        }

        ERASE_ALL -> {
            pendingWrap = false
            clearRows(0, rows)
            eraseScreenImages()
        }

        ERASE_SCROLLBACK -> {
            grid.clearHistory()
            images.prune(grid.firstLine)
        }
    }
}

private fun State.clearRows(
    from: Int,
    to: Int,
) {
    val blank = blank()
    for (r in from until to) grid.screenRow(r)?.clear(blank)
}

/** ECH: blanks [n] cells from the cursor without moving anything. */
internal fun State.eraseChars(n: Int) {
    pendingWrap = false
    cursorRow().fillRange(col, col + n, blank())
}

/** ICH (and IRM before a character): opens [n] blank cells at the cursor, pushing the rest of the line towards the right margin. */
internal fun State.insertChars(n: Int) {
    pendingWrap = false
    if (inLrMargins()) cursorRow().insertCells(col, right + 1, n, blank())
}

/** DCH: removes [n] cells at the cursor, pulling the rest of the line in from the right margin. */
internal fun State.deleteChars(n: Int) {
    pendingWrap = false
    if (inLrMargins()) cursorRow().deleteCells(col, right + 1, n, blank())
}

/** IL: [n] blank lines at the cursor row, pushing the region down. */
internal fun State.insertLines(n: Int) = shiftLines(n, insert = true)

/** DL: removes [n] lines at the cursor row, pulling the region up. */
internal fun State.deleteLines(n: Int) = shiftLines(n, insert = false)

/** IL and DL act on the rows from the cursor to the bottom margin and leave the cursor at the left margin (DEC STD 070). */
private fun State.shiftLines(
    n: Int,
    insert: Boolean,
) {
    pendingWrap = false
    if (!inTbMargins() || !inLrMargins()) return
    if (insert) scrollRegionDown(row, n) else scrollRegionUp(row, n)
    col = left
}

/** SU and the scroll of IND: the scroll region moves up [n] rows. */
internal fun State.scrollUp(n: Int) = scrollRegionUp(top, n)

/** SD and the scroll of RI: the scroll region moves down [n] rows. */
internal fun State.scrollDown(n: Int) = scrollRegionDown(top, n)

/** Scrolls rows [from] to the bottom margin up; only a scroll of the whole screen feeds the scrollback. */
private fun State.scrollRegionUp(
    from: Int,
    n: Int,
) {
    val blank = blank()
    if (isFullWidth()) grid.scrollRegionUp(from, bottom + 1, n, blank) else scrollColumns(from, n, up = true, blank)
}

private fun State.scrollRegionDown(
    from: Int,
    n: Int,
) {
    val blank = blank()
    if (isFullWidth()) grid.scrollRegionDown(from, bottom + 1, n, blank) else scrollColumns(from, n, up = false, blank)
}

private fun State.isFullWidth(): Boolean = left == 0 && right >= lastCol

/**
 * Scrolling inside left and right margins (DECLRMM): only the cells between them move, row by row,
 * and nothing reaches the scrollback, since no whole line leaves the screen (DEC STD 070, xterm).
 */
private fun State.scrollColumns(
    from: Int,
    count: Int,
    up: Boolean,
    blank: Cell,
) {
    val height = bottom - from + 1
    val n = count.coerceAtMost(height)
    val width = right - left + 1
    for (i in 0 until height - n) {
        val to = if (up) from + i else bottom - i
        val source = grid.screenRow(if (up) to + n else to - n) ?: continue
        grid.screenRow(to)?.writeCells(left, source, left, width)
    }
    val cleared = if (up) bottom + 1 - n else from
    for (r in cleared until cleared + n) grid.screenRow(r)?.fillRange(left, right + 1, blank)
}

/** DECALN: the screen filled with `E` in the default style, margins reset, cursor home (VT100 screen alignment test). */
internal fun State.alignmentTest() {
    resetMargins()
    val fill = Cell.char(ALIGNMENT_CHAR.code, StyleId.DEFAULT)
    for (r in 0 until rows) grid.screenRow(r)?.clear(fill)
    goto(0, 0)
}
