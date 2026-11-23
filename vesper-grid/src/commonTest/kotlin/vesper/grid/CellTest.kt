package vesper.grid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CellTest {
    @Test
    fun emptyCellIsAllZeroAndReadsEmpty() {
        assertEquals(0L, Cell.EMPTY.bits)
        assertTrue(Cell.EMPTY.isEmpty)
        assertTrue(Cell.char(0).isEmpty)
        assertFalse(Cell.char(' '.code).isEmpty, "a typed space is not an erased cell")
    }

    @Test
    fun charAndClusterNeverAlias() {
        val id = ClusterId('a'.code)
        val ch = Cell.char('a'.code)
        val cl = Cell.cluster(id)
        assertNotEquals(ch, cl)
        assertEquals('a'.code, ch.codePoint)
        assertFalse(ch.isCluster)
        assertTrue(cl.isCluster)
        assertEquals(id, cl.clusterId)
        assertEquals(0, cl.codePoint)
    }

    @Test
    fun publicFlagSettersCannotForgeACluster() {
        val cell = Cell.char('x'.code, StyleId(3)).withFlags(-1)
        assertFalse(cell.isCluster)
        assertEquals('x'.code, cell.codePoint)
        assertEquals(CellFlags.ALL, cell.flags)
        val cl = Cell.cluster(ClusterId(1), StyleId(3)).withoutFlags(-1)
        assertTrue(cl.isCluster)
        assertEquals(ClusterId(1), cl.clusterId)
    }

    @Test
    fun spacerKeepsTheHeadStyleAndHasNoText() {
        val s = Cell.spacer(StyleId(9))
        assertEquals(StyleId(9), s.style)
        assertTrue(s.isEmpty)
        assertTrue(s.hasFlags(CellFlags.SPACER))
    }

    @Test
    fun fieldsDoNotBleedIntoEachOther() {
        val cell = Cell.char(0x10FFFF, StyleId(0xFFFF)).withFlags(CellFlags.WIDE or CellFlags.PROTECTED)
        assertEquals(0x10FFFF, cell.codePoint)
        assertEquals(StyleId(0xFFFF), cell.style)
        assertEquals(CellFlags.WIDE or CellFlags.PROTECTED, cell.flags)
        assertEquals(StyleId(2), cell.withStyle(StyleId(2)).style)
        assertEquals(cell.flags, cell.withStyle(StyleId(2)).flags)
    }

    @Test
    fun nonScalarValuesAndWideIdsAreRefused() {
        assertFailsWith<IllegalArgumentException> { Cell.char(0xD800) }
        assertFailsWith<IllegalArgumentException> { Cell.char(0x110000) }
        assertFailsWith<IllegalArgumentException> { Cell.char(-1) }
        assertFailsWith<IllegalArgumentException> { StyleId(0x10000) }
        assertFailsWith<IllegalArgumentException> { ClusterId(-1) }
    }

    @Test
    fun cellIsEightBytes() {
        assertEquals(8, CELL_BYTES)
    }
}
