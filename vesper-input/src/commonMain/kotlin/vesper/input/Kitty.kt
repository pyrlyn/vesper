package vesper.input

// The kitty keyboard protocol: progressive-enhancement flags, the per-screen flag stack a program
// drives with `CSI = > < ? u`, and the `CSI … u` encoder for every flag combination. Separate from
// the legacy encoder because once a flag is set every report changes shape.
//
// Implemented from the specification, not from kitty's GPL-3.0-only code:
// https://sw.kovidgoyal.net/kitty/keyboard-protocol/. Ported from the earlier Rust core
// (`kitty.rs` of its `input` crate).

private const val FLAG_DISAMBIGUATE = 1
private const val FLAG_REPORT_EVENTS = 1 shl 1
private const val FLAG_REPORT_ALTERNATES = 1 shl 2
private const val FLAG_REPORT_ALL_KEYS = 1 shl 3
private const val FLAG_REPORT_TEXT = 1 shl 4
private const val FLAG_ALL = 0x1F

/**
 * Progressive-enhancement flags.
 * https://sw.kovidgoyal.net/kitty/keyboard-protocol/#progressive-enhancement
 */
public value class KittyFlags private constructor(
    public val bits: Int,
) {
    public operator fun plus(other: KittyFlags): KittyFlags = KittyFlags(bits or other.bits)

    public operator fun minus(other: KittyFlags): KittyFlags = KittyFlags(bits and other.bits.inv())

    public operator fun contains(other: KittyFlags): Boolean = bits and other.bits == other.bits

    /** True when key events use the legacy encoding: only the escaping flags move a key out of it. */
    public fun isLegacy(): Boolean = bits and ESCAPING == 0

    override fun toString(): String = "KittyFlags($bits)"

    public companion object {
        // The other two flags only refine a report that is already an escape code.
        private const val ESCAPING = FLAG_DISAMBIGUATE or FLAG_REPORT_EVENTS or FLAG_REPORT_ALL_KEYS

        public val NONE: KittyFlags = KittyFlags(0)

        /** Esc, Alt+key, Ctrl+key and keypad keys become `CSI u` reports. */
        public val DISAMBIGUATE: KittyFlags = KittyFlags(FLAG_DISAMBIGUATE)

        /** Repeat and release events are reported. */
        public val REPORT_EVENTS: KittyFlags = KittyFlags(FLAG_REPORT_EVENTS)

        /** Shifted and base-layout keys ride along with the key code. */
        public val REPORT_ALTERNATES: KittyFlags = KittyFlags(FLAG_REPORT_ALTERNATES)

        /** Text keys, Enter, Tab, Backspace and modifiers are reports too. */
        public val REPORT_ALL_KEYS: KittyFlags = KittyFlags(FLAG_REPORT_ALL_KEYS)

        /** Reports carry the text the key typed. */
        public val REPORT_TEXT: KittyFlags = KittyFlags(FLAG_REPORT_TEXT)
        public val ALL: KittyFlags = KittyFlags(FLAG_ALL)

        /** Flags from a PTY parameter; undefined bits are dropped. */
        public fun of(raw: Int): KittyFlags = KittyFlags(raw and FLAG_ALL)
    }
}

/** How `CSI = flags ; mode u` combines `flags` with the current ones. */
public enum class SetMode {
    /** Mode 1, the default. */
    Replace,

    /** Mode 2: set the given bits. */
    Union,

    /** Mode 3: clear the given bits. */
    Difference,

    ;

    public companion object {
        /** The mode for a PTY parameter; an omitted one (0) is the default, an unknown one is null and the request is ignored. */
        public fun of(raw: Int): SetMode? =
            when (raw) {
                0, 1 -> Replace
                2 -> Union
                3 -> Difference
                else -> null
            }
    }
}

/**
 * Entries one flag stack holds. The specification asks for a cap against denial of service and
 * for evicting the oldest entry when it is full; kitty itself uses 8.
 */
public const val KITTY_FLAG_STACK_DEPTH: Int = 8

private const val QUERY_MARKER = '?'.code

/**
 * One screen's flag stack. The top entry is the active flags; an empty stack means none. Fixed
 * size, so a program pushing forever costs nothing.
 */
public class KittyFlagStack {
    private val entries = IntArray(KITTY_FLAG_STACK_DEPTH)

    /** Entries on the stack. */
    public var depth: Int = 0
        private set

    /** The active flags. */
    public val current: KittyFlags get() = if (depth == 0) KittyFlags.NONE else KittyFlags.of(entries[depth - 1])

    /** `CSI > flags u`: push, evicting the oldest entry when full. */
    public fun push(flags: Int) {
        if (depth == KITTY_FLAG_STACK_DEPTH) {
            entries.copyInto(entries, 0, 1, KITTY_FLAG_STACK_DEPTH)
            depth--
        }
        entries[depth++] = KittyFlags.of(flags).bits
    }

    /** `CSI < count u`: pop [count] entries (0 means the default, 1). Popping past the bottom resets all flags. */
    public fun pop(count: Int) {
        depth = (depth - count.coerceAtLeast(1)).coerceAtLeast(0)
    }

