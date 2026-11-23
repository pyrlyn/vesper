package vesper.term

// Cursor movement: absolute and relative moves, carriage return, index and reverse index, tabs.
// Separate from editing because none of these change a cell; IND and RI only scroll.
//
// Margin rules follow xterm and DEC STD 070 (and the esctest2 cases listed in tests/README.md): a
// relative move stops at a margin only when it starts inside it, absolute moves honour origin mode
// (DECOM), and every move clears the pending wrap.

/** Puts the cursor at an absolute screen position, clamped to the screen. */
internal fun State.goto(
    toRow: Int,
    toCol: Int,
) {
    row = toRow.coerceIn(0, lastRow)
    col = toCol.coerceIn(0, lastCol)
    pendingWrap = false
}

/**
 * CUP and HVP: [toRow] and [toCol] count from 0 at the origin, which is the top-left margin corner
 * in origin mode, and are clamped into it.
 */
internal fun State.gotoOrigin(
    toRow: Int,
    toCol: Int,
) {
    if (modes.origin) {
        goto((top + toRow).coerceAtMost(bottom), (left + toCol).coerceAtMost(right))
    } else {
        goto(toRow, toCol)
    }
}

/** CHA and HPA: column [toCol] of the current row, origin-relative. */
internal fun State.gotoCol(toCol: Int) = goto(row, if (modes.origin) (left + toCol).coerceAtMost(right) else toCol)

/** VPA: row [toRow] in the current column, origin-relative. */
internal fun State.gotoRow(toRow: Int) = goto(if (modes.origin) (top + toRow).coerceAtMost(bottom) else toRow, col)

/** CUU: up [n], stopping at the top margin when starting below it. */
internal fun State.up(n: Int) {
    val floor = if (row >= top) top else 0
    goto((row - n).coerceAtLeast(floor), col)
}

/** CUD: down [n], stopping at the bottom margin when starting above it. */
internal fun State.down(n: Int) {
    val ceiling = if (row <= bottom) bottom else lastRow
    goto((row + n).coerceAtMost(ceiling), col)
}

/** CUF: right [n], stopping at the right margin when starting left of it. */
internal fun State.forward(n: Int) = goto(row, (col + n).coerceAtMost(lineEnd()))

/**
 * CUB and BS: left [n], stopping at the left margin when starting right of it. From a pending wrap
 * the cursor leaves the last column, as xterm does without reverse wraparound.
 */
internal fun State.back(n: Int) {
    val floor = if (col >= left) left else 0
    goto(row, (col - n).coerceAtLeast(floor))
}

/** HPR and VPR: relative moves bounded by the screen, not the margins. */
internal fun State.relative(
    downRows: Int,
    rightCols: Int,
) = goto(row + downRows, col + rightCols)

/** CR: to the left margin, or column 0 when already left of it. */
internal fun State.carriageReturn() = goto(row, if (col >= left) left else 0)

/**
 * IND and LF: down one row; at the bottom margin the region scrolls up instead, if the cursor is
 * inside the left and right margins.
 */
internal fun State.index() {
    pendingWrap = false
    if (row == bottom) {
        if (inLrMargins()) scrollUp(1)
    } else if (row < lastRow) {
        row++
    }
}

/** RI: up one row; at the top margin the region scrolls down instead. */
internal fun State.reverseIndex() {
    pendingWrap = false
    if (row == top) {
        if (inLrMargins()) scrollDown(1)
    } else if (row > 0) {
        row--
    }
}

/** LF, VT and FF: an index, plus a carriage return under LNM. */
internal fun State.linefeed() {
    index()
    if (modes.newline) carriageReturn()
}

/**
 * HT and CHT: forward [n] tab stops, never past the right margin and never wrapping. The pending
 * wrap is kept: it can only be set at the line end, where a tab does not move.
 */
internal fun State.tab(n: Int) {
    col = tabs.next(col, n, lineEnd())
}

/** CBT: back [n] tab stops, down to column 0 whatever the margins. */
internal fun State.backTab(n: Int) = goto(row, tabs.prev(col, n))
