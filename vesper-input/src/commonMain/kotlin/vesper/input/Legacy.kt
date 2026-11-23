package vesper.input

// xterm's legacy key encoding: text as UTF-8, Ctrl as C0 bytes, Alt as an ESC prefix, function
// keys as CSI or SS3 with xterm's modifier parameter, application cursor and keypad modes, and
// modifyOtherKeys. Separate from the kitty encoder because it is what every program understands
// and the terminal's default.
//
// The kitty specification's legacy section pins down the cases xterm leaves to its resources; it
// is followed where the two agree, and xterm where they differ (F3 is `SS3 R`):
// https://sw.kovidgoyal.net/kitty/keyboard-protocol/#legacy-key-event-encoding,
// https://invisible-island.net/xterm/ctlseqs/ctlseqs.html#h2-PC-Style-Function-Keys.
// Ported from the earlier Rust core (`legacy.rs` of its `input` crate).

/** modifyOtherKeys reports are `CSI 27 ; mods ; char ~`. */
private const val MODIFY_OTHER_KEYS_NUMBER = 27

/** Shift+Tab is back-tab, `CSI Z`. */
private const val BACKTAB = 'Z'.code

/** xterm keeps F3 as `SS3 R`; kitty moved it to `13 ~` only because `CSI 1 ; m R` collides with the cursor position report. */
private const val F3_FINAL = 'R'.code
private const val F3 = 3

/** F1–F4 finals; unmodified they always go out as SS3. */
private const val PF1 = 'P'.code
private const val PF4 = 'S'.code

/** Menu is terminfo `kf16`, `CSI 29 ~`. */
private const val MENU_NUMBER = 29

/** DECKPAM finals: digits are `SS3 p` to `SS3 y`. */
private const val KEYPAD_DIGIT0_FINAL = 'p'.code
private const val ASCII_END = 0x80

internal fun encodeLegacy(
    event: KeyEvent,
    modes: KeyModes,
    out: ByteSink,
) {
    // Legacy encodings have no key-up.
    if (event.action == KeyAction.Release) return
    val mods = event.reportedMods - Modifiers.LOCKS
    when (val key = event.key) {
        is Key.Character -> textKey(event, key.codePoint, mods, modes, out)
        Key.Enter -> c0Key(key, CR, mods, modes, out)
        Key.Escape -> c0Key(key, ESC, mods, modes, out)
        Key.Tab -> c0Key(key, TAB, mods, modes, out)
        Key.Backspace -> c0Key(key, DEL, mods, modes, out)
        is Key.Keypad -> keypad(event, key.key, mods, modes, out)
        else -> functionKey(legacyForm(key) ?: return, mods, modes, out)
    }
}

private fun legacyForm(key: Key): Form? =
    when {
        key is Key.F && key.number == F3 -> Form(LETTER_KEY_NUMBER, F3_FINAL)
        key is Key.System && key.key == SystemKey.Menu -> Form(MENU_NUMBER, FINAL_TILDE)
        else -> functional(key)
    }

private fun functionKey(
    form: Form,
    mods: Modifiers,
    modes: KeyModes,
    out: ByteSink,
) {
    // Media, modifier, lock and F13+ keys have no legacy spelling; sending kitty's private-use
    // codes would confuse programs that never asked.
    if (form.final == FINAL_U) return
    if (form.final != FINAL_TILDE && mods.isEmpty()) {
        if (modes.applicationCursor || form.final in PF1..PF4) out.ss3() else out.csi()
    } else {
        out.csi()
        out.decimal(form.number)
        if (!mods.isEmpty()) {
            out.push(SEMI)
            out.decimal(mods.wire)
        }
    }
    out.push(form.final)
}

/** Enter, Escape, Tab and Backspace, per the kitty specification's C0 table. */
private fun c0Key(
    key: Key,
    byte: Int,
    mods: Modifiers,
    modes: KeyModes,
    out: ByteSink,
) {
    val backTab = key == Key.Tab && mods == Modifiers.SHIFT
    if (modes.modifyOtherKeys == ModifyOtherKeys.Level2 && !mods.isEmpty() && !backTab) {
        modifyOtherKeys(byte, mods, out)
        return
    }
    val allThree = Modifiers.CTRL + Modifiers.ALT + Modifiers.SHIFT
    if (mods.intersects(Modifiers.BEYOND_XTERM) || allThree in mods) {
        fallback(byte, byte, mods, modes, out)
        return
    }
    if (Modifiers.ALT in mods) out.push(ESC)
    when {
        key == Key.Tab && Modifiers.SHIFT in mods -> {
            out.csi()
            out.push(BACKTAB)
        }

        key == Key.Backspace && Modifiers.CTRL in mods -> {
            out.push(BS)
        }

        else -> {
            out.push(byte)
        }
    }
}

