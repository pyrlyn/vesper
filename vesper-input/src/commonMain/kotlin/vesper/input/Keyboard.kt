package vesper.input

// The one entry point for key events: picks win32-input-mode, the kitty protocol or the legacy
// xterm encoding from the modes the terminal holds. Separate so the precedence between the three
// lives in one place. Ported from the earlier Rust core (`keyboard.rs` of its `input` crate).

/**
 * xterm's `modifyOtherKeys` resource, set by `CSI > 4 ; level m`.
 * https://invisible-island.net/xterm/ctlseqs/ctlseqs.html (XTMODKEYS)
 */
public enum class ModifyOtherKeys {
    /** Unencodable combinations fall back to kitty's `CSI u` form. */
    Off,

    /** Only combinations with no legacy spelling become `CSI 27 ; mods ; char ~`. */
    Level1,

    /** Every modified key except Shift alone on text. */
    Level2,
}

/**
 * The terminal modes key encoding depends on. The terminal owns them; this module only reads them.
 *
 * @param applicationCursor DECCKM (`CSI ? 1 h`): unmodified cursor keys send SS3.
 * @param applicationKeypad DECKPAM (`ESC =`): the keypad sends SS3 instead of digits.
 * @param kitty the kitty flags of the active screen's stack.
 * @param win32Input win32-input-mode (`CSI ? 9001 h`), which ConPTY sets.
 */
public data class KeyModes(
    public val applicationCursor: Boolean = false,
    public val applicationKeypad: Boolean = false,
    public val modifyOtherKeys: ModifyOtherKeys = ModifyOtherKeys.Off,
    public val kitty: KittyFlags = KittyFlags.NONE,
    public val win32Input: Boolean = false,
)

/**
 * Appends the bytes the program expects for [event]. Nothing is appended when the event has no
 * encoding in the active modes (a release in legacy mode, a key being composed, an F-key beyond
 * F35).
 */
public fun encodeKey(
    event: KeyEvent,
    modes: KeyModes,
    out: ByteSink,
) {
    if (event.composing) return
    // ConPTY asks for win32 records to rebuild console input records; any other format would lose
    // key-up events and scan codes.
    when {
        modes.win32Input -> encodeWin32(event, out)
        modes.kitty.isLegacy() -> encodeLegacy(event, modes, out)
        else -> encodeKitty(event, modes.kitty, out)
    }
}
