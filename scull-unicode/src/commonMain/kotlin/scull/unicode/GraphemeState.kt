package scull.unicode

// Incremental extended-grapheme-cluster segmentation (UAX #29, Unicode 18.0.0, revision 49).
// Separate from width because the terminal feeds code points one at a time as they arrive from the
// PTY: the state here is the only memory of the cluster so far, and nothing looks ahead or back.

private const val NO_PREVIOUS = -1

// Where the current cluster stands with respect to rule GB11.
private const val EMOJI_NONE = 0

/** `\p{Extended_Pictographic} Extend*` just seen. */
private const val EMOJI_PICTOGRAPHIC = 1

/** `\p{Extended_Pictographic} Extend* ZWJ` just seen. */
private const val EMOJI_PICTOGRAPHIC_ZWJ = 2

/**
 * Grapheme-break state fed one code point at a time.
 *
 * [next] answers whether a cluster boundary falls *before* the code point; the first code point
 * always starts a cluster (GB1).
 */
public class GraphemeState(
    private val version: UnicodeVersion = UnicodeVersion.LATEST,
) {
    private var prev = NO_PREVIOUS

    /** An odd number of regional indicators ends the text so far (GB12, GB13). */
    private var oddRegionalIndicators = false

    /** `\p{InCB=Linker} \p{InCB=Extend}*` ends the text so far (GB9c). */
    private var afterLinker = false
    private var emoji = EMOJI_NONE

    /** Feeds [cp]; returns `true` when a cluster boundary falls before it. */
    public fun next(cp: Int): Boolean {
        val props = lookup(cp, version)
        val gcb = gcb(props)
        val incb = incb(props)
        val pictographic = isExtendedPictographic(props)
        val boundary = prev == NO_PREVIOUS || breaksBetween(prev, gcb, incb, pictographic)

        afterLinker =
            when (incb) {
                INCB_LINKER -> true
                INCB_EXTEND -> afterLinker
                else -> false
            }
        emoji =
            when {
                pictographic -> EMOJI_PICTOGRAPHIC
                gcb == GCB_EXTEND && emoji == EMOJI_PICTOGRAPHIC -> EMOJI_PICTOGRAPHIC
                gcb == GCB_ZWJ && emoji == EMOJI_PICTOGRAPHIC -> EMOJI_PICTOGRAPHIC_ZWJ
                else -> EMOJI_NONE
            }
        oddRegionalIndicators = gcb == GCB_REGIONAL_INDICATOR && !(prev == GCB_REGIONAL_INDICATOR && oddRegionalIndicators)
        prev = gcb
        return boundary
    }

    /** Rules GB3 to GB999, in the order UAX #29 applies them. */
    private fun breaksBetween(
        prev: Int,
        next: Int,
        incb: Int,
        pictographic: Boolean,
    ): Boolean =
        when {
            prev == GCB_CR && next == GCB_LF -> false
            isControl(prev) || isControl(next) -> true
            prev == GCB_L && (next == GCB_L || next == GCB_V || next == GCB_LV || next == GCB_LVT) -> false
            (prev == GCB_LV || prev == GCB_V) && (next == GCB_V || next == GCB_T) -> false
            (prev == GCB_LVT || prev == GCB_T) && next == GCB_T -> false
            next == GCB_EXTEND || next == GCB_ZWJ || next == GCB_SPACING_MARK || prev == GCB_PREPEND -> false
            afterLinker && incb == INCB_CONSONANT -> false
            emoji == EMOJI_PICTOGRAPHIC_ZWJ && pictographic -> false
            prev == GCB_REGIONAL_INDICATOR && next == GCB_REGIONAL_INDICATOR -> !oddRegionalIndicators
            else -> true
        }

    private fun isControl(gcb: Int): Boolean = gcb == GCB_CONTROL || gcb == GCB_CR || gcb == GCB_LF
}
