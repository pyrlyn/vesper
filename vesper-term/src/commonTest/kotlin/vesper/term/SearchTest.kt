package vesper.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ported from the earlier Rust core (`search/tests.rs` of its `term` crate).

private fun term(
    cols: Int,
    rows: Int,
    feed: String,
): Terminal = Terminal(cols, rows, 50).also { it.feed(feed) }

private fun p(
    line: Long,
    col: Int,
) = Point(line, col)

private fun m(
    start: Pair<Long, Int>,
    end: Pair<Long, Int>,
) = Match(p(start.first, start.second), p(end.first, end.second))

class SearchTest {
    @Test
    fun caseFoldingIsChosenPerSearch() {
        val t = term(10, 3, "Foo foo\r\nFOO")
        assertTrue(t.searchSet("foo", true))
        assertEquals(listOf(m(0L to 4, 0L to 6)), t.searchMatches())
        assertTrue(t.searchSet("fOo", false))
        assertEquals(3, t.searchCount())
        assertEquals(m(1L to 0, 1L to 2), t.searchMatches()[2])
    }

    @Test
    fun foldingIsPerCodePointOnBothSides() {
        val t = term(10, 2, "ΣΑΣ Ǆ")
        assertTrue(t.searchSet("σασ", false), "a final sigma is not special")
        assertEquals(1, t.searchCount())
        assertTrue(t.searchSet("ǆ", false))
        assertEquals(listOf(m(0L to 4, 0L to 4)), t.searchMatches())
    }

    @Test
    fun aMatchCrossesASoftWrapButNotAHardLineEnd() {
        val t = term(4, 4, "xxabcd\r\nab\r\ncd")
        assertTrue(t.searchSet("abcd", true))
        assertEquals(listOf(m(0L to 2, 1L to 1)), t.searchMatches())
        assertTrue(t.searchSet("xx a", true))
        assertTrue(t.searchMatches().isEmpty())
    }

    @Test
    fun aWideMatchEndsOnItsSpacerAndPaddingIsSkipped() {
        val t = term(5, 2, "abcd中文")
        assertTrue(t.searchSet("d中文", true))
        assertEquals(listOf(m(0L to 3, 1L to 3)), t.searchMatches())
    }

    @Test
    fun matchesDoNotOverlapAndTrailingBlanksAreNotText() {
        val t = term(10, 2, "aaaaa\r\nb  ")
        assertTrue(t.searchSet("aa", true))
        assertEquals(listOf(m(0L to 0, 0L to 1), m(0L to 2, 0L to 3)), t.searchMatches())
        assertTrue(t.searchSet("b ", true))
        assertEquals(0, t.searchCount())
    }

    @Test
    fun historyIsSearchedAndSteppingRevealsTheMatch() {
        val t = term(8, 2, "needle\r\n1\r\n2\r\n3\r\nneedle")
        assertTrue(t.searchSet("needle", true))
        assertEquals(listOf(m(0L to 0, 0L to 5), m(4L to 0, 4L to 5)), t.searchMatches())
        val last = t.searchPrev()
        assertEquals(m(4L to 0, 4L to 5), last, "from the bottom of the viewport")
        assertEquals(last, t.searchCurrent)
        val first = t.searchPrev()
        assertEquals(0L, first?.start?.line)
        assertEquals(0, t.viewportRow(0), "scrolled back to show it")
        assertEquals(last, t.searchPrev(), "wraps round")
        assertEquals(first, t.searchNext(), "wraps round")
        assertEquals(last, t.searchNext(p(0, 0)))
        assertEquals(first, t.searchNext(p(4, 0)))
    }

    @Test
    fun steppingWithNoMatchOrNoSearchFindsNothing() {
        val t = term(8, 2, "abc")
        assertNull(t.searchNext())
        assertTrue(t.searchSet("zz", false))
        assertNull(t.searchNext())
        assertNull(t.searchPrev())
        assertNull(t.searchCurrent)
    }

    @Test
    fun aBackwardStepCrossesManyLinesBack() {
        val t = Terminal(8, 4, 1000)
        t.feed("hit" + "\r\n.".repeat(600))
        assertTrue(t.searchSet("hit", true))
        assertEquals(m(0L to 0, 0L to 2), t.searchPrev())
    }

    @Test
    fun badPatternsAreRefused() {
        val t = term(8, 2, "abc")
        assertTrue(t.searchSet("a", true))
        assertFalse(t.searchSet("", true))
        assertEquals(0, t.searchCount(), "a refused pattern clears the search")
        assertFalse(t.searchSet("x".repeat(MAX_SEARCH_PATTERN + 1), true))
        assertFalse(t.searchSet("é".repeat(MAX_SEARCH_PATTERN / 2 + 1), true), "the cap is in UTF-8 bytes")
        assertTrue(t.searchSet("x".repeat(MAX_SEARCH_PATTERN), true))
    }

    @Test
    fun aLongPatternMatchesAcrossManyWrappedRows() {
        val line = "0123456789".repeat(20)
        val t = term(7, 40, "ab$line")
        assertTrue(t.searchSet(line, true))
        val found = t.searchMatches()
        assertEquals(1, found.size)
        assertEquals(p(0, 2), found[0].start)
        assertEquals(p(28, 5), found[0].end, "202 cells end at line 28, column 5")
    }

    @Test
    fun countingAndListingStopAtTheCap() {
        val t = Terminal(100, 120, 0)
        t.feed("a".repeat(12_000))
        assertTrue(t.searchSet("a", true))
        assertEquals(MAX_SEARCH_MATCHES, t.searchCount())
        assertEquals(MAX_SEARCH_MATCHES, t.searchMatches().size)
    }

    @Test
    fun aResizeForgetsTheCurrentMatchButKeepsThePattern() {
        val t = term(8, 2, "abc")
        assertTrue(t.searchSet("b", true))
        assertTrue(t.searchNext() != null)
        t.resize(9, 2)
        assertNull(t.searchCurrent)
        assertEquals(1, t.searchCount())
        t.searchClear()
        assertEquals(0, t.searchCount())
    }

    @Test
    fun linesKeepTheirNumbersAfterHistoryIsEvicted() {
        val t = Terminal(8, 2, 2)
        t.feed("a\r\nb\r\nc\r\nd\r\nneedle")
        assertEquals(3L, t.screenTopLine)
        assertTrue(t.searchSet("needle", true))
        assertEquals(listOf(m(4L to 0, 4L to 5)), t.searchMatches())
        assertTrue(t.searchSet("a", true))
        assertEquals(0, t.searchCount(), "line 0 left the ring")
    }
}
