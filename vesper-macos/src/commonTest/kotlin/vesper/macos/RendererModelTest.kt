package vesper.macos

import vesper.grid.RowId
import vesper.term.Damage
import vesper.term.RowStamp
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

class AtlasLayoutTest {
    @Test
    fun glyphsShareShelvesOfNearlyOneHeight() {
        val atlas = AtlasLayout(initialSize = 64)
        val a = assertNotNull(atlas.place(1, 10, 14, 0, 12, false, frame = 1))
        val b = assertNotNull(atlas.place(2, 10, 13, 0, 12, false, frame = 1))
        assertEquals(a.y, b.y)
        assertEquals(11, b.x)
        assertEquals(a, atlas.get(1, 2))
        atlas.putEmpty(3)
        assertTrue(atlas.get(3, 2)!!.isEmpty)
        assertNull(atlas.get(4, 2))
    }

    @Test
    fun theLeastRecentlyDrawnShelfIsEvictedOnceTheGpuIsDoneWithIt() {
        val atlas = AtlasLayout(initialSize = 32, maxSize = 32, framesInFlight = 3)
        atlas.place(1, 30, 14, 0, 0, false, frame = 1)
        atlas.place(2, 30, 14, 0, 0, false, frame = 2)
        // Both shelves are in flight: nothing can go.
        assertNull(atlas.place(3, 30, 14, 0, 0, false, frame = 3))
        atlas.touch(1, frame = 5)
        val placed = assertNotNull(atlas.place(3, 30, 14, 0, 0, false, frame = 9))
        assertEquals(0, placed.shelf)
        assertNull(atlas.get(1, 9))
        assertNotNull(atlas.get(2, 9))
    }

    @Test
    fun aFullAtlasDoublesAndStartsOver() {
        val atlas = AtlasLayout(initialSize = 16, maxSize = 64)
        atlas.place(1, 14, 14, 0, 0, false, frame = 1)
        val big = assertNotNull(atlas.place(2, 14, 14, 0, 0, false, frame = 1))
        assertEquals(32, atlas.size)
        assertEquals(1, atlas.generation)
        assertEquals(0, big.y)
        assertNull(atlas.get(1, 1))
        assertNull(atlas.place(3, 100, 10, 0, 0, false, frame = 1))
    }

    @Test
    fun keysTellGlyphsFromSprites() {
        val glyph = glyphKey(3, 0xBEEF)
        assertEquals(3, keyFont(glyph))
        assertEquals(0xBEEF, keyGlyph(glyph))
        val sprite = spriteKey(0x110002)
        assertTrue(isSpriteKey(sprite))
        assertEquals(0x110002, keySprite(sprite))
        assertNotEquals(glyphKey(0, 0x2500), spriteKey(0x2500))
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
