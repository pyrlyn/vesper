package vesper.macos

import vesper.term.MAX_SEARCH_MATCHES
import vesper.term.SelectionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelectionGestureTest {
    private fun press(
        clicks: Int = 1,
        shift: Boolean = false,
        option: Boolean = false,
        hasSelection: Boolean = false,
    ) = selectionPress(clicks, shift, option, hasSelection)

    @Test
    fun clicksPickTheKind() {
        assertEquals(SelectionPress.Start(SelectionKind.Cell), press())
        assertEquals(SelectionPress.Start(SelectionKind.Word), press(clicks = 2))
        assertEquals(SelectionPress.Start(SelectionKind.Line), press(clicks = 3))
        assertEquals(SelectionPress.Start(SelectionKind.Line), press(clicks = 5), "more clicks stay on lines")
        assertEquals(SelectionPress.Start(SelectionKind.Block), press(option = true))
        assertEquals(SelectionPress.Start(SelectionKind.Word), press(clicks = 2, option = true), "Option only changes a drag")
    }

    @Test
    fun shiftExtendsASelectionThatIsThere() {
        assertEquals(SelectionPress.Extend, press(shift = true, hasSelection = true))
        assertEquals(SelectionPress.Extend, press(clicks = 2, shift = true, hasSelection = true))
        assertEquals(SelectionPress.Start(SelectionKind.Cell), press(shift = true), "with none, Shift starts one")
    }

    @Test
    fun onlyAPlainClickClearsOnRelease() {
        assertTrue(press().clearsOnRelease)
        assertTrue(press(option = true).clearsOnRelease)
        assertFalse(press(clicks = 2).clearsOnRelease)
        assertFalse(press(clicks = 3).clearsOnRelease)
        assertFalse(SelectionPress.Extend.clearsOnRelease)
    }

    @Test
    fun aDragPastAnEdgeScrollsTheHistory() {
        assertEquals(1, dragScroll(-0.5, 100.0))
        assertEquals(0, dragScroll(0.0, 100.0))
        assertEquals(0, dragScroll(99.9, 100.0))
        assertEquals(-1, dragScroll(100.0, 100.0))
    }
}

class FindStateTest {
    @Test
    fun typingSearchesAtOnceAndStepsAndCountsOnceSettled() {
        val find = FindState()
        assertEquals(emptyList(), find.edit("x"), "a closed bar does nothing")
        assertEquals(emptyList(), find.open(), "nothing to search yet")
        assertFalse(find.canStep)
        assertEquals(listOf(FindEffect.Search("ab", false)), find.edit("ab"))
        assertTrue(find.unsettled)
        assertEquals(listOf(FindEffect.Step(true), FindEffect.Count), find.settle())
        assertEquals(emptyList(), find.settle(), "settles once")
        find.count = 3
        assertEquals("3 matches", find.label)
        assertEquals(listOf(FindEffect.Step(false)), find.step(forward = false), "counted already")
    }

    @Test
    fun steppingBeforeTheSettleCountsToo() {
        val find = FindState()
        find.open()
        find.edit("ab")
        assertEquals(listOf(FindEffect.Step(true), FindEffect.Count), find.step(forward = true))
        assertFalse(find.unsettled)
        assertEquals(emptyList(), find.settle())
    }

    @Test
    fun matchCaseSearchesAgainAndAnEmptyPatternEndsTheSearch() {
        val find = FindState()
        find.open()
        find.edit("Ab")
        find.count = 2
        assertEquals(listOf(FindEffect.Search("Ab", true)), find.toggleCase())
        assertEquals(null, find.count, "a new search is counted again")
        assertEquals(listOf(FindEffect.Search("", true)), find.edit(""))
        assertFalse(find.unsettled)
        assertFalse(find.canStep)
        assertEquals(emptyList(), find.step(forward = true))
    }

    @Test
    fun closingEndsTheSearchAndReopeningRestoresIt() {
        val find = FindState()
        find.open()
        find.edit("ab")
        assertEquals(listOf(FindEffect.Search("", false)), find.close())
        assertEquals(emptyList(), find.close())
        assertFalse(find.canStep)
        assertEquals(listOf(FindEffect.Search("ab", false)), find.open())
        assertEquals("ab", find.pattern)
    }

    @Test
    fun theLabelCountsMatches() {
        val find = FindState()
        assertEquals("", find.label)
        find.count = 0
        assertEquals("No matches", find.label)
        find.count = 1
        assertEquals("1 match", find.label)
        find.count = MAX_SEARCH_MATCHES
        assertEquals("$MAX_SEARCH_MATCHES+ matches", find.label)
    }
}
