package scull.input

// The platform-neutral key event every key encoder reads, and the tables that name keys on the
// wire. Separate from the encoders so the C ABI can mirror one plain struct and no UI-toolkit key
// type reaches the core. The fields follow Ghostty's embedding API: the platform reports what was
// pressed and what text it produced; the core decides the bytes.
//
// Ported from the creator's Rust scull (`crates/scull-input/src/key.rs`).

private const val MOD_SHIFT = 1
private const val MOD_ALT = 1 shl 1
private const val MOD_CTRL = 1 shl 2
private const val MOD_SUPER = 1 shl 3
private const val MOD_HYPER = 1 shl 4
private const val MOD_META = 1 shl 5
private const val MOD_CAPS_LOCK = 1 shl 6
private const val MOD_NUM_LOCK = 1 shl 7
private const val MOD_ALL = 0xFF

/**
 * Modifier state. The bits are the kitty protocol's, which extend xterm's (shift 1, alt 2, ctrl 4,
 * meta/super 8), so every encoding sends `bits + 1`.
 * https://sw.kovidgoyal.net/kitty/keyboard-protocol/#modifiers
 */
public value class Modifiers private constructor(
    public val bits: Int,
) {
    public operator fun plus(other: Modifiers): Modifiers = Modifiers(bits or other.bits)

    public operator fun minus(other: Modifiers): Modifiers = Modifiers(bits and other.bits.inv())

    /** Whether every bit of [other] is held. */
    public operator fun contains(other: Modifiers): Boolean = bits and other.bits == other.bits

    public fun intersects(other: Modifiers): Boolean = bits and other.bits != 0

    public fun isEmpty(): Boolean = bits == 0

    /** The value on the wire: kitty and xterm both send `1 + bits`, so an absent parameter (1) means none. */
    internal val wire: Int get() = bits + 1

    override fun toString(): String = "Modifiers($bits)"

    public companion object {
        /** Modifiers from their bits; bits past the eight defined ones are dropped. */
        public fun of(bits: Int): Modifiers = Modifiers(bits and MOD_ALL)

        public val NONE: Modifiers = Modifiers(0)
        public val SHIFT: Modifiers = Modifiers(MOD_SHIFT)

        /** Alt, Option on macOS. */
        public val ALT: Modifiers = Modifiers(MOD_ALT)
        public val CTRL: Modifiers = Modifiers(MOD_CTRL)

        /** The Windows key, or Command on macOS. */
        public val SUPER: Modifiers = Modifiers(MOD_SUPER)

        /** X11 and Wayland only. */
        public val HYPER: Modifiers = Modifiers(MOD_HYPER)

        /** X11 and Wayland only. */
        public val META: Modifiers = Modifiers(MOD_META)
        public val CAPS_LOCK: Modifiers = Modifiers(MOD_CAPS_LOCK)
        public val NUM_LOCK: Modifiers = Modifiers(MOD_NUM_LOCK)

        /** The lock states, which legacy encodings cannot carry. */
        public val LOCKS: Modifiers = Modifiers(MOD_CAPS_LOCK or MOD_NUM_LOCK)

        /** Modifiers xterm has no legacy spelling for in front of a text key. */
        internal val BEYOND_XTERM: Modifiers = Modifiers(MOD_SUPER or MOD_HYPER or MOD_META)
    }
}

/** What happened to the key; [code] is the kitty event type. */
public enum class KeyAction(
    public val code: Int,
) {
    Press(1),
    Repeat(2),
    Release(3),
}

private const val KEYPAD_FIRST = 57_399
private const val MEDIA_FIRST = 57_428
private const val MODIFIER_FIRST = 57_441
private const val SYSTEM_FIRST = 57_358

/**
 * Keypad keys. They are declared in the order of kitty's functional key table, so [code] is the
 * first code plus the ordinal and the encoder needs no second table; do not reorder them.
 * https://sw.kovidgoyal.net/kitty/keyboard-protocol/#functional-key-definitions
 */
public enum class KeypadKey {
    Digit0,
    Digit1,
    Digit2,
    Digit3,
    Digit4,
    Digit5,
    Digit6,
    Digit7,
    Digit8,
    Digit9,
    Decimal,
    Divide,
    Multiply,
    Subtract,
    Add,
    Enter,
    Equal,
    Separator,

