package scull.term

import scull.grid.Cell
import scull.grid.CellFlags
import scull.grid.Style
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val FLOOD = 100_000

class TerminalTest {
    @Test
    fun aNewTerminalStartsHomeWithAutowrap() {
        val term = Terminal(10, 5, 0)
        assertEquals(Cursor(), term.cursor)
        assertTrue(term.modes.autowrap)
        assertEquals(Margins(top = 0, bottom = 4, left = 0, right = 9), term.margins)
        assertEquals(Style.DEFAULT, term.pen)
        assertFalse(term.isAltScreen)
    }

    @Test
    fun aTerminalWithoutCellsIsRefused() {
        assertFailsWith<IllegalArgumentException> { Terminal(0, 5, 0) }
        assertFailsWith<IllegalArgumentException> { Terminal(10, 0, 0) }
        assertFailsWith<IllegalArgumentException> { Terminal(10, MAX_ROWS + 1, 0) }
    }

    @Test
    fun aSequenceSplitAcrossFeedsStillApplies() {
        val term = Terminal(10, 5, 0)
        term.feed("$CSI" + "3")
        term.feed(";4H")
        assertEquals(Cursor(row = 2, col = 3), term.cursor)
    }

    @Test
    fun feedReadsOnlyTheRangeItIsGiven() {
        val term = Terminal(10, 5, 0)
        term.feed("abcdef".encodeToByteArray(), 2, 4)
        assertEquals("cd........", term.screenText()[0])
    }

    @Test
    fun aWideCharacterPastTheLastColumnLeavesALeadingSpacerAndWraps() {
        val term = Terminal(5, 3, 0)
        term.feed("abcd中")
        assertTrue(term.cell(0, 4).hasFlags(CellFlags.LEADING_SPACER))
        assertTrue(term.cell(0, 4).isEmpty)
        assertTrue(checkNotNull(term.grid.screenRow(0)).wrapped)
        assertEquals(listOf("abcd.", "中...", "....."), term.screenText())
        assertTrue(term.cell(1, 0).hasFlags(CellFlags.WIDE))
        assertTrue(term.cell(1, 1).hasFlags(CellFlags.SPACER))
        assertEquals(Cursor(row = 1, col = 2), term.cursor)
    }

    @Test
    fun aWideCharacterFromThePendingWrapLeavesNoSpacer() {
        val term = Terminal(5, 3, 0)
        term.feed("abcde中")
        assertEquals('e'.code, term.cell(0, 4).codePoint)
        assertTrue((0 until 5).none { term.cell(0, it).hasFlags(CellFlags.LEADING_SPACER) })
        assertTrue(checkNotNull(term.grid.screenRow(0)).wrapped)
        assertTrue(term.cell(1, 0).hasFlags(CellFlags.WIDE))
    }

    @Test
    fun withoutAutowrapAWideCharacterOverwritesTheLastTwoColumns() {
        val term = Terminal(5, 3, 0)
        term.feed("$CSI?7labcde中")
        assertEquals(listOf("abc中", ".....", "....."), term.screenText())
        assertTrue(term.cell(0, 3).hasFlags(CellFlags.WIDE))
        assertTrue((0 until 5).none { term.cell(0, it).hasFlags(CellFlags.LEADING_SPACER) })
        assertFalse(checkNotNull(term.grid.screenRow(0)).wrapped)
        assertEquals(Cursor(row = 0, col = 4), term.cursor)
    }

    @Test
    fun anAsciiRunWrapsLikeItsBytesFedOneAtATime() {
        val text = "the quick brown fox jumps over the lazy dog"
        val whole = Terminal(7, 4, 10)
        whole.feed(text)
        val single = Terminal(7, 4, 10)
        text.forEach { single.feed(it.toString()) }
        assertEquals(single.screenText(), whole.screenText())
        assertEquals(single.cursor, whole.cursor)
        assertEquals(single.grid.historyLength, whole.grid.historyLength)
        assertEquals(listOf("umps ov", "er the ", "lazy do", "g......"), whole.screenText())
        assertEquals(3, whole.grid.historyLength)
    }

    @Test
    fun withoutAutowrapAnAsciiRunKeepsItsLastByteInTheLastColumn() {
        val term = Terminal(5, 2, 0)
        term.feed("$CSI?7labcdefgh")
        assertEquals(listOf("abcdh", "....."), term.screenText())
        assertEquals(Cursor(row = 0, col = 4), term.cursor)
    }

