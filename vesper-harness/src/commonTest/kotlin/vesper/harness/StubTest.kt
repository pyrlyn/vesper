package vesper.harness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StubTest {
    @Test
    fun recordedStreamMatchesTheExpectedGrid() {
        val stub = Stub(3, 2)
        stub.feed("xy\nz".encodeToByteArray())
        assertEquals(listOf("xy ", "z  "), stub.lines())
    }

    // xterm DECAWM (ctlseqs; esctest2 test_DECSET_DECAWM and
    // test_DECSET_DECAWM_CursorAtRightMargin): the last column arms a wrap and the cursor stays
    // there. CR returns to column 0 of that line; only the next printable wraps.
    @Test
    fun xtermDecawmDefersWrapUntilTheNextPrintable() {
        val sameLine = Stub(4, 2)
        sameLine.feed("abcd\rX".encodeToByteArray())
        assertEquals(listOf("Xbcd", "    "), sameLine.lines())

        val next = Stub(4, 2)
        next.feed("abcdW".encodeToByteArray())
        assertEquals(listOf("abcd", "W   "), next.lines())

        // A control clears the pending wrap, so a line feed starts the next row.
        val lineFeed = Stub(4, 3)
        lineFeed.feed("abcd\nZ".encodeToByteArray())
        assertEquals(listOf("abcd", "Z   ", "    "), lineFeed.lines())

        assertGolden("abcd\rX\nabcdY".encodeToByteArray(), "wrap.txt")
    }

    @Test
    fun newlineCarriageReturnAndIgnoredControlsMatchTheirGoldenSnapshot() {
        assertGolden(byteArrayOf(*"ab\ncd\rX".encodeToByteArray(), 0x00, 0x1B, 'Y'.code.toByte()), "controls.txt")
    }

    @Test
    fun aGoldenFrameOfTheWrongShapeIsRefused() {
        assertEquals(listOf("ab", "  "), parseGolden("ok", "2 2\nab\n  \n").lines)
        assertEquals(listOf("ab"), parseGolden("crlf", "2 1\r\nab\r\n").lines)
        val malformed =
            listOf(
                "2\nab\n",
                "0 1\n\n",
                "x 1\nab\n",
                "2 2\nab\n",
                "2 1\nab",
                "2 1\nab\ncd\n",
                "3 1\nab\n",
            )
        for (text in malformed) assertFailsWith<IllegalArgumentException>(text) { parseGolden("bad", text) }
    }

    @Test
    fun feedingBytesNeverChangesDimensions() {
        val tiny = Stub(0, 0)
        assertEquals(1 to 1, tiny.cols to tiny.rows)
        tiny.feed(byteArrayOf(*"hello\n\r".encodeToByteArray(), 0x00, 0x7F))
        assertEquals(1 to 1, tiny.cols to tiny.rows)
        // Only `h` fits; the wrap for `e` leaves the grid and the rest is dropped.
        assertEquals(listOf("h"), tiny.lines())

        val wide = Stub(5, 2)
        wide.feed(byteArrayOf(0x00, '\n'.code.toByte(), 'A'.code.toByte(), 0x7F, 0xFF.toByte()))
        assertEquals(5 to 2, wide.cols to wide.rows)
        assertEquals(listOf("     ", "A    "), wide.lines())
    }

    @Test
    fun oversizedAxesAreCapped() {
        val stub = Stub(Int.MAX_VALUE, -1)
        assertEquals(4096 to 1, stub.cols to stub.rows)
    }

    @Test
    fun seededCorpusKeepsTheGridSizeOnEdgeSlices() {
        val past = Stub(2, 1)
        past.feed("abcd".encodeToByteArray())
        assertEquals(listOf("ab"), past.lines())

        val edges =
            listOf(
                ByteArray(0),
                byteArrayOf(0x00, 0x01, 0x07, 0x1B, 0x7F),
                "abcdefghijklmnopqrstuvwxyz".encodeToByteArray(),
            )
        edges.forEach(::stubFeedKeepsSize)

        fuzzCases(0x00C0FFEE, 64) { stubFeedKeepsSize(it.nextBytes(it.nextInt(32))) }
    }

    @Test
    fun aFailingFuzzCaseNamesItsIndexAndSeed() {
        var calls = 0
        val error = assertFailsWith<AssertionError> { fuzzCases(5, 10) { check(++calls < 4) { "boom" } } }
        val message = error.message.orEmpty()
        assertTrue(message.startsWith("fuzz case 3 failed (") && message.endsWith("): boom"), message)
    }
}
