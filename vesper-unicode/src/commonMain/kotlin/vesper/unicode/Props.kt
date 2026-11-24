package vesper.unicode

// Per-code-point properties and the one lookup into the generated tables. Separate from width and
// grapheme code so both read the same record from a single three-stage lookup. A record is a packed
// Int, so a lookup allocates nothing; build-logic's vesper/ucd/Ucd.kt writes the same layout.

internal const val WIDTH_ZERO = 0
internal const val WIDTH_NARROW = 1
internal const val WIDTH_WIDE = 2

/** East Asian Width `A`: narrow or wide depending on the setting. */
internal const val WIDTH_AMBIGUOUS = 3

// `Grapheme_Cluster_Break` values (UAX #29, table 2) in the generator's order.
internal const val GCB_OTHER = 0
internal const val GCB_CR = 1
internal const val GCB_LF = 2
internal const val GCB_CONTROL = 3
internal const val GCB_EXTEND = 4
internal const val GCB_ZWJ = 5
internal const val GCB_REGIONAL_INDICATOR = 6
internal const val GCB_PREPEND = 7
internal const val GCB_SPACING_MARK = 8
internal const val GCB_L = 9
internal const val GCB_V = 10
internal const val GCB_T = 11
internal const val GCB_LV = 12
internal const val GCB_LVT = 13

// `Indic_Conjunct_Break` values, used by rule GB9c.
internal const val INCB_NONE = 0
internal const val INCB_LINKER = 1
internal const val INCB_CONSONANT = 2
internal const val INCB_EXTEND = 3

private const val WIDTH_MASK = 0x3
private const val GCB_SHIFT = 2
private const val GCB_MASK = 0xF
private const val INCB_SHIFT = 6
private const val INCB_MASK = 0x3

/** `Extended_Pictographic`, used by rule GB11 and ZWJ cluster width. */
private const val EXT_PICT = 1 shl 8

/** Has an emoji variation sequence, so VS15 and VS16 after it change its presentation. */
private const val VS_BASE = 1 shl 9

internal const val MAX_CODE_POINT = 0x10FFFF
private const val REPLACEMENT_CHARACTER = 0xFFFD

internal fun widthClass(props: Int): Int = props and WIDTH_MASK

internal fun gcb(props: Int): Int = (props shr GCB_SHIFT) and GCB_MASK

internal fun incb(props: Int): Int = (props shr INCB_SHIFT) and INCB_MASK

internal fun isExtendedPictographic(props: Int): Boolean = props and EXT_PICT != 0

internal fun isVariationBase(props: Int): Boolean = props and VS_BASE != 0

/**
 * The packed properties of [cp] in [version]'s tables. A value outside the code space gets the
 * properties of U+FFFD, which is what a decoder puts in its place, so the function stays total.
 */
internal fun lookup(
    cp: Int,
    version: UnicodeVersion,
): Int =
    when (version) {
        UnicodeVersion.V18 -> lookupLatest(if (cp in 0..MAX_CODE_POINT) cp else REPLACEMENT_CHARACTER)
    }

private const val LOW_MASK = (1 shl LOW_BITS) - 1
private const val MID_MASK = (1 shl MID_BITS) - 1

// Indexing cannot go out of bounds: the generator sizes TOP for every code point, and
// PropsTest walks all of them.
private fun lookupLatest(cp: Int): Int {
    val mid = TOP[cp shr (LOW_BITS + MID_BITS)].code
    val leaf = MID[(mid shl MID_BITS) or ((cp shr LOW_BITS) and MID_MASK)].code
    return PROPS[LEAF[(leaf shl LOW_BITS) or (cp and LOW_MASK)].code].code
}