    @Test
    fun anAsciiRunTakesThePenAndInsertMode() {
        val term = Terminal(8, 2, 0)
        term.feed("abcd\r$CSI" + "1m$CSI" + "4hXY")
        assertEquals("XYabcd..", term.screenText()[0])
        assertEquals(term.cell(0, 0).style, term.cell(0, 1).style)
        assertEquals(Style(attrs = scull.grid.Attrs.BOLD), term.grid.styles[term.cell(0, 1).style])
        assertEquals(Style.DEFAULT, term.grid.styles[term.cell(0, 2).style])
    }

    @Test
    fun aCombiningMarkJoinsTheLetterBeforeItButNotAcrossAMove() {
        val term = Terminal(10, 2, 0)
        term.feed("é")
        assertTrue(term.cell(0, 0).isCluster)
        assertEquals("é", term.grid.clusters[term.cell(0, 0).clusterId])
        assertEquals(Cursor(row = 0, col = 1), term.cursor)
        // The move ends the cluster: the mark has nothing of its own to join at the new place.
        term.feed("x$CSI" + "5Ǵ")
        assertEquals('x'.code, term.cell(0, 1).codePoint)
    }

    @Test
    fun aFloodOfRequestsNeverGrowsTheReplyQueuePastItsCap() {
        val term = Terminal(10, 5, 0)
        term.feed("${CSI}6n".repeat(FLOOD))
        val replies = term.takeReplies()
        assertTrue(replies.isNotEmpty())
        assertTrue(replies.size <= MAX_REPLY_BYTES)
        assertEquals('R'.code.toByte(), replies.last(), "only whole replies are kept")
        term.feed("${CSI}5n")
        assertEquals(STATUS_OK, term.takeReplies().decodeToString(), "draining makes room again")
    }

    @Test
    fun theAlternateScreenStartsBlankAndLeavesTheMainOneIntact() {
        val term = Terminal(10, 5, 10)
        term.feed("main")
        term.feed("$CSI?1049h")
        assertTrue(term.isAltScreen)
        assertEquals(Cell.EMPTY, term.cell(0, 0))
        term.feed("$CSI?1049l")
        assertFalse(term.isAltScreen)
        assertEquals('m'.code, term.cell(0, 0).codePoint)
    }

    @Test
    fun theAlternateScreenKeepsNoHistory() {
        val term = Terminal(4, 2, 10)
        term.feed("$CSI?1049h1\n2\n3\n4")
        assertEquals(0, term.grid.historyLength)
        term.feed("$CSI?1049l1\n2\n3\n4")
        assertEquals(2, term.grid.historyLength)
    }

    @Test
    fun bellsAreTakenOnceHoweverManyArrived() {
        val term = Terminal(10, 5, 0)
        assertFalse(term.takeBell())
        term.feed("a\u0007b\u0007\u0007")
        assertTrue(term.takeBell())
        assertFalse(term.takeBell())
        term.feed("$ESC]0;title\u0007")
        assertFalse(term.takeBell(), "BEL ending an OSC is no bell")
        assertEquals(Event.Title(TitleTarget.Both, "title"), term.pollEvent())
        assertEquals(null, term.pollEvent())
    }

    @Test
    fun aTitleThatIsTooLongOrNotUtf8IsDropped() {
        val term = Terminal(10, 5, 0)
        term.feed("$ESC]2;" + "x".repeat(MAX_EVENT_TEXT + 1) + "\u0007")
        term.feed("$ESC]2;".encodeToByteArray() + byteArrayOf(0xC3.toByte(), 0x28, 0x07))
        term.feed("$ESC]1337;File=x\u0007")
        assertEquals(null, term.pollEvent())
        term.feed("$ESC]1;" + "x".repeat(MAX_EVENT_TEXT) + "$ESC\\")
        assertEquals(Event.Title(TitleTarget.Icon, "x".repeat(MAX_EVENT_TEXT)), term.pollEvent())
    }

    @Test
    fun mouseAndKeyboardModesAreNotRecognisedYet() {
        val term = Terminal(10, 5, 0)
        term.feed("$CSI?1000h$CSI?1000\$p$CSI?1006\$p$CSI?2027\$p")
        assertEquals("$CSI?1000;0\$y$CSI?1006;0\$y$CSI?2027;2\$y", term.takeReplies().decodeToString())
    }
}
