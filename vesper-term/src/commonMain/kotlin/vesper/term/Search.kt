package vesper.term

import vesper.grid.CellFlags
import vesper.grid.Grid
import vesper.grid.Row
import vesper.unicode.appendCodePoint

// Literal search over the scrollback and the screen. Separate from the selection because it reads
// text the user did not mark.
//
// Nothing but the pattern and the current match is kept: matches are found again on every call, so
// they never go stale under new output, and the frame searches only the viewport. Rows are read one at
// a time into a window that carries the tail of a soft-wrapped row on to the next, so a match may cross
// a wrap while the window never outgrows a row plus the pattern. Case folding maps each code point to
// its lowercase form, the pattern and the text alike, one code point at a time so both sides fold the
// same way. Ported from the earlier Rust core (`search.rs` of its `term` crate).

/** UTF-8 bytes of pattern accepted: a find bar's worth. The window carries this much from row to row, so it bounds the cost of each row searched. */
public const val MAX_SEARCH_PATTERN: Int = 256

/** Matches counted or listed at most. */
public const val MAX_SEARCH_MATCHES: Int = 10_000

/** Lines a backward search scans per step before looking further back. */
private const val BACK_STEP = 256L

private const val ASCII_LIMIT = 0x80
private const val INITIAL_CELLS = 256

/** One match: its first and last cell, inclusive (a wide character's spacer included), in absolute lines. */
public data class Match(
    public val start: Point,
    public val end: Point,
)

internal class Search(
    /** The pattern, folded unless the search is case-sensitive. */
    val pattern: String,
    /** Code points in [pattern]. */
    val codePoints: Int,
    val fold: Boolean,
) {
    var current: Match? = null
}

/** Appends [codePoint], lowercased when [fold]. */
private fun StringBuilder.appendFolded(
    codePoint: Int,
    fold: Boolean,
) {
    when {
        !fold -> appendCodePoint(codePoint)
        codePoint < ASCII_LIMIT -> append(codePoint.toChar().lowercaseChar())
        else -> append(StringBuilder().appendCodePoint(codePoint).toString().lowercase())
    }
}

/** Calls [action] with each code point of [text] from [from]; a lone surrogate is passed as it is. */
private inline fun forEachCodePoint(
    text: CharSequence,
    from: Int,
    action: (Int) -> Unit,
) {
    var i = from
    while (i < text.length) {
        val c = text[i]
        if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
            action(((c.code - HIGH_SURROGATE_MIN) shl SURROGATE_SHIFT) + (text[i + 1].code - LOW_SURROGATE_MIN) + SUPPLEMENTARY_MIN)
            i += 2
        } else {
            action(c.code)
            i++
        }
    }
}

private const val HIGH_SURROGATE_MIN = 0xD800
private const val LOW_SURROGATE_MIN = 0xDC00
private const val SUPPLEMENTARY_MIN = 0x10000
private const val SURROGATE_SHIFT = 10

/** [pattern] folded when [fold], code point by code point as the text is. */
internal fun foldPattern(
    pattern: String,
    fold: Boolean,
): String {
    if (!fold) return pattern
    val out = StringBuilder()
    forEachCodePoint(pattern, 0) { out.appendFolded(it, true) }
    return out.toString()
}

internal fun countCodePoints(text: String): Int {
    var n = 0
    forEachCodePoint(text, 0) { n++ }
    return n
}

/** Text read from consecutive rows, each code point with the cell it came from: its offset in [text], the cell and the last column the cell covers. */
private class Window {
    val text = StringBuilder()
    var size = 0
        private set
    private var offsets = IntArray(INITIAL_CELLS)
    private var lines = LongArray(INITIAL_CELLS)
    private var cols = IntArray(INITIAL_CELLS)
    private var lastCols = IntArray(INITIAL_CELLS)

    fun offset(i: Int): Int = offsets[i]

    fun point(i: Int): Point = Point(lines[i], cols[i])

