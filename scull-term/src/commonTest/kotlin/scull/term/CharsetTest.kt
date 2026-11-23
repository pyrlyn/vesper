package scull.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun Charsets.map(ch: Char): Char = map(ch.code).toChar()

class CharsetTest {
    @Test
    fun decSpecialGraphicsDrawsLinesAndLeavesLettersBelowIt() {
        val c = Charsets()
        c.designate(0, Charset.DecSpecial)
        assertEquals('─', c.map('q'))
        assertEquals('│', c.map('x'))
        assertEquals('┌', c.map('l'))
        assertEquals(' ', c.map('_'))
        assertEquals('·', c.map('~'))
        assertEquals('A', c.map('A'))
        assertEquals('中', c.map('中'))
        assertEquals(0x7F, c.map(0x7F))
    }

    @Test
    fun shiftOutSelectsG1AndShiftInReturnsToG0() {
        val c = Charsets()
        c.designate(1, Charset.DecSpecial)
        assertEquals('q', c.map('q'))
        assertTrue(c.isAscii)
        c.lockShift(1)
        assertFalse(c.isAscii)
        assertEquals('─', c.map('q'))
        c.lockShift(0)
        assertEquals('q', c.map('q'))
    }

    @Test
    fun aSingleShiftLastsOneCharacter() {
        val c = Charsets()
        c.designate(2, Charset.Uk)
        c.singleShift(2)
        assertFalse(c.isAscii)
        assertEquals('£', c.map('#'))
        assertTrue(c.isAscii)
        assertEquals('#', c.map('#'))
    }

    @Test
    fun outOfRangeSlotsAreIgnored() {
        val c = Charsets()
        c.designate(9, Charset.DecSpecial)
        c.lockShift(9)
        c.singleShift(9)
        assertTrue(c.sameAs(Charsets()))
        assertEquals(3, Charsets.slotFor('+'))
        assertEquals(-1, Charsets.slotFor('-'))
        assertNull(Charset.fromFinal('Z'))
    }

    @Test
    fun aCopyDoesNotFollowTheOriginal() {
        val c = Charsets()
        c.designate(0, Charset.DecSpecial)
        val saved = c.copy()
        c.reset()
        assertTrue(c.sameAs(Charsets()))
        c.assign(saved)
        assertEquals('─', c.map('q'))
    }
}
