package vesper.grid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val BIG_HISTORY = 1000
private val RED = StyleId(1)

private fun ch(c: Char): Cell = Cell.char(c.code)

class ResizeTest {
    private fun Grid.rewrap(
        cols: Int,
        screen: Int,
        cursor: TrackPoint,
        points: List<TrackPoint> = emptyList(),
    ): Tracked = resize(cols, screen, Reflow.REWRAP, cursor, points)

    @Test
    fun shrinkThenGrowRestoresEveryRowAndTheCursor() {
        val g = Grid(10, 4, BIG_HISTORY)
        val at = write(g, "hello world\nsecond line here\nx")
        val original = rows(g)
        assertEquals(listOf("hello worl\\", "d", "second lin\\", "e here", "x"), original)
        val cursor = g.rewrap(4, 4, at).cursor
        assertEquals(listOf("hell\\", "o wo\\", "rld", "seco\\", "nd l\\", "ine \\", "here", "x"), rows(g))
        assertEquals(TrackPoint(7, 1), cursor)
        assertEquals(at, g.rewrap(10, 4, cursor).cursor)
        assertEquals(original, rows(g))
    }

    @Test
    fun aWideCharacterAtTheWrapEdgeMovesWholeBehindALeadingSpacer() {
        val g = Grid(6, 3, BIG_HISTORY)
        var cursor = write(g, "abcde中f")
        assertEquals(listOf("abcde>\\", "中_f", ""), rows(g))
        var t = g.rewrap(7, 3, cursor, listOf(TrackPoint(1, 0), TrackPoint(1, 1)))
        assertEquals(listOf("abcde中_\\", "f", ""), rows(g))
        assertEquals(listOf(TrackPoint(0, 5), TrackPoint(0, 6)), t.points)
        t = g.rewrap(5, 3, t.cursor, t.points)
        assertEquals(listOf("abcde\\", "中_f", ""), rows(g))
        t = g.rewrap(6, 3, t.cursor, t.points)
        cursor = t.cursor
        assertEquals(listOf("abcde>\\", "中_f", ""), rows(g))
        assertEquals(listOf(TrackPoint(1, 0), TrackPoint(1, 1)), t.points)
        assertEquals(TrackPoint(1, 3), cursor)
    }

    @Test
    fun aPointOnALeadingSpacerMovesToTheWideCharacterAsClamped() {
        val g = Grid(6, 3, BIG_HISTORY)
        val cursor = write(g, "abcde中f")
        val p = g.rewrap(8, 3, cursor, listOf(TrackPoint(0, 5))).points[0]
        assertEquals(0 to 5, p.row to p.col)
        assertTrue(p.clamped, "its placeholder cell is gone")
    }

    @Test
    fun aCursorOnAWrappedLineFollowsItsCell() {
        val g = Grid(8, 4, BIG_HISTORY)
        write(g, "0123456789AB")
        var cursor = TrackPoint(1, 2)
        assertEquals(ch('A'), cellAt(g, cursor))
        cursor = g.rewrap(5, 4, cursor).cursor
        assertEquals(listOf("01234\\", "56789\\", "AB", ""), rows(g))
        assertEquals(TrackPoint(2, 0), cursor)
        cursor = g.rewrap(12, 4, cursor).cursor
        assertEquals(TrackPoint(0, 10), cursor)
        assertEquals(ch('A'), cellAt(g, cursor))
    }

    @Test
    fun aCursorPastTheEndOfALineKeepsItsColumn() {
        val g = Grid(10, 3, BIG_HISTORY)
        write(g, "ab")
        var cursor = g.rewrap(4, 3, TrackPoint(0, 6)).cursor
        assertEquals(listOf("ab\\", "", ""), rows(g))
        assertEquals(TrackPoint(1, 2), cursor)
        cursor = g.rewrap(10, 3, cursor).cursor
        assertEquals(listOf("ab", "", ""), rows(g))
        assertEquals(TrackPoint(0, 6), cursor)
    }

    @Test
    fun aCursorAtTheEndOfAFullLineStaysOnItsLastCell() {
        val g = Grid(4, 3, BIG_HISTORY)
        var cursor = write(g, "abcd")
        assertEquals(TrackPoint(0, 3), cursor)
        cursor = g.rewrap(3, 3, cursor).cursor
        assertEquals(listOf("abc\\", "d", ""), rows(g))
        assertEquals(TrackPoint(1, 0), cursor)
        assertEquals(ch('d'), cellAt(g, cursor))
    }

