package vesper.unicode

// The one width authority: cells per code point and per grapheme cluster. The scanner and the grid
// both call into this file, so they can never disagree about where the cursor ends up.
//
// Cluster width under DEC private mode 2027 follows the Terminal Unicode Core specification
// (https://github.com/contour-terminal/terminal-unicode-core, spec/terminal-unicode-core.tex,
// commit 64f5385, checked 2026-10-07): a cluster is one cell group, ZWJ emoji are two cells wide,
// and VS16 widens the cluster to two cells. VS15 keeps the width, as the specification says,
// although kitty, foot and Contour's libunicode narrow the cluster to one cell: applications that
// count cells with wcwidth give VS15 no width, so narrowing would move the cursor away from where
// they think it is, while keeping the width costs at most a blank cell beside a narrow glyph. Like
// foot and Contour, both selectors act only on a base that has an emoji variation sequence in
// emoji-variation-sequences.txt.

/** Emoji-presentation selector (VS16). */
private const val VS16 = 0xFE0F
private const val ZWJ = 0x200D
private const val NARROW = 1
private const val WIDE = 2

/** Width of East Asian Ambiguous characters (UAX #11, class `A`). */
public enum class AmbiguousWidth {
    /** One cell: what most non-CJK fonts and applications assume. */
    Narrow,

    /** Two cells: what CJK legacy encodings and fonts assume. */
    Wide,
}

/** Settings every width question needs. */
public data class WidthOptions(
    /** Cells for East Asian Ambiguous characters. */
    public val ambiguous: AmbiguousWidth = AmbiguousWidth.Narrow,
    /** Which Unicode tables answer. */
    public val version: UnicodeVersion = UnicodeVersion.LATEST,
) {
    public companion object {
        public val DEFAULT: WidthOptions = WidthOptions()
    }
}

/** How a cluster's width is decided; DEC private mode 2027 selects between them. */
public enum class ClusterPolicy {
    /**
     * Mode 2027 reset: the first code point decides, as legacy wcwidth callers expect.
     * Segmentation still groups the cluster into one cell.
     */
    FirstCodePoint,

    /** Mode 2027 set: variation selectors and ZWJ sequences change the width. */
    Grapheme,
}

/**
 * Cells taken by [cp] on its own: 0, 1 or 2.
 *
 * Zero for nonspacing and enclosing marks, format characters (ZWJ, variation selectors, tags),
 * Hangul medial vowels and final consonants, and C0, DEL and C1 controls. Controls are 0 rather than
 * an error because the parser executes them before anything asks for a width, so the answer is
 * never used for layout and the function stays total. Regional indicators are 2, so a flag does not
 * move the cursor when its second half arrives.
 */
public fun width(
    cp: Int,
    options: WidthOptions = WidthOptions.DEFAULT,
): Int = classWidth(widthClass(lookup(cp, options.version)), options.ambiguous)

private fun classWidth(
    widthClass: Int,
    ambiguous: AmbiguousWidth,
): Int =
    when (widthClass) {
        WIDTH_ZERO -> 0
        WIDTH_WIDE -> WIDE
        WIDTH_AMBIGUOUS -> if (ambiguous == AmbiguousWidth.Wide) WIDE else NARROW
        else -> NARROW
    }

/**
 * Width of the grapheme cluster being built, fed the same code points as [GraphemeState] put into
 * that cluster.
 *
 * The base is the first code point with a non-zero width; until one arrives the cluster is zero
 * cells wide and the grid decides where it goes.
 */
public class ClusterWidth(
    private val options: WidthOptions = WidthOptions.DEFAULT,
    private val policy: ClusterPolicy = ClusterPolicy.Grapheme,
) {
    /** Cells the cluster takes so far. */
    public var width: Int = 0
        private set
    private var hasBase = false
    private var baseHasVariations = false
    private var afterZwj = false

    /** Adds [cp] to the cluster and returns the cluster's width after it. */
    public fun push(cp: Int): Int {
        val props = lookup(cp, options.version)
        val own = classWidth(widthClass(props), options.ambiguous)
        if (!hasBase) {
            if (own > 0) {
                hasBase = true
                baseHasVariations = isVariationBase(props)
                width = own
            }
        } else if (policy == ClusterPolicy.Grapheme) {
            if ((cp == VS16 && baseHasVariations) || (afterZwj && isExtendedPictographic(props))) width = WIDE
        }
        afterZwj = cp == ZWJ
        return width
    }
}

/** Cells taken by a whole cluster, for callers that already hold its text. */
public fun clusterWidth(
    cluster: String,
    options: WidthOptions = WidthOptions.DEFAULT,
    policy: ClusterPolicy = ClusterPolicy.Grapheme,
): Int {
    val acc = ClusterWidth(options, policy)
    cluster.forEachCodePoint { acc.push(it) }
    return acc.width
}

@PublishedApi
internal const val SURROGATE_BITS: Int = 10
@PublishedApi
internal const val SUPPLEMENTARY_FIRST: Int = 0x10000

/** Calls [action] with each code point of the string; an unpaired surrogate is passed as itself. */
public inline fun String.forEachCodePoint(action: (Int) -> Unit) {
    var i = 0
    while (i < length) {
        val high = this[i]
        if (high.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
            val offset = ((high - Char.MIN_HIGH_SURROGATE) shl SURROGATE_BITS) + (this[i + 1] - Char.MIN_LOW_SURROGATE)
            action(SUPPLEMENTARY_FIRST + offset)
            i += 2
        } else {
            action(high.code)
            i++
        }
    }
}

/** The first code point of the string, decoded as [forEachCodePoint] does; 0 for an empty string. */
public fun String.firstCodePoint(): Int {
    forEachCodePoint { return it }
    return 0
}

private const val SURROGATE_MASK = 0x3FF

/** Appends the scalar value [cp] as one or two UTF-16 units. */
public fun StringBuilder.appendCodePoint(cp: Int): StringBuilder {
    if (cp < SUPPLEMENTARY_FIRST) return append(cp.toChar())
    val offset = cp - SUPPLEMENTARY_FIRST
    append(Char.MIN_HIGH_SURROGATE + (offset ushr SURROGATE_BITS))
    return append(Char.MIN_LOW_SURROGATE + (offset and SURROGATE_MASK))
}
