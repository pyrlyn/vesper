package vesper.host

import vesper.grid.Attrs
import vesper.grid.Color
import vesper.grid.RowId
import vesper.grid.Style
import vesper.term.Damage
import vesper.term.RowStamp
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaletteTest {
    private val palette = Palette()

    @Test
    fun indexedColoursAreXterms() {
        assertEquals(0xCD0000, palette.resolve(Color.indexed(1), 0))
        assertEquals(0x000000, palette.resolve(Color.indexed(16), 0))
        assertEquals(0x5F87AF, palette.resolve(Color.indexed(67), 0))
        assertEquals(0xFFFFFF, palette.resolve(Color.indexed(231), 0))
        assertEquals(0x080808, palette.resolve(Color.indexed(232), 0))
        assertEquals(0xEEEEEE, palette.resolve(Color.indexed(255), 0))
    }

    @Test
    fun directAndDefaultColours() {
        assertEquals(0x123456, palette.resolve(Color.rgb(0x12, 0x34, 0x56), 0))
        assertEquals(0xABCDEF, palette.resolve(Color.DEFAULT, 0xABCDEF))
    }

    @Test
    fun reverseVideoSwapsAndDimBlends() {
        assertEquals(CellColors(palette.foreground, palette.background), palette.colors(Style.DEFAULT))
        assertEquals(CellColors(palette.background, palette.foreground), palette.colors(Style(attrs = Attrs.INVERSE)))
        val dim = palette.colors(Style(fg = Color.rgb(200, 100, 0), bg = Color.rgb(0, 0, 0), attrs = Attrs.DIM))
        assertEquals(CellColors(rgb(100, 50, 0), 0), dim)
    }
}

class GeometryTest {
    private val cell = CellSize(8.0, 16.0)

    @Test
    fun theGridFitsWholeCellsInsideThePadding() {
        assertEquals(Cells(80, 24), gridSize(80 * 8 + 2 * PADDING + 7, 24 * 16 + 2 * PADDING + 15, cell))
        assertEquals(Cells(1, 1), gridSize(0.0, 0.0, cell))
        val (width, height) = viewSize(Cells(80, 24), cell)
        assertEquals(Cells(80, 24), gridSize(width, height, cell))
    }

    @Test
    fun pointsMapToCellsAndClampAtTheEdges() {
        val grid = Cells(80, 24)
        assertEquals(Cells(0, 0), cellAt(PADDING, PADDING, cell, grid))
        assertEquals(Cells(1, 2), cellAt(PADDING + 8, PADDING + 40, cell, grid))
        assertEquals(Cells(0, 0), cellAt(-50.0, -50.0, cell, grid))
        assertEquals(Cells(79, 23), cellAt(5000.0, 5000.0, cell, grid))
    }
}

class QuadsTest {
    @Test
    fun quadsKeepTheShaderLayout() {
        val quads = Quads(capacity = 1)
        quads.add(1.5f, 2f, 3f, 4f, shaderColor(0x123456, 0x80), TINTED, u = 7, v = 9, spanU = 0xFFFF, spanV = 2)
        quads.add(0f, 0f, 1f, 1f, 0)
        assertEquals(2, quads.count)
        assertEquals(1.5f.toRawBits(), quads.data[0])
        assertEquals(7 or (9 shl 16), quads.data[4])
        assertEquals(0x80563412.toInt(), quads.data[5])
        assertEquals(TINTED, quads.data[6])
        assertEquals(0xFFFF or (2 shl 16), quads.data[7])
        val copy = Quads()
        copy.addCopy(quads, 0, dy = 10f, color = 5)
        assertEquals(12f, copy.y(0))
        assertEquals(5, copy.color(0))
        assertEquals(quads.data[4], copy.data[4])
        assertEquals(quads.data[7], copy.data[7])
    }
}

class RowSlotsTest {
    private fun stamps(vararg ids: Long) = ids.map { RowStamp(RowId(it), 0) }

    @Test
    fun updatesComposeIntoSourcesForTheLastDrawnPicture() {
        val damage = Damage()
        val sources = RowSources()
        damage.compute(emptyList(), stamps(1, 2, 3, 4), full = true)
        sources.absorb(damage, 4)
        assertContentEquals(IntArray(4) { REPAINT }, sources.sources)
        sources.drawn(4)
        // Two one-line scrolls before one draw: the top row now shows what was row 2.
        damage.compute(stamps(1, 2, 3, 4), stamps(2, 3, 4, 5), full = false)
        sources.absorb(damage, 4)
        damage.compute(stamps(2, 3, 4, 5), stamps(3, 4, 5, 6), full = false)
        sources.absorb(damage, 4)
        assertContentEquals(intArrayOf(2, 3, REPAINT, REPAINT), sources.sources)
    }

    @Test
    fun aScrollReordersSlotsAndBuildsOnlyTheNewRows() {
        val slots = RowSlots(4)
        val dirty = slots.remap(intArrayOf(1, 2, 3, REPAINT))
        assertEquals(listOf(3), dirty)
        assertContentEquals(intArrayOf(1, 2, 3, 0), slots.slotOfRow)
        // Two rows claiming one source: the second is built.
        assertEquals(listOf(1, 2, 3), slots.remap(intArrayOf(0, 0, REPAINT, REPAINT)))
    }
}