    // Navigation: what the keypad sends while Num Lock is off.
    Left,
    Right,
    Up,
    Down,
    PageUp,
    PageDown,
    Home,
    End,
    Insert,
    Delete,
    Begin,
    ;

    public val code: Int get() = KEYPAD_FIRST + ordinal

    /** The digit or sign the key types, or null for Enter and the navigation keys. */
    internal fun textChar(): Int? =
        when (this) {
            Decimal -> '.'.code
            Divide -> '/'.code
            Multiply -> '*'.code
            Subtract -> '-'.code
            Add -> '+'.code
            Equal -> '='.code
            Separator -> ','.code
            else -> if (ordinal <= Digit9.ordinal) '0'.code + ordinal else null
        }
}

/** Media and volume keys, in kitty's order: [code] follows the ordinal. */
public enum class MediaKey {
    Play,
    Pause,
    PlayPause,
    Reverse,
    Stop,
    FastForward,
    Rewind,
    TrackNext,
    TrackPrevious,
    Record,
    LowerVolume,
    RaiseVolume,
    MuteVolume,
    ;

    public val code: Int get() = MEDIA_FIRST + ordinal
}

/** Modifier keys pressed on their own, in kitty's order: [code] follows the ordinal. */
public enum class ModifierKey {
    LeftShift,
    LeftControl,
    LeftAlt,
    LeftSuper,
    LeftHyper,
    LeftMeta,
    RightShift,
    RightControl,
    RightAlt,
    RightSuper,
    RightHyper,
    RightMeta,
    IsoLevel3Shift,
    IsoLevel5Shift,
    ;

    public val code: Int get() = MODIFIER_FIRST + ordinal
}

/** Lock and system keys, in kitty's order: [code] follows the ordinal. */
public enum class SystemKey {
    CapsLock,
    ScrollLock,
    NumLock,
    PrintScreen,
    Pause,
    Menu,
    ;

    public val code: Int get() = SYSTEM_FIRST + ordinal
}

/** Which key was pressed, independent of the keyboard layout. */
public sealed interface Key {
    /**
     * A key of the main block, named by the code point it types on the US (PC-101) layout without
     * modifiers: `a`, `1`, space. This is kitty's base layout key, which keeps Ctrl+C working on
     * layouts where the same key types `с`.
     */
    public data class Character(
        public val codePoint: Int,
    ) : Key

    public data object Escape : Key

    /** Enter or Return of the main block. */
    public data object Enter : Key

    public data object Tab : Key

    public data object Backspace : Key

    public data object Insert : Key

    /** Forward delete. */
    public data object Delete : Key

    public data object Left : Key

    public data object Right : Key

    public data object Up : Key

    public data object Down : Key

    public data object PageUp : Key

    public data object PageDown : Key

    public data object Home : Key

    public data object End : Key

    /** Function key F1 to F35; other numbers encode to nothing. */
    public data class F(
        public val number: Int,
    ) : Key

    public data class Keypad(
        public val key: KeypadKey,
    ) : Key

    public data class Media(
        public val key: MediaKey,
    ) : Key

    /** A modifier key on its own. */
    public data class Modifier(
        public val key: ModifierKey,
    ) : Key

    public data class System(
        public val key: SystemKey,
    ) : Key
}

/**
 * One key event from the platform.
 *
 * @param mods modifiers held, including the effect of this event when the key is itself a modifier
 *   (kitty requires it).
 * @param consumed modifiers the layout used up to produce [text] (Option on macOS typing `å`). They
 *   are not reported, except Shift, which every protocol reports alongside the shifted text.
 * @param text what the key types with Shift and consumed modifiers applied but without Ctrl, whose
 *   effect the encoders compute. Control characters in it are ignored.
 * @param unshifted the code point the key types on the current layout without modifiers (`с` for
 *   `Character('c')` on a Russian layout); null means the US layout's one from [key].
 * @param composing an input method is composing; the event must not reach the program.
 */
