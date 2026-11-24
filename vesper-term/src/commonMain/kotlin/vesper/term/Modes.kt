package vesper.term

import vesper.input.KeyModes
import vesper.input.KittyKeyboard
import vesper.input.ModifyOtherKeys
import vesper.input.MouseEncoding
import vesper.input.MouseModes
import vesper.input.MouseTracking

// Terminal modes: the flags SM, RM, DECSET and DECRST switch, and the state DECRQM reports.
// Setting by number lives here too, because switching some modes moves the cursor or changes
// screens.
//
// Side effects follow xterm's ctlseqs: DECOM homes the cursor both ways; resetting DECAWM drops a
// pending wrap (DEC STD 070 "last column flag"); resetting DECLRMM removes the left and right
// margins. Resetting any mouse tracking mode turns tracking off and resetting any encoding goes
// back to the default one, whichever was set: xterm and Ghostty do the same, so a program that
// leaves with the wrong reset still frees the mouse.

/** ANSI modes (SM, RM). */
private const val IRM = 4
private const val LNM = 20

/** DEC private modes (DECSET, DECRST). */
private const val DECCKM = 1
private const val X10_MOUSE = 9
private const val DECOM = 6
private const val DECAWM = 7
private const val DECTCEM = 25
private const val ALT_SCREEN = 47
private const val DECLRMM = 69
private const val NORMAL_MOUSE = 1000
private const val BUTTON_MOUSE = 1002
private const val ANY_MOUSE = 1003
private const val FOCUS_EVENTS = 1004
private const val UTF8_MOUSE = 1005
private const val SGR_MOUSE = 1006
private const val ALTERNATE_SCROLL = 1007
private const val URXVT_MOUSE = 1015
private const val SGR_PIXEL_MOUSE = 1016
private const val ALT_SCREEN_CLEAR = 1047
private const val SAVE_CURSOR = 1048
private const val ALT_SCREEN_SAVE_CURSOR = 1049
private const val BRACKETED_PASTE = 2004
private const val SYNCHRONIZED_OUTPUT = 2026
private const val GRAPHEME_CLUSTERS = 2027
private const val WIN32_INPUT = 9001

/** DECRQM answers (`Ps` of DECRPM). */
private const val NOT_RECOGNIZED = 0
private const val SET = 1
private const val RESET = 2

/** The mode flags, as a host or a renderer reads them. Only the terminal changes them. */
public class Modes internal constructor() {
    /** DECAWM (`?7`): printing past the last column wraps to the next line. */
    public var autowrap: Boolean = true
        internal set

    /** DECOM (`?6`): cursor addresses count from the top-left margin corner. */
    public var origin: Boolean = false
        internal set

    /** IRM (`4`): printing pushes the rest of the line right instead of overwriting. */
    public var insert: Boolean = false
        internal set

    /** LNM (`20`): a line feed also returns the carriage. */
    public var newline: Boolean = false
        internal set

    /** Mode `?2027`: a grapheme cluster takes the width of the whole cluster, not of its first code point. */
    public var graphemeClusters: Boolean = false
        internal set

    /** DECCKM (`?1`): cursor keys send application sequences. */
    public var cursorKeys: Boolean = false
        internal set

    /** DECKPAM and DECKPNM: the keypad sends application sequences. */
    public var keypadApp: Boolean = false
        internal set

    /** DECTCEM (`?25`): the cursor is shown. */
    public var cursorVisible: Boolean = true
        internal set

    /** DECLRMM (`?69`): DECSLRM sets left and right margins, and `CSI s` means DECSLRM. */
    public var leftRightMargins: Boolean = false
        internal set

    /** Mode `?1004`: the host reports focus in and out. */
    public var focusEvents: Boolean = false
        internal set

    /** Mouse tracking (`?9`, `?1000`–`?1003`) and its encoding (`?1005`, `?1006`, `?1015`, `?1016`). */
    public var mouse: MouseModes = MouseModes()
        internal set

    /** Mode `?1007`: on the alternate screen the wheel sends cursor keys while the mouse is not tracked. On at power-on, as in xterm. */
    public var alternateScroll: Boolean = true
        internal set

    /** Mode `?2004`: the host brackets pasted text. */
    public var bracketedPaste: Boolean = false
        internal set

    /** Mode `?2026`: the program is in the middle of a frame. */
    public var synchronizedOutput: Boolean = false
        internal set

    /** xterm's modifyOtherKeys, set by XTMODKEYS (`CSI > 4 ; level m`). */
    public var modifyOtherKeys: ModifyOtherKeys = ModifyOtherKeys.Off
        internal set

    /** Mode `?9001`: keys go out as win32-input-mode records, which ConPTY asks for. */
    public var win32Input: Boolean = false
        internal set

    /** The kitty keyboard flag stacks; only the program's sequences change them, so they stay internal. */
    internal val kitty = KittyKeyboard()

    /** What key encoding reads, with the kitty flags of the screen shown. */
    internal fun keyModes(alternateScreen: Boolean): KeyModes =
        KeyModes(
            applicationCursor = cursorKeys,
            applicationKeypad = keypadApp,
            modifyOtherKeys = modifyOtherKeys,
            kitty = kitty.stack(alternateScreen).current,
            win32Input = win32Input,
        )

