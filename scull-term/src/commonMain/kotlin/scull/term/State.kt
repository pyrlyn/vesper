package scull.term

import scull.grid.Cell
import scull.grid.Grid
import scull.grid.Row
import scull.parser.Csi
import scull.parser.Esc
import scull.parser.Handler
import scull.parser.Osc
import scull.unicode.ClusterPolicy
import scull.unicode.GraphemeState
import scull.unicode.WidthOptions

// The state the parser's actions drive: grid, cursor, pen, margins, tab stops and modes. Separate
// from Terminal.kt so the public type stays a thin shell around the parser and this handler, and
// the semantics live in the files that extend it (Dispatch, Print, Motion, Edit, Screen, Reply).

/**
 * Where the next character goes. Rows and columns count from 0 at the top left of the screen,
 * whatever the origin mode.
 */
public data class Cursor(
    val row: Int = 0,
    val col: Int = 0,
    /**
     * The last column was just written with autowrap on: the next printable character wraps first
     * (DEC STD 070 "last column flag").
     */
    val pendingWrap: Boolean = false,
)

/** Scroll margins, inclusive, counted from 0: DECSTBM sets [top] and [bottom], DECSLRM [left] and [right]. */
public data class Margins(
    val top: Int,
    val bottom: Int,
    val left: Int,
    val right: Int,
)

internal const val NO_CHAR = -1

/** Everything the handler mutates. [alt] must have the size of [grid]. */
internal class State(
    /** The screen shown. */
    var grid: Grid,
    /** The screen not shown: the alternate one, or the main one while the alternate is active. */
    var alt: Grid,
    val width: WidthOptions,
) : Handler {
    // Fields, not getters on the grid: the print path reads them per character. Only resize sets them.
    var cols: Int = grid.cols
    var rows: Int = grid.screenRows
    var lastCol: Int = cols - 1
    var lastRow: Int = rows - 1

    var altActive = false

    /** DECSC state for the main and the alternate screen. */
    val saved = arrayOfNulls<SavedCursor>(2)
    val charsets = Charsets()
    val replies = Replies()
    val events = EventQueue()

    // The cursor and the margins are plain fields, not the public data classes: printing moves the
    // cursor once per run, and a copy per move would be garbage on the hot path.
    var row = 0
    var col = 0
    var pendingWrap = false
    var top = 0
    var bottom = lastRow
    var left = 0
    var right = lastCol

    val pen = Pen()
    val modes = Modes()
    val tabs = TabStops(cols)

    /** Segmentation of the text printed since the last other action. */
    val grapheme = GraphemeState(width.version)

    /** The last character printed, for REP, or [NO_CHAR]. */
    var lastChar = NO_CHAR

    /** The cursor's row for writing. The cursor never leaves the screen, so the row exists. */
    fun cursorRow(): Row = checkNotNull(grid.screenRow(row)) { "cursor row $row of $rows" }

    /** What erasing leaves behind with the current pen. */
    fun blank(): Cell = pen.blank(grid)

    /**
     * Any action other than printing ends the cluster being built, so a combining mark after a
     * cursor move never reaches back across it.
     */
    fun endCluster() = grapheme.reset()

    val policy: ClusterPolicy
        get() = if (modes.graphemeClusters) ClusterPolicy.Grapheme else ClusterPolicy.FirstCodePoint

    /** The column printing stops at: the right margin, or the screen edge for a cursor already right of the margin (xterm). */
    fun lineEnd(): Int = if (col <= right) right else lastCol

    /** Whether the cursor is inside the left and right margins, where scrolling and line edits act. */
    fun inLrMargins(): Boolean = col in left..right

    /** Whether the cursor is inside the top and bottom margins. */
    fun inTbMargins(): Boolean = row in top..bottom

    fun resetMargins() {
        top = 0
        bottom = lastRow
        left = 0
        right = lastCol
    }

    override fun print(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ) = printText(bytes, start, end)

    override fun execute(byte: Int) = executeControl(byte)

    override fun escDispatch(esc: Esc) = dispatchEsc(esc)

    override fun csiDispatch(csi: Csi) = dispatchCsi(csi)

    override fun oscDispatch(osc: Osc) = dispatchOsc(osc)
}
