package vesper.term

import vesper.grid.Cell
import vesper.grid.CellFlags
import vesper.grid.GridException
import vesper.unicode.appendCodePoint
import vesper.unicode.clusterWidth
import vesper.unicode.width

// Printing: code points into grapheme clusters, clusters into one or two cells, and autowrap.
// Separate because it is the hot path and the one place where Unicode width meets the grid.
//
// Autowrap follows DEC STD 070 and xterm (ctlseqs, DECAWM): writing the last column of the line
// leaves the cursor there with a pending wrap, and only the next printable character wraps, to the
// left margin of the next line. A wide character that does not fit at the end of a line leaves a
// leading-spacer cell behind and wraps whole, as foot and Alacritty do.
//
// A code point that does not start a new grapheme cluster (UAX #29), or that is zero cells wide,
// joins the cell left of the cursor. Under mode 2027 the joined cluster may change width (VS16,
// emoji ZWJ sequences) and the cursor moves with it.
//
// ASCII is written a run at a time: one row call for as many bytes as fit the line, since a call
// per cell costs more than the write itself (docs/benchmarks/grid.md).

private const val WIDE = 2

private const val LEAD_3 = 0xE0
private const val LEAD_4 = 0xF0
private const val LEAD_2_MASK = 0x1F
private const val LEAD_3_MASK = 0x0F
private const val LEAD_4_MASK = 0x07
private const val CONTINUATION_MASK = 0x3F
private const val CONTINUATION_BITS = 6
private const val BYTE_MASK = 0xFF
private const val REPLACEMENT = 0xFFFD

/** A run of printable text from the parser: valid UTF-8, no controls. */
internal fun State.printText(
    bytes: ByteArray,
    start: Int,
    end: Int,
) {
    var i = start
    while (i < end) {
        val lead = bytes[i].toInt()
        if (lead >= 0) {
            i = printAscii(bytes, i, end)
            continue
        }
        val length =
            when {
                lead and BYTE_MASK < LEAD_3 -> 2
                lead and BYTE_MASK < LEAD_4 -> 3
                else -> 4
            }
        // The parser promises whole sequences; a cut one is still never read past the run.
        if (i + length > end) {
            printCodePoint(REPLACEMENT)
            return
        }
        var cp =
            when (length) {
                2 -> lead and LEAD_2_MASK
                3 -> lead and LEAD_3_MASK
                else -> lead and LEAD_4_MASK
            }
        for (k in 1 until length) cp = (cp shl CONTINUATION_BITS) or (bytes[i + k].toInt() and CONTINUATION_MASK)
        printCodePoint(cp)
        i += length
    }
}

/** Prints the ASCII bytes that begin at [from] and returns the index after the last one printed. */
private fun State.printAscii(
    bytes: ByteArray,
    from: Int,
    end: Int,
): Int {
    val first = bytes[from].toInt()
    if (modes.insert || !charsets.isAscii) {
        printCodePoint(first)
        return from + 1
    }
    // Only the first byte can continue a cluster (after a prepend character); every later one
    // follows an ASCII character, which always ends its cluster.
    if (!grapheme.next(first)) {
        join(first)
        return from + 1
    }
    var to = from + 1
    while (to < end && bytes[to] >= 0) to++
    var at = from
    while (at < to) {
        if (pendingWrap && modes.autowrap) wrap()
        val count = minOf(lineEnd() - col + 1, to - at)
        // Asked for after the wrap: its scroll may intern the erase style and sweep the pen's id.
        val style = pen.textId(grid)
        val row = cursorRow()
        row.putAscii(col, bytes, at, count, style)
        stampLink(row, col, col + count)
        advance(col, count)
        at += count
        if (at < to && !modes.autowrap) {
            // Without autowrap the rest of the run overwrites the last column, so only its last byte shows.
            row.putAscii(col, bytes, to - 1, 1, style)
            stampLink(row, col, col + 1)
            at = to
        }
    }
    lastChar = bytes[to - 1].toInt()
    return to
}

