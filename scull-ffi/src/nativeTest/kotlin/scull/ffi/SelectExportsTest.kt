package scull.ffi

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import scull.ffi.c.TT_CELL_CLUSTER
import scull.ffi.c.TT_CELL_CURRENT_MATCH
import scull.ffi.c.TT_CELL_MATCH
import scull.ffi.c.TT_CELL_SELECTED
import scull.ffi.c.TT_EMPTY
import scull.ffi.c.TT_FULL
import scull.ffi.c.TT_INVALID
import scull.ffi.c.TT_MAX_SEARCH_MATCHES
import scull.ffi.c.TT_MAX_SEARCH_PATTERN
import scull.ffi.c.TT_MAX_SELECTION_BYTES
import scull.ffi.c.TT_OK
import scull.ffi.c.TT_SEARCH_IGNORE_CASE
import scull.ffi.c.TT_SELECT_CELL
import scull.ffi.c.TT_SELECT_LINE
import scull.ffi.c.TT_SELECT_WORD
import scull.ffi.c.tt_match
import scull.term.FrameCell
import scull.term.MAX_SEARCH_MATCHES
import scull.term.MAX_SEARCH_PATTERN
import scull.term.MAX_SELECTION_BYTES
import kotlin.test.Test
import kotlin.test.assertEquals

/** The selected text with the status and the length the core reported. */
private fun selectionText(
    term: COpaquePointer,
    cap: Int = 256,
): Triple<UInt, Int, String> =
    memScoped {
        val buf = allocArray<ByteVar>(cap.coerceAtLeast(1))
        val len = alloc<ULongVar>()
        len.value = 99u
        val status = ttTermSelectionText(term, if (cap == 0) null else buf, cap.toULong(), len.ptr)
        val n = len.value.toInt()
        Triple(status, n, if (status == TT_OK) buf.readBytes(n).decodeToString() else "")
    }

private fun searchSet(
    term: COpaquePointer,
    pattern: ByteArray,
    flags: Int = 0,
): UInt =
    if (pattern.isEmpty()) {
        ttTermSearchSet(term, null, 0u, flags.toUInt())
    } else {
        pattern.usePinned { ttTermSearchSet(term, it.addressOf(0), pattern.size.toULong(), flags.toUInt()) }
    }

private fun searchSet(
    term: COpaquePointer,
    pattern: String,
    flags: Int = 0,
): UInt = searchSet(term, pattern.encodeToByteArray(), flags)

private fun count(term: COpaquePointer): Int =
    memScoped {
        val n = alloc<ULongVar>()
        assertEquals(TT_OK, ttTermSearchCount(term, n.ptr))
        n.value.toInt()
    }

/** The status of a step and the match as `line,col-line,col`, empty without one. */
private fun step(
    term: COpaquePointer,
    forward: Boolean,
): Pair<UInt, String> =
    memScoped {
        val m = alloc<tt_match>()
        m.struct_size = sizeOf<tt_match>().toUInt()
        val status = ttTermSearchStep(term, if (forward) 1u else 0u, m.ptr)
        status to if (status == TT_OK) "${m.start_line},${m.start_col}-${m.end_line},${m.end_col}" else ""
    }

class SelectExportsTest {
    @Test
    fun theHeaderAgreesWithTheCore() {
        assertEquals(
            listOf(FrameCell.CLUSTER, FrameCell.SELECTED, FrameCell.MATCH, FrameCell.CURRENT_MATCH),
            listOf(TT_CELL_CLUSTER, TT_CELL_SELECTED, TT_CELL_MATCH, TT_CELL_CURRENT_MATCH),
        )
        assertEquals(
            listOf(MAX_SEARCH_PATTERN, MAX_SEARCH_MATCHES, MAX_SELECTION_BYTES),
            listOf(TT_MAX_SEARCH_PATTERN, TT_MAX_SEARCH_MATCHES, TT_MAX_SELECTION_BYTES),
        )
    }

