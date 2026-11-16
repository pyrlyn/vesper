package app.vesper

import app.vesper.terminal.vt.Attr
import app.vesper.terminal.vt.Terminal
import app.vesper.terminal.vt.TerminalListener
import app.vesper.terminal.vt.Utf8Decoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalTest {
    private fun term(cols: Int = 20, rows: Int = 5) = Terminal(cols, rows)

    private fun Terminal.lines(): List<String> = (0 until mainLineCount()).map { mainLine(it).text() }

    @Test
    fun printsAndBreaksLines() {
        val t = term()
        t.feed("hello\r\nworld")
        assertEquals(listOf("hello", "world"), t.lines())
        assertEquals(5, t.cursorX)
    }

    @Test
    fun wrapsLongLinesAndMarksThemWrapped() {
        val t = term(cols = 5)
        t.feed("abcdefgh")
        assertEquals(listOf("abcde", "fgh"), t.lines())
        assertTrue(t.mainLine(0).wrapped)
        assertEquals("abcdefgh", t.plainText())
    }

    @Test
    fun scrollsIntoScrollback() {
        val t = term(rows = 3)
        t.feed("1\r\n2\r\n3\r\n4\r\n5")
        assertEquals(2, t.scrollback.size)
        assertEquals(listOf("1", "2", "3", "4", "5"), t.lines())
    }

    @Test
    fun carriageReturnOverwritesForProgressBars() {
        val t = term()
        t.feed("10%\r50%\r100%")
        assertEquals(listOf("100%"), t.lines())
    }

    @Test
    fun eraseInLineAndCursorMovement() {
        val t = term()
        t.feed("abcdef\u001b[3D\u001b[K")
        assertEquals(listOf("abc"), t.lines())
        t.feed("\u001b[1;1HX")
        assertEquals(listOf("Xbc"), t.lines())
    }

    @Test
    fun sgrColorsAreStored() {
        val t = term()
        t.feed("\u001b[1;31mR\u001b[38;5;200mP\u001b[38;2;1;2;3mT\u001b[0mN")
        val l = t.mainLine(0)
        assertEquals(Attr.palette(1), Attr.fg(l.attrs[0]))
        assertTrue(Attr.flags(l.attrs[0]) and Attr.BOLD != 0)
        assertEquals(Attr.palette(200), Attr.fg(l.attrs[1]))
        assertEquals(Attr.rgb(1, 2, 3), Attr.fg(l.attrs[2]))
        assertEquals(0L, l.attrs[3])
    }

    @Test
    fun colonTrueColorForm() {
        val t = term()
        t.feed("\u001b[38:2::10:20:30mX")
        assertEquals(Attr.rgb(10, 20, 30), Attr.fg(t.mainLine(0).attrs[0]))
    }

    @Test
    fun alternateScreenRestoresMainContent() {
        var alt = false
        val t = Terminal(20, 5, listener = object : TerminalListener {
            override fun onAltScreen(active: Boolean) {
                alt = active
            }
        })
        t.feed("main")
        t.feed("\u001b[?1049h\u001b[2J\u001b[Hvim")
        assertTrue(t.isAltScreen)
        assertTrue(alt)
        assertEquals("vim", t.screenLine(0).text())
        t.feed("\u001b[?1049l")
        assertFalse(t.isAltScreen)
        assertEquals(listOf("main"), t.lines())
        assertEquals(4, t.cursorX)
    }

    @Test
    fun scrollRegionKeepsLinesOutsideIt() {
        val t = term(rows = 4)
        t.feed("a\r\nb\r\nc\r\nd")
        t.feed("\u001b[2;3r\u001b[3;1H\n")
        assertEquals(listOf("a", "c", "", "d"), (0 until 4).map { t.screenLine(it).text() })
    }

    @Test
    fun oscIsReportedToListener() {
        val seen = mutableListOf<Pair<Int, String>>()
        val t = Terminal(20, 5, listener = object : TerminalListener {
            override fun onOsc(code: Int, data: String) {
                seen += code to data
            }
        })
        t.feed("\u001b]133;D;1\u0007x\u001b]7;file://host/tmp\u001b\\")
        assertEquals(listOf(133 to "D;1", 7 to "file://host/tmp"), seen)
        assertEquals("x", t.mainLine(0).text())
    }

    @Test
    fun cursorPositionReport() {
        val responses = mutableListOf<String>()
        val t = Terminal(20, 5, listener = object : TerminalListener {
            override fun respond(text: String) {
                responses += text
            }
        })
        t.feed("ab\u001b[6n")
        assertEquals(listOf("\u001b[1;3R"), responses)
    }

    @Test
    fun wideCharactersTakeTwoColumns() {
        val t = term()
        t.feed("日本x")
        assertEquals(5, t.cursorX)
        assertEquals("日本x", t.mainLine(0).text())
    }

    @Test
    fun utf8DecoderHandlesSplitSequences() {
        val bytes = "→ ok 日本".encodeToByteArray()
        val d = Utf8Decoder()
        val out = StringBuilder()
        for (b in bytes) out.append(d.decode(byteArrayOf(b)))
        assertEquals("→ ok 日本", out.toString())
    }

    @Test
    fun deleteAndInsertCharacters() {
        val t = term()
        t.feed("abcdef\u001b[1;2H\u001b[2P")
        assertEquals("adef", t.mainLine(0).text())
        t.feed("\u001b[2@")
        assertEquals("a  def", t.mainLine(0).text())
    }

    @Test
    fun resizeShrinkPushesTopRowsToScrollback() {
        val t = term(rows = 4)
        t.feed("1\r\n2\r\n3\r\n4")
        t.resize(20, 2)
        assertEquals(listOf("1", "2", "3", "4"), t.lines())
        assertEquals(1, t.cursorY)
    }
}
