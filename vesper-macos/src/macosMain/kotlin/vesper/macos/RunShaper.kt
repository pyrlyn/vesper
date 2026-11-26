package vesper.macos

import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import platform.CoreFoundation.CFArrayGetCount
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFAttributedStringCreate
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFGetTypeID
import platform.CoreFoundation.CFIndexVar
import platform.CoreFoundation.CFRangeMake
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreGraphics.CGGlyphVar
import platform.CoreGraphics.CGPoint
import platform.CoreText.CTFontCopyPostScriptName
import platform.CoreText.CTFontGetSize
import platform.CoreText.CTFontGetTypeID
import platform.CoreText.CTFontRef
import platform.CoreText.CTLineCreateWithAttributedString
import platform.CoreText.CTLineGetGlyphRuns
import platform.CoreText.CTRunGetAttributes
import platform.CoreText.CTRunGetGlyphCount
import platform.CoreText.CTRunGetGlyphs
import platform.CoreText.CTRunGetPositions
import platform.CoreText.CTRunGetStringIndices
import platform.CoreText.CTRunRef
import platform.CoreText.kCTFontAttributeName
import platform.Foundation.CFBridgingRelease
import vesper.host.Clusters
import vesper.host.ShapeCache
import vesper.host.ShapedGlyph
import vesper.host.glyphKey
import vesper.host.isSprite
import vesper.host.spriteKey

// Shapes the core's text runs with CoreText. CoreText brings ligatures and font fallback (colour
// emoji among them), but its advances belong to whichever font it fell back to, so each glyph keeps
// only its offset inside its cluster (see Clusters).

/** The fonts glyph keys refer to: the four faces first, then each fallback font CoreText picks, numbered as they turn up. */
internal class FontRegistry {
    private val fonts = ArrayList<CTFontRef?>()
    private val ids = HashMap<String, Int>()

    fun id(font: CTFontRef?): Int {
        val name = "${CFBridgingRelease(CTFontCopyPostScriptName(font)) as? String}@${CTFontGetSize(font)}"
        ids[name]?.let { return it }
        // Kept for good: an atlas key may name it long after the line that found it is gone.
        CFRetain(font)
        fonts.add(font)
        ids[name] = fonts.size - 1
        return fonts.size - 1
    }

    fun font(id: Int): CTFontRef? = fonts.getOrNull(id)
}

internal class RunShaper(
    text: TextStyle,
    cacheLimit: Int = 4096,
) {
    val registry = FontRegistry()
    private val faces = text.faces
    private val attributes: List<CFDictionaryRef?> = faces.map(::shapingAttributes)
    private val cache = ShapeCache(cacheLimit, ::layout)

    init {
        faces.forEach(registry::id)
    }

    /** The glyphs of [text] in face [face] (see [face]), each cluster taking [width] columns. */
    fun shape(
        text: String,
        face: Int,
        width: Int,
    ): List<ShapedGlyph> = cache.shape(text, face, width)

    private fun layout(
        text: String,
        face: Int,
        width: Int,
    ): List<ShapedGlyph> {
        val clusters = Clusters(text, width)
        val sprites = HashSet<Int>()
        val out = ArrayList<ShapedGlyph>()
        // A sprite stands alone in its cell; a cluster with marks goes to the font.
        for ((index, cluster) in clusters.clusters.withIndex()) {
            if (!isSprite(cluster.codePoint) || !cluster.isSingle) continue
            sprites.add(cluster.start)
            out.add(ShapedGlyph(spriteKey(cluster.codePoint), index * width, 0.0, 0.0))
        }
        if (text.isEmpty() || sprites.size == clusters.clusters.size) return out
        placeGlyphs(text, face, clusters.column, clusters.start, sprites, out)
        return out
    }

    private fun placeGlyphs(
        text: String,
        face: Int,
        column: IntArray,
        clusterStart: IntArray,
        sprites: Set<Int>,
        out: MutableList<ShapedGlyph>,
    ) {
        val string = CFStringCreateWithCString(null, text, kCFStringEncodingUTF8) ?: return
        val attributed = CFAttributedStringCreate(null, string, attributes[face])
        val line = CTLineCreateWithAttributedString(attributed)
        // A cluster's origin is the pen position of its base character; marks may sit left of it.
        val origin = HashMap<Int, Pair<Int, Double>>()
        val placed = ArrayList<ShapedGlyph>()
        val runs = CTLineGetGlyphRuns(line)
        for (r in 0 until CFArrayGetCount(runs)) {
            val run: CTRunRef? = CFArrayGetValueAtIndex(runs, r)?.reinterpret()
            val count = CTRunGetGlyphCount(run).toInt()
            if (count <= 0) continue
            val value = CFDictionaryGetValue(CTRunGetAttributes(run), kCTFontAttributeName)
            val runFont: CTFontRef? = if (value != null && CFGetTypeID(value) == CTFontGetTypeID()) value.reinterpret() else faces[face]
            val font = registry.id(runFont)
            memScoped {
                val glyphs = allocArray<CGGlyphVar>(count)
                val positions = allocArray<CGPoint>(count)
                val indices = allocArray<CFIndexVar>(count)
                val all = CFRangeMake(0, 0)
                CTRunGetGlyphs(run, all, glyphs)
                CTRunGetPositions(run, all, positions)
                CTRunGetStringIndices(run, all, indices)
                for (i in 0 until count) {
                    val unit = indices[i].toInt()
                    if (unit !in text.indices || unit in sprites) continue
                    val x = positions[i].x
                    val start = clusterStart[unit]
                    val known = origin[start]
                    if (known == null || unit < known.first) origin[start] = unit to x
                    placed.add(ShapedGlyph(glyphKey(font, glyphs[i].toInt()), unit, x, positions[i].y))
                }
            }
        }
        for (glyph in placed) {
            val unit = glyph.column
            val penX = origin[clusterStart[unit]]?.second ?: glyph.dx
            out.add(glyph.copy(column = column[unit], dx = glyph.dx - penX))
        }
        CFRelease(line)
        CFRelease(attributed)
        CFRelease(string)
    }

    private fun shapingAttributes(face: CTFontRef?): CFDictionaryRef? {
        val dictionary = CFDictionaryCreateMutable(null, 1, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        CFDictionarySetValue(dictionary, kCTFontAttributeName, face)
        return dictionary
    }
}
