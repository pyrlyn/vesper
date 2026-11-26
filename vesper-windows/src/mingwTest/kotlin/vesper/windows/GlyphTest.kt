package vesper.windows

import vesper.grid.Underline
import vesper.host.Bitmap
import vesper.host.GlyphCache
import vesper.host.glyphKey
import vesper.host.isSpriteKey
import vesper.host.keySprite
import vesper.host.spriteKey
import vesper.host.underlineSprite
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val EM = 20f

private fun Bitmap.pixels() = (0 until height).flatMap { y -> (0 until width).map { x -> pixel(x, y) } }

class GlyphTest {
    @Test
    fun aLetterBecomesWhiteCoverageStandingOnTheBaseline() {
        FontSystem.create().use { fonts ->
            val face = assertNotNull(fonts.face("Consolas"))
            val m = assertNotNull(fonts.glyphBitmap(face, face.glyph('M'.code), EM))
            assertFalse(m.isColor)
            assertTrue(m.top in 1..EM.toInt() && m.top - m.height <= 1, "M sits on the baseline: top ${m.top}, height ${m.height}")
            assertTrue(m.width in 1..EM.toInt())
            // Premultiplied white: every channel is the coverage.
            for (pixel in m.pixels()) {
                val a = pixel ushr 24
                assertEquals(a * 0x010101, pixel and 0xFFFFFF)
            }
            assertTrue(m.pixels().maxOf { it ushr 24 } > 200, "the stems are covered")
            val g = assertNotNull(fonts.glyphBitmap(face, face.glyph('g'.code), EM))
            assertTrue(g.top - g.height < 0, "g descends below the baseline")
            assertNull(fonts.glyphBitmap(face, face.glyph(' '.code), EM))
            face.close()
        }
    }

    @Test
    fun anEmojiKeepsItsColours() {
        FontSystem.create().use { fonts ->
            val face = assertNotNull(fonts.face("Segoe UI Emoji"))
            val glyph = face.glyph(0x1F600)
            assertNotEquals(0, glyph)
            val emoji = assertNotNull(fonts.glyphBitmap(face, glyph, EM))
            assertTrue(emoji.isColor)
            assertTrue(
                emoji.pixels().any { (it ushr 16 and 0xFF) != (it and 0xFF) },
                "some pixel is neither grey nor white",
            )
            face.close()
        }
    }

    @Test
    fun fallbackFindsAFaceForWhatConsolasLacks() {
        FontSystem.create().use { fonts ->
            val own = fonts.fallback("A", "Consolas")
            assertEquals(1, own.length)
            assertNotEquals(0, assertNotNull(own.face).glyph('A'.code))
            val emoji = fonts.fallback("😀 x", "Consolas")
            // The surrogate pair is one character and one face draws it.
            assertTrue(emoji.length >= 2)
            assertNotEquals(0, assertNotNull(emoji.face).glyph(0x1F600))
            assertTrue(emoji.scale > 0f)
            val han = fonts.fallback("中", "Consolas")
            assertEquals(1, han.length)
            assertNotEquals(0, assertNotNull(han.face).glyph('中'.code))
            listOf(own, emoji, han).forEach { it.face?.close() }
        }
    }

    /** The Windows half of T28: box drawing and the underlines are the shared sprites, not the font's glyphs. */
    @Test
    fun boxLinesAndUnderlinesAreSprites() {
        FontSystem.create().use { fonts ->
            assertNotNull(TextStyle.create(fonts)).use { style ->
                val metrics = style.metrics(2.0)
                val glyphs = style.shape("\u2500a\uE0B0", 0, 1)
                assertEquals(listOf(true, false, true), glyphs.map { isSpriteKey(it.key) })
                assertEquals(0x2500, keySprite(glyphs[0].key))
                val line = assertNotNull(style.rasterize(glyphs[0].key, metrics, 2.0))
                assertEquals(ceil(metrics.width).toInt(), line.width)
                // The line crosses the whole cell, so it meets the next one without a gap.
                val middle = (0 until line.height).first { line.pixel(0, it) ushr 24 == 255 }
                assertTrue((0 until line.width).all { line.pixel(it, middle) ushr 24 == 255 })
                val underline = assertNotNull(style.rasterize(spriteKey(underlineSprite(Underline.Curly)), metrics, 2.0))
                assertTrue(underline.pixels().any { it ushr 24 > 0 })
            }
        }
    }

    @Test
    fun glyphsReachTheAtlasTextureAndSurviveItsGrowth() {
        Gpu.create(warp = true).use { gpu ->
            FontSystem.create().use { fonts ->
                GpuAtlas(gpu).use { atlas ->
                    val face = assertNotNull(fonts.face("Consolas"))
                    // Too small for one glyph: the cache doubles the texture before placing it.
                    val cache = assertNotNull(GlyphCache.create(atlas, size = 8, maxSize = 64))
                    val glyph = face.glyph('W'.code)
                    var made = 0
                    val bitmap = assertNotNull(fonts.glyphBitmap(face, glyph, EM))
                    val entry =
                        assertNotNull(
                            cache.entry(glyphKey(0, glyph), 1) {
                                made++
                                bitmap
                            },
                        )
                    assertTrue(cache.size > 8 && cache.generation > 0)
                    assertEquals(cache.size, atlas.size)
                    assertEquals(entry, cache.entry(glyphKey(0, glyph), 2) { error("rasterised twice") })
                    assertEquals(1, made)
                    val read = gpu.read(assertNotNull(atlas.texture), atlas.size, atlas.size)
                    for (y in 0 until bitmap.height) {
                        for (x in 0 until bitmap.width) {
                            assertEquals(bitmap.pixel(x, y), read[(entry.y + y) * atlas.size + entry.x + x], "pixel ($x, $y)")
                        }
                    }
                    assertNotNull(atlas.view)
                    face.close()
                }
            }
        }
    }
}
