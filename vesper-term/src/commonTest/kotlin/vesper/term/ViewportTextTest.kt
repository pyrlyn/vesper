package vesper.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun read(
    feed: String,
    cols: Int = 8,
    rows: Int = 3,
): ViewportText = Terminal(cols, rows, 10).apply { feed(feed) }.viewportText()

private fun ViewportText.sub(range: TextRange): String = text.substring(range.start, range.end)

class ViewportTextTest {
    @Test
    fun eachRowIsALineWithoutItsTrailingBlanks() {
        val t = read("ab  c\r\n\r\nx")
        assertEquals("ab  c\n\nx", t.text)
        assertEquals(3, t.lineCount)
        assertEquals(TextRange(0, 6), t.lineRange(0), "a line's range holds its newline")
        assertEquals(TextRange(6, 7), t.lineRange(1))
        assertEquals(TextRange(7, 8), t.lineRange(2))
        assertNull(t.lineRange(3))
        assertEquals(listOf(0, 0, 1, 2, 2, 2), listOf(0, 5, 6, 7, 8, 99).map(t::lineOf), "past the end is the last line")
    }

    @Test
    fun asciiOffsetsAndColumnsMapBothWays() {
        val t = read("ab  c")
        assertEquals(listOf(0, 1, 2, 4, 5, 5), listOf(0, 1, 2, 4, 5, 7).map { t.indexAt(0, it) }, "past the text is the line's end")
        assertEquals(TextCell(0, 4, 1), t.cellAt(4))
        assertNull(t.cellAt(5), "a line's end is no character")
        assertEquals(TextCell(0, 1, 2), t.cellsOf(0, TextRange(1, 3)))
    }

    @Test
    fun cjkWideCellsReadOnceAndCoverTwoColumns() {
        val t = read("中x文")
        assertEquals("中x文\n\n", t.text)
        assertEquals(TextCell(0, 0, 2), t.cellAt(0))
        assertEquals(TextCell(0, 2, 1), t.cellAt(1))
        assertEquals(TextCell(0, 3, 2), t.cellAt(2))
        assertEquals(listOf(0, 0, 1, 2, 2, 3), (0..5).map { t.indexAt(0, it) }, "the spacer column is its character's")
        assertEquals(TextCell(0, 2, 3), t.cellsOf(0, TextRange(1, 3)))
    }

    @Test
    fun emojiAndClustersAreOneCharacterOfSeveralUnits() {
        val t = read("$CSI?2027h👍🏽é!", cols = 10)
        assertEquals("👍🏽é!\n\n", t.text)
        assertEquals(TextCell(0, 0, 2), t.cellAt(0))
        assertEquals(TextCell(0, 0, 2), t.cellAt(3), "an offset inside a cluster is its cell")
        assertEquals(TextCell(0, 2, 1), t.cellAt(4))
        assertEquals(TextCell(0, 3, 1), t.cellAt(6))
        assertEquals(listOf(0, 0, 4, 6, 7), (0..4).map { t.indexAt(0, it) })
    }

    @Test
    fun aWrappedLineReadsAsTwoRowsAndConcealedTextAsBlanks() {
        assertEquals("abcdefgh\nij\n", read("abcdefghij").text)
        assertEquals("pw:\n  !\n", read("pw: ${CSI}8msecret${CSI}m!").text, "the password wrapped and reads as spaces")
    }

    @Test
    fun aReadersRangeSelectsTheCellsOfItsCharacters() {
        val t = read("ab中\r\ncd")
        assertEquals(TextCell(0, 1, 1) to TextCell(0, 2, 2), t.cellsFor(TextRange(1, 3)))
        assertEquals(TextCell(0, 2, 2) to TextCell(1, 0, 1), t.cellsFor(TextRange(2, 5)), "the newline is skipped")
        assertNull(t.cellsFor(TextRange(3, 4)), "only a newline")
        assertNull(t.cellsFor(TextRange(99, 100)))
    }

    @Test
    fun theSelectionIsOneRangePerRowWithoutTheBlanksPastTheText() {
        val term = Terminal(8, 3, 10)
        term.feed("ab中d\r\nxy\r\nz")
        term.selectStart(SelectionKind.Cell, term.viewportPoint(0, 3))
        term.selectExtend(term.viewportPoint(1, 7))
        val t = term.viewportText()
        assertEquals(listOf(TextRange(2, 4), TextRange(5, 7)), t.selected, "half a wide character selects it whole")
        assertEquals(listOf("中d", "xy"), t.selected.map { t.sub(it) })
        term.selectStart(SelectionKind.Block, term.viewportPoint(0, 5))
        term.selectExtend(term.viewportPoint(2, 6))
        assertEquals(emptyList(), term.viewportText().selected, "a block past every row's text has no text")
        term.selectClear()
        assertEquals(emptyList(), term.viewportText().selected)
    }

    @Test
    fun theViewportFollowsTheHistory() {
        val term = Terminal(4, 2, 10)
        term.feed("1\r\n2\r\n3")
        term.scrollDisplay(1)
        assertEquals("1\n2", term.viewportText().text)
        assertEquals("", ViewportText.EMPTY.text)
        assertEquals(0, ViewportText.EMPTY.indexAt(5, 5))
    }
}
