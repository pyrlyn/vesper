package vesper.term

import vesper.grid.LinkId
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The OSC paths shell.txt cannot show: answers to clipboard reads, the caps, and the link table
// past its size.

private const val BEL = "\u0007"
private const val ST = "$ESC\\"

private fun Terminal.link(uri: String) = feed("$ESC]8;;$uri$ST")

private fun Terminal.endLink() = feed("$ESC]8;;$ST")

private fun Terminal.events(): List<Event> = generateSequence(::pollEvent).toList()

class OscTest {
    @Test
    fun aClipboardAnswerEndsTheWayTheReadDid() {
        val term = Terminal(10, 5, 0)
        term.feed("$ESC]52;c;?$BEL")
        assertEquals(listOf<Event>(Event.ClipboardRead("c")), term.events())
        assertTrue(term.answerClipboard("c", "hi".encodeToByteArray()))
        assertEquals("$ESC]52;c;aGk=$BEL", term.takeReplies().decodeToString())

        term.feed("$ESC]52;p;?$ST")
        assertTrue(term.denyClipboard("p"))
        assertEquals("$ESC]52;p;$ST", term.takeReplies().decodeToString())
    }

    @Test
    fun anEmptyTargetListReadsAsXtermsDefault() {
        val term = Terminal(10, 5, 0)
        term.feed("$ESC]52;;?$ST")
        assertEquals(listOf<Event>(Event.ClipboardRead("s0")), term.events())
        term.feed("$ESC]52;x;?$ST")
        assertEquals(emptyList(), term.events())
    }