    /** Back to the power-on values, for RIS. */
    internal fun reset() {
        autowrap = true
        origin = false
        insert = false
        newline = false
        graphemeClusters = false
        cursorKeys = false
        keypadApp = false
        cursorVisible = true
        leftRightMargins = false
        focusEvents = false
        mouse = MouseModes()
        alternateScroll = true
        bracketedPaste = false
        synchronizedOutput = false
        modifyOtherKeys = ModifyOtherKeys.Off
        win32Input = false
        kitty.clear()
    }
}

/** SM and RM. Unknown modes are ignored. */
internal fun State.setAnsiMode(
    code: Int,
    on: Boolean,
) {
    when (code) {
        IRM -> modes.insert = on
        LNM -> modes.newline = on
    }
}

/** DECSET and DECRST. Unknown modes are ignored. */
internal fun State.setDecMode(
    code: Int,
    on: Boolean,
) {
    when (code) {
        DECCKM -> {
            modes.cursorKeys = on
        }

        DECOM -> {
            modes.origin = on
            gotoOrigin(0, 0)
        }

        DECAWM -> {
            modes.autowrap = on
            if (!on) pendingWrap = false
        }

        DECTCEM -> {
            modes.cursorVisible = on
        }

        ALT_SCREEN -> {
            if (on) enterAltScreen(clear = false) else leaveAltScreen(clear = false)
        }

        DECLRMM -> {
            modes.leftRightMargins = on
            if (!on) {
                left = 0
                right = lastCol
            }
        }

        X10_MOUSE, NORMAL_MOUSE, BUTTON_MOUSE, ANY_MOUSE -> {
            modes.mouse = modes.mouse.copy(tracking = if (on) mouseTracking(code) else MouseTracking.Off)
        }

        UTF8_MOUSE, SGR_MOUSE, URXVT_MOUSE, SGR_PIXEL_MOUSE -> {
            modes.mouse = modes.mouse.copy(encoding = if (on) mouseEncoding(code) else MouseEncoding.Default)
        }

        ALTERNATE_SCROLL -> {
            modes.alternateScroll = on
        }

        FOCUS_EVENTS -> {
            modes.focusEvents = on
        }

        ALT_SCREEN_CLEAR -> {
            if (on) enterAltScreen(clear = false) else leaveAltScreen(clear = true)
        }

        SAVE_CURSOR -> {
            if (on) saveCursor() else restoreCursor()
        }

        ALT_SCREEN_SAVE_CURSOR -> {
            if (on) {
                if (!altActive) saveCursor()
                enterAltScreen(clear = true)
            } else if (altActive) {
                leaveAltScreen(clear = false)
                restoreCursor()
            }
        }

        BRACKETED_PASTE -> {
            modes.bracketedPaste = on
        }

        SYNCHRONIZED_OUTPUT -> {
            modes.synchronizedOutput = on
        }

        GRAPHEME_CLUSTERS -> {
            modes.graphemeClusters = on
        }

        WIN32_INPUT -> {
            modes.win32Input = on
        }
    }
}

private fun mouseTracking(code: Int): MouseTracking =
    when (code) {
        X10_MOUSE -> MouseTracking.X10
        NORMAL_MOUSE -> MouseTracking.Normal
        BUTTON_MOUSE -> MouseTracking.ButtonEvent
        else -> MouseTracking.AnyEvent
    }

private fun mouseEncoding(code: Int): MouseEncoding =
    when (code) {
        UTF8_MOUSE -> MouseEncoding.Utf8
        SGR_MOUSE -> MouseEncoding.Sgr
        URXVT_MOUSE -> MouseEncoding.Urxvt
        else -> MouseEncoding.SgrPixels
    }

private fun status(on: Boolean): Int = if (on) SET else RESET

/** The DECRQM answer for an ANSI mode. */
internal fun State.ansiModeStatus(code: Int): Int =
    when (code) {
        IRM -> status(modes.insert)
        LNM -> status(modes.newline)
        else -> NOT_RECOGNIZED
    }

/** The DECRQM answer for a DEC private mode. `?1048` has no state of its own and reads as reset, as in xterm. */
internal fun State.decModeStatus(code: Int): Int =
    when (code) {
        DECCKM -> status(modes.cursorKeys)
        DECOM -> status(modes.origin)
        DECAWM -> status(modes.autowrap)
        DECTCEM -> status(modes.cursorVisible)
        ALT_SCREEN, ALT_SCREEN_CLEAR, ALT_SCREEN_SAVE_CURSOR -> status(altActive)
        DECLRMM -> status(modes.leftRightMargins)
        X10_MOUSE, NORMAL_MOUSE, BUTTON_MOUSE, ANY_MOUSE -> status(modes.mouse.tracking == mouseTracking(code))
        UTF8_MOUSE, SGR_MOUSE, URXVT_MOUSE, SGR_PIXEL_MOUSE -> status(modes.mouse.encoding == mouseEncoding(code))
        ALTERNATE_SCROLL -> status(modes.alternateScroll)
        FOCUS_EVENTS -> status(modes.focusEvents)
        SAVE_CURSOR -> RESET
        BRACKETED_PASTE -> status(modes.bracketedPaste)
        SYNCHRONIZED_OUTPUT -> status(modes.synchronizedOutput)
        GRAPHEME_CLUSTERS -> status(modes.graphemeClusters)
        WIN32_INPUT -> status(modes.win32Input)
        else -> NOT_RECOGNIZED
    }
