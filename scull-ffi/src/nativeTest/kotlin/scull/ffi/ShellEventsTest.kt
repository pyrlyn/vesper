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
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import scull.ffi.c.TT_CLOSED
import scull.ffi.c.TT_EMPTY
import scull.ffi.c.TT_EVENT_CLIPBOARD_READ
import scull.ffi.c.TT_EVENT_CLIPBOARD_WRITE
import scull.ffi.c.TT_EVENT_DIRECTORY
import scull.ffi.c.TT_EVENT_LINK
import scull.ffi.c.TT_EVENT_NOTIFICATION
import scull.ffi.c.TT_EVENT_SHELL_MARK
import scull.ffi.c.TT_EVENT_TEXT_TITLE
import scull.ffi.c.TT_EXIT_CODE_UNKNOWN
import scull.ffi.c.TT_FULL
import scull.ffi.c.TT_INVALID
import scull.ffi.c.TT_OK
import scull.ffi.c.TT_SHELL_COMMAND_EXECUTED
import scull.ffi.c.TT_SHELL_COMMAND_FINISHED
import scull.ffi.c.TT_SHELL_COMMAND_START
import scull.ffi.c.TT_SHELL_PROMPT_START
import scull.term.MAX_CLIPBOARD_BYTES
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val ESC = "\u001b"
private const val BEL = "\u0007"
private val TITLE = TT_EVENT_TEXT_TITLE.toUInt()

/** The URI of link [id], with the status and the length the core reported. */
private fun linkUri(
    term: COpaquePointer,
    id: UInt,
    cap: Int = 256,
): Triple<UInt, Int, String> =
    memScoped {
        val buf = allocArray<ByteVar>(cap.coerceAtLeast(1))
        val len = alloc<ULongVar>()
        val status = ttTermLinkUri(term, id, if (cap == 0) null else buf, cap.toULong(), len.ptr)
        val n = len.value.toInt()
        Triple(status, n, if (status == TT_OK) buf.readBytes(n).decodeToString() else "")
    }

private fun reply(
    term: COpaquePointer,
    targets: ByteArray,
    data: ByteArray,
): UInt =
    targets.usePinned { t ->
        data.usePinned { d ->
            ttTermClipboardReply(
                term,
                t.addressOf(0).takeIf { targets.isNotEmpty() },
                targets.size.toULong(),
                d.addressOf(0).takeIf { data.isNotEmpty() },
                data.size.toULong(),
            )
        }
    }

private fun deny(
    term: COpaquePointer,
    targets: String,
): UInt {
    val bytes = targets.encodeToByteArray()
    return bytes.usePinned { ttTermClipboardDeny(term, it.addressOf(0), bytes.size.toULong()) }
}

@OptIn(ExperimentalNativeApi::class)
private val windows = Platform.osFamily == OsFamily.WINDOWS

class ShellEventsTest {
    @Test
    fun aDirectoryCarriesItsUri() {
        val term = newTerm()
        term.feed("$ESC]7;file://host/tmp$BEL")
        val event = assertNotNull(poll(term))
        assertEquals(TT_EVENT_DIRECTORY, event.kind)
        assertEquals("file://host/tmp", eventText(term, event.serial).third)
        assertEquals(0, event.titleLen)
        ttTermFree(term)
    }

    @Test
    fun shellMarksCarryTheirKindAndOnlyAFinishCarriesAnExitCode() {
        val term = newTerm()
        term.feed("$ESC]133;A$BEL$ESC]133;B$BEL$ESC]133;C$BEL$ESC]133;D;7$BEL$ESC]133;D$BEL")
        val marks = generateSequence { poll(term) }.toList()
        assertTrue(marks.all { it.kind == TT_EVENT_SHELL_MARK && it.textLen == 0 })
        assertEquals(
            listOf(
                TT_SHELL_PROMPT_START,
                TT_SHELL_COMMAND_START,
                TT_SHELL_COMMAND_EXECUTED,
                TT_SHELL_COMMAND_FINISHED,
                TT_SHELL_COMMAND_FINISHED,
            ).map { it.toUInt() },
            marks.map { it.detail },
        )
        assertEquals(listOf(0u, 0u, 0u, 7u, TT_EXIT_CODE_UNKNOWN), marks.map { it.exitCode })
        ttTermFree(term)
    }

    @Test
    fun aClipboardWriteCarriesTheDecodedBytesAndItsTargets() {
        val term = newTerm()
        term.feed("$ESC]52;cp;aGk=$BEL")
        val event = assertNotNull(poll(term))
        assertEquals(TT_EVENT_CLIPBOARD_WRITE, event.kind)
        assertEquals(2 to 2, event.textLen to event.titleLen)
        assertEquals("hi", eventText(term, event.serial).third)
        assertEquals("cp", eventText(term, event.serial, part = TITLE).third)
        ttTermFree(term)
    }

    @Test
    fun aClipboardReadCarriesOnlyItsTargets() {
        val term = newTerm()
        term.feed("$ESC]52;;?$BEL")
        val event = assertNotNull(poll(term))
        assertEquals(TT_EVENT_CLIPBOARD_READ, event.kind)
        assertEquals(0, event.textLen)
        assertEquals(Triple(TT_OK, 0, ""), eventText(term, event.serial))
        // An empty selection reads as xterm's default.
        assertEquals("s0", eventText(term, event.serial, part = TITLE).third)
        ttTermFree(term)
    }