public data class KeyEvent(
    public val key: Key,
    public val action: KeyAction = KeyAction.Press,
    public val mods: Modifiers = Modifiers.NONE,
    public val consumed: Modifiers = Modifiers.NONE,
    public val text: String = "",
    public val unshifted: Int? = null,
    public val composing: Boolean = false,
) {
    /** Modifiers to report: everything held except what the layout used up, Shift excepted. */
    internal val reportedMods: Modifiers get() = mods - (consumed - Modifiers.SHIFT)

    /** Each code point of [text] but control characters, so a platform bug cannot smuggle an escape sequence through. */
    internal inline fun forEachText(action: (Int) -> Unit) {
        forEachCodePoint(text) { if (!isControl(it)) action(it) }
    }

    internal fun hasText(): Boolean {
        forEachText { return true }
        return false
    }

    /** The one code point typed, when [text] is exactly one. */
    internal fun singleChar(): Int? {
        var only: Int? = null
        var count = 0
        forEachText {
            only = it
            count++
        }
        return if (count == 1) only else null
    }

    internal fun writeText(out: ByteSink) {
        forEachText { out.codePoint(it) }
    }
}

/** Final byte of a `CSI number u` report. */
internal const val FINAL_U = 'u'.code

/** Final byte of a `CSI number ~` report. */
internal const val FINAL_TILDE = '~'.code

/** The number of the arrows, Home, End and F1–F4, whose final letter names the key; omitted without modifiers. */
internal const val LETTER_KEY_NUMBER = 1

internal const val CR = '\r'.code
internal const val TAB = '\t'.code
internal const val DEL = 0x7F

/** Backspace with Ctrl. */
internal const val BS = 0x08

/** F1 is `CSI 1 P`; F13 is the first function key with a private-use code. */
private const val F5 = 5
private const val F12 = 12
private const val F13 = 13
private const val F13_CODE = 57_376
private const val LAST_F_KEY = 35

/** `CSI n ~` numbers of F5–F12; the gaps are DEC's. */
private val F5_TO_F12 = intArrayOf(15, 17, 18, 19, 20, 21, 23, 24)

/** How a key is spelled on the wire: `CSI number final`. */
internal data class Form(
    val number: Int,
    val final: Int,
)

private fun letter(final: Char) = Form(LETTER_KEY_NUMBER, final.code)

private fun tilde(number: Int) = Form(number, FINAL_TILDE)

private fun code(number: Int) = Form(number, FINAL_U)

/**
 * How kitty's functional key table spells [key]; null for keys of the main block (they report
 * their code point) and for function keys outside F1 to F35.
 * https://sw.kovidgoyal.net/kitty/keyboard-protocol/#functional-key-definitions
 */
internal fun functional(key: Key): Form? =
    when (key) {
        is Key.Character -> null
        Key.Escape -> code(ESC)
        Key.Enter -> code(CR)
        Key.Tab -> code(TAB)
        Key.Backspace -> code(DEL)
        Key.Insert -> tilde(2)
        Key.Delete -> tilde(3)
        Key.PageUp -> tilde(5)
        Key.PageDown -> tilde(6)
        Key.Up -> letter('A')
        Key.Down -> letter('B')
        Key.Right -> letter('C')
        Key.Left -> letter('D')
        Key.End -> letter('F')
        Key.Home -> letter('H')
        is Key.F -> functionKey(key.number)
        is Key.Keypad -> if (key.key == KeypadKey.Begin) letter('E') else code(key.key.code)
        is Key.Media -> code(key.key.code)
        is Key.Modifier -> code(key.key.code)
        is Key.System -> code(key.key.code)
    }

private fun functionKey(n: Int): Form? =
    when (n) {
        1 -> letter('P')
        2 -> letter('Q')
        3 -> tilde(13)
        4 -> letter('S')
        in F5..F12 -> tilde(F5_TO_F12[n - F5])
        in F13..LAST_F_KEY -> code(F13_CODE + n - F13)
        else -> null
    }

/** Ctrl keeps the low five bits of `@ A–Z [ \ ] ^ _`. */
private const val CTRL_MASK = 0x1F

/**
 * The byte Ctrl plus [codePoint] sends, from kitty's superset of the VT100 table, or -1 for keys
 * Ctrl leaves alone.
 * https://sw.kovidgoyal.net/kitty/keyboard-protocol/#legacy-ctrl-mapping-of-ascii-keys
 */
internal fun ctrlByte(codePoint: Int): Int =
    when (codePoint) {
        in 'a'.code..'z'.code, '@'.code, '['.code, '\\'.code, ']'.code, '^'.code, '_'.code -> codePoint and CTRL_MASK
        ' '.code, '2'.code -> 0
        in '3'.code..'7'.code -> codePoint - '3'.code + ESC
        '/'.code -> '_'.code and CTRL_MASK
        '~'.code -> '^'.code and CTRL_MASK
        '8'.code, '?'.code -> DEL
        else -> -1
    }
