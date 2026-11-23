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
import scull.ffi.c.TT_CLOSED
import scull.ffi.c.TT_EMPTY
import scull.ffi.c.TT_EVENT_BELL
import scull.ffi.c.TT_EVENT_CHILD_EXITED
import scull.ffi.c.TT_EVENT_CLIPBOARD_READ
import scull.ffi.c.TT_EVENT_CLIPBOARD_WRITE
import scull.ffi.c.TT_EVENT_DIRECTORY
import scull.ffi.c.TT_EVENT_LINK
import scull.ffi.c.TT_EVENT_NOTIFICATION
import scull.ffi.c.TT_EVENT_SHELL_MARK
import scull.ffi.c.TT_EVENT_TEXT_BODY
import scull.ffi.c.TT_EVENT_TEXT_TITLE
import scull.ffi.c.TT_EVENT_TITLE
import scull.ffi.c.TT_EXIT_CODE_UNKNOWN
import scull.ffi.c.TT_FULL
import scull.ffi.c.TT_SHELL_COMMAND_EXECUTED
import scull.ffi.c.TT_SHELL_COMMAND_FINISHED
import scull.ffi.c.TT_SHELL_COMMAND_START
import scull.ffi.c.TT_SHELL_PROMPT_START
import scull.ffi.c.tt_event
import scull.grid.LinkId
import scull.pty.PtyException
import scull.pty.PtyLimits
import scull.pty.withLock
import scull.term.Event
import scull.term.MAX_CLIPBOARD_BYTES
import scull.term.ShellMarkKind
import kotlin.native.CName

/** What one poll took: an event of the terminal, or the child's exit after all of them. */
private class Taken(
    val kind: Int,
    val detail: Int = 0,
    val id: Int = 0,
    val exitCode: UInt = 0u,
    val signaled: Boolean = false,
    val text: ByteArray? = null,
    val title: ByteArray? = null,
)

private fun Event.taken(): Taken =
    when (this) {
        Event.Bell -> {
            Taken(TT_EVENT_BELL)
        }

        is Event.Title -> {
            Taken(TT_EVENT_TITLE, detail = target.code, text = text.encodeToByteArray())
        }

        is Event.Directory -> {
            Taken(TT_EVENT_DIRECTORY, text = uri.encodeToByteArray())
        }

        is Event.ShellMark -> {
            val finished = kind == ShellMarkKind.CommandFinished
            Taken(
                TT_EVENT_SHELL_MARK,
                detail = kind.abi(),
                exitCode = if (finished) exitCode?.toUInt() ?: TT_EXIT_CODE_UNKNOWN else 0u,
            )
        }

        is Event.ClipboardWrite -> {
            Taken(TT_EVENT_CLIPBOARD_WRITE, text = data, title = targets.encodeToByteArray())
        }

        is Event.ClipboardRead -> {
            Taken(TT_EVENT_CLIPBOARD_READ, title = targets.encodeToByteArray())
        }

        is Event.Link -> {
            Taken(TT_EVENT_LINK, id = id.value, text = uri.encodeToByteArray())
        }

        is Event.Notification -> {
            Taken(TT_EVENT_NOTIFICATION, text = body.encodeToByteArray(), title = title.encodeToByteArray())
        }
    }

/** `TT_SHELL_*` spelled out rather than taken from the ordinal, so reordering the enum cannot renumber the ABI. */
private fun ShellMarkKind.abi(): Int =
    when (this) {
        ShellMarkKind.PromptStart -> TT_SHELL_PROMPT_START
        ShellMarkKind.CommandStart -> TT_SHELL_COMMAND_START
        ShellMarkKind.CommandExecuted -> TT_SHELL_COMMAND_EXECUTED
        ShellMarkKind.CommandFinished -> TT_SHELL_COMMAND_FINISHED
    }

