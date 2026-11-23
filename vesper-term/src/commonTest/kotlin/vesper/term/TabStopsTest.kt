package vesper.term

import kotlin.test.Test
import kotlin.test.assertEquals

private const val COLS = 30
private const val LAST = COLS - 1

class TabStopsTest {
    @Test
    fun defaultStopsFallEveryEightColumns() {
        val tabs = TabStops(COLS)
        assertEquals(8, tabs.next(0, 1, LAST))
        assertEquals(16, tabs.next(8, 1, LAST))
        assertEquals(24, tabs.next(0, 3, LAST))
    }

    @Test
    fun nextStopsAtTheLimitWhenStopsRunOut() {
        val tabs = TabStops(COLS)
        assertEquals(LAST, tabs.next(25, 1, LAST))
        assertEquals(LAST, tabs.next(3, 9, LAST))
        assertEquals(LAST, tabs.next(LAST, 1, LAST))
    }

    @Test
    fun prevStopsAtColumnZero() {
        val tabs = TabStops(COLS)
        assertEquals(16, tabs.prev(20, 1))
        assertEquals(0, tabs.prev(16, 2))
        assertEquals(0, tabs.prev(0, 1))
    }

    @Test
    fun setAndClearChangeSingleColumns() {
        val tabs = TabStops(COLS)
        tabs.set(3)
        tabs.clear(8)
        assertEquals(3, tabs.next(0, 1, LAST))
        assertEquals(16, tabs.next(3, 1, LAST))
        tabs.clearAll()
        assertEquals(LAST, tabs.next(0, 1, LAST))
        tabs.set(COLS + 5)
        assertEquals(LAST, tabs.next(0, 1, LAST), "out of range is ignored")
        tabs.reset()
        assertEquals(8, tabs.next(0, 1, LAST))
    }
}
