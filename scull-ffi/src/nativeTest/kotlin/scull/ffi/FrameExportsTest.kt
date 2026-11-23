package scull.ffi

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.sizeOf
import scull.ffi.c.TT_ATTR_BOLD
import scull.ffi.c.TT_ATTR_ITALIC
import scull.ffi.c.TT_CELL_CLUSTER
import scull.ffi.c.TT_COLOR_DEFAULT
import scull.ffi.c.TT_COLOR_INDEXED
import scull.ffi.c.TT_COLOR_RGB
import scull.ffi.c.TT_UNDERLINE_CURLY
import scull.ffi.c.tt_frame_view
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameExportsTest {
    private var term: COpaquePointer = newTerm()
    private val frame: COpaquePointer = ttFrameNew()!!

    @AfterTest
    fun free() {
        ttFrameFree(frame)
        ttTermFree(term)
    }

    @Test
    fun theFirstUpdateIsFullAndCarriesEveryRow() {
        term.feed("héllo")
        val view = update(frame, term)
        assertEquals(sizeOf<tt_frame_view>().toUInt(), view.structSize)
        assertTrue(view.updated)
        assertTrue(view.full)
        assertEquals(10 to 3, view.cols to view.rows)
        assertEquals(30, view.cells.size)
        assertEquals(listOf(0, 1, 2), view.dirty)
        assertEquals(listOf("héllo", "", ""), view.text)
        assertEquals(listOf(RunView(0, 5, 0, 1, "héllo")), view.runs[0])
        assertEquals(listOf('h'.code, 0, 1, 0), view.cell(0, 0))
        assertEquals(listOf(0xE9, 0, 1, 0), view.cell(0, 1))
        assertEquals(Triple(0, 5, true), view.cursor)
        assertEquals(StyleView(TT_COLOR_DEFAULT.toUInt(), TT_COLOR_DEFAULT.toUInt(), TT_COLOR_DEFAULT.toUInt(), 0, 0), view.styles[0])
    }

    @Test
    fun aCellIsTheLittleEndianWordTheHeaderDescribes() {
        term.feed("\u001b[1mA")
        val view = update(frame, term)
        val (cp, style, width, flags) = view.cell(0, 0)
        assertEquals(cp.toLong() or (style.toLong() shl 32) or (width.toLong() shl 48) or (flags.toLong() shl 56), view.packed[0])
        assertEquals(1, width)
    }

    @Test
    fun runOffsetsCountUtf8BytesAcrossWideCharacters() {
        term.feed("a世b")
        val view = update(frame, term)
        assertEquals("a世b", view.text[0])
        assertEquals(
            listOf(RunView(0, 1, 0, 1, "a"), RunView(1, 2, 0, 2, "世"), RunView(3, 1, 0, 1, "b")),
            view.runs[0],
        )
        assertEquals(listOf(0x4e16, 0, 2, 0), view.cell(0, 1))
        assertEquals(0, view.cell(0, 2)[2])
    }

    @Test
    fun aClusterIsFlaggedAndKeepsAllItsText() {
        term.feed("é")
        val view = update(frame, term)
        assertEquals(listOf('e'.code, 0, 1, TT_CELL_CLUSTER), view.cell(0, 0))
        assertEquals("é", view.runs[0][0].text)
    }

    @Test
    fun stylesCarryTheHeaderEncoding() {
        term.feed("\u001b[1;31mR\u001b[0;3;4:3;38;2;1;2;3;48;5;200mG")
        val view = update(frame, term)
        val red = view.styles[view.cell(0, 0)[1]]
        assertEquals(TT_COLOR_INDEXED or 1u, red.fg)
        assertEquals(TT_ATTR_BOLD, red.attrs)
        val green = view.styles[view.cell(0, 1)[1]]
        assertEquals(TT_COLOR_RGB or 0x010203u, green.fg)
        assertEquals(TT_COLOR_INDEXED or 200u, green.bg)
        assertEquals(TT_ATTR_ITALIC, green.attrs)
        assertEquals(TT_UNDERLINE_CURLY, green.underline)
        assertEquals(2, view.runs[0].size)
    }

    @Test
    fun laterUpdatesListOnlyWhatChanged() {
        term.feed("one")
        update(frame, term)
        val idle = update(frame, term)
        assertTrue(idle.updated)
        assertFalse(idle.full)
        assertEquals(emptyList(), idle.dirty)

        term.feed("\r\ntwo")
        val view = update(frame, term)
        assertFalse(view.full)
        assertEquals(listOf(1), view.dirty)
        assertEquals(listOf("one", "two", ""), view.text)
        assertEquals(Triple(1, 3, true), view.cursor)
    }

    @Test
    fun aScrollMovesRowIdsAndListsTheBlit() {
        term.feed("1\r\n2\r\n3")
        val before = update(frame, term)
        term.feed("\r\n4")
        val view = update(frame, term)
        assertEquals(listOf("2", "3", "4"), view.text)
        assertEquals(before.ids.drop(1), view.ids.take(2))
        assertEquals(listOf(Triple(0, 2, 1)), view.scrolls)
        assertEquals(listOf(2), view.dirty)
    }

    @Test
    fun synchronizedOutputHoldsThePicture() {
        term.feed("a")
        update(frame, term)
        term.feed("\u001b[?2026hb")
        val held = update(frame, term)
        assertFalse(held.updated)
        assertEquals(emptyList(), held.dirty)
        term.feed("\u001b[?2026l")
        val shown = update(frame, term)
        assertTrue(shown.updated)
        assertEquals("ab", shown.text[0])
    }

    @Test
    fun aResizeRepaintsEverything() {
        term.feed("abc")
        update(frame, term)
        ttTermResize(term, 4u, 2u, 0u, 0u)
        val view = update(frame, term)
        assertTrue(view.full)
        assertEquals(4 to 2, view.cols to view.rows)
        assertEquals(listOf(0, 1), view.dirty)
        assertEquals(8, view.cells.size)
    }

    @Test
    fun aHiddenCursorIsNotVisible() {
        term.feed("\u001b[?25l")
        assertFalse(update(frame, term).cursor.third)
    }
}