private fun TermHandle.take(): Taken? {
    terminal.pollEvent()?.let { return it.taken() }
    // The exit comes last, after every event the child's output raised.
    val status = exit ?: return null
    if (exitReported) return null
    exitReported = true
    return Taken(TT_EVENT_CHILD_EXITED, exitCode = status.code, signaled = status.signal != null)
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
                handle.heldTitle = taken.title
                taken to handle.serial
            }
        memScoped {
            val out = alloc<tt_event>()
            out.kind = taken.kind.toUInt()
            out.serial = serial
            out.id = taken.id.toULong()
            out.detail = taken.detail.toUInt()
            out.exit_code = taken.exitCode
            out.signaled = if (taken.signaled) 1u else 0u
            out.text_len = (taken.text?.size ?: 0).toUInt()
            out.title_len = (taken.title?.size ?: 0).toUInt()
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
        val title =
            when (part) {
                TT_EVENT_TEXT_BODY.toUInt() -> false
                TT_EVENT_TEXT_TITLE.toUInt() -> true
                else -> throw Invalid()
            }
        checkOut(buf, cap, len)
        val text =
            handle.lock.withLock {
                when {
                    serial == 0uL || serial != handle.heldSerial -> null
                    title -> handle.heldTitle ?: ByteArray(0)
                    else -> handle.heldText ?: ByteArray(0)
                }
            } ?: return@guarded TT_EMPTY
        copyOut(text, buf, cap, len)
    }

@CName("tt_term_link_uri")
public fun ttTermLinkUri(
    term: COpaquePointer?,
    id: UInt,
    buf: CPointer<ByteVar>?,
    cap: ULong,
    len: CPointer<ULongVar>?,
): UInt =
    guarded(term.term()) { handle ->
        checkOut(buf, cap, len)
        // Ids past an Int are no link the core handed out.
        val link = id.toInt().takeIf { it > 0 }?.let(::LinkId) ?: return@guarded TT_EMPTY
        val uri = handle.lock.withLock { handle.terminal.linkUri(link) } ?: return@guarded TT_EMPTY
        copyOut(uri.encodeToByteArray(), buf, cap, len)
    }

@CName("tt_term_clipboard_reply")
public fun ttTermClipboardReply(
    term: COpaquePointer?,
    targets: CPointer<ByteVar>?,
    targetsLen: ULong,
    data: CPointer<ByteVar>?,
    len: ULong,
): UInt =
    guarded(term.term()) { handle ->
        // Refused before the copy: a host buffer past the cap is never read.
        if (len > MAX_CLIPBOARD_BYTES.toULong()) throw Invalid()
        handle.sendClipboard(bytesAt(targets, targetsLen), bytesAt(data, len))
    }

@CName("tt_term_clipboard_deny")
public fun ttTermClipboardDeny(
    term: COpaquePointer?,
    targets: CPointer<ByteVar>?,
    targetsLen: ULong,
): UInt = guarded(term.term()) { handle -> handle.sendClipboard(bytesAt(targets, targetsLen), ByteArray(0)) }

/**
 * Writes the OSC 52 answer straight to the child's input, whole. Queued with the terminal's replies
 * it would leave only when the child next writes, and a program waiting for it writes nothing.
 */
private fun TermHandle.sendClipboard(
    targetBytes: ByteArray,
    data: ByteArray,
): UInt {
    val targets =
        try {
            targetBytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            throw Invalid()
        }
    val reply =
        try {
            lock.withLock { terminal.clipboardReply(targets, data) }
        } catch (_: IllegalArgumentException) {
            // Only the targets and the size are checked there; nothing in the terminal changed.
            throw Invalid()
        }
    if (reply.size > PtyLimits.MAX_PENDING_WRITE_BYTES) throw Invalid()
    val pty = pty ?: return TT_CLOSED
    return try {
        if (pty.writeInputWhole(reply)) OK else TT_FULL
    } catch (_: PtyException.Closed) {
        TT_CLOSED
    }
}

/** The out-parameter rules every sized copy shares: a `len`, and a `buf` whenever there is a `cap`. */
private fun checkOut(
    buf: CPointer<ByteVar>?,
    cap: ULong,
    len: CPointer<ULongVar>?,
) {
    if (len == null || (buf == null && cap > 0u)) throw Invalid()
}

/** Reports [bytes]' length in [len] and copies them into [buf], or answers `TT_FULL` with nothing copied when [cap] is short. */
private fun copyOut(
    bytes: ByteArray,
    buf: CPointer<ByteVar>?,
    cap: ULong,
    len: CPointer<ULongVar>?,
): UInt {
    checkNotNull(len).pointed.value = bytes.size.toULong()
    if (bytes.size.toULong() > cap) return TT_FULL
    if (bytes.isNotEmpty()) bytes.usePinned { memcpy(buf, it.addressOf(0), bytes.size.toULong()) }
    return OK
}