    @Test
    fun anAnswerIsCheckedAndMayPassTheReplyCap() {
        val term = Terminal(10, 5, 0)
        assertFailsWith<IllegalArgumentException> { term.answerClipboard("cz", ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> { term.answerClipboard("c", ByteArray(MAX_CLIPBOARD_BYTES + 1)) }
        val data = ByteArray(MAX_CLIPBOARD_BYTES) { it.toByte() }
        assertTrue(term.answerClipboard("c", data))
        // Only one answer of that size fits until the replies are taken.
        assertFalse(term.answerClipboard("c", data))
        val reply = term.takeReplies().decodeToString()
        assertContentEquals(data, Base64.decode(reply.removePrefix("$ESC]52;c;").removeSuffix(ST)))
        assertEquals(0, term.takeReplies().size)
    }

    @Test
    fun aClipboardWritePastTheCapIsDroppedAndTheQueueKeepsItsBudget() {
        val term = Terminal(10, 5, 0)
        val most = Base64.encode(ByteArray(MAX_CLIPBOARD_BYTES))
        term.feed("$ESC]52;c;${Base64.encode(ByteArray(MAX_CLIPBOARD_BYTES + 3))}$BEL")
        assertEquals(emptyList(), term.events())
        // Two writes of the largest size pass the byte budget, so the second waits for nobody.
        term.feed("$ESC]52;c;$most$BEL$ESC]52;c;$most$BEL")
        val events = term.events()
        assertEquals(1, events.size)
        assertEquals(MAX_CLIPBOARD_BYTES, (events[0] as Event.ClipboardWrite).data.size)
        term.feed("$ESC]52;c;aGk$BEL")
        assertContentEquals("hi".encodeToByteArray(), (term.pollEvent() as Event.ClipboardWrite).data)
    }

    @Test
    fun shellMarksReadOnlyTheirLetterAndDsExitCode() {
        val term = Terminal(10, 5, 0)
        term.feed("$ESC]133;B$BEL$ESC]133;C;cmdline=ls$BEL$ESC]133;D$BEL$ESC]133;D;x$BEL$ESC]133;D;2;aid=1$BEL")
        term.feed("$ESC]133;Z$BEL$ESC]133;AB$BEL")
        assertEquals(
            listOf<Event>(
                Event.ShellMark(ShellMarkKind.CommandStart, null),
                Event.ShellMark(ShellMarkKind.CommandExecuted, null),
                Event.ShellMark(ShellMarkKind.CommandFinished, null),
                Event.ShellMark(ShellMarkKind.CommandFinished, null),
                Event.ShellMark(ShellMarkKind.CommandFinished, 2),
            ),
            term.events(),
        )
    }

    @Test
    fun notificationsAndTheDirectoryAreCapped() {
        val term = Terminal(10, 5, 0)
        term.feed("$ESC]9;" + "x".repeat(MAX_EVENT_TEXT + 1) + BEL)
        term.feed("$ESC]777;notify;t;" + "x".repeat(MAX_EVENT_TEXT) + BEL)
        term.feed("$ESC]7;" + "x".repeat(MAX_URI_BYTES + 1) + BEL)
        term.feed("$ESC]7;$BEL$ESC]9;$BEL$ESC]9;12$BEL")
        assertEquals(emptyList(), term.events())
        term.feed("$ESC]777;notify;only a title$BEL")
        assertEquals(listOf<Event>(Event.Notification("only a title", "")), term.events())
    }

    @Test
    fun aLinkIsKeyedByItsIdParameterAndItsUri() {
        val term = Terminal(10, 5, 0)
        term.feed("$ESC]8;id=a;http://x${ST}a$ESC]8;id=a;http://x${ST}b")
        term.feed("$ESC]8;id=b;http://x${ST}c$ESC]8;;http://x${ST}d$ESC]8;;http://x${ST}e")
        term.endLink()
        assertEquals(
            listOf<Event>(
                Event.Link(LinkId(1), "http://x"),
                Event.Link(LinkId(2), "http://x"),
                Event.Link(LinkId(3), "http://x"),
            ),
            term.events(),
        )
        val row = assertNotNull(term.grid.screenRow(0))
        assertEquals(listOf(1, 1, 2, 3, 3), (0 until 5).map { row.linkAt(it)?.value })
        assertEquals("http://x", term.linkUri(LinkId(2)))
        assertNull(term.linkUri(LinkId(4)))
    }

    @Test
    fun linksFollowScrollAndGoWithEraseOverwriteAndReset() {
        val term = Terminal(10, 2, 10)
        term.link("http://x")
        term.feed("ab\r\n\r\n")
        val history = assertNotNull(term.grid.row(0))
        assertEquals(LinkId(1), history.linkAt(1))
        // Still open: the new line gets it too, until an erase or an unlinked print removes it.
        term.feed("cde")
        term.endLink()
        term.feed("\rX$ESC[2;3H$ESC[K")
        val row = assertNotNull(term.grid.screenRow(1))
        assertEquals(listOf(null, 1, null), (0 until 3).map { row.linkAt(it)?.value })
        term.link("http://y")
        term.feed("${ESC}cz")
        assertNull(assertNotNull(term.grid.screenRow(0)).linkAt(0))
    }

    @Test
    fun aFullTableSweepsTheIdsNoCellHolds() {
        val term = Terminal(10, 2, 0)
        term.link("http://keep")
        term.feed("k")
        term.endLink()
        term.feed("\r\n")
        term.events()
        val announced = ArrayList<Event.Link>()
        for (i in 1..MAX_LINKS + 100) {
            // Each link lands on the same cell, so all but the newest are free to reclaim.
            term.link("http://x/$i")
            term.feed("a\r")
            announced += term.events().filterIsInstance<Event.Link>()
        }
        term.endLink()
        assertEquals(MAX_LINKS + 100, announced.size)
        val last = announced.last()
        assertEquals("http://x/${MAX_LINKS + 100}", term.linkUri(last.id))
        assertEquals(last.id, assertNotNull(term.grid.screenRow(1)).linkAt(0))
        assertEquals("http://keep", term.linkUri(LinkId(1)))
    }

    @Test
    fun aTableOfLiveIdsRefusesNewLinksAndTheirCellsCarryNone() {
        val term = Terminal(MAX_LINKS + 10, 1, 0)
        for (i in 1..MAX_LINKS) {
            term.link("http://x/$i")
            term.feed("a")
            term.events()
        }
        term.link("http://x/new")
        term.feed("b")
        term.endLink()
        assertEquals(emptyList(), term.events())
        val row = assertNotNull(term.grid.screenRow(0))
        assertEquals(LinkId(MAX_LINKS), row.linkAt(MAX_LINKS - 1))
        assertNull(row.linkAt(MAX_LINKS))
    }
}
