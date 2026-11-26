package vesper.host

import vesper.unicode.GraphemeState
import vesper.unicode.forEachCodePoint

private const val SUPPLEMENTARY = 0x10000
private const val BOLD = 1
private const val ITALIC = 2

/** How many faces a text style has: regular, bold, italic and bold italic, indexed by [face]. */
public const val FACES: Int = 4

/** The index of a style's face among the [FACES]. */
public fun face(
    bold: Boolean,
    italic: Boolean,
): Int = (if (bold) BOLD else 0) or (if (italic) ITALIC else 0)

/** Whether face [index] is bold. */
public fun isBoldFace(index: Int): Boolean = index and BOLD != 0

/** Whether face [index] is italic. */
public fun isItalicFace(index: Int): Boolean = index and ITALIC != 0

/** One glyph of a shaped run: its atlas key, its cluster's column from the run's first, and points from the cluster's pen (y up). */
public data class ShapedGlyph(
    val key: Long,
    val column: Int,
    val dx: Double,
    val dy: Double,
)

/** One grapheme cluster of a run: where it [start]s and how many UTF-16 [units] it takes, and its first [codePoint]. */
public data class Cluster(
    val start: Int,
    val codePoint: Int,
    val units: Int,
) {
    /** Whether the cluster is its first code point alone, with no marks or joiners. */
    val isSingle: Boolean get() = units == (if (codePoint >= SUPPLEMENTARY) 2 else 1)
}

/**
 * A run's grapheme clusters, each [width] columns wide. The core alone decides columns, so a shaper
 * pins each glyph to its cluster's column, whatever the font's advances say.
 */
public class Clusters(
    text: String,
    width: Int,
) {
    /** Per UTF-16 unit: its cluster's column from the run's first. */
    public val column: IntArray = IntArray(text.length)

    /** Per UTF-16 unit: where its cluster starts. */
    public val start: IntArray = IntArray(text.length)

    public val clusters: List<Cluster>

    init {
        val graphemes = GraphemeState()
        val out = ArrayList<Cluster>()
        var at = 0
        text.forEachCodePoint { cp ->
            val units = if (cp >= SUPPLEMENTARY) 2 else 1
            // The first code point always starts a cluster.
            if (graphemes.next(cp)) out.add(Cluster(at, cp, 0))
            val index = out.size - 1
            out[index] = out[index].copy(units = at + units - out[index].start)
            for (unit in at until at + units) {
                column[unit] = index * width
                start[unit] = out[index].start
            }
            at += units
        }
        clusters = out
    }
}

/**
 * Shaped runs by text, face and width, bounded. Two generations keep what is still in use: a hit in
 * the old one moves forward, and the old one is dropped whole when the new one fills.
 */
public class ShapeCache(
    private val limit: Int = 4096,
    private val layout: (text: String, face: Int, width: Int) -> List<ShapedGlyph>,
) {
    private data class Key(
        val text: String,
        val face: Int,
        val width: Int,
    )

    private var recent = HashMap<Key, List<ShapedGlyph>>()
    private var older = HashMap<Key, List<ShapedGlyph>>()

    /** The glyphs of [text] in face [face], each cluster taking [width] columns. */
    public fun shape(
        text: String,
        face: Int,
        width: Int,
    ): List<ShapedGlyph> {
        val key = Key(text, face.coerceIn(0, FACES - 1), width.coerceAtLeast(1))
        recent[key]?.let { return it }
        val glyphs = older.remove(key) ?: layout(text, key.face, key.width)
        if (recent.size >= limit) {
            older = recent
            recent = HashMap()
        }
        recent[key] = glyphs
        return glyphs
    }
}

/** What a renderer needs from a host's text system. */
public interface GlyphSource {
    /** The glyphs of [text] in face [face] (see [face]), each cluster taking [width] columns. */
    public fun shape(
        text: String,
        face: Int,
        width: Int,
    ): List<ShapedGlyph>

    /** The bitmap for atlas key [key], with cells of [metrics] at [scale] pixels per point; null when it draws nothing. */
    public fun rasterize(
        key: Long,
        metrics: CellMetrics,
        scale: Double,
    ): Bitmap?
}
