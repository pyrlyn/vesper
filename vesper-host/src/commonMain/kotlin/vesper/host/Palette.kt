package vesper.host

import vesper.grid.Attrs
import vesper.grid.Color
import vesper.grid.Style
import vesper.term.FrameCell

private const val CHANNEL = 0xFF
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val GREY_RAMP = 24
private const val GREY_FIRST = 8
private const val GREY_STEP = 10

// xterm's 16 system colours.
private val ANSI =
    intArrayOf(
        0x000000,
        0xCD0000,
        0x00CD00,
        0xCDCD00,
        0x0000EE,
        0xCD00CD,
        0x00CDCD,
        0xE5E5E5,
        0x7F7F7F,
        0xFF0000,
        0x00FF00,
        0xFFFF00,
        0x5C5CFF,
        0xFF00FF,
        0x00FFFF,
        0xFFFFFF,
    )

// xterm's cube levels (256colres.pl), which most terminals copy, so indexed colours match what
// programs were tuned against.
private val CUBE_LEVELS = intArrayOf(0, 95, 135, 175, 215, 255)

/** Foreground and background of a cell as 0xRRGGBB. */
public data class CellColors(
    public val fg: Int,
    public val bg: Int,
)

/** Colours as the core names them (default, palette index or RGB) resolved to sRGB, xterm's palette until themes arrive (T20). */
public class Palette(
    public val foreground: Int = 0xE5E5E5,
    public val background: Int = 0x141414,
    public val cursor: Int = 0xE5E5E5,
    // Dark enough that the default foreground stays readable on each.
    public val selection: Int = 0x264F78,
    public val match: Int = 0x5A4A14,
    public val currentMatch: Int = 0x9A6700,
) {
    private val indexed =
        IntArray(256).also { table ->
            ANSI.copyInto(table)
            var at = ANSI.size
            for (r in CUBE_LEVELS) for (g in CUBE_LEVELS) for (b in CUBE_LEVELS) table[at++] = rgb(r, g, b)
            for (i in 0 until GREY_RAMP) {
                val level = GREY_FIRST + GREY_STEP * i
                table[at++] = rgb(level, level, level)
            }
        }

    /** The sRGB value of [color], [fallback] for the default. */
    public fun resolve(
        color: Color,
        fallback: Int,
    ): Int =
        when {
            color.isIndexed -> indexed[color.index]
            color.isRgb -> rgb(color.red, color.green, color.blue)
            else -> fallback
        }

    /**
     * What a cell of [style] shows: reverse video swaps the two, dim halves the way to the background.
     * A highlight in [flags] ([FrameCell] bits) takes the background: the current match first, then
     * the selection, then any match.
     */
    public fun colors(
        style: Style,
        flags: Int = 0,
    ): CellColors {
        var fg = resolve(style.fg, foreground)
        var bg = resolve(style.bg, background)
        if (style.attrs and Attrs.INVERSE != 0) fg = bg.also { bg = fg }
        if (style.attrs and Attrs.DIM != 0) fg = blend(fg, bg)
        return CellColors(fg, highlight(flags) ?: bg)
    }

    /** The background a highlight in [flags] paints, or null for none. */
    public fun highlight(flags: Int): Int? =
        when {
            flags and FrameCell.CURRENT_MATCH != 0 -> currentMatch
            flags and FrameCell.SELECTED != 0 -> selection
            flags and FrameCell.MATCH != 0 -> match
            else -> null
        }
}

public fun rgb(
    red: Int,
    green: Int,
    blue: Int,
): Int = (red shl RED_SHIFT) or (green shl GREEN_SHIFT) or blue

public fun red(color: Int): Int = (color shr RED_SHIFT) and CHANNEL

public fun green(color: Int): Int = (color shr GREEN_SHIFT) and CHANNEL

public fun blue(color: Int): Int = color and CHANNEL

/** The colour halfway between [a] and [b]. */
public fun blend(
    a: Int,
    b: Int,
): Int = rgb((red(a) + red(b)) / 2, (green(a) + green(b)) / 2, (blue(a) + blue(b)) / 2)