/** Keys that type text, per the kitty specification's legacy text keys. */
private fun textKey(
    event: KeyEvent,
    base: Int,
    keyMods: Modifiers,
    modes: KeyModes,
    out: ByteSink,
) {
    val code = event.unshifted ?: base
    // Shift does not change what Space sends (Ctrl+Shift+Space is NUL).
    val mods = if (base == ' '.code) keyMods - Modifiers.SHIFT else keyMods
    val typed = event.singleChar() ?: code
    if ((mods - Modifiers.SHIFT).isEmpty()) {
        if (event.hasText()) {
            event.writeText(out)
        } else if (mods.isEmpty()) {
            out.codePoint(code)
        }
        return
    }
    if (modes.modifyOtherKeys == ModifyOtherKeys.Level2) {
        modifyOtherKeys(typed, mods, out)
        return
    }
    if (mods.intersects(Modifiers.BEYOND_XTERM) || (Modifiers.CTRL + Modifiers.SHIFT) in mods) {
        fallback(code, typed, mods, modes, out)
        return
    }
    if (Modifiers.ALT in mods) out.push(ESC)
    when {
        Modifiers.CTRL in mods -> {
            // On a non-Latin layout Ctrl acts on the US key, so Ctrl+С is ^C.
            val ascii = if (code < ASCII_END) code else base
            val byte = ctrlByte(ascii)
            if (byte >= 0) out.push(byte) else out.codePoint(ascii)
        }

        event.hasText() -> {
            event.writeText(out)
        }

        else -> {
            out.codePoint(code)
        }
    }
}

private fun keypad(
    event: KeyEvent,
    k: KeypadKey,
    mods: Modifiers,
    modes: KeyModes,
    out: ByteSink,
) {
    val twin = mainBlockTwin(k)
    if (twin != null) {
        functional(twin)?.let { functionKey(it, mods, modes, out) }
        return
    }
    if (modes.applicationKeypad && mods.isEmpty()) {
        val final = applicationKeypadFinal(k)
        if (final != null) {
            out.ss3()
            out.push(final)
            return
        }
    }
    if (k == KeypadKey.Enter) {
        c0Key(Key.Enter, CR, mods, modes, out)
    } else {
        k.textChar()?.let { textKey(event, it, mods, modes, out) }
    }
}

/** Legacy reports keypad navigation keys as their main-block twins. */
private fun mainBlockTwin(k: KeypadKey): Key? =
    when (k) {
        KeypadKey.Left -> Key.Left
        KeypadKey.Right -> Key.Right
        KeypadKey.Up -> Key.Up
        KeypadKey.Down -> Key.Down
        KeypadKey.PageUp -> Key.PageUp
        KeypadKey.PageDown -> Key.PageDown
        KeypadKey.Home -> Key.Home
        KeypadKey.End -> Key.End
        KeypadKey.Insert -> Key.Insert
        KeypadKey.Delete -> Key.Delete
        KeypadKey.Begin -> Key.Keypad(KeypadKey.Begin)
        else -> null
    }

/** DECKPAM finals from xterm's VT220-style keypad table. */
private fun applicationKeypadFinal(k: KeypadKey): Int? =
    when (k) {
        KeypadKey.Decimal -> 'n'.code
        KeypadKey.Divide -> 'o'.code
        KeypadKey.Multiply -> 'j'.code
        KeypadKey.Subtract -> 'm'.code
        KeypadKey.Add -> 'k'.code
        KeypadKey.Enter -> 'M'.code
        KeypadKey.Equal -> 'X'.code
        KeypadKey.Separator -> 'l'.code
        else -> if (k.ordinal <= KeypadKey.Digit9.ordinal) KEYPAD_DIGIT0_FINAL + k.ordinal else null
    }

/** A combination legacy cannot spell: modifyOtherKeys when enabled, else kitty's `CSI code ; mods u`, which kitty sends even in legacy mode. */
private fun fallback(
    code: Int,
    typed: Int,
    mods: Modifiers,
    modes: KeyModes,
    out: ByteSink,
) {
    if (modes.modifyOtherKeys != ModifyOtherKeys.Off) {
        modifyOtherKeys(typed, mods, out)
        return
    }
    out.csi()
    out.decimal(code)
    out.push(SEMI)
    out.decimal(mods.wire)
    out.push(FINAL_U)
}

private fun modifyOtherKeys(
    typed: Int,
    mods: Modifiers,
    out: ByteSink,
) {
    out.csi()
    out.decimal(MODIFY_OTHER_KEYS_NUMBER)
    out.push(SEMI)
    out.decimal(mods.wire)
    out.push(SEMI)
    out.decimal(typed)
    out.push(FINAL_TILDE)
}