    /** `CSI = flags ; mode u`: change the active flags in place. */
    public fun set(
        flags: Int,
        mode: Int,
    ) {
        val setMode = SetMode.of(mode) ?: return
        // Setting flags with nothing pushed makes them the bottom entry, so a later pop still
        // returns to "no flags".
        if (depth == 0) push(0)
        val given = KittyFlags.of(flags)
        val top = KittyFlags.of(entries[depth - 1])
        entries[depth - 1] =
            when (setMode) {
                SetMode.Replace -> given
                SetMode.Union -> top + given
                SetMode.Difference -> top - given
            }.bits
    }

    /** Empties the stack (full reset). */
    public fun clear() {
        depth = 0
    }

    /** The reply to `CSI ? u`: `CSI ? flags u`. */
    public fun writeQueryReply(out: ByteSink) {
        out.csi()
        out.push(QUERY_MARKER)
        out.decimal(current.bits)
        out.push(FINAL_U)
    }
}

/** The two stacks the specification requires, so an editor on the alternate screen cannot disturb the shell's flags. */
public class KittyKeyboard {
    public val main: KittyFlagStack = KittyFlagStack()
    public val alternate: KittyFlagStack = KittyFlagStack()

    public fun stack(alternateScreen: Boolean): KittyFlagStack = if (alternateScreen) alternate else main

    public fun clear() {
        main.clear()
        alternate.clear()
    }
}

internal fun encodeKitty(
    event: KeyEvent,
    flags: KittyFlags,
    out: ByteSink,
) {
    val reportAll = KittyFlags.REPORT_ALL_KEYS in flags
    val reportEvents = KittyFlags.REPORT_EVENTS in flags
    val release = event.action == KeyAction.Release
    if (release && !reportEvents) return
    val reported = event.reportedMods
    val plain = reported - Modifiers.LOCKS
    val key = event.key
    val typesText = key is Key.Character || (key is Key.Keypad && key.key.textChar() != null)
    if (!reportAll && legacyShortcut(event, plain, typesText, out)) return
    val form =
        if (key is Key.Character) {
            Form(event.unshifted ?: key.codePoint, FINAL_U)
        } else {
            functional(key) ?: return
        }
    // Lock states are not reported for text keys unless every key is a report.
    val mods = if (typesText && !reportAll) plain else reported
    val eventType = if (reportEvents && event.action != KeyAction.Press) event.action else null
    val withText = reportAll && KittyFlags.REPORT_TEXT in flags && !release && event.hasText()
    val withMods = !mods.isEmpty() || eventType != null

    out.csi()
    if (form.final == FINAL_U || form.final == FINAL_TILDE || withMods) out.decimal(form.number)
    if (form.final == FINAL_U && KittyFlags.REPORT_ALTERNATES in flags) writeAlternates(event, form.number, mods, out)
    if (withMods || withText) out.push(SEMI)
    if (withMods) {
        out.decimal(mods.wire)
        if (eventType != null) {
            out.push(COLON)
            out.decimal(eventType.code)
        }
    }
    if (withText) {
        out.push(SEMI)
        var first = true
        event.forEachText {
            if (!first) out.push(COLON)
            first = false
            out.decimal(it)
        }
    }
    out.push(form.final)
}

/**
 * The keys that stay outside reports without report-all-keys. Returns true when [event] was fully
 * handled (written or dropped).
 */
private fun legacyShortcut(
    event: KeyEvent,
    plain: Modifiers,
    typesText: Boolean,
    out: ByteSink,
): Boolean {
    val release = event.action == KeyAction.Release
    val c0 =
        when (val key = event.key) {
            Key.Enter -> CR
            Key.Tab -> TAB
            Key.Backspace -> DEL
            is Key.Modifier -> return true
            is Key.System -> if (key.key == SystemKey.CapsLock || key.key == SystemKey.NumLock) return true else -1
            else -> -1
        }
    // Enter, Tab and Backspace stay legacy and never report a release, so `reset` can still be
    // typed after a program dies in this mode.
    if (c0 >= 0) {
        if (release) return true
        if (plain.isEmpty()) {
            out.push(c0)
            return true
        }
    }
    val shiftOnly = (plain - Modifiers.SHIFT).isEmpty()
    if (typesText && shiftOnly && (release || event.hasText())) {
        if (!release) event.writeText(out)
        return true
    }
    return false
}

/**
 * `:shifted:base`. The shifted key only when Shift is held and it differs; the base-layout key
 * only when it differs from the reported code.
 */
private fun writeAlternates(
    event: KeyEvent,
    number: Int,
    mods: Modifiers,
    out: ByteSink,
) {
    val shifted = event.singleChar()?.takeIf { Modifiers.SHIFT in mods && it != number }
    val base = (event.key as? Key.Character)?.codePoint?.takeIf { it != number }
    if (shifted == null && base == null) return
    out.push(COLON)
    if (shifted != null) out.decimal(shifted)
    if (base != null) {
        out.push(COLON)
        out.decimal(base)
    }
}
