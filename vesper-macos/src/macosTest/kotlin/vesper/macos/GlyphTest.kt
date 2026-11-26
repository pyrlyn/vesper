package vesper.macos

import platform.Metal.MTLCreateSystemDefaultDevice
import vesper.grid.Underline
import vesper.host.Bitmap
import vesper.host.CellMetrics
import vesper.host.isSpriteKey
import vesper.host.keyFont
import vesper.host.keyGlyph
import vesper.host.keySprite
import vesper.host.underlineSprite
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun alpha(pixel: Int) = pixel ushr 24

class RunShaperTest {
    private val text = TextStyle()
    private val shaper = RunShaper(text)

    @Test
    fun glyphsArePinnedToTheirClusterColumns() {
        assertEquals(listOf(0, 1, 2), shaper.shape("abc", 0, 1).map { it.column })
        assertTrue(shaper.shape("abc", 0, 1).all { it.dx == 0.0 })
        assertEquals(listOf(0, 2), shaper.shape("中文", 0, 2).map { it.column })
        // A combining mark stays in its base's cell, whether the font composes it or not.
        val marked = shaper.shape("q\u0301x", 0, 1).map { it.column }
        assertEquals(1, marked.last())
        assertTrue(marked.dropLast(1).all { it == 0 }, "$marked")
    }

    @Test
    fun boxDrawingBecomesSpritesAndTheRestGlyphs() {
        val glyphs = shaper.shape("─x", 0, 1)
        assertEquals(2, glyphs.size)
        assertTrue(isSpriteKey(glyphs[0].key))
        assertEquals(0x2500, keySprite(glyphs[0].key))
        assertFalse(isSpriteKey(glyphs[1].key))
        assertEquals(1, glyphs[1].column)
    }

    @Test
    fun emojiComeFromAColourFallbackFont() {
        val glyph = shaper.shape("😀", 0, 2).single()
        val font = keyFont(glyph.key)
        assertTrue(font >= text.faces.size, "font $font")
        val bitmap = assertNotNull(Bitmap.glyph(keyGlyph(glyph.key), shaper.registry.font(font), 2.0))
        assertTrue(bitmap.isColor)
        val letter = shaper.shape("a", 0, 1).single()
        assertFalse(assertNotNull(Bitmap.glyph(keyGlyph(letter.key), shaper.registry.font(keyFont(letter.key)), 2.0)).isColor)
    }

    @Test
    fun aRepeatedRunComesFromTheCache() {
        val first = shaper.shape("cached", 0, 1)
        assertTrue(first === shaper.shape("cached", 0, 1))
    }
}

class GlyphAtlasTest {
    @Test
    fun entriesAreRasterisedOnceAndSpacesTakeNoRoom() {
        // A runner without a GPU (a VM) has no device; the app falls back to CoreText there.
        val device = MTLCreateSystemDefaultDevice() ?: return println("no Metal device: skipped")
        val atlas = assertNotNull(GlyphAtlas.create(device, size = 256))
        var made = 0
        val bitmap = { Bitmap.draw(10, 12, 0, 10, false) { made++ } }
        val entry = assertNotNull(atlas.cache.entry(1, 1, bitmap))
        assertEquals(entry, atlas.cache.entry(1, 2, bitmap))
        assertEquals(1, made)
        assertTrue(assertNotNull(atlas.cache.entry(2, 2) { null }).isEmpty)
        assertEquals(256uL, atlas.texture.width)
    }
}