    fun push(
        offset: Int,
        line: Long,
        col: Int,
        lastCol: Int,
    ) {
        if (size == offsets.size) {
            offsets = offsets.copyOf(size * 2)
            lines = lines.copyOf(size * 2)
            cols = cols.copyOf(size * 2)
            lastCols = lastCols.copyOf(size * 2)
        }
        offsets[size] = offset
        lines[size] = line
        cols[size] = col
        lastCols[size] = lastCol
        size++
    }

    fun pop() {
        size--
        text.setLength(offsets[size])
    }

    /** Keeps the last [keep] code points only. */
    fun keepLast(keep: Int) {
        val from = (size - keep).coerceAtLeast(0)
        val cut = if (from < size) offsets[from] else text.length
        text.deleteRange(0, cut)
        for (i in from until size) {
            val to = i - from
            offsets[to] = offsets[i] - cut
            lines[to] = lines[i]
            cols[to] = cols[i]
            lastCols[to] = lastCols[i]
        }
        size -= from
    }

    /** Entries whose offset passes [test], which holds for a prefix of them. */
    inline fun partition(test: (Int) -> Boolean): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (test(offsets[mid])) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Entries whose cell is at or before [p]. */
    fun upTo(p: Point): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (point(mid) <= p) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** The match covering text from [s] until [e]. */
    fun span(
        s: Int,
        e: Int,
    ): Match? {
        val first = partition { it <= s } - 1
        val last = partition { it < e } - 1
        if (first < 0 || last < 0) return null
        return Match(point(first), Point(lines[last], lastCols[last]))
    }
}

/** Calls [visit] with each match starting on lines [first]..[last] (and any that begins up to one pattern earlier inside the same soft-wrapped line, or runs on past [last]), in order, until it answers true. */
internal fun Grid.scan(
    s: Search,
    first: Long,
    last: Long,
    visit: (Match) -> Boolean,
) {
    val keep = (s.codePoints - 1).coerceAtLeast(0)
    // A wrapped row holds at least half its width in characters.
    val perRow = (cols / 2).coerceAtLeast(1)
    val reach = (keep + perRow - 1) / perRow
    val lo = firstLine
    val hi = lastLine
    var line = maxOf(first, lo)
    repeat(reach) { if (lineRow(line - 1)?.wrapped == true) line-- }
    var stop = minOf(last, hi)
    repeat(reach) { if (stop < hi && lineRow(stop)?.wrapped == true) stop++ }
    val w = Window()
    val scratch = StringBuilder()
    var lastEnd: Point? = null
    while (line <= stop) {
        val row = lineRow(line) ?: break
        w.keepLast(keep)
        readRow(row, line, s.fold, w, scratch)
        val skip = lastEnd?.let(w::upTo) ?: 0
        var pos = if (skip < w.size) w.offset(skip) else w.text.length
        while (true) {
            val at = w.text.indexOf(s.pattern, pos)
            if (at < 0) break
            pos = at + s.pattern.length
            val m = w.span(at, pos) ?: continue
            lastEnd = m.end
            if (visit(m)) return
        }
        if (!row.wrapped) w.keepLast(0)
        line++
    }
}

/** Appends [row]'s characters to the window; a row that ends its line loses its trailing blanks, as copied text does. */
private fun Grid.readRow(
    row: Row,
    line: Long,
    fold: Boolean,
    w: Window,
    scratch: StringBuilder,
) {
    val first = w.size
    for (col in 0 until minOf(row.cols, cols)) {
        val cell = row.cell(col)
        if (cell.flags and PADDING != 0) continue
        scratch.setLength(0)
        scratch.appendCell(this, cell)
        val lastCol = if (cell.flags and CellFlags.WIDE != 0) col + 1 else col
        forEachCodePoint(scratch, 0) { cp ->
            var at = w.text.length
            w.text.appendFolded(cp, fold)
            // A code point that folds to several gets one entry each, all on this cell.
            while (at < w.text.length) {
                w.push(at, line, col, lastCol)
                at += if (w.text[at].isHighSurrogate() && at + 1 < w.text.length && w.text[at + 1].isLowSurrogate()) 2 else 1
            }
        }
    }
    if (!row.wrapped) {
        while (w.size > first && w.text.endsWith(' ')) w.pop()
    }
}

