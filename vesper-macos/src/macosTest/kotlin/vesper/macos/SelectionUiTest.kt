package vesper.macos

import kotlinx.cinterop.BetaInteropApi
import platform.AppKit.NSApplication
import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSEvent
import platform.AppKit.NSEventModifierFlagShift
import platform.AppKit.NSEventTypeLeftMouseDown
import platform.AppKit.NSEventTypeLeftMouseDragged
import platform.AppKit.NSEventTypeLeftMouseUp
import platform.AppKit.NSMenuItem
import platform.AppKit.NSPasteboard
import platform.AppKit.NSPasteboardTypeString
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowStyleMaskBorderless
import platform.Foundation.NSDate
import platform.Foundation.NSMakePoint
import platform.Foundation.NSMakeRect
import platform.Foundation.NSRunLoop
import platform.Foundation.NSSelectorFromString
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import vesper.host.Cells
import vesper.host.PADDING
import vesper.host.Palette
import vesper.host.viewSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val COLS = 16
private const val ROWS = 3

/**
 * The view in a borderless window, so mouse events carry window coordinates, running `cat` with
 * "one two two" typed; Copy goes to a private pasteboard.
 */
private class Pane {
    val text = TextStyle()
    val size = viewSize(Cells(COLS, ROWS), text.cell)
    val window = NSWindow(NSMakeRect(0.0, 0.0, size.first, size.second), NSWindowStyleMaskBorderless, NSBackingStoreBuffered, false)
    val view = TerminalView(text, NSMakeRect(0.0, 0.0, size.first, size.second))
    val pasteboard: NSPasteboard = NSPasteboard.pasteboardWithUniqueName()

    init {
        NSApplication.sharedApplication
        window.contentView = view
        view.pasteboard = pasteboard
        view.start(2.0, program = "/bin/cat")
        view.insertText("one two two", NO_RANGE)
        await { view.screenText.lines().first() == "one two two" }
    }

    fun await(done: () -> Boolean): Unit = awaitViews({ listOf(view) }, done)

    /** A left-button event over the middle of cell ([row], [col]); window y counts up from the bottom. */
    fun mouse(
        type: ULong,
        row: Int,
        col: Int,
        clicks: Long = 1,
        flags: ULong = 0u,
    ): NSEvent {
        val x = PADDING + (col + 0.5) * text.cell.width
        val y = size.second - (PADDING + (row + 0.5) * text.cell.height)
        return assertNotNull(NSEvent.mouseEventWithType(type, NSMakePoint(x, y), flags, 0.0, window.windowNumber, null, 0, clicks, 1f))
    }

    fun click(
        row: Int,
        col: Int,
        clicks: Long = 1,
        flags: ULong = 0u,
    ) {
        view.mouseDown(mouse(NSEventTypeLeftMouseDown, row, col, clicks, flags))
        view.mouseUp(mouse(NSEventTypeLeftMouseUp, row, col, clicks, flags))
    }

    val canCopy: Boolean get() = view.validateMenuItem(NSMenuItem("Copy", NSSelectorFromString("copy:"), ""))

    fun copied(): String? {
        pasteboard.clearContents()
        view.copy(null)
        return pasteboard.stringForType(NSPasteboardTypeString)
    }

    fun close() {
        view.stop()
        pasteboard.releaseGlobally()
    }
}

@OptIn(BetaInteropApi::class)
class SelectionUiTest {
    @Test
    fun aDragSelectsAndCopyTakesTheText() {
        val pane = Pane()
        try {
            assertFalse(pane.canCopy)
            pane.view.mouseDown(pane.mouse(NSEventTypeLeftMouseDown, 0, 0))
            pane.view.mouseDragged(pane.mouse(NSEventTypeLeftMouseDragged, 0, 5))
            pane.view.mouseUp(pane.mouse(NSEventTypeLeftMouseUp, 0, 5))
            assertTrue(pane.canCopy)
            assertEquals("one tw", pane.copied())
            // Shift extends the selection that is there.
            pane.click(0, 9, flags = NSEventModifierFlagShift)
            assertEquals("one two tw", pane.copied())
            pane.click(0, 2)
            assertFalse(pane.canCopy, "a click that never moved selects nothing")
            assertNull(pane.copied())
        } finally {
            pane.close()
        }
    }

    @Test
    fun twoClicksTakeAWordAndThreeTheLine() {
        val pane = Pane()
        try {
            pane.click(0, 5, clicks = 2)
            assertEquals("two", pane.copied())
            pane.click(0, 1, clicks = 3)
            assertEquals("one two two", pane.copied())
        } finally {
            pane.close()
        }
    }

    @Test
    fun theFindBarSearchesStepsAndCounts() {
        val pane = Pane()
        val view = pane.view
        try {
            val stepItem = NSMenuItem("Find Next", NSSelectorFromString("findNext:"), "g")
            assertFalse(view.validateMenuItem(stepItem))
            view.showFind(null)
            val bar = assertNotNull(view.findBar)
            view.findEdited("TWO")
            assertTrue(view.validateMenuItem(stepItem))
            assertEquals("", bar.statusText, "counted once the pattern settles")
            view.settleFind()
            assertEquals("2 matches", bar.statusText)
            assertEquals(2, view.find.count)
            // Match case finds none of the lower-case words.
            bar.onToggleCase()
            view.settleFind()
            assertEquals("No matches", bar.statusText)
            view.closeFind()
            assertNull(view.findBar)
            assertFalse(view.validateMenuItem(stepItem))
            view.showFind(null)
            assertEquals("TWO", assertNotNull(view.findBar).pattern, "the bar reopens on the last search")
            view.closeFind()
        } finally {
            pane.close()
        }
    }

    /** The task's check: a selection and a highlighted match in one rendering. */
    @Test
    fun snapshotShowsASelectionAndTheMatches() {
        val pane = Pane()
        val view = pane.view
        try {
            pane.click(0, 1, clicks = 2)
            view.showFind(null)
            view.findEdited("two")
            view.settleFind()
            val bitmap = view.snapshot(2.0) ?: return println("no Metal device: skipped")
            val palette = Palette()

            fun corner(col: Int): Int {
                val x = ((PADDING + (col + 0.05) * pane.text.cell.width) * 2.0).toInt()
                val y = ((PADDING + 0.05 * pane.text.cell.height) * 2.0).toInt()
                return bitmap.pixel(x, y) and 0xFFFFFF
            }
            assertEquals(palette.selection, corner(0))
            assertEquals(palette.currentMatch, corner(4))
            assertEquals(palette.match, corner(8))
            savePng(bitmap, "macos-selection.png")
            view.closeFind()
        } finally {
            pane.close()
        }
    }
}
