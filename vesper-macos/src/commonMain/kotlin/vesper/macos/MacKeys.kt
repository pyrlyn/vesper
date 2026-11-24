package vesper.macos

import vesper.input.Key
import vesper.input.KeyAction
import vesper.input.KeyEvent
import vesper.input.KeypadKey
import vesper.input.Modifiers

// Mac virtual key codes and modifier flags to the core's key vocabulary. The core names keys by
// position (the US-layout character of a main-block key), so the mapping works from the hardware
// key code, never from the layout's text, which travels separately. Codes follow Carbon's kVK_*
// constants (HIToolbox Events.h); flags follow NSEventModifierFlags.

private const val FLAG_CAPS_LOCK = 1L shl 16
private const val FLAG_SHIFT = 1L shl 17
private const val FLAG_CONTROL = 1L shl 18
private const val FLAG_OPTION = 1L shl 19
private const val FLAG_COMMAND = 1L shl 20

private const val HIGH_FIRST = 0xD800
private const val LOW_FIRST = 0xDC00
private const val SURROGATE_BITS = 10
private const val SUPPLEMENTARY_FIRST = 0x10000

// The main block in kVK_ANSI_* order; gaps are keys handled elsewhere or absent from the US layout.
private val MAIN_BLOCK: Map<Int, Int> =
    buildMap {
        for ((start, chars) in listOf(0x00 to "asdfhgzxcv", 0x0B to "bqweryt123465=97-80]ou[ip", 0x25 to "lj'k;\\,/nm.", 0x31 to " `")) {
            chars.forEachIndexed { offset, char -> put(start + offset, char.code) }
        }
    }

private val SPECIAL: Map<Int, Key> =
    buildMap {
        val editing =
            listOf(
                0x35 to Key.Escape,
                0x24 to Key.Enter,
                0x30 to Key.Tab,
                0x33 to Key.Backspace,
                0x72 to Key.Insert,
                0x75 to Key.Delete,
                0x7B to Key.Left,
                0x7C to Key.Right,
                0x7E to Key.Up,
                0x7D to Key.Down,
                0x74 to Key.PageUp,
                0x79 to Key.PageDown,
                0x73 to Key.Home,
                0x77 to Key.End,
            )
        putAll(editing)
        val functions =
            intArrayOf(
                0x7A,
                0x78,
                0x63,
                0x76,
                0x60,
                0x61,
                0x62,
                0x64,
                0x65,
                0x6D,
                0x67,
                0x6F,
                0x69,
                0x6B,
                0x71,
                0x6A,
                0x40,
                0x4F,
                0x50,
                0x5A,
            )
        functions.forEachIndexed { index, code -> put(code, Key.F(index + 1)) }
        // Keypad 0-9, Decimal, Divide, Multiply, Subtract, Add, Enter, Equal: KeypadKey's first entries.
        val keypad = intArrayOf(0x52, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5B, 0x5C, 0x41, 0x4B, 0x43, 0x4E, 0x45, 0x4C, 0x51)
        keypad.forEachIndexed { index, code -> put(code, Key.Keypad(KeypadKey.entries[index])) }
    }

/** The core's key for a Mac virtual key code, or null for one it has no name for. */
internal fun macKey(keyCode: Int): Key? = SPECIAL[keyCode] ?: MAIN_BLOCK[keyCode]?.let { Key.Character(it) }

/** The core's modifiers for AppKit's flags: Option is Alt and Command is Super, as the kitty protocol's macOS notes say. */
internal fun macModifiers(flags: Long): Modifiers {
    var mods = Modifiers.NONE
    if (flags and FLAG_SHIFT != 0L) mods += Modifiers.SHIFT
    if (flags and FLAG_OPTION != 0L) mods += Modifiers.ALT
    if (flags and FLAG_CONTROL != 0L) mods += Modifiers.CTRL
    if (flags and FLAG_COMMAND != 0L) mods += Modifiers.SUPER
    if (flags and FLAG_CAPS_LOCK != 0L) mods += Modifiers.CAPS_LOCK
    return mods
}

/**
 * A key event from what `NSEvent` reports: [characters] with every modifier applied,
 * [ignoringModifiers] with all but Shift, [unmodified] with none. Null for a key the core has no
 * name for; its text, if any, reaches the program through the input method path instead (T16).
 */
internal fun macKeyEvent(
    keyCode: Int,
    flags: Long,
    characters: String?,
    ignoringModifiers: String?,
    unmodified: String?,
    action: KeyAction,
): KeyEvent? {
    val key = macKey(keyCode) ?: return null
    val mods = macModifiers(flags)
    // Only main-block keys type text; the others carry AppKit's private-use function-key characters.
    if (key !is Key.Character) return KeyEvent(key, action, mods)
    // Ctrl's effect is the encoder's to compute, so the text is the key's without it.
    val text = (if (Modifiers.CTRL in mods) ignoringModifiers else characters).orEmpty()
    // Option typing a character of its own (å) used Option up; otherwise it stays Alt.
    val consumed = if (Modifiers.ALT in mods && text != ignoringModifiers.orEmpty()) Modifiers.ALT else Modifiers.NONE
    val base = unmodified?.singleCodePoint()
    return KeyEvent(key, action, mods, consumed, text, base?.takeIf { it != key.codePoint })
}

private fun String.singleCodePoint(): Int? =
    when {
        length == 1 -> {
            this[0].code
        }

        length == 2 && this[0].isHighSurrogate() && this[1].isLowSurrogate() -> {
            ((this[0].code - HIGH_FIRST) shl SURROGATE_BITS) + (this[1].code - LOW_FIRST) + SUPPLEMENTARY_FIRST
        }

        else -> {
            null
        }
    }
