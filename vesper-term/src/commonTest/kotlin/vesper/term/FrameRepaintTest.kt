package vesper.term

import vesper.grid.Style
import vesper.grid.StyleId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

// Seeded so a failure reproduces. A UI that blits the scrolls from its last picture and repaints only
// the dirty rows must end up with what a full repaint shows, whatever the PTY sends.
private const val SEED = 0x2026
private const val CASES = 400
private const val MAX_COLS = 16
private const val MAX_ROWS = 7
private const val SCROLLBACK = 6
private const val MAX_STEPS = 24
private const val MAX_SEQUENCES = 6
private const val MAX_VIEW_DELTA = 128

/** Longer than the hold cap, so some steps release a hold by time. */
private const val MAX_PAUSE_MS = 200

/**
 * Whole sequences the fragments rarely assemble: synchronized output, screens, regions, colours,
 * clusters and erases, and a write mid-screen so one update often holds several scrolls.
 */
private val SEQUENCES =
    listOf(
        "$CSI?2026h",
        "$CSI?2026l",
        "$CSI?1049h",
        "$CSI?1049l",
        "${CSI}2;3r",
        "${CSI}r",
        "${CSI}31m",
        "${CSI}44m",
        "${CSI}m",
        "$CSI?2027h",
        "${CSI}2J",
        "${CSI}3J",
        "${CSI}L",
        "${CSI}M",
        "${ESC}M",
        "\r\n",
        "${CSI}2H",
        "x",
    )

/** A row as a UI puts it on screen: every cell with its style resolved, and every run with its text. */
private data class Painted(
    val cells: List<List<Any?>>,
    val runs: List<List<Any?>>,
)

private fun Frame.paint(r: Int): Painted {
    val row = checkNotNull(row(r))
    return Painted(
        cells = (0 until cols).map { c -> cell(r, c).let { listOf(it.codePoint, it.width, it.flags, style(it.style)) } },
        runs = row.runs.map { listOf(it.col, it.cols, it.width, style(it.style), row.runText(it)) },
    )
}

private fun Frame.paintAll(): List<Painted> = (0 until rows).map { paint(it) }

/** What a UI does with the damage: blit the scrolls from its last picture, then repaint the dirty rows. */
private fun MutableList<Painted>.repaint(f: Frame) {
    val d = f.damage
    if (d.isFull) {
        clear()
        addAll(f.paintAll())
        return
    }
    val last = toList()
    for (s in d.scrolls) for ((i, r) in s.rows.withIndex()) this[r] = last[s.from + i]
    for (r in d.dirty) this[r] = f.paint(r)
}

private fun Random.step(term: Terminal) {
    when (nextInt(9)) {
        in 0..3 -> term.feed(stream())
        in 4..7 -> term.feed(List(nextInt(1, MAX_SEQUENCES)) { SEQUENCES.random(this) }.joinToString(""))
        else -> term.scrollDisplay(nextInt(-MAX_VIEW_DELTA, MAX_VIEW_DELTA))
    }
}

class FrameRepaintTest {
    @Test
    fun repaintingTheDamageOverTheLastPictureEqualsAFullRepaint() {
        val random = Random(SEED)
        repeat(CASES) { case ->
            val cols = random.nextInt(1, MAX_COLS)
            val rows = random.nextInt(1, MAX_ROWS)
            var now = TimeSource.Monotonic.markNow()
            val t = Terminal(cols, rows, SCROLLBACK)
            val frame = Frame()
            val canvas = ArrayList<Painted>()
            repeat(random.nextInt(1, MAX_STEPS)) { step ->
                val at = "case $case step $step"
                random.step(t)
                now += random.nextInt(MAX_PAUSE_MS).milliseconds
                val shown = frame.paintAll()
                if (!t.updateFrame(frame, now)) {
                    assertTrue(frame.damage.isEmpty, at)
                    assertEquals(shown, frame.paintAll(), "$at: a held picture never changes")
                    return@repeat
                }
                canvas.repaint(frame)
                val full = Frame()
                assertTrue(t.updateFrame(full, now), at)
                assertEquals(full.paintAll(), canvas, at)
                assertEquals(canvas, frame.paintAll(), at)
                assertEquals(full.cursor, frame.cursor, at)
                for (r in 0 until rows) {
                    assertEquals(full.stamp(r), frame.stamp(r), at)
                    for (c in 0 until cols) {
                        val id = frame.cell(r, c).style
                        assertEquals(t.grid.styles[StyleId(id)] ?: Style.DEFAULT, frame.style(id), "$at: no stale style")
                    }
                }
            }
        }
    }
}
