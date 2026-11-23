package vesper.macos

import vesper.grid.Attrs
import vesper.grid.Color
import vesper.grid.Style

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
internal data class CellColors(
    val fg: Int,
    val bg: Int,
)

/** Colours as the core names them (default, palette index or RGB) resolved to sRGB, xterm's palette until themes arrive (T20). */
internal class Palette(
    val foreground: Int = 0xE5E5E5,
    val background: Int = 0x141414,
    val cursor: Int = 0xE5E5E5,
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
    fun resolve(
        color: Color,
        fallback: Int,
    ): Int =
        when {
            color.isIndexed -> indexed[color.index]
            color.isRgb -> rgb(color.red, color.green, color.blue)
            else -> fallback
        }

    /** What a cell of [style] shows: reverse video swaps the two, dim halves the way to the background. */
    fun colors(style: Style): CellColors {
        var fg = resolve(style.fg, foreground)
        var bg = resolve(style.bg, background)
        if (style.attrs and Attrs.INVERSE != 0) fg = bg.also { bg = fg }
        if (style.attrs and Attrs.DIM != 0) fg = blend(fg, bg)
        return CellColors(fg, bg)
    }
}

internal fun rgb(
    red: Int,
    green: Int,
    blue: Int,
): Int = (red shl RED_SHIFT) or (green shl GREEN_SHIFT) or blue

internal fun red(color: Int): Int = (color shr RED_SHIFT) and CHANNEL

internal fun green(color: Int): Int = (color shr GREEN_SHIFT) and CHANNEL

internal fun blue(color: Int): Int = color and CHANNEL

/** The colour halfway between [a] and [b]. */
internal fun blend(
    a: Int,
    b: Int,
): Int = rgb((red(a) + red(b)) / 2, (green(a) + green(b)) / 2, (blue(a) + blue(b)) / 2)
