package vesper.windows

import vesper.config.Font
import vesper.config.Settings
import vesper.host.Bitmap
import vesper.host.CellMetrics
import vesper.host.CellSize
import vesper.host.Clusters
import vesper.host.FACES
import vesper.host.GlyphSource
import vesper.host.ShapeCache
import vesper.host.ShapedGlyph
import vesper.host.glyphKey
import vesper.host.isBoldFace
import vesper.host.isItalicFace
import vesper.host.isSprite
import vesper.host.isSpriteKey
import vesper.host.keyFont
import vesper.host.keyGlyph
import vesper.host.keySprite
import vesper.host.spriteBitmap
import vesper.host.spriteKey
import vesper.unicode.forEachCodePoint
import kotlin.math.ceil

/** What an empty `font.family` means on Windows, and the stand-in for a family that is not installed. */
private const val DEFAULT_FAMILY = "Consolas"

/** In every Windows install, so a window always has a font. */
private const val LAST_RESORT_FAMILY = "Courier New"
private const val REGULAR = 400
private const val BOLD = 700

private class SizedFace(
    val face: FontFace,
    /** The em size in DIPs. */
    val size: Double,
)

/**
 * The user's fixed-pitch [family] in its four faces, as the renderer's [GlyphSource]. Glyphs come
 * from each face's character map, one cluster at a time, and a cluster the face lacks goes to the
 * system's font fallback. Box drawing, blocks and Powerline separators are the shared sprites, drawn
 * from the cell's geometry so neighbouring cells meet. There is no shaping yet: no ligatures, and a joined emoji sequence draws
 * its parts on top of each other.
 */
class TextStyle private constructor(
    private val fonts: FontSystem,
    private val family: String,
    private val size: Double,
    faces: List<FontFace>,
) : GlyphSource,
    AutoCloseable {
    // Glyph keys name fonts by their index here: the four faces first, then each fallback as it turns up.
    private val registry = faces.mapTo(ArrayList()) { SizedFace(it, size) }
    private val fallbacks = HashMap<Pair<String, Int>, Int>()
    private val shapes = ShapeCache(layout = ::layout)

    /** The family in use, which differs from the configured one after a fallback, at its size in DIPs. */
    val font: Font get() = Font(family, size)

    /** The cell in DIPs, from the advance of `M` and the line height. */
    val cell: CellSize

    init {
        val regular = faces[0]
        val perUnit = size / regular.unitsPerEm
        cell =
            CellSize(
                ceil(regular.advance(regular.glyph('M'.code)) * perUnit),
                ceil((regular.ascent + regular.descent + regular.lineGap) * perUnit),
            )
    }

    /** The cell's geometry at [scale] pixels per DIP. */
    fun metrics(scale: Double): CellMetrics {
        val face = registry[0].face
        val perUnit = size / face.unitsPerEm
        return CellMetrics.of(
            cell,
            scale,
            face.ascent * perUnit,
            face.underlinePosition * perUnit,
            face.underlineThickness * perUnit,
            face.xHeight * perUnit,
        )
    }

    override fun shape(
        text: String,
        face: Int,
        width: Int,
    ): List<ShapedGlyph> = shapes.shape(text, face, width)

    override fun rasterize(
        key: Long,
        metrics: CellMetrics,
        scale: Double,
    ): Bitmap? {
        if (isSpriteKey(key)) return spriteBitmap(keySprite(key), metrics)
        val font = registry.getOrNull(keyFont(key)) ?: return null
        return fonts.glyphBitmap(font.face, keyGlyph(key), (font.size * scale).toFloat())
    }

    private fun layout(
        text: String,
        face: Int,
        width: Int,
    ): List<ShapedGlyph> {
        val out = ArrayList<ShapedGlyph>()
        for ((index, cluster) in Clusters(text, width).clusters.withIndex()) {
            // A sprite stands alone in its cell; a cluster with marks goes to the font.
            if (isSprite(cluster.codePoint) && cluster.isSingle) {
                out.add(ShapedGlyph(spriteKey(cluster.codePoint), index * width, 0.0, 0.0))
                continue
            }
            val part = text.substring(cluster.start, cluster.start + cluster.units)
            val font = fontFor(part, face)
            val drawn = registry[font].face
            var first = true
            part.forEachCodePoint { cp ->
                val glyph = drawn.glyph(cp)
                // A mark or selector the font has no glyph for adds nothing; a missing base shows the font's box.
                if (glyph != 0 || first) out.add(ShapedGlyph(glyphKey(font, glyph), index * width, 0.0, 0.0))
                first = false
            }
        }
        return out
    }

    /** The font that draws [part] in face [face]: the face itself when it has every character. */
    private fun fontFor(
        part: String,
        face: Int,
    ): Int {
        var covered = true
        part.forEachCodePoint { if (registry[face].face.glyph(it) == 0) covered = false }
        if (covered) return face
        return fallbacks.getOrPut(part to face) {
            val found = fonts.fallback(part, family, if (isBoldFace(face)) BOLD else REGULAR, isItalicFace(face))
            val fallback = found.face ?: return@getOrPut face
            val scaled = size * found.scale
            // DirectWrite hands out one face object per font, so a font met before keeps its id and atlas entries.
            val known = registry.indexOfFirst { it.face.face == fallback.face && it.size == scaled }
            if (known >= 0) {
                fallback.close()
                known
            } else {
                registry.add(SizedFace(fallback, scaled))
                registry.size - 1
            }
        }
    }

    override fun close() {
        registry.forEach { it.face.close() }
        registry.clear()
    }

    companion object {
        /**
         * The configured [font], its size in DIPs (Windows' 1/96 inch, which a scale of 1 draws one
         * pixel each). A family that is not installed falls back to Consolas, then Courier New, rather
         * than leaving the window without text; null only when none of them is.
         */
        fun of(
            fonts: FontSystem,
            font: Font = Settings.DEFAULT.font,
        ): TextStyle? =
            listOf(font.family, DEFAULT_FAMILY, LAST_RESORT_FAMILY)
                .filter { it.isNotEmpty() }
                .distinct()
                .firstNotNullOfOrNull { create(fonts, it, font.size) }

        /** [family] at [size] DIPs, or null when it is not installed. */
        fun create(
            fonts: FontSystem,
            family: String = DEFAULT_FAMILY,
            size: Double = Settings.DEFAULT.font.size,
        ): TextStyle? {
            // GetFirstMatchingFont gives the nearest face, so a family without bold or italic still has four.
            val faces = ArrayList<FontFace>()
            for (index in 0 until FACES) {
                val face = fonts.face(family, if (isBoldFace(index)) BOLD else REGULAR, isItalicFace(index))
                if (face == null) {
                    faces.forEach(FontFace::close)
                    return null
                }
                faces.add(face)
            }
            return TextStyle(fonts, family, size, faces)
        }
    }
}
