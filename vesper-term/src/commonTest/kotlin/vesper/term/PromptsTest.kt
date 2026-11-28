package vesper.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val PROMPT = "\u001b]133;A\u0007"

/** [blocks] prompts, each with [output] lines below it. */
private fun Terminal.prompts(
    blocks: Int,
    output: Int = 2,
    prompt: (Int) -> String = { "p$it" },
) {
    repeat(blocks) { i ->
        feed("$PROMPT${prompt(i)}\r\n")
        repeat(output) { feed("out\r\n") }
    }
}

/** The line at the viewport's top row. */
private val Terminal.viewTop: Long get() = screenTopLine - grid.displayOffset

/** The prompt lines counted from the oldest line the ring holds. */
private fun Terminal.relativePrompts(): List<Long> = promptLines().map { it - grid.firstLine }

class PromptsTest {
    @Test
    fun promptStartsAreRecordedOncePerLine() {
        val t = Terminal(10, 3, 100)
        t.prompts(3)
        assertEquals(listOf(0L, 3L, 6L), t.promptLines())
        t.feed("$PROMPT$PROMPT\u001b]133;B\u0007ls\u001b]133;C\u0007")
        assertEquals(listOf(0L, 3L, 6L, 9L), t.promptLines(), "B, C and a repeated A add nothing")
        // A prompt redrawn higher up, as after a cursor move, still lands in order.
        t.feed("\u001b[1;1H$PROMPT")
        assertEquals(listOf(0L, 3L, 6L, 7L, 9L), t.promptLines())
    }

    @Test
    fun theViewportWalksThePromptsBothWays() {
        val t = Terminal(10, 3, 100)
        t.prompts(6)
        assertEquals(16L, t.screenTopLine)
        for (line in listOf(15L, 12L, 9L, 6L, 3L, 0L)) {
            assertTrue(t.scrollToPrompt(forward = false))
            assertEquals(line, t.viewTop)
        }
        assertFalse(t.scrollToPrompt(forward = false), "nothing above the first prompt")
        assertEquals(0L, t.viewTop, "and nothing moved")
        for (line in listOf(3L, 6L, 9L, 12L, 15L)) {
            assertTrue(t.scrollToPrompt(forward = true))
            assertEquals(line, t.viewTop)
        }
        // The next prompt is on the screen below its top: the viewport goes to the bottom, then no further.
        t.feed("$PROMPT")
        assertEquals(listOf(0L, 3L, 6L, 9L, 12L, 15L, 18L), t.promptLines())
        assertTrue(t.scrollToPrompt(forward = true))
        assertEquals(0, t.grid.displayOffset)
        assertFalse(t.scrollToPrompt(forward = true))
        assertTrue(t.scrollToPrompt(forward = false))
        assertEquals(15L, t.viewTop)
    }

    @Test
    fun withoutPromptsNothingMoves() {
        val t = Terminal(10, 3, 100)
        repeat(10) { t.feed("out\r\n") }
        t.scrollDisplay(3)
        assertFalse(t.scrollToPrompt(forward = false))
        assertFalse(t.scrollToPrompt(forward = true))
        assertEquals(3, t.grid.displayOffset)
    }

    @Test
    fun marksLeaveWithTheLinesTheRingDrops() {
        val t = Terminal(10, 3, 5)
        t.prompts(5)
        assertTrue(t.grid.firstLine > 0)
        assertEquals(listOf(9L, 12L), t.promptLines(), "only the prompts the ring still holds")
        assertTrue(t.scrollToPrompt(forward = false))
        assertTrue(t.scrollToPrompt(forward = false))
        assertEquals(9L, t.viewTop)
        assertFalse(t.scrollToPrompt(forward = false))
    }

    @Test
    fun erasingTheScreenDropsItsMarksAndErasingHistoryDropsTheRest() {
        val t = Terminal(10, 3, 100)
        t.prompts(3)
        t.feed("${PROMPT}a\r\n$PROMPT")
        assertEquals(listOf(0L, 3L, 6L, 9L, 10L), t.promptLines())
        t.feed("\u001b[2J")
        assertEquals(listOf(0L, 3L, 6L), t.promptLines(), "ED 2 drops the marks on the screen's rows")
        t.feed("\u001b[H$PROMPT")
        t.feed("\u001b[3J")
        assertEquals(listOf(t.screenTopLine), t.promptLines(), "ED 3 drops the marks in history")
        assertFalse(t.scrollToPrompt(forward = false))
    }

    @Test
    fun aFullResetDropsTheScreensMarksAndKeepsHistorys() {
        val t = Terminal(10, 3, 100)
        t.prompts(3)
        t.feed(PROMPT)
        t.feed("\u001bc")
        assertEquals(listOf(0L, 3L, 6L), t.promptLines())
    }

    @Test
    fun theAlternateScreenRecordsNothingAndKeepsThePrimarysMarks() {
        val t = Terminal(10, 3, 100)
        t.prompts(3)
        t.feed("\u001b[?1049h$PROMPT\u001b[2J\u001b[3J")
        repeat(10) { t.feed("x\r\n") }
        assertEquals(listOf(0L, 3L, 6L), t.promptLines())
        assertFalse(t.scrollToPrompt(forward = false), "the alternate screen has no prompts")
        t.feed("\u001b[?1049l")
        assertTrue(t.scrollToPrompt(forward = false))
        assertEquals(6L, t.viewTop)
    }

    @Test
    fun marksRideARewrappingResize() {
        val t = Terminal(10, 3, 100)
        // Each prompt line wraps onto two rows at 10 columns.
        t.prompts(4, output = 1) { "x".repeat(15) }
        assertEquals(listOf(0L, 3L, 6L, 9L), t.relativePrompts())
        t.resize(20, 3)
        assertEquals(listOf(0L, 2L, 4L, 6L), t.relativePrompts())
        t.resize(5, 3)
        assertEquals(listOf(0L, 4L, 8L, 12L), t.relativePrompts())
        assertTrue(t.scrollToPrompt(forward = false))
        assertEquals(t.grid.firstLine + 12, t.viewTop)
        assertEquals("xxxxx", t.rowText(checkNotNull(t.grid.visibleRow(0))).trimEnd(), "the prompt's first row is on top")
    }

    @Test
    fun aResizeOnTheAlternateScreenMovesThePrimarysMarks() {
        val t = Terminal(10, 3, 100)
        t.prompts(2, output = 1) { "x".repeat(15) }
        t.feed("\u001b[?1049h")
        t.resize(20, 3)
        t.feed("\u001b[?1049l")
        assertEquals(listOf(0L, 2L), t.relativePrompts())
    }
}
