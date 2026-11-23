package scull.ffi

import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import scull.ffi.c.TT_EMPTY
import scull.ffi.c.TT_EVENT_BELL
import scull.ffi.c.TT_EVENT_TITLE
import scull.ffi.c.TT_FULL
import scull.ffi.c.TT_INVALID
import scull.ffi.c.TT_OK
import scull.ffi.c.TT_TITLE_BOTH
import scull.ffi.c.TT_TITLE_WINDOW
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventExportsTest {
    @Test
    fun eventsComeInOrderWithTheirText() {
        val term = newTerm()
        term.feed("\u0007\u001b]2;hé\u0007\u001b]0;both\u001b\\")
        val bell = poll(term)!!
        assertEquals(TT_EVENT_BELL, bell.kind)
        assertEquals(0, bell.textLen)
        val title = poll(term)!!
        assertEquals(TT_EVENT_TITLE, title.kind)
        assertEquals(TT_TITLE_WINDOW.toUInt(), title.detail)
        assertEquals(3, title.textLen)
        assertTrue(title.serial > bell.serial)
        assertEquals(Triple(TT_OK, 3, "hé"), eventText(term, title.serial))
        val both = poll(term)!!
        assertEquals(TT_TITLE_BOTH.toUInt(), both.detail)
        assertEquals("both", eventText(term, both.serial).third)
        assertNull(poll(term))
        ttTermFree(term)
    }

    @Test
    fun onlyTheLastEventsTextIsHeld() {
        val term = newTerm()
        term.feed("\u001b]2;first\u0007\u001b]2;second\u0007")
        val first = poll(term)!!
        val second = poll(term)!!
        assertEquals(TT_EMPTY, eventText(term, first.serial).first)
        assertEquals(TT_EMPTY, eventText(term, 0u).first)
        assertEquals("second", eventText(term, second.serial).third)
        // An empty poll keeps it.
        assertNull(poll(term))
        assertEquals("second", eventText(term, second.serial).third)
        ttTermFree(term)
    }

    @Test
    fun aShortBufferGetsTheLengthAndNothingElse() {
        val term = newTerm()
        term.feed("\u001b]2;twelve bytes\u0007")
        val title = poll(term)!!
        assertEquals(Triple(TT_FULL, 12, ""), eventText(term, title.serial, cap = 4))
        assertEquals(Triple(TT_FULL, 12, ""), eventText(term, title.serial, cap = 0))
        assertEquals("twelve bytes", eventText(term, title.serial, cap = 12).third)
        ttTermFree(term)
    }

    @Test
    fun anUnknownTextPartIsInvalid() {
        val term = newTerm()
        term.feed("\u001b]2;t\u0007")
        val title = poll(term)!!
        memScoped {
            val len = alloc<ULongVar>()
            assertEquals(TT_INVALID, ttTermEventText(term, title.serial, 1u, null, 0u, len.ptr))
        }
        ttTermFree(term)
    }
}
