package scull.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ported from the creator's Rust scull (`crates/scull-term/src/selection/tests.rs`).

private fun term(
    cols: Int,
    rows: Int,
    feed: String,
): Terminal = Terminal(cols, rows, 20).also { it.feed(feed) }

/** Selects from viewport cell [a] to [b] and reads the text. */
private fun Terminal.select(
    kind: SelectionKind,
    a: Pair<Int, Int>,
    b: Pair<Int, Int>,
): String {
    selectStart(kind, viewportPoint(a.first, a.second))
    selectExtend(viewportPoint(b.first, b.second))
    return selectionText()
}

class SelectionTest {
    @Test
    fun aCellSelectionTrimsEachLineAndEndsItWithANewline() {
        val t = term(8, 3, "ab  \r\nxy z\r\n12")
        assertEquals("b\nxy z\n1", t.select(SelectionKind.Cell, 0 to 1, 2 to 0))
        assertEquals("\nxy z", t.select(SelectionKind.Cell, 1 to 3, 0 to 6), "backwards")
    }

    @Test
    fun aSoftWrapJoinsItsRowsAndABlankSelectionReadsEmpty() {
        val t = term(4, 3, "abcdefg")
        assertEquals("cdefg", t.select(SelectionKind.Cell, 0 to 2, 1 to 3))
        assertEquals("", t.select(SelectionKind.Cell, 2 to 0, 2 to 3))
    }

    @Test
    fun blanksInsideASoftWrappedLineAreKept() {
        val t = term(4, 3, "ab  cd")
        assertEquals("ab  cd", t.select(SelectionKind.Cell, 0 to 0, 1 to 3))
    }

    @Test
    fun wideCharactersReadOnceFromEitherHalf() {
        val t = term(8, 2, "a中b")
        assertEquals("中", t.select(SelectionKind.Cell, 0 to 2, 0 to 2))
        val range = assertNotNull(t.selection)
        assertEquals(1 to 2, range.start.col to range.end.col, "the spacer pulls in its head")
        assertEquals("a中", t.select(SelectionKind.Cell, 0 to 0, 0 to 1))
    }

    @Test
    fun aWideCharacterWrappedEarlySkipsItsLeadingPadding() {
        val t = term(5, 2, "abcd中")
        assertEquals("abcd中", t.select(SelectionKind.Cell, 0 to 0, 1 to 1))
        assertEquals("abcd中", t.select(SelectionKind.Word, 1 to 0, 1 to 0))
    }

    @Test
    fun aWordEndsAtBlanksAndSeparators() {
        val t = term(30, 2, "ls ~/a-b/c.txt (x)  y")

        fun word(col: Int) = t.select(SelectionKind.Word, 0 to col, 0 to col)
        assertEquals("~/a-b/c.txt", word(6))
        assertEquals("(", word(15), "a separator is a word of its own")
        assertEquals("x", word(16))
        assertEquals("", word(19), "a run of blanks trims to nothing")
        assertEquals("ls ~/a-b/c.txt", t.select(SelectionKind.Word, 0 to 1, 0 to 3))
    }

    @Test
    fun aWordRunsAcrossASoftWrapButNotAHardLineEnd() {
        val t = term(4, 3, "x abcdef\r\ngh")
        assertEquals("abcdef", t.select(SelectionKind.Word, 1 to 1, 1 to 1))
        assertEquals("gh", t.select(SelectionKind.Word, 2 to 0, 2 to 0))
    }

    @Test
    fun aLineSelectionTakesTheWholeWrappedLine() {
        val t = term(4, 4, "abcdef\r\nxy\r\nz")
        assertEquals("abcdef", t.select(SelectionKind.Line, 1 to 1, 1 to 1))
        assertEquals("abcdef\nxy", t.select(SelectionKind.Line, 0 to 3, 2 to 0))
    }