/** Where a step starts: [from], else the current match (excluded), else [fallback] (included). */
private fun origin(
    s: Search,
    from: Point?,
    fallback: Point,
): Pair<Point, Boolean> {
    val current = s.current
    return when {
        from != null -> from to false
        current != null -> current.start to false
        else -> fallback to true
    }
}

/** The first match after [from], wrapping round to the oldest line. */
internal fun Grid.searchNext(
    s: Search,
    from: Point?,
): Match? {
    val (at, inclusive) = origin(s, from, viewportPoint(0, 0))
    return findForward(s, at, inclusive, lastLine) ?: findForward(s, Point(firstLine, 0), true, at.line)
}

/** The last match before [from], wrapping round to the screen's bottom. */
internal fun Grid.searchPrev(
    s: Search,
    from: Point?,
): Match? {
    val (at, inclusive) = origin(s, from, viewportPoint(Int.MAX_VALUE, Int.MAX_VALUE))
    return findBackward(s, at, inclusive) ?: findBackward(s, Point(lastLine, Int.MAX_VALUE), true)
}

/** Makes [m] current and scrolls the viewport to show it mid-way, so the lines around it give context. */
internal fun Grid.stepTo(
    s: Search,
    m: Match,
) {
    s.current = m
    if (viewportRow(m.start.line) >= 0) return
    // The screen itself needs no offset; history is clamped to.
    val above = (screenTopLine - m.start.line).coerceAtLeast(0)
    val offset = if (above == 0L) 0L else above + screenRows / 2
    scrollDisplay((offset.coerceAtMost(Int.MAX_VALUE.toLong()) - displayOffset).toInt())
}

private fun Grid.findForward(
    s: Search,
    from: Point,
    inclusive: Boolean,
    last: Long,
): Match? {
    var hit: Match? = null
    scan(s, from.line, last) { m ->
        val after = m.start > from || (inclusive && m.start == from)
        if (after) hit = m
        after
    }
    return hit
}

private fun Grid.findBackward(
    s: Search,
    from: Point,
    inclusive: Boolean,
): Match? {
    val first = firstLine
    var hi = minOf(from.line, lastLine)
    while (true) {
        val lo = maxOf(hi - (BACK_STEP - 1), first)
        var hit: Match? = null
        scan(s, lo, hi) { m ->
            val before = m.start < from || (inclusive && m.start == from)
            if (before) hit = m
            !before
        }
        if (hit != null || lo <= first) return hit
        hi = lo - 1
    }
}

/** Every match, oldest first, up to [MAX_SEARCH_MATCHES]. */
internal fun Grid.matches(s: Search): List<Match> {
    val out = ArrayList<Match>()
    scan(s, firstLine, lastLine) { m ->
        out += m
        out.size >= MAX_SEARCH_MATCHES
    }
    return out
}

/** Lays the matches in the viewport over the cells they cover. */
internal fun Grid.searchMarks(
    s: Search,
    out: MutableList<Mark>,
) {
    val top = viewportPoint(0, 0)
    val bottom = viewportPoint(Int.MAX_VALUE, Int.MAX_VALUE).line
    var left = MAX_SEARCH_MATCHES
    scan(s, top.line, bottom) { m ->
        if (m.start.line > bottom) return@scan true
        if (m.end < top) return@scan false
        val flags = if (s.current == m) FrameCell.MATCH or FrameCell.CURRENT_MATCH else FrameCell.MATCH
        for (line in maxOf(m.start.line, top.line)..minOf(m.end.line, bottom)) {
            val start = if (line == m.start.line) m.start.col else 0
            val end = if (line == m.end.line) m.end.col else lastCol
            out += Mark(viewportRow(line), start, end + 1, flags)
        }
        --left == 0
    }
}
