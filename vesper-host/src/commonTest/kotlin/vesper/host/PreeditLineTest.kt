package vesper.host

import vesper.term.FramePreedit
import vesper.term.PreeditKind
import vesper.term.PreeditSpan
import kotlin.test.Test
import kotlin.test.assertEquals

class PreeditLineTest {
    @Test
    fun thePreeditIsUnderlinedPerClauseOrAsOneLine() {
        assertEquals(listOf(PreeditLine(2, 4, false)), preeditLines(FramePreedit(0, 2, 4, emptyList())))
        val spans = listOf(PreeditSpan(2, 4, PreeditKind.Converted), PreeditSpan(6, 2, PreeditKind.Target))
        assertEquals(listOf(PreeditLine(2, 4, false), PreeditLine(6, 2, true)), preeditLines(FramePreedit(0, 2, 6, spans)))
    }
}
