package vesper.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
