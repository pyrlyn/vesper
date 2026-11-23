package scull.term

import scull.grid.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

private const val COLS = 8
private const val ROWS = 4

private fun term(): Terminal = Terminal(COLS, ROWS, 10)

private fun Terminal.updated(frame: Frame) = assertTrue(updateFrame(frame))

private fun Terminal.fresh(): Frame = Frame().also { updated(it) }

private data class R(
    val col: Int,
    val cols: Int,
    val width: Int,
    val text: String,
)

private fun Frame.runs(r: Int): List<R> {
    val row = checkNotNull(row(r))
    return row.runs.map { R(it.col, it.cols, it.width, row.runText(it)) }
}

private fun Frame.text(r: Int): String = checkNotNull(row(r)).text

class FrameTest {
    @Test
    fun runsSplitOnStyleWidthAndBlankCells() {
        val t = term()
        t.feed("ab${CSI}31mc${CSI}m中文d e")
        val f = t.fresh()
        assertEquals(
            listOf(R(0, 2, 1, "ab"), R(2, 1, 1, "c"), R(3, 4, 2, "中文"), R(7, 1, 1, "d")),
            f.runs(0),
            "the space and the rest past the screen edge wrapped",
        )
        assertEquals(Color.indexed(1), f.style(f.cell(0, 2).style)?.fg)
        assertEquals("abc中文d", f.text(0))
        assertEquals(listOf(R(0, 2, 1, " e")), f.runs(1), "a typed space is text")
    }

    @Test
    fun erasedCellsEndARunAndKeepTheirBackground() {
        val t = term()
        t.feed("ab${CSI}44m${CSI}X${CSI}m${CSI}2Cc")
        val f = t.fresh()
        assertEquals(listOf(R(0, 2, 1, "ab"), R(4, 1, 1, "c")), f.runs(0))
        val blank = f.cell(0, 2)
        assertEquals(0 to 1, blank.codePoint to blank.width)
        assertEquals(Color.indexed(4), f.style(blank.style)?.bg)
    }

    @Test
    fun wideCellsAreAHeadAndASpacer() {
        val t = term()
        t.feed("中")
        val f = t.fresh()
        assertEquals(0x4E2D to 2, f.cell(0, 0).let { it.codePoint to it.width })
        assertEquals(0 to 0, f.cell(0, 1).let { it.codePoint to it.width })
    }

    @Test
    fun aClusterKeepsItsWholeTextInTheRun() {
        val t = term()
        t.feed("$CSI?2027héx")
        val f = t.fresh()
        val cell = f.cell(0, 0)
        assertEquals('e'.code, cell.codePoint)
        assertEquals(FrameCell.CLUSTER, cell.flags)
        assertEquals(listOf(R(0, 2, 1, "éx")), f.runs(0))
    }

    @Test
    fun theFirstUpdateAndAScreenSwitchAreFull() {
        val t = term()
        val f = Frame()
        t.updated(f)
        assertTrue(f.damage.isFull)
        t.updated(f)
        assertTrue(f.damage.isEmpty)
        t.feed("$CSI?1049h")
        t.updated(f)
        assertTrue(f.damage.isFull, "ids of the other screen collide")
    }

    @Test
    fun aScrollMovesRowsInsideTheFrame() {
        val t = term()
        t.feed("a\r\nb\r\nc\r\nd")
        val f = t.fresh()
        t.feed("\r\ne")
        t.updated(f)
        assertEquals(listOf(Scroll(0, 3, 1)), f.damage.scrolls)
        assertEquals(listOf(3), f.damage.dirty)
        assertEquals(listOf("b", "c", "d", "e"), (0 until ROWS).map { f.text(it) })
        assertEquals('b'.code, f.cell(0, 0).codePoint)
    }

    @Test
    fun everyScrollOfOneUpdateMovesItsRows() {
        val t = term()
        t.feed("a\r\nb\r\nc\r\nd")
        val f = t.fresh()
        t.feed("\r\ne${CSI}2Hx")
        t.updated(f)
        assertEquals(listOf(Scroll(0, 1, 1), Scroll(2, 3, 3)), f.damage.scrolls)
        assertEquals(listOf(1, 3), f.damage.dirty)
        assertEquals(listOf("b", "x", "d", "e"), (0 until ROWS).map { f.text(it) })
    }

    @Test
    fun scrollsReadThePreviousPictureNotEachOther() {
        val t = term()
        t.feed("a\r\nb\r\nc\r\nd")
        val f = t.fresh()
        // Row 1 is where the first move lands and where the second one reads from.
        t.feed("${CSI}H${ESC}M${CSI}3H${CSI}L")
        t.updated(f)
        assertEquals(listOf(Scroll(1, 2, 0), Scroll(3, 4, 1)), f.damage.scrolls)
        assertEquals(listOf("", "a", "", "b"), (0 until ROWS).map { f.text(it) })
    }

    @Test
    fun aHeldPictureStaysUntilTheUpdateEnds() {
        val start = TimeSource.Monotonic.markNow()
        val t = term()
        val f = Frame()
        assertTrue(t.updateFrame(f, start))
        t.feed("$CSI?2026hhalf")
        assertFalse(t.updateFrame(f, start))
        assertTrue(f.damage.isEmpty)
        assertEquals("", f.text(0))
        t.feed(" ok$CSI?2026l")
        assertTrue(t.updateFrame(f, start))
        assertEquals("half ok", f.text(0))
    }

    @Test
    fun aHeldPictureIsReleasedByTheTimeCap() {
        val start = TimeSource.Monotonic.markNow()
        val t = term()
        val f = Frame()
        t.feed("$CSI?2026hstuck")
        assertFalse(t.updateFrame(f, start))
        assertTrue(t.updateFrame(f, start + MAX_SYNC_HOLD))
        assertEquals("stuck", f.text(0))
    }

    @Test
    fun theCursorHidesWithDectcemAndOffTheViewport() {
        val t = term()
        t.feed("${CSI}2;3H")
        assertEquals(FrameCursor(1, 2), t.fresh().cursor)
        t.feed("$CSI?25l")
        assertNull(t.fresh().cursor)
        t.feed("$CSI?25h${CSI}4H\r\n\r\n\r\n")
        t.scrollDisplay(1)
        assertNull(t.fresh().cursor)
    }
}