    @Test
    fun notificationsCarryATitleAndABody() {
        val term = newTerm()
        term.feed("$ESC]777;notify;Build;done; ok$BEL$ESC]9;plain$BEL")
        val rxvt = assertNotNull(poll(term))
        assertEquals(TT_EVENT_NOTIFICATION, rxvt.kind)
        assertEquals("done; ok", eventText(term, rxvt.serial).third)
        assertEquals("Build", eventText(term, rxvt.serial, part = TITLE).third)
        val iterm = assertNotNull(poll(term))
        assertEquals(0, iterm.titleLen)
        assertEquals("plain", eventText(term, iterm.serial).third)
        assertEquals(Triple(TT_OK, 0, ""), eventText(term, iterm.serial, part = TITLE))
        ttTermFree(term)
    }

    @Test
    fun aLinkIdResolvesAndTheFrameListsItsCells() {
        val term = newTerm()
        val frame = ttFrameNew()!!
        term.feed("x$ESC]8;;http://a/é$BEL" + "ab$ESC]8;;$BEL" + "y")
        val event = assertNotNull(poll(term))
        assertEquals(TT_EVENT_LINK, event.kind)
        assertTrue(event.id > 0u)
        assertEquals("http://a/é", eventText(term, event.serial).third)
        val id = event.id.toUInt()
        assertEquals(Triple(TT_OK, 11, "http://a/é"), linkUri(term, id))
        assertEquals(Triple(TT_FULL, 11, ""), linkUri(term, id, cap = 4))
        assertEquals(Triple(TT_FULL, 11, ""), linkUri(term, id, cap = 0))
        val view = update(frame, term)
        assertEquals(listOf(Triple(id.toInt(), 1, 2)), view.links[0])
        assertEquals(emptyList(), view.links[1])
        ttFrameFree(frame)
        ttTermFree(term)
    }

    @Test
    fun anUnknownOrFreedLinkIsEmptyAndAMissingLengthInvalid() {
        val term = newTerm()
        assertEquals(TT_EMPTY, linkUri(term, 0u).first)
        assertEquals(TT_EMPTY, linkUri(term, 999u).first)
        assertEquals(TT_EMPTY, linkUri(term, UInt.MAX_VALUE).first)
        assertEquals(TT_INVALID, ttTermLinkUri(term, 1u, null, 0u, null))
        memScoped {
            val len = alloc<ULongVar>()
            assertEquals(TT_INVALID, ttTermLinkUri(term, 1u, null, 4u, len.ptr))
        }
        ttTermFree(term)
    }

    @Test
    fun aClipboardAnswerWithoutAChildIsClosed() {
        val term = newTerm()
        assertEquals(TT_CLOSED, reply(term, "c".encodeToByteArray(), "hi".encodeToByteArray()))
        assertEquals(TT_CLOSED, deny(term, "c"))
        ttTermFree(term)
    }

    @Test
    fun aClipboardAnswerThatCannotBeSentIsInvalid() {
        val term = newTerm()
        val hi = "hi".encodeToByteArray()
        assertEquals(TT_INVALID, reply(term, "c!".encodeToByteArray(), hi))
        assertEquals(TT_INVALID, reply(term, byteArrayOf(0xFF.toByte()), hi))
        assertEquals(TT_INVALID, deny(term, "x"))
        assertEquals(TT_INVALID, reply(term, "c".encodeToByteArray(), ByteArray(MAX_CLIPBOARD_BYTES + 1)))
        // Within the clipboard cap, but its base64 passes what the input queue can ever hold.
        assertEquals(TT_INVALID, reply(term, "c".encodeToByteArray(), ByteArray(200 * 1024)))
        assertEquals(TT_INVALID, ttTermClipboardReply(term, null, 1u, null, 0u))
        ttTermFree(term)
    }

    @Test
    fun aChildWaitingForItsClipboardGetsTheAnswerAndTheDenial() {
        // The child reads its answer without writing anything first: an answer queued with the
        // terminal's replies would never reach it. ESC and BEL come back as E and B to fit the row.
        if (windows) return
        for ((data, expected) in listOf("hi" to "E]52;c;aGk=B", "" to "E]52;c;B")) {
            val script = "stty -icanon -echo; printf '\\033]52;c;?\\007'; head -c ${expected.length} | tr '\\033\\007' EB"
            val (status, term) = spawn(listOf("/bin/sh", "-c", script), cols = 20, rows = 3)
            assertEquals(TT_OK, status)
            term!!
            waitFor("the clipboard read") { generateSequence { poll(term) }.any { it.kind == TT_EVENT_CLIPBOARD_READ } }
            val answered = if (data.isEmpty()) deny(term, "c") else reply(term, "c".encodeToByteArray(), data.encodeToByteArray())
            assertEquals(TT_OK, answered)
            val frame = ttFrameNew()!!
            waitFor("the child to print its answer") { update(frame, term).text.any { it.startsWith(expected) } }
            ttFrameFree(frame)
            ttTermFree(term)
        }
    }
}
