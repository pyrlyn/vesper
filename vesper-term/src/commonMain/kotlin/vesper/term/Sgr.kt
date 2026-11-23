package vesper.term

import vesper.grid.Attrs
import vesper.grid.Color
import vesper.grid.Style
import vesper.grid.Underline
import vesper.parser.Params

// SGR (Select Graphic Rendition, `CSI ... m`): parameters to a Style. Separate because it is a
// pure function of the parameters, tested without a grid.
//
// Codes follow xterm's ctlseqs ("CSI Pm m"). Extended colours accept both the ITU T.416 colon form
// (`38:2::r:g:b`, `38:2:r:g:b`, `38:5:n`) and the xterm semicolon form (`38;2;r;g;b`, `38;5;n`).
// Underline shapes use the colon sub-parameter `4:n` that kitty introduced and xterm, foot, WezTerm
// and Contour accept. An unknown code or an out-of-range colour is ignored, never clamped into
// another colour.

private const val RESET = 0
private const val BOLD = 1
private const val DIM = 2
private const val ITALIC = 3
private const val UNDERLINE = 4
private const val BLINK_SLOW = 5
private const val BLINK_FAST = 6
private const val INVERSE = 7
private const val HIDDEN = 8
private const val STRIKE = 9
private const val DOUBLE_UNDERLINE = 21
private const val NORMAL_INTENSITY = 22
private const val NOT_ITALIC = 23
private const val NOT_UNDERLINED = 24
private const val NOT_BLINKING = 25
private const val NOT_INVERSE = 27
private const val NOT_HIDDEN = 28
private const val NOT_STRIKE = 29
private const val FG_FIRST = 30
private const val FG_LAST = 37
private const val FG_EXTENDED = 38
private const val FG_DEFAULT = 39
private const val BG_FIRST = 40
private const val BG_LAST = 47
private const val BG_EXTENDED = 48
private const val BG_DEFAULT = 49
private const val OVERLINE = 53
private const val NOT_OVERLINE = 55
private const val UNDERLINE_COLOR = 58
private const val UNDERLINE_COLOR_DEFAULT = 59
private const val BRIGHT_FG_FIRST = 90
private const val BRIGHT_FG_LAST = 97
private const val BRIGHT_BG_FIRST = 100
private const val BRIGHT_BG_LAST = 107

/** Palette index of the first bright colour (`90` and `100` map here). */
private const val BRIGHT_BASE = 8

/** Colour space selectors of an extended colour. */
private const val DIRECT = 2
private const val INDEXED = 5

/** Components of a direct colour; more sub-parameters than this after the `2` mean a colour space id comes first (`38:2:cs:r:g:b`). */
private const val RGB = 3
private const val MAX_BYTE = 255

/** A colour parameter that is not there. */
private const val MISSING = -1

/** `4:n` shapes, indexed by `n`. */
private val UNDERLINE_SHAPES = Underline.entries

