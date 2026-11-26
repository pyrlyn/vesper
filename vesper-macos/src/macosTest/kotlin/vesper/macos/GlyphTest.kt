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

class SpritesTest {
    private val text = TextStyle()
    private val cell = CellMetrics.of(text.faces[0], text.cell, 2.0)

    @Test
    fun aLightLineCrossesTheWholeCellAtItsMiddle() {
        val line = assertNotNull(spriteBitmap(0x2500, cell))
        val middle = (line.height / 2 - 1 until line.height / 2 + 1).first { alpha(line.pixel(0, it)) == 255 }
        for (x in 0 until line.width) assertEquals(255, alpha(line.pixel(x, middle)), "column $x")
        assertEquals(0, alpha(line.pixel(0, 0)))
        assertEquals(0, alpha(line.pixel(0, line.height - 1)))
    }

    @Test
    fun blocksAndShadesFillTheirShare() {
        val full = assertNotNull(spriteBitmap(0x2588, cell))
        assertEquals(255, alpha(full.pixel(0, 0)))
        assertEquals(255, alpha(full.pixel(full.width - 1, full.height - 1)))
        val lower = assertNotNull(spriteBitmap(0x2584, cell))
        assertEquals(0, alpha(lower.pixel(0, 0)))
        assertEquals(255, alpha(lower.pixel(0, lower.height - 1)))
        val light = assertNotNull(spriteBitmap(0x2591, cell))
        assertTrue(alpha(light.pixel(1, 1)) in 60..70)
    }

    @Test
    fun underlinesSitOnTheUnderlineRow() {
        val single = assertNotNull(spriteBitmap(underlineSprite(Underline.Single), cell))
        val row = cell.underline.toInt()
        assertTrue((row - 1..row).any { alpha(single.pixel(single.width / 2, it)) == 255 })
        assertEquals(0, alpha(single.pixel(single.width / 2, 0)))
        assertNull(spriteBitmap(underlineSprite(Underline.None), cell))
        assertNull(spriteBitmap('A'.code, cell))
    }

    /** The T28 check: every Powerline separator is a sprite that reaches the cell's edges. */
    @Test
    fun powerlineSeparatorsReachTheCellEdges() {
        // The sides each solid shape stands on: up, right, down, left.
        val flat =
            mapOf(
                0xE0B0 to "l",
                0xE0B2 to "r",
                0xE0B4 to "l",
                0xE0B6 to "r",
                0xE0B8 to "ld",
                0xE0BA to "rd",
                0xE0BC to "lu",
                0xE0BE to "ru",
            )
        for (code in 0xE0B0..0xE0BF) {
            val name = "U+${code.toString(16).uppercase()}"
            assertTrue(isSprite(code), name)
            val sprite = assertNotNull(spriteBitmap(code, cell), name)
            val w = sprite.width
            val h = sprite.height
            val edges =
                mapOf(
                    'u' to (0 until w).map { sprite.pixel(it, 0) },
                    'r' to (0 until h).map { sprite.pixel(w - 1, it) },
                    'd' to (0 until w).map { sprite.pixel(it, h - 1) },
                    'l' to (0 until h).map { sprite.pixel(0, it) },
                )
            for ((side, pixels) in edges) assertTrue(pixels.any { alpha(it) > 0 }, "$name leaves its $side edge bare")
            // A flat side is covered all along, so the next segment's colour meets it without a seam;
            // only its ends may be cut, by a slanted side as steep as the cell is tall for its width.
            val cut = ceil(h.toDouble() / w).toInt() + 1
            for (side in flat[code].orEmpty()) {
                val pixels = edges.getValue(side)
                assertTrue(pixels.drop(cut).dropLast(cut).all { alpha(it) == 255 }, "$name's $side side has a gap")
            }
        }
    }
}

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
