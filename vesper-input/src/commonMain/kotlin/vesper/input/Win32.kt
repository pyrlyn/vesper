package vesper.input

// win32-input-mode (`CSI ? 9001 h`): every key event, down and up, as the fields of a Windows
// KEY_EVENT_RECORD, `CSI Vk ; Sc ; Uc ; Kd ; Cs ; Rc _`. ConPTY sets the mode so Windows console
// programs get the input records they expect. Separate because it ignores every other key mode:
// ConPTY rebuilds VT sequences from the record itself.
// https://github.com/microsoft/terminal/blob/main/doc/specs/%234999%20-%20Improved%20keyboard%20handling%20in%20Conpty.md
//
// The virtual-key and scan codes are derived from Key on the US layout, so the encoder stays
// platform-neutral. Virtual keys: https://learn.microsoft.com/windows/win32/inputdev/virtual-key-codes;
// scan codes are PC/AT set 1. Ported from the earlier Rust core (`win32.rs` of its `input` crate).

// dwControlKeyState bits (wincon.h). Left and right are not tracked in Modifiers, so held
// modifiers report as the left key.
private const val LEFT_ALT_PRESSED = 0x0002
private const val LEFT_CTRL_PRESSED = 0x0008
private const val SHIFT_PRESSED = 0x0010
private const val NUMLOCK_ON = 0x0020
private const val CAPSLOCK_ON = 0x0080
private const val ENHANCED_KEY = 0x0100

/** Final byte of a win32-input-mode record. */
private const val FINAL = '_'.code

/** Each record stands for one keystroke. */
private const val REPEAT_COUNT = 1

/** A key's Windows identity: virtual key, scan code and whether it is an extended (E0-prefixed) key. */
internal data class Win32Key(
    val vk: Int,
    val scan: Int,
    val enhanced: Boolean = false,
)

private val UNKNOWN = Win32Key(0, 0)

private fun ext(
    vk: Int,
    scan: Int,
) = Win32Key(vk, scan, enhanced = true)

/** Main-block rows of scan code set 1: first code and the US keys in order. */
private val SCAN_ROWS =
    listOf(
        0x02 to "1234567890-=",
        0x10 to "qwertyuiop[]",
        0x1E to "asdfghjkl;'`",
        0x2C to "zxcvbnm,./",
    )
private const val SCAN_BACKSLASH = 0x2B
private const val SCAN_SPACE = 0x39

/** VK_OEM_* codes of the US punctuation keys. */
private val OEM_VKS =
    mapOf(
        ';' to 0xBA,
        '=' to 0xBB,
        ',' to 0xBC,
        '-' to 0xBD,
        '.' to 0xBE,
        '/' to 0xBF,
        '`' to 0xC0,
        '[' to 0xDB,
        '\\' to 0xDC,
        ']' to 0xDD,
        '\'' to 0xDE,
    )

/** Keypad digit scan codes, indexed by digit. */
private val KEYPAD_DIGIT_SCANS = intArrayOf(0x52, 0x4F, 0x50, 0x51, 0x4B, 0x4C, 0x4D, 0x47, 0x48, 0x49)
private const val VK_NUMPAD0 = 0x60

// Virtual keys run F1 to F24 without a gap; scan codes come in three consecutive runs (F1–F10,
// F11–F12, F13–F23) and a lone F24.
private const val VK_F1 = 0x70
private const val F1 = 1
private const val F10 = 10
private const val F11 = 11
private const val F12 = 12
private const val F13 = 13
private const val F23 = 23
private const val F24 = 24
private const val SCAN_F1 = 0x3B
private const val SCAN_F11 = 0x57
private const val SCAN_F13 = 0x64
private const val SCAN_F24 = 0x76
private const val ASCII_END = 0x80

private fun charKey(codePoint: Int): Win32Key {
    if (codePoint >= ASCII_END) return UNKNOWN
    val lower = codePoint.toChar().lowercaseChar()
    val scan =
        when (lower) {
            ' ' -> SCAN_SPACE
            '\\' -> SCAN_BACKSLASH
            else -> SCAN_ROWS.firstNotNullOfOrNull { (first, row) -> row.indexOf(lower).takeIf { it >= 0 }?.plus(first) } ?: 0
        }
    val vk = if (lower.isLetterOrDigit() || lower == ' ') lower.uppercaseChar().code else OEM_VKS[lower] ?: 0
    return Win32Key(vk, scan)
}

