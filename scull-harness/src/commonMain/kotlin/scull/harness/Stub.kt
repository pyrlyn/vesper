package scull.harness

/**
 * Stand-in for the terminal core, so recorded streams and golden frames can be checked before the
 * real parser and grid exist. The size is fixed at construction so later tasks can hang the same
 * assertions on the real grid.
 *
 * A zero axis is raised to 1: a grid with no cells cannot host a cursor, and every later test
 * assumes at least one.
 */
public class Stub(
    cols: Int,
    rows: Int,
) {
    public val cols: Int = cols.coerceIn(1, MAX_AXIS)
    public val rows: Int = rows.coerceIn(1, MAX_AXIS)

    private val cells = Array(this.rows) { CharArray(this.cols) { ' ' } }
    private var cursorCol = 0

    // `rows` means the cursor wrapped off the last row; printable bytes stop there so the stub
    // never scrolls, while a line feed still clamps onto the last row.
    private var cursorRow = 0

    // The last column was just written, so the next printable wraps first (xterm DECAWM,
    // DEC STD 070 "last column flag").
    private var pendingWrap = false

    /**
     * Writes [bytes] into the grid.
     *
     * Printable ASCII (`0x20..0x7E`) lands at the cursor. Filling the last column arms a wrap and
     * leaves the cursor there; only the next printable wraps, to column 0 of the next row (xterm
     * DECAWM, the rule `scull-term` follows). Past the last row those bytes are dropped. `\n` and
     * `\r` clear a pending wrap instead of wrapping: `\n` goes to column 0 of the next row and
     * stays on the last row, `\r` returns to column 0. Any other byte is ignored, so a capture may
     * contain controls the stub does not understand.
     */
    public fun feed(bytes: ByteArray) {
        for (byte in bytes) {
            when (val b = byte.toInt() and 0xFF) {
                LF -> {
                    pendingWrap = false
                    cursorCol = 0
                    cursorRow = minOf(cursorRow + 1, rows - 1)
                }

                CR -> {
                    pendingWrap = false
                    cursorCol = 0
                }

                in PRINTABLE -> {
                    writePrintable(b.toChar())
                }
            }
        }
    }

    /** The character at 0-based [row] and [col]. */
    public operator fun get(
        row: Int,
        col: Int,
    ): Char = cells[row][col]

    /** Row [row] as a string of exactly [cols] characters. */
    public fun line(row: Int): String = cells[row].concatToString()

    /** Every row, top to bottom. */
    public fun lines(): List<String> = List(rows) { line(it) }

    private fun writePrintable(ch: Char) {
        if (pendingWrap) {
            pendingWrap = false
            cursorCol = 0
            cursorRow += 1
        }
        if (cursorRow >= rows) return
        cells[cursorRow][cursorCol] = ch
        if (cursorCol < cols - 1) {
            cursorCol += 1
        } else {
            pendingWrap = true
        }
    }

    private companion object {
        const val LF = 0x0A
        const val CR = 0x0D
        val PRINTABLE = 0x20..0x7E

        // A fuzzer- or file-chosen size must not ask for the whole address space.
        const val MAX_AXIS = 4096
    }
}
