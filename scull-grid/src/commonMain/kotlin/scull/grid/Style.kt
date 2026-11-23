package scull.grid

// SGR styles and their interning table. Separate from the cell so a cell carries a 16-bit id while
// colours, underline colour and attributes live once per distinct style: underline colour is the
// rare data that would otherwise widen every cell.

/** Live styles at most, the default included: every id fits the cell's 16 bits. */
public const val MAX_STYLES: Int = 1 shl 16

private const val KIND_SHIFT = 24
private const val INDEXED = 1
private const val RGB = 2
private const val CHANNEL_MASK = 0xFF
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8

/**
 * A colour as the application named it. The palette resolves it later, so a palette change (OSC 4)
 * repaints without touching the grid.
 */
public value class Color private constructor(
    private val packed: Int,
) {
    /** The terminal's default foreground, background or underline colour. */
    public val isDefault: Boolean get() = packed == 0

    /** One of the 256 palette entries; [index] names it. */
    public val isIndexed: Boolean get() = packed ushr KIND_SHIFT == INDEXED

    /** A direct colour (SGR 38, 48 or 58 with `2`); [red], [green] and [blue] give it. */
    public val isRgb: Boolean get() = packed ushr KIND_SHIFT == RGB

    public val index: Int get() = packed and CHANNEL_MASK
    public val red: Int get() = (packed shr RED_SHIFT) and CHANNEL_MASK
    public val green: Int get() = (packed shr GREEN_SHIFT) and CHANNEL_MASK
    public val blue: Int get() = packed and CHANNEL_MASK

    override fun toString(): String =
        when {
            isIndexed -> "Color.indexed($index)"
            isRgb -> "Color.rgb($red, $green, $blue)"
            else -> "Color.DEFAULT"
        }

    public companion object {
        public val DEFAULT: Color = Color(0)

        public fun indexed(index: Int): Color {
            require(index in 0..CHANNEL_MASK) { "palette index $index" }
            return Color((INDEXED shl KIND_SHIFT) or index)
        }

        public fun rgb(
            red: Int,
            green: Int,
            blue: Int,
        ): Color {
            require(red in 0..CHANNEL_MASK && green in 0..CHANNEL_MASK && blue in 0..CHANNEL_MASK) { "rgb($red, $green, $blue)" }
            return Color((RGB shl KIND_SHIFT) or (red shl RED_SHIFT) or (green shl GREEN_SHIFT) or blue)
        }
    }
}

/** Underline shape (SGR 4 with a sub-parameter, SGR 21). */
public enum class Underline { None, Single, Double, Curly, Dotted, Dashed }

/** SGR on/off attributes, combined into [Style.attrs]. */
public object Attrs {
    public const val BOLD: Int = 1

    public const val DIM: Int = 1 shl 1

    public const val ITALIC: Int = 1 shl 2

    /** SGR 5 and 6. */
    public const val BLINK: Int = 1 shl 3

    public const val INVERSE: Int = 1 shl 4

    public const val HIDDEN: Int = 1 shl 5

    public const val STRIKE: Int = 1 shl 6

    /** SGR 53. */
    public const val OVERLINE: Int = 1 shl 7
}

/** Everything SGR can say about a cell. */
public data class Style(
    public val fg: Color = Color.DEFAULT,
    public val bg: Color = Color.DEFAULT,
    /** Underline colour (SGR 58); default follows the foreground. */
    public val underlineColor: Color = Color.DEFAULT,
    public val underline: Underline = Underline.None,
    /** A combination of [Attrs]. */
    public val attrs: Int = 0,
) {
    public companion object {
        public val DEFAULT: Style = Style()
    }
}

/** Distinct styles, at most [cap] (clamped to [MAX_STYLES]) live at once. */
public class StyleTable(
    cap: Int = MAX_STYLES,
) {
    private val table = Interner(cap.coerceIn(1, MAX_STYLES), listOf(Style.DEFAULT))

    /** The id of [style], adding it when new. */
    public fun intern(style: Style): StyleId {
        val id = tryIntern(style)
        if (id == FULL) throw GridException.StyleTableFull(cap)
        return StyleId(id)
    }

    /** The id of [style], or [FULL]; ids stay below [cap], so they fit 16 bits. */
    internal fun tryIntern(style: Style): Int = table.intern(style)

    /** The style behind [id]; null for an id that was reclaimed. */
    public operator fun get(id: StyleId): Style? = table[id.value]

    /** Live styles, the default included. */
    public val size: Int get() = table.size

    public val cap: Int get() = table.cap

    /** Styles added since the last sweep. */
    public val insertsSinceSweep: Int get() = table.insertsSinceSweep

    /** A blank mark set for [sweep]. */
    public fun marks(): Marks = table.marks()

    /**
     * Reclaims every id [live] does not mark and returns how many. Ids held outside the marked rows
     * are invalid afterwards. Marks made before the latest intern reclaim nothing.
     */
    public fun sweep(live: Marks): Int = table.sweep(live)
}
