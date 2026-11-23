package scull.bench

import scull.grid.Attrs
import scull.grid.Cell
import scull.grid.Color
import scull.grid.Grid
import scull.grid.MAX_RING_ROWS
import scull.grid.Style
import scull.grid.StyleId
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

// What one scrollback row costs and what one scrolled line costs, on the fixture of the Rust bench
// this replaces (scull-grid/benches/scrollback.rs at 69d9be4): 120 columns, 40 screen rows and a
// full history of 10 000 rows, filled with blank rows, typical log lines and SGR-heavy lines.
//
// Memory is read from the collector, which counts the heap in steps of 128 KiB and more. Over
// 10 000 rows that is tens of bytes per row, so the memory sample is the largest ring instead.
// Garbage between two readings skews the second one: with a grid dropped before the next was built,
// a plain row read 619 B against 675 B in a fresh process. The three grids therefore stay alive
// until the last figure is taken, and the memory table runs before the timings.

private const val GRID_COLS = 120
private const val GRID_SCREEN = 40
private const val GRID_HISTORY = 10_000

/** History of the memory sample: with the screen it fills the largest ring, so no slot stays spare. */
private const val MEMORY_HISTORY = MAX_RING_ROWS - GRID_SCREEN

/** A typical log line: shorter than the window, default style. */
private const val PLAIN_CELLS = 72

/** Palette colours the SGR-heavy row cycles through, each plain and bold. */
private const val SGR_PALETTE = 16
private const val LETTERS = 26

/** Timed batches per operation; an odd count gives the median a middle run. */
private const val GRID_RUNS = 21

/** Scrolled lines per batch: long enough for the clock, short enough to stay a quick run. */
private const val LINES_PER_RUN = 20_000

private typealias Line = (Grid) -> Unit

private fun plainLine(g: Grid) {
    val row = checkNotNull(g.screenRow(GRID_SCREEN - 1))
    for (col in 0 until PLAIN_CELLS) row.put(col, Cell.char('a'.code + col % LETTERS))
}

private val plainBytes = ByteArray(PLAIN_CELLS) { ('a'.code + it % LETTERS).toByte() }

/** The same line as [plainLine], written in one call: what the terminal does with a run of ASCII. */
private fun plainRun(g: Grid) {
    checkNotNull(g.screenRow(GRID_SCREEN - 1)).putAscii(0, plainBytes, 0, PLAIN_CELLS, StyleId.DEFAULT)
}

private fun sgrStyles(g: Grid): List<StyleId> =
    buildList {
        for (n in 0 until SGR_PALETTE) {
            for (attrs in intArrayOf(0, Attrs.BOLD)) {
                add(g.internStyle(Style(fg = Color.indexed(n), bg = Color.indexed(SGR_PALETTE - 1 - n), attrs = attrs)))
            }
        }
    }

/** A style change in every cell of the row. */
private fun sgrLine(g: Grid): Line {
    val styles = sgrStyles(g)
    return { grid ->
        val row = checkNotNull(grid.screenRow(GRID_SCREEN - 1))
        for (col in 0 until GRID_COLS) row.put(col, Cell.char('x'.code, styles[col % styles.size]))
    }
}

/** A grid whose [history] is full of lines written by what [line] returns for it. */
private fun filled(
    history: Int,
    line: (Grid) -> Line,
): Pair<Grid, Line> {
    val g = Grid(GRID_COLS, GRID_SCREEN, history)
    val write = line(g)
    repeat(history + GRID_SCREEN) {
        write(g)
        g.scrollUp(1)
    }
    return g to write
}

/**
 * Collects twice. After one collection the heap figure still counts garbage that collection freed:
 * measured that way, an SGR-heavy row read 409 B instead of 1061 B.
 */
@OptIn(NativeRuntimeApi::class)
private fun collect() {
    GC.collect()
    GC.collect()
}

/** Live heap after a full collection, as the collector itself counts it. */
@OptIn(NativeRuntimeApi::class, ExperimentalStdlibApi::class)
private fun heapBytes(): Long {
    collect()
    val heap = GC.lastGCInfo?.memoryUsageAfter?.get("heap")
    return checkNotNull(heap) { "the collector reported no heap figure" }.totalObjectsSizeBytes
}

/**
 * Heap one history row costs, per fill: the growth of the live heap over building a full grid,
 * divided by its history rows. It counts the row object, its cells and its slot in the ring, and
 * spreads the 40 screen rows and the tables over the history rows.
 */
private fun bytesPerHistoryRow(fills: List<(Grid) -> Line>): List<Long> {
    val kept = ArrayList<Grid>(fills.size)
    var heap = heapBytes()
    val bytes =
        fills.map { fill ->
            kept += filled(MEMORY_HISTORY, fill).first
            val grown = heapBytes()
            (grown - heap).also { heap = grown } / MEMORY_HISTORY
        }
    // Read after the last measurement, so every grid is still live when the collector counts it.
    check(kept.all { it.historyLength == MEMORY_HISTORY })
    return bytes
}

/** Median nanoseconds per scrolled line of [GRID_RUNS] batches, after a warm-up batch. */
private fun nanosPerLine(
    line: (Grid) -> Line,
    write: Boolean,
): Double {
    val (g, writeLine) = filled(GRID_HISTORY, line)
    // The sample before this one left garbage; it is collected here, not on this sample's clock.
    collect()
    val batch = {
        repeat(LINES_PER_RUN) {
            if (write) writeLine(g)
            g.scrollUp(1)
        }
    }
    batch()
    val runs =
        List(GRID_RUNS) {
            val mark = TimeSource.Monotonic.markNow()
            batch()
            mark.elapsedNow()
        }
    check(g.historyLength == GRID_HISTORY)
    return runs.sorted()[GRID_RUNS / 2].toDouble(DurationUnit.NANOSECONDS) / LINES_PER_RUN
}

/** The grid tables: bytes per scrollback row, then nanoseconds per scrolled line. */
internal fun gridMarkdown(): String {
    val (blank, plain, sgr) = bytesPerHistoryRow(listOf({ _ -> { } }, { _ -> ::plainLine }, ::sgrLine))
    return buildString {
        appendLine("| Scrollback row at $GRID_COLS columns, of $MEMORY_HISTORY | Bytes |")
        appendLine("| --- | --- |")
        appendLine("| Blank | $blank |")
        appendLine("| Plain text ($PLAIN_CELLS cells) | $plain |")
        appendLine("| SGR-heavy ($GRID_COLS cells, ${SGR_PALETTE * 2 + 1} styles) | $sgr |")
        appendLine()
        appendLine("| One line into a full history of $GRID_HISTORY | Median (ns) |")
        appendLine("| --- | --- |")
        appendLine("| Scroll only | ${fixed(nanosPerLine({ _ -> ::plainLine }, write = false), 1)} |")
        appendLine("| Write a plain line and scroll | ${fixed(nanosPerLine({ _ -> ::plainLine }, write = true), 1)} |")
        appendLine("| Write a plain line as one run and scroll | ${fixed(nanosPerLine({ _ -> ::plainRun }, write = true), 1)} |")
        appendLine("| Write an SGR-heavy line and scroll | ${fixed(nanosPerLine(::sgrLine, write = true), 1)} |")
    }
}
