package scull.ffi

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import scull.ffi.c.TT_CLOSED
import scull.ffi.c.TT_FULL
import scull.ffi.c.TT_INVALID
import scull.ffi.c.TT_KEY_ESCAPE
import scull.ffi.c.TT_KEY_PRESS
import scull.ffi.c.TT_KEY_RELEASE
import scull.ffi.c.TT_MOUSE_LEFT
import scull.ffi.c.TT_MOUSE_MOTION
import scull.ffi.c.TT_MOUSE_NONE
import scull.ffi.c.TT_MOUSE_PRESS
import scull.ffi.c.TT_MOUSE_WHEEL_UP
import scull.ffi.c.TT_OK
import scull.ffi.c.tt_key_event
import scull.ffi.c.tt_mouse_event
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalNativeApi::class)
private val windows = Platform.osFamily == OsFamily.WINDOWS

/** kitty's code for the Up arrow: the eighth key after Escape. */
private val KEY_UP = TT_KEY_ESCAPE.toUInt() + 8u

private fun MemScope.key(
    code: UInt,
    text: String = "",
    action: Int = TT_KEY_PRESS,
): tt_key_event =
    alloc<tt_key_event>().apply {
        struct_size = sizeOf<tt_key_event>().toUInt()
        key = code
        this.action = action.toUByte()
        str(this.text, text.encodeToByteArray())
    }

private fun MemScope.mouse(
    action: Int,
    button: Int,
    col: Int = 0,
    row: Int = 0,
): tt_mouse_event =
    alloc<tt_mouse_event>().apply {
        struct_size = sizeOf<tt_mouse_event>().toUInt()
        this.action = action.toUByte()
        this.button = button.toUByte()
        this.col = col.toUInt()
        this.row = row.toUInt()
    }

private fun COpaquePointer.text(text: String): UInt {
    val bytes = text.encodeToByteArray()
    return bytes.usePinned { ttTermText(this, if (bytes.isEmpty()) null else it.addressOf(0), bytes.size.toULong()) }
}

private fun COpaquePointer.paste(bytes: ByteArray): UInt = bytes.usePinned { ttTermPaste(this, it.addressOf(0), bytes.size.toULong()) }

/** Every row of the viewport, read through a fresh frame so all of them are there. */
private fun COpaquePointer.screen(): List<String> {
    val frame = ttFrameNew()!!
    val rows = update(frame, this).text
    ttFrameFree(frame)
    return rows
}

/**
 * A child that reads [count] bytes raw, without echo, and prints them in hex between `READY` and
 * `END`; returned once it is reading. Null on Windows, which has no `sh` (the C# tests cover it).
 */
private fun hexChild(count: Int): COpaquePointer? {
    if (windows) return null
    val script = "stty raw -echo; echo READY; head -c $count | od -An -tx1 -v | tr -d ' \\n'; echo END"
    val (status, term) = spawn(listOf("/bin/sh", "-c", script), cols = 120, rows = 4)
    assertEquals(TT_OK, status)
    waitFor("the child to read") { term!!.screen().any { "READY" in it } }
    return term
}

/** The hex the child of [hexChild] printed, once it has. */
private fun COpaquePointer.received(): String {
    var hex: String? = null
    waitFor("the child to print what it read") {
        val all = screen().joinToString("").filterNot { it.isWhitespace() }
        hex = Regex("READY([0-9a-f]*)END").find(all)?.groupValues?.get(1)
        hex != null
    }
    ttTermFree(this)
    return hex!!
}

class InputExportsTest {
    @Test
    fun keysAndTextReachTheChildAsTheModesSpellThem() {
        val term = hexChild(6) ?: return
        memScoped {
            assertEquals(TT_OK, ttTermKey(term, key('a'.code.toUInt(), "a").ptr))
            assertEquals(TT_OK, ttTermKey(term, key(KEY_UP).ptr))
        }
        // Control characters in committed text never reach the child.
        assertEquals(TT_OK, term.text("\u001bé"))
        assertEquals("61" + "1b5b41" + "c3a9", term.received())
    }

    @Test
    fun pasteFocusAndMouseFollowWhatTheProgramAskedFor() {
        val term = hexChild(25) ?: return
        term.feed("\u001b[?2004h\u001b[?1004h\u001b[?1000h\u001b[?1006h")
        assertEquals(TT_OK, term.paste("x".encodeToByteArray()))
        assertEquals(TT_OK, ttTermFocus(term, 1u))
        memScoped {
            val taken = alloc<UByteVar>()
            assertEquals(TT_OK, ttTermMouse(term, mouse(TT_MOUSE_PRESS, TT_MOUSE_LEFT, col = 2, row = 3).ptr, taken.ptr))
            assertEquals(1, taken.value.toInt())
        }
        val paste = "1b5b3230307e" + "78" + "1b5b3230317e"
        assertEquals(paste + "1b5b49" + "1b5b3c303b333b344d", term.received())
    }

    @Test
    fun withoutAChildInputThatSendsBytesIsClosed() {
        val term = newTerm()
        memScoped {
            assertEquals(TT_CLOSED, ttTermKey(term, key('a'.code.toUInt(), "a").ptr))
            // Nothing to send is no error: a release in legacy mode, a key being composed.
            assertEquals(TT_OK, ttTermKey(term, key('a'.code.toUInt(), "a", TT_KEY_RELEASE).ptr))
            val composing = key('a'.code.toUInt(), "a").apply { this.composing = 1u }
            assertEquals(TT_OK, ttTermKey(term, composing.ptr))
            assertEquals(TT_OK, ttTermFocus(term, 1u), "focus reports are off")
            assertEquals(TT_OK, term.text(""))
        }
        ttTermFree(term)
    }

