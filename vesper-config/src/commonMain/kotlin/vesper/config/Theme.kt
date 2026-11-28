package vesper.config

private const val RGB_MASK = 0xFF_FFFF
private const val RGB_DIGITS = 6
private const val HEX = 16

/** A colour as `0xRRGGBB`, written `#rrggbb` in the file. */
public value class Rgb private constructor(
    public val value: Int,
) {
    override fun toString(): String = "#" + value.toString(HEX).padStart(RGB_DIGITS, '0')

    public companion object {
        /** The colour `0xRRGGBB`; the bits above 24 are dropped. */
        public fun of(rgb: Int): Rgb = Rgb(rgb and RGB_MASK)

        /** `#rrggbb`, or `null` for anything else. */
        public fun parse(text: String): Rgb? {
            val digits = text.removePrefix("#")
            // toInt(16) would also take a sign, so the digits are checked first.
            if (digits.length != RGB_DIGITS || digits.length == text.length || !digits.all { it.isHexDigit() }) return null
            return Rgb(digits.toInt(HEX))
        }

        private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}

/** The colours a host paints with: default text, background, cursor and palette entries 0 to 15. */
public data class Theme(
    val foreground: Rgb,
    val background: Rgb,
    val cursor: Rgb,
    /** Black, red, green, yellow, blue, magenta, cyan, white, then the bright eight. */
    val ansi: List<Rgb>,
)

/** The built-in colour schemes; [id] is how the file spells each one. */
public enum class Scheme(
    public val id: String,
) {
    /** Light text on near-black; the xterm palette. */
    VesperDark("vesper-dark"),

    /** Dark text on near-white. */
    VesperLight("vesper-light"),

    /** Ethan Schoonover's Solarized, dark variant. */
    SolarizedDark("solarized-dark"),
    ;

    /** The colours of the scheme. */
    public val theme: Theme
        get() =
            when (this) {
                VesperDark -> theme(0xE5E5E5, 0x141414, 0xE5E5E5, XTERM)
                VesperLight -> theme(0x2B2B2B, 0xFAFAFA, 0x2B2B2B, LIGHT)
                SolarizedDark -> theme(0x839496, 0x002B36, 0x93A1A1, SOLARIZED)
            }

    public companion object {
        /** The scheme the file names `id`, or `null`. */
        public fun byId(id: String): Scheme? = entries.firstOrNull { it.id == id }
    }
}

private fun theme(
    foreground: Int,
    background: Int,
    cursor: Int,
    ansi: IntArray,
) = Theme(Rgb.of(foreground), Rgb.of(background), Rgb.of(cursor), ansi.map(Rgb::of))

// xterm's own values, which programs tuned their indexed colours against.
private val XTERM =
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

// Darker than xterm's: yellow and white would vanish on a near-white ground.
private val LIGHT =
    intArrayOf(
        0x1F1F1F,
        0xB3261E,
        0x1E7B34,
        0x8A6100,
        0x1D4ED8,
        0x9333EA,
        0x0E7490,
        0xBDBDBD,
        0x5C5C5C,
        0xD92D20,
        0x2E9E4A,
        0xA67C00,
        0x3B6EF0,
        0xA855F7,
        0x1593B0,
        0xF5F5F5,
    )

private val SOLARIZED =
    intArrayOf(
        0x073642,
        0xDC322F,
        0x859900,
        0xB58900,
        0x268BD2,
        0xD33682,
        0x2AA198,
        0xEEE8D5,
        0x002B36,
        0xCB4B16,
        0x586E75,
        0x657B83,
        0x839496,
        0x6C71C4,
        0x93A1A1,
        0xFDF6E3,
    )