private fun functionKey(n: Int): Win32Key {
    val vk = VK_F1 + n - F1
    return when (n) {
        in F1..F10 -> Win32Key(vk, SCAN_F1 + n - F1)
        in F11..F12 -> Win32Key(vk, SCAN_F11 + n - F11)
        in F13..F23 -> Win32Key(vk, SCAN_F13 + n - F13)
        F24 -> Win32Key(vk, SCAN_F24)
        else -> UNKNOWN
    }
}

internal fun win32Key(key: Key): Win32Key =
    when (key) {
        is Key.Character -> charKey(key.codePoint)
        Key.Escape -> Win32Key(0x1B, 0x01)
        Key.Enter -> Win32Key(0x0D, 0x1C)
        Key.Tab -> Win32Key(0x09, 0x0F)
        Key.Backspace -> Win32Key(0x08, 0x0E)
        Key.Insert -> ext(0x2D, 0x52)
        Key.Delete -> ext(0x2E, 0x53)
        Key.Left -> ext(0x25, 0x4B)
        Key.Right -> ext(0x27, 0x4D)
        Key.Up -> ext(0x26, 0x48)
        Key.Down -> ext(0x28, 0x50)
        Key.PageUp -> ext(0x21, 0x49)
        Key.PageDown -> ext(0x22, 0x51)
        Key.Home -> ext(0x24, 0x47)
        Key.End -> ext(0x23, 0x4F)
        is Key.F -> functionKey(key.number)
        is Key.Keypad -> keypadKey(key.key)
        is Key.Media -> mediaKey(key.key)
        is Key.Modifier -> modifierKey(key.key)
        is Key.System -> systemKey(key.key)
    }

private fun mediaKey(k: MediaKey): Win32Key =
    when (k) {
        MediaKey.Play, MediaKey.Pause, MediaKey.PlayPause -> ext(0xB3, 0x22)
        MediaKey.Stop -> ext(0xB2, 0x24)
        MediaKey.TrackNext -> ext(0xB0, 0x19)
        MediaKey.TrackPrevious -> ext(0xB1, 0x10)
        MediaKey.LowerVolume -> ext(0xAE, 0x2E)
        MediaKey.RaiseVolume -> ext(0xAF, 0x30)
        MediaKey.MuteVolume -> ext(0xAD, 0x20)
        MediaKey.Reverse, MediaKey.FastForward, MediaKey.Rewind, MediaKey.Record -> UNKNOWN
    }

// Windows reports the generic VK_SHIFT, VK_CONTROL and VK_MENU in records.
private fun modifierKey(k: ModifierKey): Win32Key =
    when (k) {
        ModifierKey.LeftShift -> Win32Key(0x10, 0x2A)
        ModifierKey.RightShift -> Win32Key(0x10, 0x36)
        ModifierKey.LeftControl -> Win32Key(0x11, 0x1D)
        ModifierKey.RightControl -> ext(0x11, 0x1D)
        ModifierKey.LeftAlt -> Win32Key(0x12, 0x38)
        ModifierKey.RightAlt -> ext(0x12, 0x38)
        ModifierKey.LeftSuper -> ext(0x5B, 0x5B)
        ModifierKey.RightSuper -> ext(0x5C, 0x5C)
        else -> UNKNOWN
    }

private fun systemKey(k: SystemKey): Win32Key =
    when (k) {
        SystemKey.CapsLock -> Win32Key(0x14, 0x3A)
        SystemKey.ScrollLock -> Win32Key(0x91, 0x46)
        SystemKey.NumLock -> ext(0x90, 0x45)
        SystemKey.PrintScreen -> ext(0x2C, 0x37)
        SystemKey.Pause -> Win32Key(0x13, 0x45)
        SystemKey.Menu -> ext(0x5D, 0x5D)
    }