/** `CSI params m` applied to [current]. No parameters means reset. */
internal fun applySgr(
    current: Style,
    params: Params,
): Style {
    if (params.isEmpty()) return Style.DEFAULT
    var fg = current.fg
    var bg = current.bg
    var underlineColor = current.underlineColor
    var underline = current.underline
    var attrs = current.attrs
    var at = 0
    while (at < params.size) {
        val code = params[at]
        val end = groupEnd(params, at)
        var next = end
        when (code) {
            RESET -> {
                fg = Color.DEFAULT
                bg = Color.DEFAULT
                underlineColor = Color.DEFAULT
                underline = Underline.None
                attrs = 0
            }

            BOLD -> {
                attrs = attrs or Attrs.BOLD
            }

            DIM -> {
                attrs = attrs or Attrs.DIM
            }

            ITALIC -> {
                attrs = attrs or Attrs.ITALIC
            }

            // `4` alone is a single underline; `4:n` picks the shape, and an unknown shape leaves the current one.
            UNDERLINE -> {
                underline = if (end - at == 1) Underline.Single else UNDERLINE_SHAPES.getOrNull(params[at + 1]) ?: underline
            }

            BLINK_SLOW, BLINK_FAST -> {
                attrs = attrs or Attrs.BLINK
            }

            INVERSE -> {
                attrs = attrs or Attrs.INVERSE
            }

            HIDDEN -> {
                attrs = attrs or Attrs.HIDDEN
            }

            STRIKE -> {
                attrs = attrs or Attrs.STRIKE
            }

            DOUBLE_UNDERLINE -> {
                underline = Underline.Double
            }

            NORMAL_INTENSITY -> {
                attrs = attrs and (Attrs.BOLD or Attrs.DIM).inv()
            }

            NOT_ITALIC -> {
                attrs = attrs and Attrs.ITALIC.inv()
            }

            NOT_UNDERLINED -> {
                underline = Underline.None
            }

            NOT_BLINKING -> {
                attrs = attrs and Attrs.BLINK.inv()
            }

            NOT_INVERSE -> {
                attrs = attrs and Attrs.INVERSE.inv()
            }

            NOT_HIDDEN -> {
                attrs = attrs and Attrs.HIDDEN.inv()
            }

            NOT_STRIKE -> {
                attrs = attrs and Attrs.STRIKE.inv()
            }

            in FG_FIRST..FG_LAST -> {
                fg = Color.indexed(code - FG_FIRST)
            }

            in BG_FIRST..BG_LAST -> {
                bg = Color.indexed(code - BG_FIRST)
            }

            in BRIGHT_FG_FIRST..BRIGHT_FG_LAST -> {
                fg = Color.indexed(code - BRIGHT_FG_FIRST + BRIGHT_BASE)
            }

            in BRIGHT_BG_FIRST..BRIGHT_BG_LAST -> {
                bg = Color.indexed(code - BRIGHT_BG_FIRST + BRIGHT_BASE)
            }

            FG_DEFAULT -> {
                fg = Color.DEFAULT
            }

            BG_DEFAULT -> {
                bg = Color.DEFAULT
            }

            UNDERLINE_COLOR_DEFAULT -> {
                underlineColor = Color.DEFAULT
            }

            OVERLINE -> {
                attrs = attrs or Attrs.OVERLINE
            }

            NOT_OVERLINE -> {
                attrs = attrs and Attrs.OVERLINE.inv()
            }

            FG_EXTENDED, BG_EXTENDED, UNDERLINE_COLOR -> {
                val color: Color
                if (end - at > 1) {
                    color = colonColor(params, at + 1, end)
                } else {
                    // The semicolon form: the colour takes the parameters that follow, also when
                    // they do not make a colour, so `38;5;300;1` is not read as bold after a bad colour.
                    next = semicolonEnd(params, end)
                    color = semicolonColor(params, end)
                }
                // An extended colour is never the default one, so that value marks "no colour".
                if (!color.isDefault) {
                    when (code) {
                        FG_EXTENDED -> fg = color
                        BG_EXTENDED -> bg = color
                        else -> underlineColor = color
                    }
                }
            }
        }
        at = next
    }
    return Style(fg, bg, underlineColor, underline, attrs)
}

/** The index after the parameter at [at] and its sub-parameters. */
private fun groupEnd(
    params: Params,
    at: Int,
): Int {
    var end = at + 1
    while (end < params.size && params.isSubParameter(end)) end++
    return end
}

/** The first value of the parameter at [at], or [MISSING] past the end. */
private fun valueAt(
    params: Params,
    at: Int,
): Int = if (at < params.size) params[at] else MISSING

/** `38:5:n`, `38:2:r:g:b` or `38:2:cs:r:g:b`: the sub-parameters are values [from] until [end]. */
private fun colonColor(
    params: Params,
    from: Int,
    end: Int,
): Color {
    val rest = end - from - 1
    return when (params[from]) {
        INDEXED -> if (rest >= 1) indexedOrDefault(params[from + 1]) else Color.DEFAULT
        DIRECT -> if (rest >= RGB) rgbOrDefault(params, if (rest > RGB) from + 2 else from + 1) else Color.DEFAULT
        else -> Color.DEFAULT
    }
}

/** How many following parameters `38;5;n` or `38;2;r;g;b` takes, as the index after them. */
private fun semicolonEnd(
    params: Params,
    from: Int,
): Int {
    val taken =
        when (valueAt(params, from)) {
            MISSING -> 0
            INDEXED -> 2
            DIRECT -> 1 + RGB
            else -> 1
        }
    var at = from
    repeat(taken) { if (at < params.size) at = groupEnd(params, at) }
    return at
}

/** The colour of `38;5;n` or `38;2;r;g;b`, whose selector is the parameter at [from]. */
private fun semicolonColor(
    params: Params,
    from: Int,
): Color {
    val first = if (from < params.size) groupEnd(params, from) else from
    return when (valueAt(params, from)) {
        INDEXED -> {
            indexedOrDefault(valueAt(params, first))
        }

        DIRECT -> {
            val second = if (first < params.size) groupEnd(params, first) else first
            val third = if (second < params.size) groupEnd(params, second) else second
            rgbOrDefault(valueAt(params, first), valueAt(params, second), valueAt(params, third))
        }

        else -> {
            Color.DEFAULT
        }
    }
}

private fun indexedOrDefault(n: Int): Color = if (n in 0..MAX_BYTE) Color.indexed(n) else Color.DEFAULT

private fun rgbOrDefault(
    params: Params,
    from: Int,
): Color = rgbOrDefault(params[from], params[from + 1], params[from + 2])

private fun rgbOrDefault(
    r: Int,
    g: Int,
    b: Int,
): Color = if (r in 0..MAX_BYTE && g in 0..MAX_BYTE && b in 0..MAX_BYTE) Color.rgb(r, g, b) else Color.DEFAULT
