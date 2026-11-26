package vesper.macos

import platform.AppKit.NSAccessibilityTextAreaRole
import platform.AppKit.NSApplication
import platform.Foundation.NSDate
import platform.Foundation.NSMakeRange
import platform.Foundation.NSMakeRect
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import vesper.term.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** The view as VoiceOver reads it: the accessibility methods called directly, with `cat` echoing typed text. */
class AccessibilityTest {
    @Test
    fun voiceOverReadsTheScreenAndSelectsThroughTheCore() {
        NSApplication.sharedApplication
        val text = TextStyle()
        val (width, height) = viewSize(Cells(10, 3), text.cell)
        val view = TerminalView(text, NSMakeRect(0.0, 0.0, width, height))
        view.start(2.0, program = "/bin/cat")
        try {
            assertTrue(view.isAccessibilityElement())
            assertEquals(NSAccessibilityTextAreaRole, view.accessibilityRole())
            view.insertText("ab中", NO_RANGE)
            val end = TimeSource.Monotonic.markNow() + 10.seconds
            while (view.accessibilityValue() != "ab中\n\n") {
                if (end.hasPassedNow()) fail("expected the echo; the reader sees:\n${view.accessibilityValue()}")
                NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.05))
                view.refresh()
            }
            // The text is read from the terminal, the cursor from the frame: bring the frame up to the echo too.
            view.refresh()
            assertEquals(5L, view.accessibilityNumberOfCharacters())
            assertEquals(TextRange(0, 4), view.accessibilityRangeForLine(0).toTextRange(), "a line holds its newline")
            assertEquals(null, view.accessibilityRangeForLine(3).toTextRange())
            assertEquals(1L, view.accessibilityLineForIndex(4))
            assertEquals("b中", view.accessibilityStringForRange(NSMakeRange(1u, 2u)))
            assertEquals("ab中", view.accessibilityAttributedStringForRange(NSMakeRange(0u, 3u))?.string)
            assertEquals(0L, view.accessibilityInsertionPointLineNumber())
            assertEquals(
                TextRange(3, 3),
                view.accessibilitySelectedTextRange().toTextRange(),
                "nothing selected: the cursor after the wide character",
            )

            view.setAccessibilitySelectedTextRange(NSMakeRange(1u, 2u))
            assertEquals("b中", view.accessibilitySelectedText())
            assertEquals(TextRange(1, 3), view.accessibilitySelectedTextRange().toTextRange())
            assertEquals(1, view.accessibilitySelectedTextRanges()?.size)

            view.setAccessibilitySelectedTextRange(NSMakeRange(2u, 0u))
            assertEquals("", view.accessibilitySelectedText(), "an empty range clears the selection")
            assertEquals(TextRange(3, 3), view.accessibilitySelectedTextRange().toTextRange())
        } finally {
            view.stop()
        }
    }
}
