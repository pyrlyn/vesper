package vesper.host

import vesper.term.Frame
import vesper.term.Terminal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ESC = "\u001b"

/** A texture that keeps nothing: the builder only needs the places. */
private object NoTexture : AtlasTexture {
    override fun resize(size: Int) = true

    override fun upload(
        entry: AtlasEntry,
        bitmap: Bitmap,
    ) = Unit
}

/** One 2 by 3 glyph per character, keyed by its face and code; a space draws nothing. */
private class FakeGlyphs : GlyphSource {
    val faces = ArrayList<Int>()
    var rasterised = 0

    override fun shape(
        text: String,
        face: Int,
        width: Int,
    ): List<ShapedGlyph> {
        faces.add(face)
        return text.mapIndexed { i, c -> ShapedGlyph(glyphKey(face, c.code), i * width, 0.0, 0.0) }
    }

    override fun rasterize(
        key: Long,
        metrics: CellMetrics,
        scale: Double,
    ): Bitmap? {
        rasterised++
        if (isSpriteKey(key) || keyGlyph(key) == ' '.code) return null
        return Bitmap(2, 3, 1, 3, false, ByteArray(2 * 3 * 4) { -1 })
    }
}

class QuadBuilderTest {
    private val palette = Palette()
    private val metrics = CellMetrics(8.0, 16.0, 12.0, 14.0, 1.0, 3.0)

    private class Fixture(
        val builder: QuadBuilder,
        val glyphs: FakeGlyphs,
        val terminal: Terminal,
        val frame: Frame = Frame(),
        val sources: RowSources = RowSources(),
    ) {
        fun feed(output: String): Boolean {
            terminal.feed(output.encodeToByteArray())
            terminal.updateFrame(frame)
            sources.absorb(frame.damage, frame.rows)
            return builder.update(frame, sources)
        }

        fun row(row: Int) = builder.content[builder.slots.slotOfRow[row]]
    }

    private fun fixture(
        cols: Int,
        rows: Int,
    ): Fixture {
        val glyphs = FakeGlyphs()
        val cache = GlyphCache.create(NoTexture, size = 64)!!
        return Fixture(QuadBuilder(palette, glyphs, cache, metrics, 1.0), glyphs, Terminal(cols, rows, 10))
    }

    @Test
    fun neighbouringBackgroundsShareAQuad() {
        val f = fixture(6, 2)
        assertTrue(f.feed("$ESC[41m  $ESC[0m $ESC[41m $ESC[0m"))
        val bg = f.row(0).bg
        assertEquals(2, bg.count)
        assertEquals(listOf(0f, 16f, 3f * 8, 8f), listOf(bg.x(0), bg.w(0), bg.x(1), bg.w(1)))
        assertEquals(shaderColor(0xCD0000), bg.color(0))
        assertEquals(0, f.row(1).bg.count)
    }

    @Test
    fun glyphsSitOnTheBaselineInTheirColumnAndFace() {
        val f = fixture(6, 1)
        f.feed("a $ESC[1;32mb")
        val fg = f.row(0).fg
        // The space draws nothing; a and b stand on the baseline, one pixel right of their cells' edges.
        assertEquals(2, fg.count)
        assertEquals(listOf(1f, 2 * 8f + 1), listOf(fg.x(0), fg.x(1)))
        assertEquals(listOf(9f, 9f), listOf(fg.y(0), fg.y(1)))
        assertEquals(listOf(TINTED, TINTED), listOf(fg.kind(0), fg.kind(1)))
        assertEquals(listOf(shaderColor(palette.foreground), shaderColor(0x00CD00)), listOf(fg.color(0), fg.color(1)))
        assertEquals(listOf(0, face(bold = true, italic = false)), f.glyphs.faces.distinct())
    }

    @Test
    fun strikeAndOverlineAreLinesAcrossTheRun() {
        val f = fixture(4, 1)
        f.feed("$ESC[9;53mab")
        val fg = f.row(0).fg
        val lines = (0 until fg.count).filter { fg.kind(it) == SOLID }
        // The strike sits half the x-height above the baseline, the overline on the cell's top.
        assertEquals(listOf(12f - 3f, 0f), lines.map { fg.y(it) })
        assertEquals(listOf(16f, 16f), lines.map { fg.w(it) })
    }

    @Test
    fun theCursorIsABlockWithTheGlyphCutOutOrAnOutline() {
        val f = fixture(4, 2)
        f.feed("x\b")
        val (under, over) = f.builder.cursorQuads(f.frame, focused = true)
        assertEquals(1, under.count)
        assertEquals(shaderColor(palette.cursor), under.color(0))
        assertEquals(1, over.count)
        assertEquals(shaderColor(palette.background), over.color(0))
        val (outline, none) = f.builder.cursorQuads(f.frame, focused = false)
        assertEquals(4, outline.count)
        assertEquals(0, none.count)
    }

    @Test
    fun onlyChangedRowsAreBuiltAgain() {
        val f = fixture(4, 3)
        f.feed("a\r\nb\r\nc")
        val versions = (0 until 3).map { f.row(it).version }
        val rasterised = f.glyphs.rasterised
        assertFalse(f.feed("\r\n"))
        // The scroll moved b and c up in their slots; only the new bottom row was built.
        assertEquals(listOf(versions[1], versions[2]), listOf(f.row(0).version, f.row(1).version))
        assertTrue(f.row(2).version > versions[2])
        assertEquals(rasterised, f.glyphs.rasterised, "cached glyphs are not rasterised again")
    }
}
