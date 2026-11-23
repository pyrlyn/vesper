package scull.ffi

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import scull.ffi.c.TT_EMPTY
import scull.ffi.c.TT_SEARCH_IGNORE_CASE
import scull.ffi.c.TT_SELECT_BLOCK
import scull.ffi.c.TT_SELECT_CELL
import scull.ffi.c.TT_SELECT_LINE
import scull.ffi.c.TT_SELECT_WORD
import scull.ffi.c.tt_match
import scull.pty.withLock
import scull.term.MAX_SEARCH_PATTERN
import scull.term.SelectionKind
import kotlin.native.CName

// Selection and scrollback search for the host's mouse, copy and find bar. The core keeps both and
// marks their cells in the frame; the host calls `tt_frame_update` after a change to see it.

/** `TT_SELECT_*` spelled out rather than taken from the ordinal, so reordering the enum cannot renumber the ABI. */
private fun selectionKind(kind: UInt): SelectionKind =
    when (kind.toInt()) {
        TT_SELECT_CELL -> SelectionKind.Cell
        TT_SELECT_WORD -> SelectionKind.Word
        TT_SELECT_LINE -> SelectionKind.Line
        TT_SELECT_BLOCK -> SelectionKind.Block
        else -> throw Invalid()
    }

@CName("tt_term_select_start")
public fun ttTermSelectStart(
    term: COpaquePointer?,
    kind: UInt,
    row: UShort,
    col: UShort,
): UInt =
    guarded(term.term()) { handle ->
        val k = selectionKind(kind)
        handle.lock.withLock {
            val t = handle.terminal
            t.selectStart(k, t.viewportPoint(row.toInt(), col.toInt()))
        }
        OK
    }

@CName("tt_term_select_extend")
public fun ttTermSelectExtend(
    term: COpaquePointer?,
    row: UShort,
    col: UShort,
): UInt =
    guarded(term.term()) { handle ->
        handle.lock.withLock {
            val t = handle.terminal
            t.selectExtend(t.viewportPoint(row.toInt(), col.toInt()))
        }
        OK
    }

@CName("tt_term_select_clear")
public fun ttTermSelectClear(term: COpaquePointer?): UInt =
    guarded(term.term()) { handle ->
        handle.lock.withLock { handle.terminal.selectClear() }
        OK
    }

@CName("tt_term_selection_text")
public fun ttTermSelectionText(
    term: COpaquePointer?,
    buf: CPointer<ByteVar>?,
    cap: ULong,
    len: CPointer<ULongVar>?,
): UInt =
    guarded(term.term()) { handle ->
        checkOut(buf, cap, len)
        val text =
            handle.lock.withLock {
                val t = handle.terminal
                if (t.selection == null) null else t.selectionText()
            }
        if (text == null) {
            checkNotNull(len).pointed.value = 0u
            return@guarded TT_EMPTY
        }
        copyOut(text.encodeToByteArray(), buf, cap, len)
    }

@CName("tt_term_search_set")
public fun ttTermSearchSet(
    term: COpaquePointer?,
    pattern: CPointer<ByteVar>?,
    patternLen: ULong,
    flags: UInt,
): UInt =
    guarded(term.term()) { handle ->
        // Checked before the copy: a host buffer past the cap is never read.
        val text =
            if (flags and TT_SEARCH_IGNORE_CASE.toUInt().inv() != 0u || patternLen > MAX_SEARCH_PATTERN.toULong()) {
                null
            } else {
                try {
                    bytesAt(pattern, patternLen).decodeToString(throwOnInvalidSequence = true)
                } catch (_: CharacterCodingException) {
                    null
                }
            }
        val accepted =
            handle.lock.withLock {
                val t = handle.terminal
                if (text.isNullOrEmpty()) {
                    t.searchClear()
                    text != null
                } else {
                    t.searchSet(text, caseSensitive = flags == 0u)
                }
            }
        if (accepted) OK else throw Invalid()
    }

@CName("tt_term_search_step")
public fun ttTermSearchStep(
    term: COpaquePointer?,
    forward: UByte,
    found: CPointer<tt_match>?,
): UInt =
    guarded(term.term()) { handle ->
        val m =
            handle.lock.withLock {
                val t = handle.terminal
                if (forward == 0.toUByte()) t.searchPrev() else t.searchNext()
            } ?: return@guarded TT_EMPTY
        // The step happened either way; a host with no room for the match only loses the report.
        if (found != null) {
            memScoped {
                val out = alloc<tt_match>()
                out.start_col = m.start.col.toUShort()
                out.end_col = m.end.col.toUShort()
                out.start_line = m.start.line.toULong()
                out.end_line = m.end.line.toULong()
                writeStruct(out, found, UInt.SIZE_BYTES)
            }
        }
        OK
    }

@CName("tt_term_search_count")
public fun ttTermSearchCount(
    term: COpaquePointer?,
    count: CPointer<ULongVar>?,
): UInt =
    guarded(term.term()) { handle ->
        if (count == null) throw Invalid()
        count.pointed.value = handle.lock.withLock { handle.terminal.searchCount() }.toULong()
        OK
    }
