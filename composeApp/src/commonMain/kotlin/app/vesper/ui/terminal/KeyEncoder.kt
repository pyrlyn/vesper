package app.vesper.ui.terminal

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint

/** Translates key events into the byte sequences an xterm-compatible program expects. */
object KeyEncoder {
    private const val ESC = "\u001b"

    /**
     * Returns the sequence for [event], or null if the event is not for the terminal
     * (e.g. an app shortcut). Typed characters arrive as [KeyEventType.Unknown] (KEY_TYPED).
     */
    fun encode(event: KeyEvent, appCursor: Boolean, macOs: Boolean): String? {
        if (event.type == KeyEventType.Unknown) {
            if (event.isMetaPressed) return null
            val cp = event.utf16CodePoint
            if (cp < 0x20 || cp == 0x7f) return null // handled on key down
            if (event.isCtrlPressed && !macOs) return null
            return app.vesper.terminal.vt.codePointToString(cp)
        }
        if (event.type != KeyEventType.KeyDown) return null
        if (event.isMetaPressed) return null
        val mod = modifierParam(event)
        val key = event.key

        fun cursor(final: Char): String = when {
            mod > 1 -> "$ESC[1;$mod$final"
            appCursor -> "${ESC}O$final"
            else -> "$ESC[$final"
        }

        fun tilde(n: Int): String = if (mod > 1) "$ESC[$n;$mod~" else "$ESC[$n~"

        when (key) {
            Key.Enter, Key.NumPadEnter -> return if (event.isAltPressed) "$ESC\r" else "\r"
            Key.Backspace -> return when {
                event.isCtrlPressed -> "\u0008"
                event.isAltPressed -> "$ESC\u007f"
                else -> "\u007f"
            }
            Key.Tab -> return if (event.isShiftPressed) "$ESC[Z" else "\t"
            Key.Escape -> return ESC
            Key.DirectionUp -> return cursor('A')
            Key.DirectionDown -> return cursor('B')
            Key.DirectionRight -> return if (macOs && event.isAltPressed && !event.isShiftPressed && !event.isCtrlPressed) "${ESC}f" else cursor('C')
            Key.DirectionLeft -> return if (macOs && event.isAltPressed && !event.isShiftPressed && !event.isCtrlPressed) "${ESC}b" else cursor('D')
            Key.MoveHome -> return cursor('H')
            Key.MoveEnd -> return cursor('F')
            Key.Insert -> return tilde(2)
            Key.Delete -> return tilde(3)
            Key.PageUp -> return tilde(5)
            Key.PageDown -> return tilde(6)
            Key.F1 -> return if (mod > 1) "$ESC[1;${mod}P" else "${ESC}OP"
            Key.F2 -> return if (mod > 1) "$ESC[1;${mod}Q" else "${ESC}OQ"
            Key.F3 -> return if (mod > 1) "$ESC[1;${mod}R" else "${ESC}OR"
            Key.F4 -> return if (mod > 1) "$ESC[1;${mod}S" else "${ESC}OS"
            Key.F5 -> return tilde(15)
            Key.F6 -> return tilde(17)
            Key.F7 -> return tilde(18)
            Key.F8 -> return tilde(19)
            Key.F9 -> return tilde(20)
            Key.F10 -> return tilde(21)
            Key.F11 -> return tilde(23)
            Key.F12 -> return tilde(24)
        }

        if (event.isCtrlPressed) {
            controlChar(key)?.let { c -> return if (event.isAltPressed) ESC + c else c.toString() }
        }
        // Alt+letter as Meta on Windows/Linux (on macOS Option composes characters instead).
        if (!macOs && event.isAltPressed && !event.isCtrlPressed) {
            letter(key)?.let { ch -> return ESC + (if (event.isShiftPressed) ch.uppercaseChar() else ch) }
        }
        return null
    }

    private fun modifierParam(e: KeyEvent): Int {
        var m = 0
        if (e.isShiftPressed) m = m or 1
        if (e.isAltPressed) m = m or 2
        if (e.isCtrlPressed) m = m or 4
        return if (m == 0) 1 else m + 1
    }

    private val letters = listOf(
        Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
        Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
    )

    private fun letter(key: Key): Char? = letters.indexOf(key).takeIf { it >= 0 }?.let { 'a' + it }

    private fun controlChar(key: Key): Char? {
        letter(key)?.let { return (it.code - 'a'.code + 1).toChar() }
        return when (key) {
            Key.Spacebar, Key.Two -> '\u0000'
            Key.LeftBracket, Key.Three -> '\u001b'
            Key.Backslash, Key.Four -> '\u001c'
            Key.RightBracket, Key.Five -> '\u001d'
            Key.Six -> '\u001e'
            Key.Minus, Key.Seven, Key.Slash -> '\u001f'
            Key.Eight -> '\u007f'
            else -> null
        }
    }
}