    @Test
    fun pointsInScrollbackFollowTheirCells() {
        val g = Grid(6, 2, BIG_HISTORY)
        var t = Tracked(write(g, "abcdefgh\nijkl\nmnopqrst\nuv\nw"), listOf(TrackPoint(1, 1), TrackPoint(3, 5)))
        assertEquals(5, g.historyLength)
        val seen = t.points.map { cellAt(g, it) }
        assertEquals(listOf(ch('h'), ch('r')), seen)
        for (cols in listOf(3, 10, 2, 6)) {
            t = g.rewrap(cols, 2, t.cursor, t.points)
            assertEquals(seen, t.points.map { cellAt(g, it) }, "at $cols columns")
            assertTrue(t.points.all { !it.clamped && it.row < g.historyLength })
        }
        assertEquals(listOf("abcdefgh", "ijkl", "mnopqrst", "uv", "w"), lines(g))
    }

    @Test
    fun theHistoryCapReachedDuringAShrinkEvictsTheOldestRows() {
        val g = Grid(6, 2, 2)
        val cursor = write(g, "aaaaaa\nbbbbbb\ncccccc\ndd")
        assertEquals(2, g.historyLength)
        val t = g.rewrap(3, 2, cursor, listOf(TrackPoint(0, 1), TrackPoint(1, 4)))
        assertEquals(listOf("bbb", "ccc\\", "ccc", "dd"), rows(g))
        assertEquals(2, g.historyLength)
        assertEquals(TrackPoint(0, 0, clamped = true), t.points[0])
        assertEquals(TrackPoint(0, 1), t.points[1], "the tail of its line survived")
        assertEquals(TrackPoint(3, 2), t.cursor)
        assertTrue(g.rows.size <= g.capacity)
    }

    @Test
    fun aScrolledBackViewportStaysOnTheLineAtItsTop() {
        val g = Grid(8, 3, BIG_HISTORY)
        var cursor = write(g, ('a'..'p').joinToString("") { "$it".repeat(6) + "\n" })
        g.scrollDisplay(5)
        val top = { g.visibleRow(0)!!.cell(0) }
        val seen = top()
        for (cols in listOf(4, 3, 10, 8)) {
            cursor = g.rewrap(cols, 3, cursor).cursor
            assertEquals(seen, top(), "at $cols columns")
            assertTrue(g.displayOffset > 0)
        }
        g.scrollDisplay(Int.MIN_VALUE)
        g.rewrap(4, 3, cursor)
        assertEquals(0, g.displayOffset, "a viewport at the bottom stays there")
    }

    @Test
    fun theAltScreenTruncatesAndPadsWithoutRewrap() {
        val g = Grid(6, 3, 0)
        val cursor = write(g, "abcd中xyz")
        assertEquals(listOf("abcd中_\\", "xyz", ""), rows(g))
        val before = ids(g)
        var t = g.resize(5, 3, Reflow.TRUNCATE, cursor, listOf(TrackPoint(0, 4), TrackPoint(1, 2)))
        assertEquals(listOf("abcd\\", "xyz", ""), rows(g), "the cut wide character is blanked")
        assertTrue(t.points[0].clamped)
        assertEquals(TrackPoint(1, 2) to TrackPoint(1, 3), t.points[1] to t.cursor)
        t = g.resize(9, 3, Reflow.TRUNCATE, t.cursor, t.points)
        assertEquals(listOf("abcd\\", "xyz", ""), rows(g))
        assertEquals(before, ids(g), "rows are kept, not rebuilt")
        assertEquals(0, g.historyLength)
    }

    @Test
    fun zeroRowsOrColumnsAreRefusedAndChangeNothing() {
        val g = Grid(4, 2, 0)
        write(g, "ab")
        for ((cols, screen) in listOf(0 to 2, 4 to 0, MAX_COLS + 1 to 2)) {
            assertFailsWith<IllegalArgumentException> { g.rewrap(cols, screen, TrackPoint(0, 2)) }
        }
        assertEquals(listOf("ab", ""), rows(g))
        assertEquals(4 to 2, g.cols to g.screenRows)
    }

    @Test
    fun pointsOutsideTheGridAreClampedAndReported() {
        val g = Grid(4, 2, 0)
        val t = g.rewrap(4, 2, TrackPoint(Int.MAX_VALUE, Int.MAX_VALUE), listOf(TrackPoint(0, 9), TrackPoint(1, 1), TrackPoint(-1, -1)))
        assertEquals(TrackPoint(1, 3, clamped = true), t.cursor)
        assertEquals(TrackPoint(0, 3, clamped = true), t.points[0])
        assertFalse(t.points[1].clamped)
        assertEquals(TrackPoint(0, 0, clamped = true), t.points[2])
    }

    @Test
    fun theSameSizeChangesNothing() {
        val g = Grid(5, 2, BIG_HISTORY)
        val cursor = write(g, "abcdefg")
        val stamps = { (0 until total(g)).map { g.row(it)!!.let { r -> r.id to r.generation } } }
        val before = stamps()
        assertEquals(cursor, g.rewrap(5, 2, cursor).cursor)
        assertEquals(before, stamps())
    }

