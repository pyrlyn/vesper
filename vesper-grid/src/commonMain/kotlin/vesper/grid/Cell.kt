package vesper.grid

// The fixed 8-byte cell. Its own file because its size is the grid's memory budget: everything rare
// (multi-code-point clusters, colours, underline colour, hyperlinks) lives elsewhere and the cell
// holds ids. A cell is a Long, so a row is a LongArray with no object per cell.
//
// Wide characters follow foot and Alacritty: the head cell holds the text and the WIDE flag, the
// cell to its right is a SPACER with no text and the head's style (so its background paints). A
// wide character that does not fit at the end of a row leaves a LEADING_SPACER there and wraps;
// reflow (T11) drops that cell when the row is joined again.

/** Bytes per cell, the figure the scrollback memory in the bench is built on. */
public const val CELL_BYTES: Int = Long.SIZE_BYTES

private const val CONTENT_MASK = 0xFFFF_FFFFL
private const val STYLE_SHIFT = 32
private const val FLAGS_SHIFT = 48
private const val SHORT_MASK = 0xFFFF
private const val MAX_CODE_POINT = 0x10FFFF
private val SURROGATES = 0xD800..0xDFFF

/** Layout flags of a cell. SGR attributes live in the style, not here. */
public object CellFlags {
    /** Head of a two-cell character. */
    public const val WIDE: Int = 1

    /** Right half of a two-cell character; no text of its own. */
    public const val SPACER: Int = 1 shl 1

    /** Placeholder at the end of a row where a wide character did not fit. */
    public const val LEADING_SPACER: Int = 1 shl 2

    /** Protected from selective erase (DECSCA). */
    public const val PROTECTED: Int = 1 shl 3

    /** Every public flag. */
    public const val ALL: Int = WIDE or SPACER or LEADING_SPACER or PROTECTED
}

// Tag beside the public flags: the content is a cluster id, not a code point. Only the factories set
// it, so the content can never be misread.
private const val CLUSTER = 1 shl 15

/** Index of a [Style] in a [StyleTable]. Id 0 is the default style. */
public value class StyleId(
    public val value: Int,
) {
    init {
        require(value in 0..SHORT_MASK) { "style id $value does not fit 16 bits" }
    }

    public companion object {
        /** The default style; always present, never reclaimed. */
        public val DEFAULT: StyleId = StyleId(0)
    }
}

/** Index of a multi-code-point cluster in a [ClusterTable]. */
public value class ClusterId(
    public val value: Int,
) {
    init {
        require(value >= 0) { "negative cluster id $value" }
    }
}

/** One grid cell: content, style id and layout flags packed into a Long. */
public value class Cell internal constructor(
    internal val bits: Long,
) {
    /** The code point; 0 when the cell is empty or holds a cluster. */
    public val codePoint: Int get() = if (isCluster) 0 else (bits and CONTENT_MASK).toInt()

    /** Whether the cell holds an interned cluster; then [clusterId] names it. */
    public val isCluster: Boolean get() = rawFlags and CLUSTER != 0

    /** The cluster, valid only when [isCluster]. */
    public val clusterId: ClusterId get() = ClusterId((bits and CONTENT_MASK).toInt())

    /**
     * Nothing written or erased: no code point and no cluster. Distinct from a space so copying can
     * trim erased tails but keep typed spaces.
     */
    public val isEmpty: Boolean get() = bits and CONTENT_MASK == 0L && !isCluster

    public val style: StyleId get() = StyleId(((bits shr STYLE_SHIFT).toInt()) and SHORT_MASK)

    /** The layout flags, a combination of [CellFlags]. */
    public val flags: Int get() = rawFlags and CellFlags.ALL

    private val rawFlags: Int get() = (bits ushr FLAGS_SHIFT).toInt()

    public fun hasFlags(flags: Int): Boolean = this.flags and flags == flags

    /** This cell with [flags] added; bits outside [CellFlags.ALL] are ignored. */
    public fun withFlags(flags: Int): Cell = make(bits and CONTENT_MASK, style, rawFlags or (flags and CellFlags.ALL))

    /** This cell with [flags] cleared; bits outside [CellFlags.ALL] are ignored. */
    public fun withoutFlags(flags: Int): Cell = make(bits and CONTENT_MASK, style, rawFlags and (flags and CellFlags.ALL).inv())

    /** This cell drawn with another style. */
    public fun withStyle(style: StyleId): Cell = make(bits and CONTENT_MASK, style, rawFlags)

    override fun toString(): String = "Cell(cp=$codePoint, cluster=$isCluster, style=${style.value}, flags=$flags)"

    public companion object {
        /** Never written, default style: all bits zero. */
        public val EMPTY: Cell = Cell(0)

        /** Erased with [style]: what ED and EL leave behind with background colour erase. */
        public fun blank(style: StyleId): Cell = make(0, style, 0)

        /** One code point; 0 reads back as empty. */
        public fun char(
            codePoint: Int,
            style: StyleId = StyleId.DEFAULT,
        ): Cell {
            require(codePoint in 0..MAX_CODE_POINT && codePoint !in SURROGATES) { "not a scalar value: $codePoint" }
            return make(codePoint.toLong(), style, 0)
        }

        /** An interned cluster. */
        public fun cluster(
            id: ClusterId,
            style: StyleId = StyleId.DEFAULT,
        ): Cell = make(id.value.toLong(), style, CLUSTER)

        /** The right half of a wide character drawn with [style]. */
        public fun spacer(style: StyleId): Cell = make(0, style, CellFlags.SPACER)

        private fun make(
            content: Long,
            style: StyleId,
            flags: Int,
        ): Cell = Cell(content or (style.value.toLong() shl STYLE_SHIFT) or (flags.toLong() shl FLAGS_SHIFT))
    }
}