    @Test
    fun aSelectionIsCopiedAndMarkedInTheFrame() {
        val term = newTerm(cols = 10, rows = 3)
        term.feed("one two\r\nthree")
        assertEquals(Triple(TT_EMPTY, 0, ""), selectionText(term), "nothing selected")
        assertEquals(TT_OK, ttTermSelectStart(term, TT_SELECT_WORD.toUInt(), 0u, 5u))
        assertEquals(Triple(TT_OK, 3, "two"), selectionText(term))
        assertEquals(TT_OK, ttTermSelectStart(term, TT_SELECT_CELL.toUInt(), 0u, 4u))
        assertEquals(TT_OK, ttTermSelectExtend(term, 1u, 1u))
        assertEquals("two\nth", selectionText(term).third)
        val frame = ttFrameNew()!!
        val view = update(frame, term)
        assertEquals(listOf(0, TT_CELL_SELECTED), listOf(view.cell(0, 3)[3], view.cell(0, 4)[3]))
        assertEquals(TT_CELL_SELECTED, view.cell(1, 1)[3])
        assertEquals(0, view.cell(1, 2)[3])
        assertEquals(TT_OK, ttTermSelectClear(term))
        assertEquals(TT_EMPTY, selectionText(term).first)
        assertEquals(0, update(frame, term).cell(0, 4)[3])
        ttFrameFree(frame)
        ttTermFree(term)
    }

    @Test
    fun selectionArgumentsAreCheckedAndClamped() {
        val term = newTerm(cols = 10, rows = 3)
        term.feed("abc")
        assertEquals(TT_INVALID, ttTermSelectStart(term, 4u, 0u, 0u), "unknown kind")
        assertEquals(TT_INVALID, ttTermSelectStart(null, 0u, 0u, 0u))
        assertEquals(TT_OK, ttTermSelectStart(term, TT_SELECT_LINE.toUInt(), 60000u, 60000u))
        assertEquals("", selectionText(term).third, "clamped to the blank last row")
        assertEquals(TT_OK, ttTermSelectExtend(term, 0u, 0u))
        assertEquals("abc\n\n", selectionText(term).third)
        assertEquals(Triple(TT_FULL, 5, ""), selectionText(term, cap = 2))
        assertEquals(Triple(TT_FULL, 5, ""), selectionText(term, cap = 0))
        memScoped {
            val len = alloc<ULongVar>()
            assertEquals(TT_INVALID, ttTermSelectionText(term, null, 0u, null))
            assertEquals(TT_INVALID, ttTermSelectionText(term, null, 4u, len.ptr))
        }
        ttTermFree(term)
    }

    @Test
    fun searchSetsStepsAndCounts() {
        val term = newTerm(cols = 10, rows = 3)
        term.feed("Foo foo\r\nfoo")
        assertEquals(0, count(term), "no search")
        assertEquals(TT_EMPTY to "", step(term, forward = true))
        assertEquals(TT_OK, searchSet(term, "foo"))
        assertEquals(2, count(term))
        assertEquals(TT_OK, searchSet(term, "FOO", TT_SEARCH_IGNORE_CASE))
        assertEquals(3, count(term))
        assertEquals(TT_OK to "0,0-0,2", step(term, forward = true))
        assertEquals(TT_OK to "0,4-0,6", step(term, forward = true))
        assertEquals(TT_OK to "0,0-0,2", step(term, forward = false))
        val frame = ttFrameNew()!!
        val view = update(frame, term)
        assertEquals(TT_CELL_MATCH or TT_CELL_CURRENT_MATCH, view.cell(0, 0)[3])
        assertEquals(TT_CELL_MATCH, view.cell(0, 4)[3])
        assertEquals(TT_OK, ttTermSearchStep(term, 1u, null), "a NULL match still steps")
        assertEquals(TT_CELL_MATCH, update(frame, term).cell(0, 0)[3])
        assertEquals(TT_OK, searchSet(term, ""), "an empty pattern ends the search")
        assertEquals(0, count(term))
        assertEquals(0, update(frame, term).cell(0, 4)[3])
        ttFrameFree(frame)
        ttTermFree(term)
    }

    @Test
    fun aRefusedPatternEndsTheSearch() {
        val term = newTerm()
        term.feed("abc")
        for ((pattern, flags) in listOf(
            byteArrayOf(0xC3.toByte()) to 0,
            "b".encodeToByteArray() to 2,
            "b".repeat(TT_MAX_SEARCH_PATTERN + 1).encodeToByteArray() to 0,
        )) {
            assertEquals(TT_OK, searchSet(term, "b"))
            assertEquals(TT_INVALID, searchSet(term, pattern, flags))
            assertEquals(0, count(term))
        }
        assertEquals(TT_INVALID, ttTermSearchSet(term, null, 3u, 0u), "a NULL pattern with a length")
        assertEquals(TT_INVALID, ttTermSearchCount(term, null))
        ttTermFree(term)
    }
}