    @Test
    fun aBlockTakesTheSameColumnsOfEveryRow() {
        val t = term(6, 3, "abcdef\r\nghijkl\r\nmn")
        assertEquals("bcde\nhijk\nn", t.select(SelectionKind.Block, 0 to 4, 2 to 1))
    }

    @Test
    fun concealedTextIsCopiedAsBlanks() {
        val t = term(12, 1, "pw \u001b[8mhunter2\u001b[m!")
        assertEquals("pw        !", t.select(SelectionKind.Line, 0 to 0, 0 to 0))
    }

    @Test
    fun theSelectionStaysOnItsTextWhileOutputScrolls() {
        val t = term(6, 3, "one\r\ntwo\r\n")
        assertEquals("one\ntwo", t.select(SelectionKind.Line, 0 to 0, 1 to 0))
        t.feed("3\r\n4\r\n5\r\n6")
        assertEquals("one\ntwo", t.selectionText(), "scrolled into history")
        t.scrollDisplay(4)
        assertEquals(0, t.viewportRow(assertNotNull(t.selection).start.line))
        t.scrollDisplay(-4)
        assertEquals("one\ntwo", t.selectionText(), "the viewport is not the selection")
    }

    @Test
    fun writingIntoASelectedRowDropsTheSelectionAndElsewhereDoesNot() {
        val t = term(6, 3, "one\r\ntwo\r\nend")
        t.select(SelectionKind.Cell, 0 to 0, 0 to 2)
        t.feed("\u001b[3;1Hxyz")
        assertEquals("one", t.selectionText(), "another row changed")
        t.feed("\u001b[1;2HX")
        assertNull(t.selection)
        t.select(SelectionKind.Cell, 0 to 0, 1 to 2)
        t.feed("\u001b[2;3r\u001b[2;1H\u001bM")
        assertNull(t.selection, "a region scroll moved a row away")
    }

    @Test
    fun aResizeAScreenSwitchAndEvictionDropTheSelection() {
        // Three rows and a fourth line, so "one" is in the history.
        val t = term(6, 3, "one\r\ntwo\r\n3\r\n4")

        fun drops(change: Terminal.() -> Unit): Boolean {
            t.select(SelectionKind.Line, 0 to 0, 0 to 0)
            assertNotNull(t.selection)
            t.change()
            return t.selection == null
        }
        assertFalse(drops { resize(6, 3) }, "same size")
        assertTrue(drops { resize(7, 3) })
        assertTrue(drops { feed("\u001b[?1049h") })
        t.feed("\u001b[?1049l")
        assertFalse(drops { feed("\u001b[3J") }, "a screen row stays")
        t.feed("\r\n5")
        t.scrollDisplay(1)
        assertTrue(drops { feed("\u001b[3J") }, "its history row went")
        assertTrue(drops { feed("\n".repeat(40)) }, "scrolled out of the ring")
    }

    @Test
    fun pointsOutsideTheRingSelectNothingOrClamp() {
        val t = term(6, 3, "ab")
        t.selectStart(SelectionKind.Cell, Point(99, 0))
        assertNull(t.selection)
        t.selectStart(SelectionKind.Cell, Point(Long.MIN_VALUE, 0))
        assertNull(t.selection)
        t.selectStart(SelectionKind.Cell, t.viewportPoint(0, 1))
        t.selectExtend(Point(Long.MAX_VALUE, Int.MAX_VALUE))
        assertEquals("b\n\n", t.selectionText())
        t.selectClear()
        assertEquals("", t.selectionText())
    }

    @Test
    fun theTextIsCutAtTheCapOnACharacterBoundary() {
        assertEquals("a", capUtf8("aéé", 2), "half of é is not kept")
        assertEquals("aé", capUtf8("aéé", 3))
        assertEquals("a", capUtf8("a😀", 4), "nor half of a surrogate pair")
        assertEquals("aéé", capUtf8("aéé", 5))
    }
}
