package scull.grid

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Seeded so a failure reproduces: random screens of letters, wide characters and line breaks, resized
// again and again, must keep every tracked cell and every logical line.
private const val SEED = 0x11F
private const val CASES = 600
private const val BIG_HISTORY = 1000
private const val MAX_TEXT = 120
private const val MAX_COLS = 14
private const val MAX_ROWS = 7
private const val MAX_POINTS = 6
private const val MAX_STEPS = 6
private const val MAX_HISTORY = 10
private const val STYLES = 3
private const val COL_RANGE = 0x10000
private val CJK = 0x4E00..0x4E20

/** A random screenful: letters, wide characters, spaces and line breaks, each with one of a few styles. */
private fun Random.text(): List<Pair<Char, StyleId>> =
    List(nextInt(MAX_TEXT)) {
        val c =
            when (nextInt(10)) {
                in 0..5 -> 'a' + nextInt(26)
                in 6..7 -> CJK.random(this).toChar()
                8 -> ' '
                else -> '\n'
            }
        c to StyleId(nextInt(STYLES))
    }

class ResizePropertyTest {
    @Test
    fun rewrapSequencesLoseNoPointAndNoLine() {
        val random = Random(SEED)
        repeat(CASES) { case ->
            val g = Grid(random.nextInt(2, MAX_COLS), random.nextInt(1, MAX_ROWS), BIG_HISTORY)
            var cursor = writeStyled(g, random.text())
            // Rows below the cursor may not fit on a shorter screen, and a placeholder cell vanishes by
            // design when its line joins.
            var points =
                List(random.nextInt(MAX_POINTS)) { TrackPoint(random.nextInt(cursor.row + 1), random.nextInt(g.cols)) }
                    .filter { it.row < cursor.row || (it.row == cursor.row && it.col <= cursor.col) }
                    .filter { cellAt(g, it)?.hasFlags(CellFlags.LEADING_SPACER) != true }
            val originalLines = lines(g)
            val cursorCell = cellAt(g, cursor)
            val cells = points.map { cellAt(g, it) }
            repeat(random.nextInt(1, MAX_STEPS)) { step ->
                val at = "case $case step $step"
                val t = g.resize(random.nextInt(2, MAX_COLS), random.nextInt(1, MAX_ROWS), Reflow.REWRAP, cursor, points)
                cursor = t.cursor
                points = t.points
                assertTrue(!cursor.clamped && cursor.row >= g.historyLength && cursor.row < total(g), "$at: cursor $cursor")
                assertEquals(cursorCell, cellAt(g, cursor), at)
                points.zip(cells).forEach { (p, cell) ->
                    assertTrue(!p.clamped, "$at: $p lost")
                    assertEquals(cell, cellAt(g, p), at)
                }
                assertEquals(originalLines, lines(g), at)
            }
        }
    }

    @Test
    fun anyResizeSequenceKeepsPointsInTheGridAndTheRingSound() {
        val random = Random(SEED)
        repeat(CASES) { case ->
            val g = Grid(random.nextInt(1, MAX_COLS), random.nextInt(1, MAX_ROWS), random.nextInt(MAX_HISTORY))
            var cursor = writeStyled(g, random.text())
            var points = List(random.nextInt(MAX_POINTS)) { TrackPoint(random.nextInt(total(g) + 2), random.nextInt(COL_RANGE)) }
            repeat(random.nextInt(1, MAX_STEPS)) { step ->
                val at = "case $case step $step"
                g.scrollDisplay(random.nextInt(Byte.MIN_VALUE.toInt(), Byte.MAX_VALUE + 1))
                val cells = points.map { cellAt(g, it) }
                val mode = if (random.nextBoolean()) Reflow.REWRAP else Reflow.TRUNCATE
                val t = g.resize(random.nextInt(1, MAX_COLS), random.nextInt(1, MAX_ROWS), mode, cursor, points)
                cursor = t.cursor
                points = t.points
                assertTrue(cursor.row >= g.historyLength && cursor.row < total(g), "$at: cursor $cursor")
                points.zip(cells).forEach { (p, cell) ->
                    assertTrue(p.row in 0 until total(g) && p.col in 0 until g.cols, "$at: $p")
                    if (!p.clamped) assertEquals(cell, cellAt(g, p), "$at: $p")
                }
                assertTrue(g.historyLength <= g.historyLimit, at)
                assertTrue(g.displayOffset <= g.historyLength, at)
                assertTrue(g.rows.size <= g.capacity, at)
                val ids = ids(g)
                assertTrue(RowId.NONE !in ids, at)
                assertEquals(total(g), ids.toSet().size, at)
                assertTrue((0 until total(g)).all { g.row(it)!!.cols == g.cols }, at)
            }
        }
    }
}
