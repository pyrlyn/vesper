package scull.grid

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val GRID_COLS = 8
private const val SCREEN = 3

class GridTest {
    private fun grid(history: Int) = Grid(GRID_COLS, SCREEN, history)

    private fun ch(c: Char) = Cell.char(c.code)

    /** Writes [c] at column 0 of the bottom screen row, then scrolls it up. */
    private fun emit(
        g: Grid,
        c: Char,
    ) {
        g.screenRow(g.screenRows - 1)!!.put(0, ch(c))
        g.scrollUp(1)
    }

    private fun firstChar(row: Row): Char? =
        row
            .cell(0)
            .codePoint
            .takeIf { it != 0 }
            ?.toChar()

    private fun screenIds(g: Grid) = (0 until g.screenRows).map { g.screenRow(it)!!.id }

    private fun style(n: Int) = Style(fg = Color.indexed(n))

    @Test
    fun evictedCountsEveryRowThatLeftTheRing() {
        val g = grid(2)
        repeat(2) { emit(g, 'a') }
        assertEquals(0, g.evicted, "history still had room")
        repeat(3) { emit(g, 'b') }
        assertEquals(3, g.evicted)
        g.clearHistory()
        assertEquals(5, g.evicted)
        val alt = grid(0)
        alt.scrollUp(2)
        assertEquals(2, alt.evicted, "a screen without history evicts every scrolled row")
        emit(g, 'c')
        emit(g, 'd')
        g.resize(GRID_COLS, 1, Reflow.REWRAP, TrackPoint(g.historyLength + SCREEN - 1, 0))
        // Five rows (b, b, c, d and the cursor's blank one) in one screen row and two of history.
        assertEquals(7, g.evicted)
    }

    @Test
    fun gridNeedsARowAndAColumn() {
        assertFailsWith<IllegalArgumentException> { Grid(0, 1, 0) }
        assertFailsWith<IllegalArgumentException> { Grid(1, 0, 0) }
        assertFailsWith<IllegalArgumentException> { Grid(1, 1, -1) }
    }

    @Test
    fun scrollbackRequestIsClampedToTheRingCap() {
        val g = Grid(GRID_COLS, SCREEN, Int.MAX_VALUE)
        assertEquals(MAX_RING_ROWS - SCREEN, g.historyLimit)
        assertEquals(MAX_RING_ROWS, g.capacity)
        assertEquals(SCREEN, g.rows.size, "slots are lazy")
    }

    @Test
    fun ringIsAPowerOfTwoCoveringScreenAndHistory() {
        val g = grid(10)
        assertEquals(16, g.capacity)
        assertEquals(10, g.historyLimit, "the limit is not rounded up")
    }

    // The terminal leaves this cell when a wide character does not fit at the row end (scull-term
    // Print.kt). It is a blank that differs from the fill only by its flag, which is what a trim of
    // trailing blanks on the way into history must not drop.
    @Test
    fun aLeadingSpacerAtTheRowEndSurvivesTheMoveIntoHistory() {
        val g = grid(history = 4)
        val top = g.screenRow(0)!!
        "abcdefg".forEachIndexed { col, c -> top.put(col, ch(c)) }
        top.set(GRID_COLS - 1, Cell.EMPTY.withFlags(CellFlags.LEADING_SPACER))
        top.wrapped = true
        g.scrollUp(SCREEN)

        val kept = g.row(0)!!
        assertEquals(top.id, kept.id)
        val spacer = kept.cell(GRID_COLS - 1)
        assertTrue(spacer.hasFlags(CellFlags.LEADING_SPACER))
        assertTrue(spacer.isEmpty)
        assertTrue(kept.wrapped)
        assertEquals("abcdefg", (0 until GRID_COLS - 1).map { kept.cell(it).codePoint.toChar() }.joinToString(""))
        assertEquals(GRID_COLS, kept.cellCapacity, "the spacer is the last stored cell")
    }

