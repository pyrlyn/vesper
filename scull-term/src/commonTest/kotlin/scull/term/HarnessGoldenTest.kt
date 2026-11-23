package scull.term

import scull.harness.readGolden
import kotlin.test.Test
import kotlin.test.assertEquals

// The harness stub (scull-harness) stands in for this core with fewer rules. Where the two overlap,
// its golden frames must be what the core draws, or the stub tests check a terminal that does not
// exist. controls.txt is left out: the stub prints the byte after an ESC, the core reads both as
// one escape sequence.

class HarnessGoldenTest {
    @Test
    fun theStubWrapFrameIsWhatTheTerminalDraws() {
        val golden = readGolden("wrap.txt")
        val t = Terminal(golden.cols, golden.rows, 0)
        // The stub's line feed also returns to column 0, as text arrives through a tty with ONLCR;
        // LNM makes the terminal do the same.
        t.feed("${CSI}20h")
        // The stream of StubTest.xtermDecawmDefersWrapUntilTheNextPrintable.
        t.feed("abcd\rX\nabcdY")
        assertEquals(golden.lines, t.screenText().map { it.replace('.', ' ') })
    }
}
