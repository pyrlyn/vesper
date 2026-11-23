package vesper.grid

// Interned grapheme clusters: the text of cells holding more than one code point. Separate because
// a single code point lives inline in the cell (foot's design, docs/research/foot-contour.md §1.6),
// so only the rare multi-code-point cluster pays for a table entry.

/** Live clusters at most; each holds at most [MAX_CLUSTER_CHARS] code points, so a full table stays under a few MiB. */
public const val MAX_CLUSTERS: Int = 1 shl 16

/**
 * Code points one cell may hold. Stream-safe text (UAX #15) never needs more than 31; kitty caps at
 * 24 and Contour at 16, which cut real ZWJ emoji with skin tones and tag sequences more often.
 */
public const val MAX_CLUSTER_CHARS: Int = 32

/** Distinct multi-code-point clusters, at most [cap] (clamped to [MAX_CLUSTERS]) live at once. */
public class ClusterTable(
    cap: Int = MAX_CLUSTERS,
) {
    private val table = Interner<String>(cap.coerceIn(1, MAX_CLUSTERS), emptyList())

    /** The id of [text], adding it when new. [text] must hold two to [MAX_CLUSTER_CHARS] code points. */
    public fun intern(text: String): ClusterId {
        val id = tryIntern(text)
        if (id == FULL) throw GridException.ClusterTableFull(cap)
        return ClusterId(id)
    }

    /** The id of [text], or [FULL]; a text of the wrong length throws. */
    internal fun tryIntern(text: String): Int {
        // A low surrogate continues the code point before it; an unpaired one only makes the
        // count err towards the cap.
        val length = text.count { !it.isLowSurrogate() }
        if (length < 2) throw GridException.ClusterTooShort()
        if (length > MAX_CLUSTER_CHARS) throw GridException.ClusterTooLong(length, MAX_CLUSTER_CHARS)
        return table.intern(text)
    }

    /** The text behind [id]; null for an id that was reclaimed. */
    public operator fun get(id: ClusterId): String? = table[id.value]

    public val size: Int get() = table.size

    public val cap: Int get() = table.cap

    /** Clusters added since the last sweep. */
    public val insertsSinceSweep: Int get() = table.insertsSinceSweep

    /** A blank mark set for [sweep]. */
    public fun marks(): Marks = table.marks()

    /**
     * Reclaims every id [live] does not mark and returns how many. Ids held outside the marked rows
     * are invalid afterwards. Marks made before the latest intern reclaim nothing.
     */
    public fun sweep(live: Marks): Int = table.sweep(live)
}
