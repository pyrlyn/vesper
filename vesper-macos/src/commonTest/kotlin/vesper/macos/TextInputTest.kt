package vesper.macos

import vesper.term.FramePreedit
import vesper.term.PreeditClause
import vesper.term.PreeditKind
import vesper.term.PreeditSpan
import vesper.term.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

private fun run(
    start: Int,
    end: Int,
    thick: Boolean = false,
    tagged: Boolean = true,
) = MarkedRun(start, end, thick, tagged)

class TextInputTest {
    @Test
    fun markedRangesFollowTheComposition() {
        val input = TextInput()
        assertFalse(input.hasMarkedText)
        assertNull(input.markedRange)
        assertEquals(TextRange(0, 0), input.selectedRange)
        input.setMarked("にほ", 2, 0)
        assertEquals(TextRange(0, 2), input.markedRange)
        assertEquals(TextRange(2, 2), input.selectedRange)
        assertEquals(2, input.caret)
        input.setMarked("日本語", 2, 1)
        assertEquals(2, input.caret, "a converted clause puts the caret at its start")
        input.setMarked("ab", 9, 4)
        assertEquals(TextRange(2, 2), input.selectedRange, "ranges from the input method are cut to the text")
        input.setMarked("ab", null, 0)
        assertEquals(TextRange(2, 2), input.selectedRange, "no location is the end")
    }

    @Test
    fun clausesComeFromTheInputMethodsAttributes() {
        val input = TextInput()
        input.setMarked("今日は晴れ", 2, 1, listOf(run(0, 2), run(2, 3, thick = true), run(3, 5)))
        assertEquals(
            listOf(
                PreeditClause(0, 2, PreeditKind.Converted),
                PreeditClause(2, 3, PreeditKind.Target),
                PreeditClause(3, 5, PreeditKind.Converted),
            ),
            input.clauses,
            "converting: the thick clause is the target, the tagged others are converted",
        )
        input.setMarked("にほん", 3, 0, listOf(run(0, 3)))
        assertEquals(listOf(PreeditClause(0, 3, PreeditKind.Input)), input.clauses, "tagged with nothing thick is still input")
        input.setMarked("にほ", 2, 0)
        assertEquals(emptyList(), input.clauses)
        input.setMarked("ab", 2, 0, listOf(run(0, 2, tagged = false)))
        assertEquals(emptyList(), input.clauses, "a thin underline alone tells nothing apart")
    }

    @Test
    fun substringsComeFromTheMarkedTextOnly() {
        val input = TextInput()
        assertNull(input.substring(TextRange(0, 1)))
        input.setMarked("かな", 2, 0)
        assertEquals(TextRange(1, 2), input.substring(TextRange(1, 6)))
        assertNull(input.substring(TextRange(2, 3)))
    }

    @Test
    fun insertTextAfterAPreeditCommitsItAsText() {
        val input = TextInput()
        input.beginKey()
        input.setMarked("にほん", 3, 0)
        assertEquals(KeyOutcome.Consumed, input.endKey())
        input.beginKey()
        assertNull(input.insert("日本"))
        assertEquals(KeyOutcome.Text("日本"), input.endKey())
        assertFalse(input.hasMarkedText)
        assertEquals("→", input.insert("→"), "outside a key event (the character viewer, dictation) it is sent now")
    }

    @Test
    fun aDeadKeyComposesWithTheNextKeyAndBackspaceEditsTheComposition() {
        val input = TextInput()
        input.beginKey()
        input.setMarked("´", 1, 0)
        assertEquals(KeyOutcome.Consumed, input.endKey())
        input.beginKey()
        assertNull(input.insert("é"))
        assertEquals(KeyOutcome.Text("é"), input.endKey())
        input.beginKey()
        input.setMarked("´", 1, 0)
        input.endKey()
        input.beginKey()
        input.setMarked("", 0, 0)
        assertEquals(KeyOutcome.Consumed, input.endKey(), "backspace inside a composition reaches no program")
    }

    @Test
    fun aPlainKeyStaysAKey() {
        val input = TextInput()
        input.beginKey()
        assertNull(input.insert("a"))
        assertEquals(KeyOutcome.Key, input.endKey())
        input.beginKey()
        assertEquals(KeyOutcome.Key, input.endKey())
    }

    @Test
    fun thePreeditIsUnderlinedPerClauseOrAsOneLine() {
        assertEquals(listOf(PreeditLine(2, 4, false)), preeditLines(FramePreedit(0, 2, 4, emptyList())))
        val spans = listOf(PreeditSpan(2, 4, PreeditKind.Converted), PreeditSpan(6, 2, PreeditKind.Target))
        assertEquals(listOf(PreeditLine(2, 4, false), PreeditLine(6, 2, true)), preeditLines(FramePreedit(0, 2, 6, spans)))
    }
}
