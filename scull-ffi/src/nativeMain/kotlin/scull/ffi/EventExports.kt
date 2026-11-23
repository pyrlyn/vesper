package scull.ffi

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.memcpy
import scull.ffi.c.TT_EMPTY
import scull.ffi.c.TT_EVENT_BELL
import scull.ffi.c.TT_EVENT_CHILD_EXITED
import scull.ffi.c.TT_EVENT_TEXT_BODY
import scull.ffi.c.TT_EVENT_TITLE
import scull.ffi.c.TT_FULL
import scull.ffi.c.tt_event
import scull.pty.ExitStatus
import scull.pty.withLock
import scull.term.Event
import kotlin.native.CName

/** What one poll took: an event of the terminal, or the child's exit after all of them. */
private class Taken(
    val kind: UInt,
    val detail: UInt = 0u,
    val text: ByteArray? = null,
    val exit: ExitStatus? = null,
)

private fun TermHandle.take(): Taken? {
    // T13.3 gives the shell-integration events their kinds; until then a C host is not shown them.
    while (true) {
        when (val event = terminal.pollEvent()) {
            Event.Bell -> return Taken(TT_EVENT_BELL.toUInt())
            is Event.Title -> return Taken(TT_EVENT_TITLE.toUInt(), event.target.code.toUInt(), event.text.encodeToByteArray())
            null -> break
            else -> continue
        }
    }
    // The exit comes last, after every event the child's output raised.
    val status = exit ?: return null
    if (exitReported) return null
    exitReported = true
    return Taken(TT_EVENT_CHILD_EXITED.toUInt(), exit = status)
}

@CName("tt_term_poll_event")
public fun ttTermPollEvent(
    term: COpaquePointer?,
    event: CPointer<tt_event>?,
): UInt =
    guarded(term.term()) { handle ->
        // Checked before taking anything, so a refused call loses no event.
        if (event == null || structSize(event) < sizeOf<tt_event>()) throw Invalid()
        handle.rearm()
        val (taken, serial) =
            handle.lock.withLock {
                val taken = handle.take() ?: return@guarded TT_EMPTY
                handle.serial++
                handle.heldSerial = handle.serial
                handle.heldText = taken.text
                taken to handle.serial
            }
        memScoped {
            val out = alloc<tt_event>()
            out.kind = taken.kind
            out.serial = serial
            out.detail = taken.detail
            out.text_len = (taken.text?.size ?: 0).toUInt()
            taken.exit?.let {
                out.exit_code = it.code
                out.signaled = if (it.signal != null) 1u else 0u
            }
            writeStruct(out, event, sizeOf<tt_event>().toInt())
        }
        OK
    }

@CName("tt_term_event_text")
public fun ttTermEventText(
    term: COpaquePointer?,
    serial: ULong,
    part: UInt,
    buf: CPointer<ByteVar>?,
    cap: ULong,
    len: CPointer<ULongVar>?,
): UInt =
    guarded(term.term()) { handle ->
        if (len == null || (buf == null && cap > 0u) || part != TT_EVENT_TEXT_BODY.toUInt()) throw Invalid()
        val text =
            handle.lock.withLock {
                if (serial == 0uL || serial != handle.heldSerial) null else handle.heldText ?: ByteArray(0)
            } ?: return@guarded TT_EMPTY
        len.pointed.value = text.size.toULong()
        if (text.size.toULong() > cap) return@guarded TT_FULL
        if (text.isNotEmpty()) text.usePinned { memcpy(buf, it.addressOf(0), text.size.toULong()) }
        OK
    }
