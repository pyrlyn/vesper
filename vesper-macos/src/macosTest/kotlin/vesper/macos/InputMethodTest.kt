package vesper.macos

import kotlinx.cinterop.BetaInteropApi
import platform.AppKit.NSApplication
import platform.AppKit.NSMarkedClauseSegmentAttributeName
import platform.AppKit.NSUnderlineStyleAttributeName
import platform.AppKit.NSUnderlineStyleSingle
import platform.AppKit.NSUnderlineStyleThick
import platform.Foundation.NSAttributedString
import platform.Foundation.NSDate
import platform.Foundation.NSMakeRange
import platform.Foundation.NSMakeRect
import platform.Foundation.NSMutableAttributedString
import platform.Foundation.NSRunLoop
import platform.Foundation.appendAttributedString
import platform.Foundation.create
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import vesper.term.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Marked text as an input method gives it: thick under the clause being converted, every part tagged as a clause. */
@OptIn(BetaInteropApi::class)
private fun marked(vararg parts: Pair<String, Boolean>): NSAttributedString {
    val text = NSMutableAttributedString()
    parts.forEachIndexed { index, (part, thick) ->
        val attributes =
            mapOf<Any?, Any?>(
                NSUnderlineStyleAttributeName to if (thick) NSUnderlineStyleThick else NSUnderlineStyleSingle,
                NSMarkedClauseSegmentAttributeName to index,
            )
        text.appendAttributedString(NSAttributedString.create(string = part, attributes = attributes))
    }
    return text
}

/** The view as an input method drives it, with `cat` echoing what is committed. */
class InputMethodTest {
    @Test
    fun theInputMethodsAttributesBecomeRuns() {
        val runs = markedRuns(marked("今日" to false, "は" to true))
        assertEquals(listOf(MarkedRun(0, 2, thick = false, tagged = true), MarkedRun(2, 3, thick = true, tagged = true)), runs)
        assertEquals(emptyList(), markedRuns("plain"))
        assertEquals("今日は", plainText(marked("今日" to false, "は" to true)))
    }

    @Test
    fun rangesCrossBetweenAppKitAndTheCore() {
        assertEquals(TextRange(2, 5), NSMakeRange(2u, 3u).toTextRange())
        assertEquals(null, NO_RANGE.toTextRange())
        assertEquals(TextRange(1, 4), TextRange(1, 4).toNSRange().toTextRange())
    }

    @Test
    fun aCompositionShowsAtTheCursorAndCommitsAsText() {
        NSApplication.sharedApplication
        val text = TextStyle()
        val (width, height) = viewSize(Cells(20, 4), text.cell)
        val view = TerminalView(text, NSMakeRect(0.0, 0.0, width, height))
        view.start(2.0, program = "/bin/cat")
        try {
            view.setMarkedText("にほ", NSMakeRange(2u, 0u), NO_RANGE)
            assertTrue(view.hasMarkedText())
            assertEquals(TextRange(0, 2), view.markedRange().toTextRange())
            assertEquals("にほ", view.screenText.lines().first(), "the preedit is laid over the cursor row")
            view.setMarkedText(marked("日本" to true, "語" to false), NSMakeRange(0u, 2u), NO_RANGE)
            assertEquals("日本語", view.screenText.lines().first())
            assertEquals(TextRange(0, 2), view.selectedRange().toTextRange())
            view.insertText("日本語", NO_RANGE)
            assertFalse(view.hasMarkedText())
            // The line discipline echoes the committed text; no preedit is left over it.
            awaitFirstLine(view, "日本語")
            view.setMarkedText("x", NSMakeRange(1u, 0u), NO_RANGE)
            view.unmarkText()
            awaitFirstLine(view, "日本語x")
        } finally {
            view.stop()
        }
    }

    private fun awaitFirstLine(
        view: TerminalView,
        line: String,
    ) {
        val end = TimeSource.Monotonic.markNow() + 10.seconds
        while (view.screenText.lines().first() != line) {
            if (end.hasPassedNow()) fail("expected $line; the screen shows:\n${view.screenText}")
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.05))
            view.refresh()
        }
    }
}
