package scull.ffi

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.value
import scull.ffi.c.TT_ABI_MISMATCH
import scull.ffi.c.TT_CLOSED
import scull.ffi.c.TT_INVALID
import scull.ffi.c.TT_IO
import scull.ffi.c.tt_term_options
import scull.grid.MAX_COLS
import scull.pty.Pty
import scull.pty.PtyException
import scull.pty.PtySize
import scull.pty.SpawnOptions
import scull.pty.withLock
import scull.term.MAX_ROWS
import scull.term.Terminal
import kotlin.native.CName

/** Bytes fed to the parser per lock hold, so a huge `tt_term_feed` neither copies all of it at once nor starves a frame read. */
private const val FEED_CHUNK = 64 * 1024

private const val MAX_U16 = 0xFFFF

/** Size and history from options already read; refuses what no terminal can be. */
private class Shape(
    options: tt_term_options,
) {
    val cols = options.cols.toInt()
    val rows = options.rows.toInt()
    val scrollback = options.scrollback.coerceAtMost(Int.MAX_VALUE.toUInt()).toInt()

    init {
        if (cols !in 1..MAX_COLS || rows !in 1..MAX_ROWS) throw Invalid()
    }
}

/** Reads [options] and makes the terminal, or answers why not. */
private inline fun create(
    options: CPointer<tt_term_options>?,
    out: CPointer<COpaquePointerVar>?,
    make: (tt_term_options, Shape) -> TermHandle,
): UInt =
    guardedCreate {
        if (options == null || out == null) return@guardedCreate TT_INVALID
        memScoped {
            val read = readStruct(options, TERM_OPTIONS_MIN) ?: return@guardedCreate TT_INVALID
            if (!abiCompatible(read.abi_version)) return@guardedCreate TT_ABI_MISMATCH
            val handle = make(read, Shape(read))
            out.pointed.value = handle.toPointer()
        }
        OK
    }

@CName("tt_term_new")
public fun ttTermNew(
    options: CPointer<tt_term_options>?,
    out: CPointer<COpaquePointerVar>?,
): UInt =
    create(options, out) { read, shape ->
        TermHandle(Terminal(shape.cols, shape.rows, shape.scrollback), read.wakeup, read.userdata)
    }

@CName("tt_term_spawn")
public fun ttTermSpawn(
    options: CPointer<tt_term_options>?,
    out: CPointer<COpaquePointerVar>?,
): UInt =
    create(options, out) { read, shape ->
        val spawn = read.spawnOptions(shape)
        val handle = TermHandle(Terminal(shape.cols, shape.rows, shape.scrollback), read.wakeup, read.userdata)
        handle.pty =
            try {
                Pty.spawn(spawn, handle.lock, handle.sink, handle::wake)
            } catch (_: PtyException) {
                throw Refused(TT_IO)
            }
        handle
    }

/** What to run, from the fields `tt_term_new` does not read. */
private fun tt_term_options.spawnOptions(shape: Shape): SpawnOptions {
    val env =
        texts(env, env_len).associate { entry ->
            val eq = entry.indexOf('=')
            if (eq <= 0) throw Invalid()
            entry.substring(0, eq) to entry.substring(eq + 1)
        }
    return SpawnOptions(
        program = program.text().ifEmpty { null },
        args = texts(args, args_len),
        cwd = cwd.text().ifEmpty { null },
        env = env,
        size = PtySize(rows = shape.rows, cols = shape.cols),
    )
}

@CName("tt_term_feed")
public fun ttTermFeed(
    term: COpaquePointer?,
    bytes: CPointer<ByteVar>?,
    len: ULong,
): UInt =
    guarded(term.term()) { handle ->
        val total = len.toLength()
        if (total > 0 && bytes == null) throw Invalid()
        var at = 0
        while (at < total) {
            val chunk = bytesAt(bytes?.plus(at), minOf(FEED_CHUNK, total - at).toULong())
            handle.lock.withLock { handle.terminal.feed(chunk) }
            at += chunk.size
        }
        OK
    }

@CName("tt_term_write")
public fun ttTermWrite(
    term: COpaquePointer?,
    bytes: CPointer<ByteVar>?,
    len: ULong,
    written: CPointer<ULongVar>?,
): UInt =
    guarded(term.term()) { handle ->
        written?.pointed?.value = 0u
        val input = bytesAt(bytes, len)
        val pty = handle.pty ?: return@guarded TT_CLOSED
        val taken =
            try {
                pty.writeInput(input)
            } catch (_: PtyException.Closed) {
                return@guarded TT_CLOSED
            }
        written?.pointed?.value = taken.toULong()
        OK
    }

@CName("tt_term_resize_begin")
public fun ttTermResizeBegin(term: COpaquePointer?): UInt =
    guarded(term.term()) { handle ->
        handle.pty?.pauseOutput()
        OK
    }

@CName("tt_term_resize")
public fun ttTermResize(
    term: COpaquePointer?,
    cols: UShort,
    rows: UShort,
    widthPx: UShort,
    heightPx: UShort,
): UInt =
    guarded(term.term()) { handle ->
        // Output held back by tt_term_resize_begin flows again whatever happens here.
        try {
            val c = cols.toInt()
            val r = rows.toInt()
            if (c !in 1..MAX_COLS || r !in 1..MAX_ROWS) return@guarded TT_INVALID
            handle.lock.withLock { handle.terminal.resize(c, r) }
            val pty = handle.pty ?: return@guarded OK
            try {
                pty.resize(PtySize(rows = r.coerceAtMost(MAX_U16), cols = c, pixelWidth = widthPx.toInt(), pixelHeight = heightPx.toInt()))
            } catch (_: PtyException.Closed) {
                // The child has gone: nothing to tell, and the terminal took the size.
            } catch (_: PtyException.Resize) {
                return@guarded TT_IO
            }
            OK
        } finally {
            handle.pty?.resumeOutput()
        }
    }

@CName("tt_term_free")
public fun ttTermFree(term: COpaquePointer?) {
    val ref = term?.asStableRef<TermHandle>() ?: return
    try {
        // Outside the lock: closing waits for the I/O thread, which may be waiting for the lock.
        ref.get().pty?.close()
    } catch (_: Throwable) {
        // Freeing must not fail; the threads stop on their own when the PTY closes.
    } finally {
        ref.dispose()
    }
}
