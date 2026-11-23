package scull.grid

// A terminal-like writer and readers for the resize tests: enough to lay out soft wraps, wide
// characters and history the way the terminal layer does, without depending on it.

/** Test fixture: code points from U+3000 up take two cells. */
private fun isWide(c: Char): Boolean = c.code >= 0x3000

private fun newline(
    g: Grid,
    r: Int,
): Int =
    if (r + 1 == g.screenRows) {
        g.scrollUp(1)
        r
    } else {
        r + 1
    }

/**
 * Writes like a terminal with deferred autowrap from the screen's top left: `\n` starts a line, a wide
 * character that does not fit leaves a LEADING_SPACER. Returns where the cursor ends, clamped to the row.
 */
internal fun writeStyled(
    g: Grid,
    text: List<Pair<Char, StyleId>>,
): TrackPoint {
    var r = 0
    var c = 0
    for ((ch, style) in text) {
        if (ch == '\n') {
            r = newline(g, r)
            c = 0
            continue
        }
        val w = if (isWide(ch)) 2 else 1
        if (c + w > g.cols) {
            val row = g.screenRow(r)!!
            if (w == 2 && c + 1 == g.cols) row.set(c, Cell.EMPTY.withFlags(CellFlags.LEADING_SPACER))
            row.wrapped = true
            r = newline(g, r)
            c = 0
        }
        if (w == 2 && g.cols < 2) continue
        g.screenRow(r)!!.put(c, Cell.char(ch.code, style), wide = w == 2)
        c += w
    }
    return TrackPoint(g.historyLength + r, c.coerceAtMost(g.cols - 1))
}

internal fun write(
    g: Grid,
    text: String,
): TrackPoint = writeStyled(g, text.map { it to StyleId.DEFAULT })

internal fun total(g: Grid): Int = g.historyLength + g.screenRows

/** Each row as text: `.` empty, `_` spacer, `>` leading spacer, a trailing `\` for a soft wrap; trailing empties trimmed. */
internal fun rows(g: Grid): List<String> =
    (0 until total(g)).map { i ->
        val row = g.row(i)!!
        val s =
            buildString {
                for (c in 0 until row.cols) {
                    val cell = row.cell(c)
                    append(
                        when {
                            cell.codePoint != 0 -> cell.codePoint.toChar()
                            cell.hasFlags(CellFlags.SPACER) -> '_'
                            cell.hasFlags(CellFlags.LEADING_SPACER) -> '>'
                            else -> '.'
                        },
                    )
                }
            }.trimEnd('.')
        if (row.wrapped) "$s\\" else s
    }

/** Logical lines, joined across soft wraps, without trailing blank lines. */
internal fun lines(g: Grid): List<String> {
    val out = ArrayList<String>()
    val cur = StringBuilder()
    for (i in 0 until total(g)) {
        val row = g.row(i)!!
        for (c in 0 until row.cols) {
            val cell = row.cell(c)
            if (cell.flags and (CellFlags.SPACER or CellFlags.LEADING_SPACER) != 0) continue
            cur.append(if (cell.codePoint != 0) cell.codePoint.toChar() else ' ')
        }
        if (!row.wrapped) {
            out += cur.toString().trimEnd()
            cur.clear()
        }
    }
    while (out.lastOrNull()?.isEmpty() == true) out.removeAt(out.lastIndex)
    return out
}

internal fun cellAt(
    g: Grid,
    p: TrackPoint,
): Cell? = g.row(p.row)?.takeIf { p.col in 0 until it.cols }?.cell(p.col)

internal fun ids(g: Grid): List<RowId> = (0 until total(g)).map { g.row(it)!!.id }