internal fun State.printCodePoint(codePoint: Int) {
    val ch = charsets.map(codePoint)
    val startsCluster = grapheme.next(ch)
    val cells = width(ch, width)
    if (startsCluster && cells > 0) place(ch, cells) else join(ch)
}

/** Writes a new cluster at the cursor and advances past it. */
private fun State.place(
    ch: Int,
    cells: Int,
) {
    if (pendingWrap && modes.autowrap) wrap()
    if (col + cells - 1 > lineEnd()) {
        if (modes.autowrap) {
            cursorRow().let {
                it.set(col, blank().withFlags(CellFlags.LEADING_SPACER))
                // The spacer is padding, not part of whatever link the cell held.
                it.clearLinks(col, col + 1)
            }
            wrap()
        } else {
            col = (lineEnd() - (cells - 1)).coerceAtLeast(0)
        }
        // The margins are narrower than the character.
        if (col + cells - 1 > lineEnd()) return
    }
    if (modes.insert) insertChars(cells)
    val at = col
    val row = cursorRow()
    row.put(at, Cell.char(ch, pen.textId(grid)), cells == WIDE)
    stampLink(row, at, at + cells)
    advance(at, cells)
    lastChar = ch
}

/** Moves the cursor past a cluster written at [at], entering the pending wrap when it filled the line. */
private fun State.advance(
    at: Int,
    span: Int,
) {
    val end = lineEnd()
    val next = at + span
    if (next > end) {
        col = end
        pendingWrap = modes.autowrap
    } else {
        col = next
        pendingWrap = false
    }
}

/**
 * Autowrap: marks the row as continued and starts the next line at the left margin, scrolling if
 * the cursor is on the bottom margin.
 */
private fun State.wrap() {
    cursorRow().wrapped = true
    index()
    col = left
}

/**
 * Adds [ch] to the cluster in the cell left of the cursor. With no cluster there to join, the code
 * point is dropped, as it has no cell.
 */
private fun State.join(ch: Int) {
    val at = clusterCol()
    if (at < 0) return
    val row = cursorRow()
    val cell = row.cell(at)
    if (cell.isEmpty) return
    val text =
        buildString {
            if (cell.isCluster) append(grid.clusters[cell.clusterId].orEmpty()) else appendCodePoint(cell.codePoint)
            appendCodePoint(ch)
        }
    // Past the cluster caps the extra code point is dropped.
    val id =
        try {
            grid.internCluster(text)
        } catch (_: GridException) {
            return
        }
    val wasWide = cell.hasFlags(CellFlags.WIDE)
    val fitsWide = wasWide || at < lineEnd()
    val wide = fitsWide && clusterWidth(text, width, policy) == WIDE
    row.put(at, Cell.cluster(id, cell.style), wide)
    // A cluster that grew wide takes its head's link onto the cell it now covers.
    if (wide && !wasWide) row.linkAt(at)?.let { row.setLink(at, at + WIDE, it) } ?: row.clearLinks(at, at + WIDE)
    if (wide != wasWide) advance(at, if (wide) WIDE else 1)
}

/**
 * The head cell of the cluster just left of the cursor, or -1: under the cursor while a wrap is
 * pending, otherwise one or two columns left.
 */
private fun State.clusterCol(): Int {
    val at = if (pendingWrap) col else col - 1
    if (at < 0) return -1
    return if (cursorRow().cell(at).hasFlags(CellFlags.SPACER)) at - 1 else at
}

/**
 * REP: the last printed character [n] more times, capped at one screen of cells so a short
 * sequence cannot demand unbounded work.
 */
internal fun State.repeatLast(n: Int) {
    val ch = lastChar
    if (ch == NO_CHAR) return
    repeat(minOf(n.toLong(), cols.toLong() * rows).toInt()) {
        endCluster()
        printCodePoint(ch)
    }
}
