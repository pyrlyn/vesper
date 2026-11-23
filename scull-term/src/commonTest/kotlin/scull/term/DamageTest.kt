package scull.term

import scull.grid.RowId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun stamps(vararg ids: Long): List<RowStamp> = ids.map { RowStamp(RowId(it), 0) }

private fun damage(
    old: List<RowStamp>,
    new: List<RowStamp>,
    full: Boolean = false,
): Damage = Damage().apply { compute(old, new, full) }

class DamageTest {
    @Test
    fun identicalPicturesHaveNoDamage() {
        val s = stamps(1, 2, 3)
        assertTrue(damage(s, s).isEmpty)
    }

    @Test
    fun aFullOrResizedPictureRepaintsEveryRow() {
        val s = stamps(1, 2, 3)
        val d = damage(s, s, full = true)
        assertTrue(d.isFull)
        assertEquals(listOf(0, 1, 2), d.dirty)
        assertTrue(damage(s.take(2), s).isFull, "row count changed")
    }

    @Test
    fun aNewGenerationIsDirty() {
        val old = stamps(1, 2, 3)
        val new = old.toMutableList().apply { this[1] = this[1].copy(generation = 7) }
        val d = damage(old, new)
        assertEquals(listOf(1), d.dirty)
        assertTrue(d.scrolls.isEmpty())
    }

    @Test
    fun aScrollUpIsOneMoveAndANewBottomRow() {
        val d = damage(stamps(1, 2, 3, 4), stamps(2, 3, 4, 5))
        assertEquals(listOf(Scroll(0, 3, 1)), d.scrolls)
        assertEquals(listOf(3), d.dirty)
    }

    @Test
    fun aMovedRowThatAlsoChangedIsRepaintedNotMoved() {
        val new = stamps(2, 3, 4, 5).toMutableList().apply { this[1] = this[1].copy(generation = 1) }
        val d = damage(stamps(1, 2, 3, 4), new)
        assertEquals(listOf(Scroll(0, 1, 1), Scroll(2, 3, 3)), d.scrolls)
        assertEquals(listOf(1, 3), d.dirty)
    }

    @Test
    fun twoRegionsScrollingApartAreTwoMoves() {
        val d = damage(stamps(1, 2, 3, 4, 5, 6), stamps(2, 3, 9, 8, 4, 5))
        assertEquals(listOf(Scroll(0, 2, 1), Scroll(4, 6, 3)), d.scrolls)
        assertEquals(listOf(2, 3), d.dirty)
    }
}

class TerminalDamageTest {
    private fun Terminal.stamps(): List<RowStamp> = (0 until grid.screenRows).map { RowStamp.of(checkNotNull(grid.visibleRow(it))) }

    private fun Terminal.damageOf(change: Terminal.() -> Unit): Damage {
        val old = stamps()
        change()
        return damage(old, stamps())
    }

    @Test
    fun aLinefeedAtTheBottomIsScrollDamage() {
        val term = Terminal(10, 4, 10)
        term.feed("a\r\nb\r\nc\r\nd")
        val d = term.damageOf { feed("\r\n") }
        assertEquals(listOf(Scroll(0, 3, 1)), d.scrolls)
        assertEquals(listOf(3), d.dirty)
    }

    @Test
    fun aRegionScrollMovesOnlyTheRegion() {
        val term = Terminal(10, 6, 10)
        term.feed("${CSI}2;5r")
        val d = term.damageOf { feed("${CSI}2S") }
        assertEquals(listOf(Scroll(1, 3, 3)), d.scrolls)
        assertEquals(listOf(3, 4), d.dirty)
    }

    @Test
    fun printingDirtiesOnlyTheRowsWritten() {
        val term = Terminal(10, 4, 0)
        val d = term.damageOf { feed("${CSI}3Hx") }
        assertTrue(d.scrolls.isEmpty())
        assertEquals(listOf(2), d.dirty)
    }

    @Test
    fun scrollingTheViewportBackIsScrollDamageToo() {
        val term = Terminal(10, 3, 10)
        term.feed("1\r\n2\r\n3\r\n4\r\n5")
        val d = term.damageOf { scrollDisplay(1) }
        assertEquals(listOf(Scroll(1, 3, 0)), d.scrolls)
        assertEquals(listOf(0), d.dirty)
    }
}
