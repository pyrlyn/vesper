package vesper.term

import vesper.term.PreeditKind.Converted
import vesper.term.PreeditKind.Input
import vesper.term.PreeditKind.Target
import vesper.unicode.ClusterPolicy
import vesper.unicode.WidthOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun layout(
    text: String,
    caret: Int,
    row: Int,
    col: Int,
    cols: Int,
    clauses: List<PreeditClause> = emptyList(),
): PreeditLayout? = Preedit().apply { set(text, caret, clauses) }.layout(row, col, cols, WidthOptions.DEFAULT, ClusterPolicy.Grapheme)

private fun spans(
    text: String,
    clauses: List<PreeditClause>,
    col: Int,
    cols: Int,
): List<PreeditSpan> = checkNotNull(layout(text, text.length, 0, col, cols, clauses)).shown.spans

private fun clause(
    start: Int,
    end: Int,
    kind: PreeditKind,
) = PreeditClause(start, end, kind)

class PreeditTest {
    @Test
    fun cjkTakesTwoCellsEachAndTheCaretFollowsTheText() {
        val l = checkNotNull(layout("日本ご", 3, 1, 2, 20))
        assertEquals(FramePreedit(1, 2, 6, emptyList()), l.shown)
        assertEquals(8, l.caret)
        assertEquals(listOf(2, 2, 2), l.widths)
    }

    @Test
    fun aClusterIsOneUnitAndACaretInsideItStaysBeforeIt() {
        val l = checkNotNull(layout("éx", 1, 0, 0, 10))
        assertEquals(listOf("é", "x"), l.clusters)
        assertEquals(2 to 0, l.shown.cols to l.caret)
        val emoji = checkNotNull(layout("👍🏽a", 4, 0, 0, 10))
        assertEquals(listOf("👍🏽", "a"), emoji.clusters)
        assertEquals(listOf(2, 1), emoji.widths)
    }

    @Test
    fun nearTheRightEdgeItShiftsLeftToFit() {
        val l = checkNotNull(layout("abcd", 4, 0, 8, 10))
        assertEquals(6 to 4, l.shown.col to l.shown.cols)
        assertEquals(9, l.caret)
    }

    @Test
    fun widerThanTheRowItKeepsTheCaretInView() {
        val end = checkNotNull(layout("abcdefghij", 10, 0, 3, 4))
        assertEquals(listOf("g", "h", "i", "j"), end.clusters)
        assertEquals(0 to 3, end.shown.col to end.caret)
        val start = checkNotNull(layout("abcdefghij", 0, 0, 3, 4))
        assertEquals(listOf("a", "b", "c", "d"), start.clusters)
        assertEquals(0, start.caret)
    }

    @Test
    fun aWideClusterThatCannotFitIsNotShown() {
        assertNull(layout("日", 0, 0, 0, 1))
        assertNull(layout("", 0, 0, 0, 10))
    }

    @Test
    fun textPastTheCapIsCutOffASurrogatePair() {
        val p = Preedit()
        p.set("a" + "😀".repeat(MAX_PREEDIT_LENGTH), Int.MAX_VALUE, emptyList())
        assertEquals(MAX_PREEDIT_LENGTH - 1, p.text.length, "the pair across the cap is dropped whole")
        val l = checkNotNull(Preedit().apply { set("😀😀", 1, emptyList()) }.layout(0, 0, 10, WidthOptions.DEFAULT, ClusterPolicy.Grapheme))
        assertEquals(0, l.caret, "a caret inside a pair moves back")
    }

    @Test
    fun clausesBecomeRunsOfColumns() {
        // 日本 converted, ご the target, x still input.
        val got = spans("日本ごx", listOf(clause(0, 2, Converted), clause(2, 3, Target), clause(3, 4, Input)), 2, 20)
        assertEquals(listOf(PreeditSpan(2, 4, Converted), PreeditSpan(6, 2, Target), PreeditSpan(8, 1, Input)), got)
    }

    @Test
    fun textNoClauseNamesIsInputAndNeighboursOfOneKindMerge() {
        val got = spans("abcd", listOf(clause(0, 1, Converted), clause(1, 2, Converted)), 0, 10)
        assertEquals(listOf(PreeditSpan(0, 2, Converted), PreeditSpan(2, 2, Input)), got)
        assertEquals(emptyList(), spans("abc", emptyList(), 0, 10))
    }

    @Test
    fun untidyClausesAreCutToTheTextAndNeverOverlap() {
        // Out of order, past the end, inside a pair, overlapping, empty, reversed.
        val got = spans("日本😀", listOf(clause(1, 99, Target), clause(0, 3, Converted), clause(3, 3, Target), clause(2, 1, Target)), 0, 10)
        assertEquals(listOf(PreeditSpan(0, 4, Converted), PreeditSpan(4, 2, Target)), got)
    }

    @Test
    fun spansFollowTheClustersThatStayInView() {
        val got = spans("abcdefgh", listOf(clause(0, 4, Converted), clause(4, 8, Target)), 3, 4)
        assertEquals(listOf(PreeditSpan(0, 4, Target)), got, "the left clause scrolled out")
    }

    @Test
    fun theFrameShowsThePreeditOverTheCursorRowAndPutsItsCursorAtTheCaret() {
        val t = Terminal(10, 3, 10)
        t.feed("$ ab\r\nxyz${CSI}1;3H")
        val f = Frame()
        t.updateFrame(f)
        f.setPreedit("日本", 1, listOf(clause(0, 1, Converted), clause(1, 2, Target)))
        t.updateFrame(f)
        assertEquals(FramePreedit(0, 2, 4, listOf(PreeditSpan(2, 2, Converted), PreeditSpan(4, 2, Target))), f.preedit)
        assertEquals(FrameCursor(0, 4), f.cursor)
        assertEquals("$ 日本", checkNotNull(f.row(0)).text, "the preedit covers the text under it")
        assertEquals(listOf(0), f.damage.dirty, "only the row it entered is repainted")
        assertEquals(2, f.cell(0, 2).width)
        assertEquals(0, f.cell(0, 3).width)
        f.setPreedit("")
        t.updateFrame(f)
        assertNull(f.preedit)
        assertEquals(FrameCursor(0, 2), f.cursor)
        assertEquals("$ ab", checkNotNull(f.row(0)).text, "the row is back once it ends")
        assertEquals(listOf(0), f.damage.dirty)
    }

    @Test
    fun aHiddenCursorStillGetsACaretAndHalfACoveredWideCharacterIsBlank() {
        val t = Terminal(8, 2, 10)
        t.feed("中文字${CSI}1;4H$CSI?25l")
        val f = Frame()
        f.setPreedit("x")
        t.updateFrame(f)
        assertEquals(FrameCursor(0, 4), f.cursor)
        assertEquals("中x字", checkNotNull(f.row(0)).text)
        assertEquals(1 to 0, f.cell(0, 2).width to f.cell(0, 2).codePoint, "the head of 文 left of the preedit")
    }

    @Test
    fun outputThatScrollsWhileComposingLeavesNoPreeditBehind() {
        val t = Terminal(6, 3, 10)
        t.feed("1\r\n2\r\n3")
        val f = Frame()
        f.setPreedit("ab")
        t.updateFrame(f)
        assertEquals("3ab", checkNotNull(f.row(2)).text)
        t.feed("\r\n4")
        t.updateFrame(f)
        assertEquals(listOf("2", "3", "4ab"), (0 until 3).map { checkNotNull(f.row(it)).text })
        assertTrue(1 in f.damage.dirty, "the row the preedit left is repainted, not moved")
    }
}
