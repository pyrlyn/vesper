package vesper.term

import vesper.unicode.ClusterPolicy
import vesper.unicode.GraphemeState
import vesper.unicode.WidthOptions
import vesper.unicode.clusterWidth
import vesper.unicode.forEachCodePoint

// The input method's composing text (preedit), laid over the frame at the cursor. It lives in the
// frame, not the terminal: it belongs to the view the user types into, never reaches the child, and
// setting it takes no lock. Laying it out in the core gives it the grid's clusters and widths, so
// every renderer draws it like any other row text. Ported from the earlier Rust core (`preedit.rs`
// of its `term` crate); offsets are UTF-16 units, as NSString and the Windows text APIs count them.

/**
 * UTF-16 units of preedit a frame keeps. The text comes from an input method, not the child, but it
 * is still capped: a composition is a few words, and the layout costs a pass over it per update.
 */
public const val MAX_PREEDIT_LENGTH: Int = 1024

private const val SUPPLEMENTARY_PLANE = 0x10000

/** How an input method treats a stretch of its composing text; renderers draw them apart. */
public enum class PreeditKind {
    /** Typed and not converted yet (a kana before its conversion). */
    Input,

    /** Converted, not the one being edited. */
    Converted,

    /** The clause the user is converting now, the one a candidate list is open on. */
    Target,
}

/** A stretch of the preedit text from UTF-16 offset [start] until [end], with its kind. */
public data class PreeditClause(
    val start: Int,
    val end: Int,
    val kind: PreeditKind,
)

/** A run of [cols] columns of the shown preedit from column [col], all of one clause kind. */
public data class PreeditSpan(
    val col: Int,
    val cols: Int,
    val kind: PreeditKind,
)

/**
 * Where the frame shows the preedit: [cols] columns of viewport row [row] from column [col]. The text
 * is already in the row's cells and runs; a renderer marks it (an underline), clause by clause when
 * [spans] lists them, as one plain stretch when it is empty.
 */
public data class FramePreedit(
    val row: Int,
    val col: Int,
    val cols: Int,
    val spans: List<PreeditSpan>,
)

/** The preedit placed on one row: where, the caret's column, and the clusters shown with their widths. */
internal data class PreeditLayout(
    val shown: FramePreedit,
    val caret: Int,
    val clusters: List<String>,
    val widths: List<Int>,
)

/** The preedit as the view set it. */
internal class Preedit {
    var text: String = ""
        private set

    /** UTF-16 offset of the caret in [text], never inside a surrogate pair. */
    private var caret = 0

    /** In text order, non-empty, on code point boundaries, not overlapping. */
    private val clauses = ArrayList<PreeditClause>()

    /**
     * Replaces the composition, cut at [MAX_PREEDIT_LENGTH]. The input method is not trusted to be
     * tidy: the caret and every clause are cut to the text and onto code point boundaries, and a
     * clause that starts inside the one before it loses the overlap. Text no clause names is input.
     */
    fun set(
        text: String,
        caret: Int,
        clauses: List<PreeditClause>,
    ) {
        this.text = text.substring(0, floor(text, MAX_PREEDIT_LENGTH))
        this.caret = floor(this.text, caret)
        this.clauses.clear()
        var from = 0
        for (c in clauses.sortedBy { it.start }) {
            val start = maxOf(floor(this.text, c.start), from)
            val end = floor(this.text, c.end)
            if (start < end) {
                this.clauses += PreeditClause(start, end, c.kind)
                from = end
            }
        }
    }

    /** Lays the preedit out at the cursor ([row], [col]) of a row [cols] wide; null when nothing fits. */
    fun layout(
        row: Int,
        col: Int,
        cols: Int,
        width: WidthOptions,
        policy: ClusterPolicy,
    ): PreeditLayout? {
        if (cols <= 0 || text.isEmpty()) return null
        val starts = clusterStarts(width)
        val all = starts.indices.map { k -> text.substring(starts[k], starts.getOrElse(k + 1) { text.length }) }
        // A lone mark still takes a cell, or it would vanish.
        val allWidths = all.map { clusterWidth(it, width, policy).coerceIn(1, 2) }
        val caretCols = starts.indices.filter { k -> starts[k] + all[k].length <= caret }.sumOf { allWidths[it] }
        var total = allWidths.sum()
        // Too wide for the row: keep the caret in view, dropping from the left first, then cut the right.
        var dropped = 0
        var first = 0
        while (total > cols && caretCols - dropped >= cols) {
            total -= allWidths[first]
            dropped += allWidths[first]
            first++
        }
        var last = all.size
        while (total > cols) total -= allWidths[--last]
        if (total == 0) return null
        // Shifted left so it ends inside the row, as a line editor would wrap.
        val start = minOf(col, cols - total)
        val caretCol = minOf(start + caretCols - dropped, cols - 1)
        val shownStarts = starts.subList(first, last)
        val widths = allWidths.subList(first, last)
        return PreeditLayout(FramePreedit(row, start, total, spans(shownStarts, widths, start)), caretCol, all.subList(first, last), widths)
    }

    private fun clusterStarts(width: WidthOptions): List<Int> {
        val starts = ArrayList<Int>()
        val segments = GraphemeState(width.version)
        var at = 0
        text.forEachCodePoint { cp ->
            if (segments.next(cp)) starts += at
            at += if (cp >= SUPPLEMENTARY_PLANE) 2 else 1
        }
        return starts
    }

    /** The shown clusters grouped by clause kind, from column [col]; a cluster belongs to the clause its first unit is in. */
    private fun spans(
        starts: List<Int>,
        widths: List<Int>,
        col: Int,
    ): List<PreeditSpan> {
        if (clauses.isEmpty()) return emptyList()
        val spans = ArrayList<PreeditSpan>()
        var at = col
        for (k in starts.indices) {
            val kind = clauses.firstOrNull { starts[k] >= it.start && starts[k] < it.end }?.kind ?: PreeditKind.Input
            val last = spans.lastOrNull()
            if (last != null && last.kind == kind) {
                spans[spans.lastIndex] = last.copy(cols = last.cols + widths[k])
            } else {
                spans += PreeditSpan(at, widths[k], kind)
            }
            at += widths[k]
        }
        return spans
    }
}

/** [offset] clamped to [text] and moved back off the second half of a surrogate pair. */
private fun floor(
    text: String,
    offset: Int,
): Int {
    val at = offset.coerceIn(0, text.length)
    return if (at in 1 until text.length && text[at].isLowSurrogate() && text[at - 1].isHighSurrogate()) at - 1 else at
}