    @Test
    fun theProgramTakesTheMouseOnlyWhileItTracksIt() {
        val term = newTerm()
        memScoped {
            val taken = alloc<UByteVar>()
            val wheel = mouse(TT_MOUSE_PRESS, TT_MOUSE_WHEEL_UP)
            assertEquals(TT_OK, ttTermMouse(term, wheel.ptr, taken.ptr))
            assertEquals(0, taken.value.toInt())
            term.feed("\u001b[?1000h")
            assertEquals(TT_CLOSED, ttTermMouse(term, wheel.ptr, taken.ptr))
            assertEquals(1, taken.value.toInt(), "taken is set whatever the status")
            assertEquals(TT_CLOSED, ttTermMouse(term, wheel.ptr, null))
            // Motion with no button held is a valid event; normal tracking does not report it.
            assertEquals(TT_OK, ttTermMouse(term, mouse(TT_MOUSE_MOTION, TT_MOUSE_NONE).ptr, taken.ptr))
        }
        ttTermFree(term)
    }

    @Test
    fun typingBringsTheViewportBackButFocusAndScrollingDoNot() {
        val term = newTerm(cols = 4, rows = 2)
        term.feed((1..9).joinToString("\r\n"))
        assertEquals(listOf("8", "9"), term.screen().map { it.trim() })
        assertEquals(TT_OK, ttTermScrollDisplay(term, 3))
        assertEquals(listOf("5", "6"), term.screen().map { it.trim() })
        term.feed("\u001b[?1004h")
        assertEquals(TT_CLOSED, ttTermFocus(term, 0u))
        assertEquals(listOf("5", "6"), term.screen().map { it.trim() })
        memScoped { assertEquals(TT_CLOSED, ttTermKey(term, key('a'.code.toUInt(), "a").ptr)) }
        assertEquals(listOf("8", "9"), term.screen().map { it.trim() })
        // Clamped to what history holds, either way.
        assertEquals(TT_OK, ttTermScrollDisplay(term, Int.MAX_VALUE))
        assertEquals(listOf("1", "2"), term.screen().map { it.trim() })
        assertEquals(TT_OK, ttTermScrollDisplay(term, Int.MIN_VALUE))
        assertEquals(listOf("8", "9"), term.screen().map { it.trim() })
        ttTermFree(term)
    }

    @Test
    fun malformedInputIsInvalid() {
        val term = newTerm()
        memScoped {
            assertEquals(TT_INVALID, ttTermKey(null, key('a'.code.toUInt()).ptr))
            assertEquals(TT_INVALID, ttTermKey(term, null))
            assertEquals(TT_INVALID, ttTermKey(term, key('a'.code.toUInt()).apply { struct_size = 2u }.ptr), "short struct")
            assertEquals(TT_INVALID, ttTermKey(term, key(0x1Bu).ptr), "a control character names no key")
            assertEquals(TT_INVALID, ttTermKey(term, key(0xD800u).ptr), "a surrogate names no key")
            assertEquals(TT_INVALID, ttTermKey(term, key('a'.code.toUInt(), action = 0).ptr))
            assertEquals(TT_INVALID, ttTermKey(term, key('a'.code.toUInt(), action = 4).ptr))
            val badText = key('a'.code.toUInt()).apply { str(text, byteArrayOf(0xFF.toByte())) }
            assertEquals(TT_INVALID, ttTermKey(term, badText.ptr), "text that is not UTF-8")
            assertEquals(TT_INVALID, ttTermText(term, null, 1u))
            assertEquals(TT_INVALID, term.paste(byteArrayOf(0xFF.toByte())))
            assertEquals(TT_INVALID, ttTermMouse(term, null, null))
            assertEquals(TT_INVALID, ttTermMouse(term, mouse(TT_MOUSE_PRESS, TT_MOUSE_NONE).ptr, null), "a press needs a button")
            assertEquals(TT_INVALID, ttTermMouse(term, mouse(TT_MOUSE_PRESS, 12).ptr, null), "no button 12")
            assertEquals(TT_INVALID, ttTermMouse(term, mouse(0, TT_MOUSE_LEFT).ptr, null))
            assertEquals(TT_INVALID, ttTermMouse(term, mouse(4, TT_MOUSE_LEFT).ptr, null))
            val taken = alloc<UByteVar>().apply { value = 7u }
            assertEquals(TT_INVALID, ttTermMouse(term, mouse(4, TT_MOUSE_LEFT).ptr, taken.ptr))
            assertEquals(0, taken.value.toInt(), "a refused event is not taken")
            assertEquals(TT_INVALID, ttTermScrollDisplay(null, 1))
        }
        ttTermFree(term)
    }

    @Test
    fun inputLargerThanTheChildsQueueIsFullBeforeItIsRead() {
        val term = newTerm()
        memScoped {
            // One byte backs the claimed length: the core must refuse before it copies.
            val one = allocArray<ByteVar>(1)
            val tooLong = 256uL * 1024uL + 1uL
            assertEquals(TT_FULL, ttTermPaste(term, one, tooLong))
            assertEquals(TT_FULL, ttTermText(term, one, tooLong))
            val big =
                key('a'.code.toUInt()).apply {
                    text.ptr = one.reinterpret()
                    text.len = tooLong
                }
            assertEquals(TT_FULL, ttTermKey(term, big.ptr))
        }
        ttTermFree(term)
    }
}
