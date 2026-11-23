package scull.ffi

import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import scull.ffi.c.TT_ABI_MISMATCH
import scull.ffi.c.TT_CLOSED
import scull.ffi.c.TT_EMPTY
import scull.ffi.c.TT_EVENT_BELL
import scull.ffi.c.TT_INVALID
import scull.ffi.c.TT_OK
import scull.ffi.c.TT_PANIC
import scull.ffi.c.TT_POISONED
import scull.ffi.c.tt_event
import scull.ffi.c.tt_frame_view
import scull.ffi.c.tt_term_options
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatusTest {
    @Test
    fun nullHandlesAndPointersAreInvalid() {
        assertEquals(TT_INVALID, ttTermFeed(null, null, 0u))
        assertEquals(TT_INVALID, ttTermWrite(null, null, 0u, null))
        assertEquals(TT_INVALID, ttTermResizeBegin(null))
        assertEquals(TT_INVALID, ttTermResize(null, 10u, 3u, 0u, 0u))
        assertEquals(TT_INVALID, ttTermPollEvent(null, null))
        assertEquals(TT_INVALID, ttTermEventText(null, 1u, 0u, null, 0u, null))
        assertEquals(TT_INVALID, ttFrameUpdate(null, null, null))
        memScoped {
            val out = alloc<COpaquePointerVar>()
            assertEquals(TT_INVALID, ttTermNew(null, out.ptr))
            assertEquals(TT_INVALID, ttTermNew(options().ptr, null))
        }
        // Freeing nothing is allowed, as free(NULL) is.
        ttTermFree(null)
        ttFrameFree(null)

        val term = newTerm()
        val frame = ttFrameNew()!!
        assertEquals(TT_INVALID, ttTermFeed(term, null, 1u))
        assertEquals(TT_INVALID, ttTermPollEvent(term, null))
        assertEquals(TT_INVALID, ttFrameUpdate(frame, term, null))
        assertEquals(TT_INVALID, ttFrameUpdate(frame, null, null))
        assertEquals(TT_OK, ttTermFeed(term, null, 0u))
        ttFrameFree(frame)
        ttTermFree(term)
    }

    @Test
    fun optionsMayStopAfterScrollbackButNoSooner() {
        memScoped {
            val out = alloc<COpaquePointerVar>()
            val short = options()
            short.struct_size = 15u
            assertEquals(TT_INVALID, ttTermNew(short.ptr, out.ptr))
            assertNull(out.value)

            // An older host's struct ends at scrollback; what follows is not read.
            val old = options(cols = 7, rows = 2)
            old.struct_size = TERM_OPTIONS_MIN.toUInt()
            assertEquals(TT_OK, ttTermNew(old.ptr, out.ptr))
            val frame = ttFrameNew()!!
            val view = update(frame, out.value!!)
            assertEquals(7 to 2, view.cols to view.rows)
            ttFrameFree(frame)
            ttTermFree(out.value)

            // A newer host's struct is longer; the core reads what it knows.
            val newer = options()
            newer.struct_size = sizeOf<tt_term_options>().toUInt() + 64u
            assertEquals(TT_OK, ttTermNew(newer.ptr, out.ptr))
            ttTermFree(out.value)
        }
    }

    @Test
    fun anotherAbiVersionIsRefused() {
        memScoped {
            val out = alloc<COpaquePointerVar>()
            val o = options()
            // While the major is 0 every minor may break.
            o.abi_version = ABI_VERSION + 1u
            assertEquals(TT_ABI_MISMATCH, ttTermNew(o.ptr, out.ptr))
            o.abi_version = ABI_VERSION + (1u shl 16)
            assertEquals(TT_ABI_MISMATCH, ttTermNew(o.ptr, out.ptr))
            o.abi_version = 0u
            assertEquals(TT_ABI_MISMATCH, ttTermNew(o.ptr, out.ptr))
            assertNull(out.value)
        }
    }

    @Test
    fun abiCompatibilityFollowsTheMajorThenTheMinor() {
        assertEquals(true, abiCompatible(ABI_VERSION))
        assertEquals(false, abiCompatible((1u shl 16) or 1u))
    }

    @Test
    fun sizesOutsideTheGridAreInvalid() {
        memScoped {
            val out = alloc<COpaquePointerVar>()
            assertEquals(TT_INVALID, ttTermNew(options(cols = 0).ptr, out.ptr))
            assertEquals(TT_INVALID, ttTermNew(options(rows = 0).ptr, out.ptr))
        }
        val term = newTerm()
        assertEquals(TT_INVALID, ttTermResize(term, 0u, 3u, 0u, 0u))
        assertEquals(TT_INVALID, ttTermResize(term, 10u, 0u, 0u, 0u))
        assertEquals(TT_OK, ttTermResize(term, 12u, 4u, 0u, 0u))
        ttTermFree(term)
    }

    @Test
    fun aTerminalWithoutAChildIsClosedForInput() {
        val term = newTerm()
        memScoped {
            val written = alloc<ULongVar>()
            written.value = 99u
            val bytes = "x".encodeToByteArray()
            bytes.usePinned {
                assertEquals(TT_CLOSED, ttTermWrite(term, it.addressOf(0), 1u, written.ptr))
            }
            assertEquals(0uL, written.value)
        }
        // Resizing a terminal with no PTY only resizes the grid.
        assertEquals(TT_OK, ttTermResizeBegin(term))
        assertEquals(TT_OK, ttTermResize(term, 20u, 5u, 0u, 0u))
        ttTermFree(term)
    }

    @Test
    fun shortOutputStructsAreRefusedBeforeAnythingIsTaken() {
        val term = newTerm()
        val frame = ttFrameNew()!!
        term.feed("\u0007ab")
        memScoped {
            val event = alloc<tt_event>()
            event.struct_size = sizeOf<tt_event>().toUInt() - 1u
            assertEquals(TT_INVALID, ttTermPollEvent(term, event.ptr))
            val view = alloc<tt_frame_view>()
            view.struct_size = sizeOf<tt_frame_view>().toUInt() - 1u
            assertEquals(TT_INVALID, ttFrameUpdate(frame, term, view.ptr))
        }
        // The refused calls lost nothing.
        assertEquals(TT_EVENT_BELL, poll(term)?.kind)
        val view = update(frame, term)
        assertEquals("ab", view.text[0])
        assertEquals(true, view.full)
        ttFrameFree(frame)
        ttTermFree(term)
    }

    @Test
    fun aLongerOutputStructGetsWhatTheCoreKnows() {
        val term = newTerm()
        term.feed("\u0007")
        memScoped {
            val event = alloc<tt_event>()
            // The tail a newer host would have stays as it was.
            val size = sizeOf<tt_event>().toUInt()
            event.struct_size = size + 8u
            assertEquals(TT_OK, ttTermPollEvent(term, event.ptr))
            assertEquals(size, event.struct_size)
        }
        ttTermFree(term)
    }

    @Test
    fun aThrowPoisonsTheTerminalAndFreeingStillWorks() {
        val term = newTerm()
        assertEquals(TT_PANIC, guarded(term.term()) { error("a bug in the core") })
        assertEquals(TT_POISONED, ttTermFeed(term, null, 0u))
        assertEquals(TT_POISONED, ttTermPollEvent(term, null))
        val frame = ttFrameNew()!!
        memScoped {
            val view = alloc<tt_frame_view>()
            view.struct_size = sizeOf<tt_frame_view>().toUInt()
            assertEquals(TT_POISONED, ttFrameUpdate(frame, term, view.ptr))
        }
        ttFrameFree(frame)
        ttTermFree(term)
    }

    @Test
    fun aRefusalDoesNotPoison() {
        val term = newTerm()
        assertEquals(TT_INVALID, guarded(term.term()) { throw Invalid() })
        assertEquals(TT_EMPTY, memScoped { ttTermEventText(term, 1u, 0u, null, 0u, alloc<ULongVar>().ptr) })
        ttTermFree(term)
    }
}
