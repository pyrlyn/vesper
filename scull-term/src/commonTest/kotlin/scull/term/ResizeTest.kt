package scull.term

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SEED = 0x11L
private const val CASES = 300
private const val MAX_SIDE = 40

class ResizeTest {
    @Test
    fun narrowingRewrapsThePrimaryScreenAndTheCursorFollows() {
        val term = Terminal(10, 3, 100)
        term.feed("abcdefgh")
        term.resize(5, 3)
        assertEquals(listOf("abcde", "fgh..", "....."), term.screenText())
        assertEquals(Cursor(row = 1, col = 3), term.cursor)
        term.feed("X")
        assertEquals("fghX.", term.screenText()[1])
    }

    @Test
    fun theAlternateScreenIsCutNotRewrapped() {
        val term = Terminal(10, 3, 100)
        term.feed("$CSI?1049habcdefgh")
        term.resize(5, 3)
        assertEquals(listOf("abcde", ".....", "....."), term.screenText())
        assertEquals(Cursor(row = 0, col = 4), term.cursor)
    }

    @Test
    fun theHiddenMainScreenIsRewrappedAndItsSavedCursorFollows() {
        val term = Terminal(10, 3, 100)
        term.feed("abcdefgh$CSI?1049hxyz")
        term.resize(5, 3)
        term.feed("$CSI?1049l")
        assertEquals(listOf("abcde", "fgh..", "....."), term.screenText())
        assertEquals(Cursor(row = 1, col = 3), term.cursor)
    }

    @Test
    fun theSavedCursorFollowsItsCell() {
        val term = Terminal(10, 3, 100)
        term.feed("abcdefg${ESC}7\r\n")
        term.resize(5, 3)
        term.feed("${ESC}8")
        assertEquals(Cursor(row = 1, col = 2), term.cursor)
    }

    @Test
    fun aPendingWrapSurvivesOnlyAChangeOfHeight() {
        val term = Terminal(4, 3, 100)
        term.feed("abcd")
        assertTrue(term.cursor.pendingWrap)
        term.resize(4, 5)
        assertTrue(term.cursor.pendingWrap)
        term.resize(6, 5)
        assertFalse(term.cursor.pendingWrap)
    }

    @Test
    fun marginsResetAndTabStopsAreKeptAndExtended() {
        val term = Terminal(10, 5, 100)
        term.feed("$CSI" + "2;4r$CSI" + "1;4H${ESC}H$CSI" + "H")
        term.resize(20, 6)
        assertEquals(Margins(top = 0, bottom = 5, left = 0, right = 19), term.margins)
        term.feed("\t")
        assertEquals(3, term.cursor.col)
        term.feed("\t\t")
        assertEquals(16, term.cursor.col)
    }

    @Test
    fun aZeroSizeIsRefusedAndChangesNothing() {
        val term = Terminal(10, 3, 100)
        term.feed("abc")
        assertFailsWith<IllegalArgumentException> { term.resize(0, 3) }
        assertFailsWith<IllegalArgumentException> { term.resize(10, 0) }
        assertFailsWith<IllegalArgumentException> { term.resize(10, MAX_ROWS + 1) }
        assertEquals(10, term.grid.cols)
        assertEquals(Cursor(row = 0, col = 3), term.cursor)
    }

    @Test
    fun anyStreamAndResizeKeepTheCursorAndMarginsOnTheScreen() {
        val random = Random(SEED)
        repeat(CASES) {
            val term = Terminal(random.nextInt(1, MAX_SIDE), random.nextInt(1, MAX_SIDE), random.nextInt(0, MAX_SIDE))
            term.feed(random.stream())
            val cols = random.nextInt(1, MAX_SIDE)
            val rows = random.nextInt(1, MAX_SIDE)
            term.resize(cols, rows)
            term.assertSound(cols, rows)
            // Both screens took the size: switching shows the other one at the same size.
            term.feed("$CSI?1049h")
            term.assertSound(cols, rows)
            term.feed("$CSI?1049l")
            term.feed(random.stream())
            term.assertSound(cols, rows)
        }
    }
}

private fun Terminal.assertSound(
    cols: Int,
    rows: Int,
) {
    assertEquals(cols, grid.cols)
    assertEquals(rows, grid.screenRows)
    assertTrue(cursor.row in 0 until rows && cursor.col in 0 until cols, "cursor $cursor in $cols x $rows")
    assertTrue(margins.top <= margins.bottom && margins.bottom < rows, "margins $margins in $rows rows")
    assertTrue(margins.left <= margins.right && margins.right < cols, "margins $margins in $cols cols")
}
