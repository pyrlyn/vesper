package vesper.input

// Mouse reports: xterm's tracking modes (X10 9, normal 1000, button-event 1002, any-event 1003)
// in each coordinate encoding (default, UTF-8 1005, SGR 1006, urxvt 1015, SGR-pixels 1016).
// Separate from the key encoders because it shares nothing with them but the CSI introducer.
//
// Follows xterm's ctlseqs "Mouse Tracking" section and, for the cases it leaves open (clamping,
// the past-end marker, release codes), xterm's own `button.c` (EditorButton, BtnCode,
// EmitMousePosition): https://invisible-island.net/xterm/ctlseqs/ctlseqs.html#h2-Mouse-Tracking.
// Ported from the earlier Rust core (`mouse.rs` of its `input` crate).

/** Mouse buttons. [index] is xterm's button index, whose low two bits, bit 2 (+64) and bit 3 (+128) make the button code. */
public enum class MouseButton(
    public val index: Int,
) {
    Left(0),
    Middle(1),
    Right(2),
    WheelUp(4),
    WheelDown(5),
    WheelLeft(6),
    WheelRight(7),
    Back(8),
    Forward(9),
    Button10(10),
    Button11(11),
    ;

    internal val isWheel: Boolean get() = this in WheelUp..WheelRight
}

/** What the pointer did. */
public enum class MouseAction {
    /** A button went down; wheel steps are presses. */
    Press,

    /** A button went up. */
    Release,

    /** The pointer moved to another cell (or pixel in SGR-pixel mode); the caller drops moves within the same position, as xterm does. */
    Motion,
}

/**
 * One mouse event.
 *
 * @param button the button pressed or released; for motion, the lowest button held, or null when none is.
 * @param mods Shift, Alt and Ctrl are reported; other modifiers are ignored.
 * @param col zero-based cell column.
 * @param row zero-based cell row.
 * @param xPx zero-based pixel x inside the text area, for SGR-pixel mode.
 * @param yPx zero-based pixel y inside the text area, for SGR-pixel mode.
 * @throws IllegalArgumentException when a position is negative.
 */
public data class MouseEvent(
    public val action: MouseAction,
    public val button: MouseButton?,
    public val mods: Modifiers = Modifiers.NONE,
    public val col: Int,
    public val row: Int,
    public val xPx: Int = 0,
    public val yPx: Int = 0,
) {
    init {
        require(col >= 0 && row >= 0 && xPx >= 0 && yPx >= 0) { "negative mouse position" }
    }
}

/** Which events a program asked for (DECSET 9, 1000, 1002, 1003). */
public enum class MouseTracking {
    /** No reports. */
    Off,

    /** Mode 9: presses of the three main buttons, no modifiers. */
    X10,

    /** Mode 1000: presses and releases. */
    Normal,

    /** Mode 1002: also motion while a button is held. */
    ButtonEvent,

    /** Mode 1003: all motion. */
    AnyEvent,
}

/** How a report is spelled (DECSET 1005, 1006, 1015, 1016). */
public enum class MouseEncoding {
    /** `CSI M Cb Cx Cy`, each value + 32 in one byte. */
    Default,

    /** Mode 1005: as default, values past 95 as UTF-8. */
    Utf8,

    /** Mode 1006: `CSI < Cb ; Cx ; Cy M|m`. */
    Sgr,

    /** Mode 1015: `CSI Cb ; Cx ; Cy M`, Cb + 32 in decimal. */
    Urxvt,

    /** Mode 1016: SGR with pixel coordinates. */
    SgrPixels,
}

/** The mouse modes of the terminal. */
public data class MouseModes(
    public val tracking: MouseTracking = MouseTracking.Off,
    public val encoding: MouseEncoding = MouseEncoding.Default,
)

/** Offset that makes X10-style values printable. */
private const val PRINTABLE_OFFSET = 32

/** Largest zero-based coordinate the default encoding carries; xterm clamps to it and sends it as 0, its past-end marker (255 - 32). */
private const val DEFAULT_LIMIT = 223

/** The same for UTF-8 encoding, whose values stop at U+07FF (2047 - 32). */
private const val UTF8_LIMIT = 2015

/** Byte sent for a coordinate at or past the limit. */
private const val PAST_END = 0