private fun keypadKey(k: KeypadKey): Win32Key =
    when (k) {
        KeypadKey.Decimal -> Win32Key(0x6E, 0x53)

        KeypadKey.Divide -> ext(0x6F, 0x35)

        KeypadKey.Multiply -> Win32Key(0x6A, 0x37)

        KeypadKey.Subtract -> Win32Key(0x6D, 0x4A)

        KeypadKey.Add -> Win32Key(0x6B, 0x4E)

        KeypadKey.Enter -> ext(0x0D, 0x1C)

        KeypadKey.Equal -> Win32Key(0x92, 0x59)

        KeypadKey.Separator -> Win32Key(0x6C, 0x7E)

        // With Num Lock off the keypad sends the navigation virtual keys on its own
        // (non-extended) scan codes.
        KeypadKey.Left -> Win32Key(0x25, 0x4B)

        KeypadKey.Right -> Win32Key(0x27, 0x4D)

        KeypadKey.Up -> Win32Key(0x26, 0x48)

        KeypadKey.Down -> Win32Key(0x28, 0x50)

        KeypadKey.PageUp -> Win32Key(0x21, 0x49)

        KeypadKey.PageDown -> Win32Key(0x22, 0x51)

        KeypadKey.Home -> Win32Key(0x24, 0x47)

        KeypadKey.End -> Win32Key(0x23, 0x4F)

        KeypadKey.Insert -> Win32Key(0x2D, 0x52)

        KeypadKey.Delete -> Win32Key(0x2E, 0x53)

        KeypadKey.Begin -> Win32Key(0x0C, 0x4C)

        else -> Win32Key(VK_NUMPAD0 + k.ordinal, KEYPAD_DIGIT_SCANS[k.ordinal])
    }

private fun controlKeyState(
    mods: Modifiers,
    enhanced: Boolean,
): Int {
    var state = if (enhanced) ENHANCED_KEY else 0
    if (Modifiers.ALT in mods) state = state or LEFT_ALT_PRESSED
    if (Modifiers.CTRL in mods) state = state or LEFT_CTRL_PRESSED
    if (Modifiers.SHIFT in mods) state = state or SHIFT_PRESSED
    if (Modifiers.NUM_LOCK in mods) state = state or NUMLOCK_ON
    if (Modifiers.CAPS_LOCK in mods) state = state or CAPSLOCK_ON
    return state
}

private fun ByteSink.record(
    key: Win32Key,
    unit: Int,
    down: Boolean,
    state: Int,
) {
    csi()
    for ((i, field) in intArrayOf(key.vk, key.scan, unit, if (down) 1 else 0, state, REPEAT_COUNT).withIndex()) {
        if (i > 0) push(SEMI)
        decimal(field)
    }
    push(FINAL)
}

internal fun encodeWin32(
    event: KeyEvent,
    out: ByteSink,
) {
    val key = win32Key(event.key)
    val down = event.action != KeyAction.Release
    // The record carries every modifier held; Windows reports AltGr as Ctrl+Alt even when the
    // layout used it up.
    val state = controlKeyState(event.mods, key.enhanced)
    val reported = event.reportedMods
    // Enter, Esc, Tab and Backspace carry their C0 byte, which the text filter would drop.
    val c0 =
        when (event.key) {
            Key.Enter, Key.Keypad(KeypadKey.Enter) -> CR
            Key.Escape -> ESC
            Key.Tab -> TAB
            Key.Backspace -> BS
            else -> -1
        }
    if (c0 >= 0) {
        out.record(key, c0, down, state)
        return
    }
    // Windows puts the control character in UnicodeChar for Ctrl+key and nothing for
    // Ctrl+Alt+key unless the layout typed text with it.
    val ctrl = Modifiers.CTRL in reported
    if (ctrl && Modifiers.ALT !in reported) {
        val pressed = event.key
        val c = if (pressed is Key.Character) event.unshifted?.takeIf { it < ASCII_END } ?: pressed.codePoint else 0
        out.record(key, ctrlByte(c).coerceAtLeast(0), down, state)
        return
    }
    if (ctrl || !event.hasText()) {
        out.record(key, 0, down, state)
        return
    }
    // Text outside the BMP goes as one record per UTF-16 unit, as Windows delivers surrogate pairs.
    event.forEachText { cp ->
        if (cp < SUPPLEMENTARY_FIRST) {
            out.record(key, cp, down, state)
        } else {
            val offset = cp - SUPPLEMENTARY_FIRST
            out.record(key, HIGH_SURROGATE_FIRST + (offset shr SURROGATE_BITS), down, state)
            out.record(key, LOW_SURROGATE_FIRST + (offset and LOW_TEN_BITS), down, state)
        }
    }
}

private const val LOW_TEN_BITS = 0x3FF
