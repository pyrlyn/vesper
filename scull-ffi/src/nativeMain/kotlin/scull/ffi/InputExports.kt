package scull.ffi

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.value
import scull.ffi.c.TT_CLOSED
import scull.ffi.c.TT_FULL
import scull.ffi.c.tt_key_event
import scull.ffi.c.tt_mouse_event
import scull.input.ByteSink
import scull.input.Key
import scull.input.KeyAction
import scull.input.KeyEvent
import scull.input.Modifiers
import scull.input.MouseAction
import scull.input.MouseButton
import scull.input.MouseEvent
import scull.pty.PtyException
import scull.pty.PtyLimits
import scull.pty.withLock
import scull.term.Terminal
import kotlin.native.CName

// Input from the host to the child: the terminal turns each event into the bytes its modes ask for,
// and they go to the child whole, since a report cut in two would reach it as a broken sequence.

/** Bytes of `tt_key_event` the core needs: up to and including `composing`. */
private const val KEY_EVENT_MIN = 16

/** Bytes of `tt_mouse_event` the core needs: up to and including `row`. */
private const val MOUSE_EVENT_MIN = 16

/** Thrown for input that could never fit the child's queue, so a huge host buffer is not copied first. */
private class TooLarge : Refused(TT_FULL)

/**
 * Encodes under the lock and writes the bytes to the child. With [follow], input that sends bytes
 * also brings the viewport back to the screen, where what was typed shows.
 */
private inline fun TermHandle.send(
    follow: Boolean,
    encode: (Terminal, ByteSink) -> Unit,
): UInt {
    val out = ByteSink()
    lock.withLock {
        encode(terminal, out)
        if (follow && !out.isEmpty()) terminal.scrollDisplay(Int.MIN_VALUE)
    }
    if (out.isEmpty()) return OK
    val pty = pty ?: return TT_CLOSED
    return try {
        if (pty.writeInputWhole(out.toByteArray())) OK else TT_FULL
    } catch (_: PtyException.Closed) {
        TT_CLOSED
    }
}

/** The host's UTF-8 text, refused before the copy when it is longer than the child's queue. */
private fun hostText(
    bytes: CPointer<ByteVar>?,
    len: ULong,
): String {
    if (len > PtyLimits.MAX_PENDING_WRITE_BYTES.toULong()) throw TooLarge()
    return utf8(bytesAt(bytes, len))
}

private fun tt_key_event.toEvent(): KeyEvent {
    if (text.len > PtyLimits.MAX_PENDING_WRITE_BYTES.toULong()) throw TooLarge()
    return KeyEvent(
        key = Key.ofCode(key.toCode()) ?: throw Invalid(),
        action = KeyAction.entries.firstOrNull { it.code == action.toInt() } ?: throw Invalid(),
        mods = Modifiers.of(mods.toInt()),
        consumed = Modifiers.of(consumed_mods.toInt()),
        text = text.text(),
        unshifted = if (unshifted == 0u) null else unshifted.toCode(),
        composing = composing.toInt() != 0,
    )
}

/** A code point from C; past `Int` it names nothing, and -1 says so to [Key.ofCode]. */
private fun UInt.toCode(): Int = if (this > Int.MAX_VALUE.toUInt()) -1 else toInt()

private fun tt_mouse_event.toEvent(): MouseEvent {
    val kind = MouseAction.entries.getOrNull(action.toInt() - 1) ?: throw Invalid()
    val pressed =
        if (button.toInt() == 0) {
            if (kind != MouseAction.Motion) throw Invalid()
            null
        } else {
            // X11 numbers the buttons from 1 in the order MouseButton declares them.
            MouseButton.entries.getOrNull(button.toInt() - 1) ?: throw Invalid()
        }
    return MouseEvent(
        action = kind,
        button = pressed,
        mods = Modifiers.of(mods.toInt()),
        col = col.toPosition(),
        row = row.toPosition(),
        xPx = x_px.toPosition(),
        yPx = y_px.toPosition(),
    )
}

/** Positions past `Int` are far outside any viewport; the encoders clamp them like any other. */
private fun UInt.toPosition(): Int = coerceAtMost(Int.MAX_VALUE.toUInt()).toInt()

@CName("tt_term_key")
public fun ttTermKey(
    term: COpaquePointer?,
    event: CPointer<tt_key_event>?,
): UInt =
    guarded(term.term()) { handle ->
        if (event == null) throw Invalid()
        val key = memScoped { (readStruct(event, KEY_EVENT_MIN) ?: throw Invalid()).toEvent() }
        handle.send(follow = true) { terminal, out -> terminal.encodeKey(key, out) }
    }

@CName("tt_term_text")
public fun ttTermText(
    term: COpaquePointer?,
    bytes: CPointer<ByteVar>?,
    len: ULong,
): UInt =
    guarded(term.term()) { handle ->
        val text = hostText(bytes, len)
        handle.send(follow = true) { terminal, out -> terminal.encodeText(text, out) }
    }

@CName("tt_term_paste")
public fun ttTermPaste(
    term: COpaquePointer?,
    bytes: CPointer<ByteVar>?,
    len: ULong,
): UInt =
    guarded(term.term()) { handle ->
        val text = hostText(bytes, len)
        handle.send(follow = true) { terminal, out -> terminal.encodePaste(text, out) }
    }

@CName("tt_term_focus")
public fun ttTermFocus(
    term: COpaquePointer?,
    focused: UByte,
): UInt =
    guarded(term.term()) { handle ->
        handle.send(follow = false) { terminal, out -> terminal.encodeFocus(focused.toInt() != 0, out) }
    }

@CName("tt_term_mouse")
public fun ttTermMouse(
    term: COpaquePointer?,
    event: CPointer<tt_mouse_event>?,
    taken: CPointer<UByteVar>?,
): UInt =
    guarded(term.term()) { handle ->
        taken?.pointed?.value = 0u
        if (event == null) throw Invalid()
        val mouse = memScoped { (readStruct(event, MOUSE_EVENT_MIN) ?: throw Invalid()).toEvent() }
        var took = false
        val status = handle.send(follow = false) { terminal, out -> took = terminal.encodeMouse(mouse, out) }
        taken?.pointed?.value = if (took) 1u else 0u
        status
    }

@CName("tt_term_scroll_display")
public fun ttTermScrollDisplay(
    term: COpaquePointer?,
    delta: Int,
): UInt =
    guarded(term.term()) { handle ->
        handle.lock.withLock { handle.terminal.scrollDisplay(delta) }
        OK
    }