/** Button code of a release when the encoding cannot say which button. */
private const val RELEASE_CODE = 3
private const val MOTION_FLAG = 32

// Modifier bits of the button code.
private const val SHIFT_FLAG = 4
private const val META_FLAG = 8
private const val CTRL_FLAG = 16

// Button index bits beyond the low two, and what they add to the code.
private const val BUTTON_LOW_BITS = 0b11
private const val WHEEL_BIT = 0b100
private const val WHEEL_FLAG = 64
private const val EXTRA_BIT = 0b1000
private const val EXTRA_FLAG = 128

private const val FINAL_PRESS = 'M'.code
private const val FINAL_RELEASE = 'm'.code
private const val SGR_MARKER = '<'.code

/** Appends the report for [event], or nothing when [modes] do not ask for it. */
public fun encodeMouse(
    event: MouseEvent,
    modes: MouseModes,
    out: ByteSink,
) {
    val sgr = modes.encoding == MouseEncoding.Sgr || modes.encoding == MouseEncoding.SgrPixels
    val code = buttonCode(event, modes.tracking, sgr) ?: return
    val pixels = modes.encoding == MouseEncoding.SgrPixels
    val x = if (pixels) event.xPx else event.col
    val y = if (pixels) event.yPx else event.row
    out.csi()
    when (modes.encoding) {
        MouseEncoding.Default, MouseEncoding.Utf8 -> {
            val utf8 = modes.encoding == MouseEncoding.Utf8
            val limit = if (utf8) UTF8_LIMIT else DEFAULT_LIMIT
            out.push(FINAL_PRESS)
            out.x10Value(code + PRINTABLE_OFFSET, utf8)
            for (v in intArrayOf(x, y)) {
                if (v >= limit) out.push(PAST_END) else out.x10Value(v + 1 + PRINTABLE_OFFSET, utf8)
            }
        }

        MouseEncoding.Sgr, MouseEncoding.SgrPixels, MouseEncoding.Urxvt -> {
            if (sgr) {
                out.push(SGR_MARKER)
                out.decimal(code)
            } else {
                out.decimal(code + PRINTABLE_OFFSET)
            }
            for (v in intArrayOf(x, y)) {
                out.push(SEMI)
                out.decimal(if (v == Int.MAX_VALUE) v else v + 1)
            }
            out.push(if (sgr && event.action == MouseAction.Release) FINAL_RELEASE else FINAL_PRESS)
        }
    }
}

/** One X10-style value: a raw byte, or in UTF-8 encoding a code point, which takes two bytes past 127. */
private fun ByteSink.x10Value(
    value: Int,
    utf8: Boolean,
) {
    if (utf8) codePoint(value) else push(value)
}

/** The button code before encoding, or null when the tracking mode does not report the event. */
private fun buttonCode(
    event: MouseEvent,
    tracking: MouseTracking,
    sgr: Boolean,
): Int? {
    val button = event.button
    val reported =
        when {
            tracking == MouseTracking.Off -> false

            tracking == MouseTracking.X10 -> event.action == MouseAction.Press && button != null && button.index <= MouseButton.Right.index

            // Wheel steps have no release.
            event.action == MouseAction.Press -> button != null

            event.action == MouseAction.Release -> button != null && !button.isWheel

            tracking == MouseTracking.Normal -> false

            tracking == MouseTracking.ButtonEvent -> button != null

            else -> true
        }
    if (!reported) return null
    // SGR keeps the released button and marks the release with `m`; the older encodings can only
    // say "a button was released".
    val shown = button?.takeIf { sgr || event.action != MouseAction.Release }
    var code =
        if (shown == null) {
            RELEASE_CODE
        } else {
            (shown.index and BUTTON_LOW_BITS) +
                (if (shown.index and WHEEL_BIT != 0) WHEEL_FLAG else 0) +
                (if (shown.index and EXTRA_BIT != 0) EXTRA_FLAG else 0)
        }
    if (event.action == MouseAction.Motion) code += MOTION_FLAG
    // X10 mode predates modifier reporting.
    if (tracking != MouseTracking.X10) {
        if (Modifiers.SHIFT in event.mods) code += SHIFT_FLAG
        if (Modifiers.ALT in event.mods) code += META_FLAG
        if (Modifiers.CTRL in event.mods) code += CTRL_FLAG
    }
    return code
}