    @Test
    fun scrollingAFullRingIsAnOffsetChange() {
        // Screen 3 plus history 5 fills a ring of 8 exactly.
        val g = grid(5)
        for (c in 'a'..'z') emit(g, c)
        assertEquals(g.capacity, g.rows.size)
        g.screenRow(0)!!.put(0, ch('!'))
        val kept = g.screenRow(0)!!.id
        val slot = g.phys(g.historyLength)
        val zero = g.zero
        g.scrollUp(1)
        assertEquals((zero + 1) and g.mask, g.zero)
        assertEquals(g.capacity, g.rows.size, "no slot added")
        val moved = g.historyLength - 1
        assertEquals(slot, g.phys(moved), "the row stayed in its slot")
        assertEquals(kept, g.row(moved)!!.id, "the row moved into history by offset")
        assertEquals('!', firstChar(g.row(moved)!!))
    }

    @Test
    fun scrollKeepsIdsAndOrderOfRows() {
        val g = grid(4)
        val before = screenIds(g)
        emit(g, 'x')
        val after = screenIds(g)
        assertEquals(before.drop(1), after.take(2))
        assertFalse(after[2] in before, "the new bottom row has a fresh id")
        assertEquals(before[0], g.row(0)!!.id)
    }

    @Test
    fun historyStopsAtItsLimitAndDropsTheOldest() {
        val g = grid(5)
        for (c in 'a'..'t') emit(g, c)
        assertEquals(5, g.historyLength)
        // 's' and 't' are still on screen; the five before them remain.
        assertEquals(listOf('n', 'o', 'p', 'q', 'r'), (0 until 5).map { firstChar(g.row(it)!!) })
    }

    @Test
    fun ringLargerThanNeededKeepsEvictedSlotsEmpty() {
        // Screen 3 plus history 2 needs 5 rows of a ring of 8.
        val g = grid(2)
        for (c in 'a'..'z') emit(g, c)
        assertTrue(g.rows.size <= g.capacity)
        val live = (0 until g.historyLength + SCREEN).map { g.phys(it) }.toSet()
        g.rows.forEachIndexed { at, row ->
            if (at !in live) {
                assertTrue(row.isUniform, "slot $at still holds cells")
                assertEquals(0, row.cellCapacity, "slot $at keeps its array")
            }
        }
    }

    @Test
    fun screenWithoutHistoryRecyclesItsOwnRows() {
        val g = grid(0)
        for (c in 'a'..'z') emit(g, c)
        assertEquals(0, g.historyLength)
        assertTrue(g.rows.size <= g.capacity)
        assertEquals('z', firstChar(g.screenRow(SCREEN - 2)!!))
    }

    @Test
    fun rowsEnteringHistoryAreCompacted() {
        val g = grid(4)
        emit(g, 'a')
        g.scrollUp(SCREEN)
        val row = g.row(g.historyLength - SCREEN + 1)!!
        assertEquals('a', firstChar(row))
        assertEquals(1, row.cellCapacity)
    }

    @Test
    fun regionScrollsRotateInsideTheRegionOnly() {
        val rows = 5
        val g = Grid(GRID_COLS, rows, 10)
        for (r in 0 until rows) g.screenRow(r)!!.put(0, ch('a' + r))

        fun text() = (0 until rows).map { firstChar(g.screenRow(it)!!) ?: '.' }.joinToString("")
        g.scrollRegionUp(1, 4, 1)
        assertEquals("acd.e", text())
        g.scrollRegionDown(1, 4, 2)
        assertEquals("a..ce", text())
        assertEquals(0, g.historyLength, "a partial region never feeds history")
        g.scrollRegionUp(0, rows, 1)
        assertEquals(1, g.historyLength, "the full screen does")
    }

    @Test
    fun viewportStaysOnItsContentWhileOutputScrolls() {
        val g = grid(10)
        for (c in 'a'..'f') emit(g, c)
        g.scrollDisplay(2)
        val seen = g.visibleRow(0)!!.id
        emit(g, 'g')
        assertEquals(seen, g.visibleRow(0)!!.id)
        assertEquals(3, g.displayOffset)
    }