    @Test
    fun rowsKeepTheirIdWhereTheyStartAndOthersGetFreshIds() {
        val g = Grid(6, 3, BIG_HISTORY)
        val cursor = write(g, "ab\nabcdefgh")
        val (short, head, tail) = ids(g)
        val shortGeneration = g.row(0)!!.generation
        val headGeneration = g.row(1)!!.generation
        g.rewrap(4, 3, cursor)
        assertEquals(listOf("ab", "abcd\\", "efgh\\", ""), rows(g), "the cursor past the text keeps a row")
        assertEquals(short to shortGeneration, g.row(0)!!.id to g.row(0)!!.generation, "unchanged row")
        assertEquals(head, g.row(1)!!.id, "a line's first row keeps its id")
        assertNotEquals(headGeneration, g.row(1)!!.generation, "but its content changed")
        assertTrue(g.row(2)!!.id !in listOf(short, head, tail), "a new row gets a fresh id")
        assertEquals(total(g), ids(g).toSet().size)
    }

    @Test
    fun changingRowsOnlyKeepsEveryRow() {
        val g = Grid(5, 4, BIG_HISTORY)
        val cursor = write(g, "abcdefg\nhi\njk\nl")
        val before = rows(g)
        val beforeIds = ids(g)
        g.rewrap(5, 2, cursor)
        assertEquals(before, rows(g))
        assertEquals(3, g.historyLength)
        assertEquals(beforeIds, ids(g))
    }

    @Test
    fun growingRowsPullsHistoryBackOntoTheScreen() {
        val g = Grid(4, 2, BIG_HISTORY)
        val cursor = write(g, "a\nb\nc\nd")
        assertEquals(2, g.historyLength)
        assertEquals(TrackPoint(3, 1), g.rewrap(4, 4, cursor).cursor)
        assertEquals(0, g.historyLength)
        assertEquals(ch('a'), g.screenRow(0)!!.cell(0))
    }

    @Test
    fun blankRowsBelowTheCursorAbsorbAShrinkBeforeHistoryDoes() {
        val g = Grid(8, 4, BIG_HISTORY)
        g.rewrap(4, 4, write(g, "abcdefgh"))
        assertEquals(0, g.historyLength)
        assertEquals(listOf("abcd\\", "efgh", "", ""), rows(g))
    }

    @Test
    fun rowsBelowTheCursorThatDoNotFitAreDropped() {
        val g = Grid(4, 4, BIG_HISTORY)
        write(g, "a\nb\nc\nd")
        val t = g.rewrap(4, 2, TrackPoint(0, 0), listOf(TrackPoint(3, 0)))
        assertEquals(listOf("a", "b"), rows(g))
        assertEquals(TrackPoint(0, 0), t.cursor)
        assertTrue(t.points[0].clamped && t.points[0].row == 1)
    }

    @Test
    fun stylesAndLinksTravelWithTheirCells() {
        val g = Grid(6, 2, BIG_HISTORY)
        var cursor = writeStyled(g, "abcdef".map { it to if (it > 'c') RED else StyleId.DEFAULT })
        g.screenRow(0)!!.setLink(2, 5, LinkId(7))
        cursor = g.rewrap(4, 2, cursor).cursor
        assertEquals(listOf("abcd\\", "ef"), rows(g))
        val style = { r: Int, c: Int -> g.row(r)!!.cell(c).style }
        assertEquals(listOf(StyleId.DEFAULT, RED, RED), listOf(style(0, 2), style(0, 3), style(1, 1)))
        val link = { r: Int, c: Int -> g.row(r)!!.linkAt(c) }
        assertEquals(listOf(null, LinkId(7), LinkId(7)), listOf(link(0, 1), link(0, 2), link(0, 3)))
        assertEquals(listOf(LinkId(7), null), listOf(link(1, 0), link(1, 1)))
        g.rewrap(6, 2, cursor)
        assertEquals(listOf(LinkSpan(2, 5, LinkId(7))), g.row(0)!!.links, "the halves join again")
    }

    @Test
    fun anErasedBackgroundSurvivesAsTheLineFill() {
        val g = Grid(6, 2, BIG_HISTORY)
        g.screenRow(0)!!.clear(Cell.blank(RED))
        g.rewrap(10, 2, write(g, "ab"))
        assertEquals(Cell.blank(RED), g.row(0)!!.cell(9), "the erase colour pads the new width")
    }

    @Test
    fun aOneColumnGridBlanksWideCharacters() {
        val g = Grid(4, 2, BIG_HISTORY)
        val t = g.rewrap(1, 2, write(g, "a中b"), listOf(TrackPoint(0, 1), TrackPoint(0, 2)))
        assertEquals(listOf("a b"), lines(g))
        assertTrue(t.points.all { it.clamped })
        assertEquals(ch('b'), cellAt(g, t.cursor))
    }
}
