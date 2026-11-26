package vesper.macos

import vesper.term.FrameCursor
import vesper.term.SelectionKind
import vesper.term.Terminal
import vesper.term.TextRange
import vesper.term.ViewportText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun terminal(
    output: String,
    cols: Int = 8,
    rows: Int = 3,
): Terminal = Terminal(cols, rows, 10).apply { feed(output.encodeToByteArray()) }

private fun read(
    output: String,
    cols: Int = 8,
    rows: Int = 3,
): ViewportText = terminal(output, cols, rows).viewportText()

class ScreenReadingTest {
    @Test
    fun withoutASelectionTheReaderSelectsTheCursor() {
        val t = read("ab中\r\ncd")
        assertEquals(TextRange(3, 3), t.cursorRange(FrameCursor(0, 4)), "the column after a wide character is the line's end")
        assertEquals(TextRange(2, 2), t.cursorRange(FrameCursor(0, 3)), "a wide character's spacer column is its own")
        assertEquals(TextRange(0, 0), t.cursorRange(null), "a hidden cursor reads as the start")
        assertEquals(TextRange(6, 6), t.selectedSpan(FrameCursor(1, 2)))
        assertEquals(listOf(TextRange(6, 6)), t.selectedRanges(FrameCursor(1, 2)))
        assertEquals("", t.selectedString)
    }

    @Test
    fun aSelectionReadsAsOneSpanAndALinePerRow() {
        val term = terminal("ab中d\r\nxy\r\nz")
        term.selectStart(SelectionKind.Cell, term.viewportPoint(0, 3))
        term.selectExtend(term.viewportPoint(1, 7))
        val t = term.viewportText()
        assertEquals(TextRange(2, 7), t.selectedSpan(FrameCursor(2, 1)))
        assertEquals(listOf(TextRange(2, 4), TextRange(5, 7)), t.selectedRanges(null))
        assertEquals("中d\nxy", t.selectedString)
    }

    @Test
    fun substringsAreCutToTheText() {
        val t = read("ab\r\ncd")
        assertEquals("b\nc", t.substring(TextRange(1, 4)))
        assertEquals("d\n", t.substring(TextRange(4, 99)))
        assertEquals("", t.substring(TextRange(6, 6)), "the end is a place, not past the text")
        assertNull(t.substring(TextRange(7, 8)))
    }

    @Test
    fun aRangesBoxIsItsColumnsOnOneLineAndWholeRowsAcrossSeveral() {
        val t = read("a中b\r\nxy")
        assertEquals(CellBox(0, 1, 2), t.boxOf(TextRange(1, 2), 8), "a wide character covers two columns")
        assertEquals(CellBox(0, 0, 4), t.boxOf(TextRange(0, 4), 8), "a line's newline stays on it")
        assertEquals(CellBox(0, 0, 8, 2), t.boxOf(TextRange(2, 6), 8))
        assertEquals(CellBox(1, 2, 1), t.boxOf(TextRange(6, 6), 8), "an empty range still has a column")
        assertNull(t.boxOf(TextRange(99, 99), 8))
    }

    @Test
    fun aPointedCellReadsAsItsWholeCharacter() {
        val t = read("\u001b[?2027h👍🏽e\u0301!  x\r\n", cols = 10)
        assertEquals(TextRange(0, 4), t.rangeAt(0, 1), "an emoji's second column")
        assertEquals(TextRange(4, 6), t.rangeAt(0, 2), "a letter with a combining mark")
        assertEquals(TextRange(7, 8), t.rangeAt(0, 4), "a blank between words is a character")
        assertEquals(TextRange(9, 10), t.rangeAt(0, 6))
        assertEquals(TextRange(10, 10), t.rangeAt(0, 9), "past the text is the line's end")
        assertEquals(TextRange(11, 11), t.rangeAt(1, 3))
    }
}