    @Test
    fun displayOffsetIsClampedToHistory() {
        val g = grid(10)
        emit(g, 'a')
        g.scrollDisplay(Int.MAX_VALUE)
        assertEquals(1, g.displayOffset)
        g.scrollDisplay(Int.MIN_VALUE)
        assertEquals(0, g.displayOffset)
        assertNull(g.visibleRow(SCREEN))
        assertNull(g.row(-1))
    }

    @Test
    fun clearHistoryKeepsTheScreen() {
        val g = grid(10)
        for (c in 'a'..'f') emit(g, c)
        val screen = screenIds(g)
        g.scrollDisplay(2)
        g.clearHistory()
        assertEquals(0 to 0, g.historyLength to g.displayOffset)
        assertEquals(screen, screenIds(g))
        emit(g, 'g')
        assertEquals(1, g.historyLength)
    }

    @Test
    fun fullStyleTableSweepsStylesNoRowUses() {
        val g = Grid(GRID_COLS, SCREEN, 0, styles = StyleTable(4))
        val used = g.internStyle(style(1))
        g.screenRow(0)!!.put(0, Cell.char('x'.code, used))
        val garbage = g.internStyle(style(2))
        val recent = g.internStyle(style(3))
        val fresh = g.internStyle(style(4))
        assertEquals(garbage, fresh, "the unused id was reclaimed")
        assertEquals(1, g.internEpoch)
        assertEquals(style(1), g.styles[used])
        assertEquals(style(3), g.styles[recent], "most recent is pinned")
    }

    @Test
    fun tableFullOfLiveStylesFailsWithoutSweepingAgain() {
        // More rows than SWEEP_DIVISOR, so one recycled row does not pay.
        val g = Grid(GRID_COLS, 16, 100, styles = StyleTable(3))
        var col = 0
        while (true) {
            val id =
                try {
                    g.internStyle(style(col + 1))
                } catch (_: GridException.StyleTableFull) {
                    break
                }
            g.screenRow(0)!!.put(col++, Cell.char('x'.code, id))
        }
        assertEquals(0, g.styles.insertsSinceSweep, "one sweep ran")
        assertEquals(0, g.internEpoch, "it reclaimed nothing")
        g.scrollRegionUp(1, 2, 1)
        assertFailsWith<GridException.StyleTableFull> { g.internStyle(style(9)) }
        assertEquals(1, g.releasedSinceSweep, "no second sweep for one recycled row")
    }

    @Test
    fun clustersOfEvictedRowsAreReclaimed() {
        val g = Grid(GRID_COLS, SCREEN, 0, clusters = ClusterTable(1))
        val a = g.internCluster("á")
        g.screenRow(0)!!.put(0, Cell.cluster(a))
        g.recentCluster = -1
        assertFailsWith<GridException.ClusterTableFull>("row 0 still shows it") { g.internCluster("é") }
        g.scrollUp(SCREEN)
        val e = g.internCluster("é")
        assertEquals("é", g.clusters[e])
        assertEquals(1, g.clusters.size)
    }

    @Test
    fun anyScrollSequenceKeepsTheRingConsistent() {
        val rows = 4
        val rng = Random(0x5c011)
        repeat(256) {
            val g = Grid(GRID_COLS, rows, rng.nextInt(12))
            repeat(rng.nextInt(200)) {
                when (rng.nextInt(5)) {
                    0 -> g.scrollUp(rng.nextInt(5))
                    1 -> g.scrollRegionUp(rng.nextInt(6), rng.nextInt(6), rng.nextInt(4))
                    2 -> g.scrollRegionDown(rng.nextInt(6), rng.nextInt(6), rng.nextInt(4))
                    3 -> g.scrollDisplay(rng.nextInt(-128, 128))
                    else -> g.clearHistory()
                }
                assertTrue(g.historyLength <= g.historyLimit)
                assertTrue(g.displayOffset <= g.historyLength)
                assertTrue(g.rows.size <= g.capacity)
                val total = g.historyLength + rows
                val ids = (0 until total).map { g.row(it)!!.id }
                assertTrue(ids.none { it == RowId.NONE }, "every live row has an id")
                assertEquals(total, ids.toSet().size, "live rows have distinct ids")
            }
        }
    }
}
