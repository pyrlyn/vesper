package vesper.macos

import kotlinx.cinterop.useContents
import platform.AppKit.NSApplication
import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSEventModifierFlagCommand
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowStyleMaskBorderless
import platform.Foundation.NSMakeRect
import vesper.config.Action
import vesper.config.Settings
import vesper.host.SplitAxis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

// US-layout key codes (kVK_ANSI_*).
private const val KEY_D: UShort = 0x02u
private const val KEY_W: UShort = 0x0Du
private const val KEY_T: UShort = 0x11u
private const val KEY_RIGHT_BRACKET: UShort = 0x1Eu

private const val WIDTH = 401.0
private const val HEIGHT = 300.0

private fun command(
    keyCode: UShort,
    characters: String,
) = keyEvent(keyCode, characters, NSEventModifierFlagCommand)

private fun TerminalView.frameValues() = frame.useContents { listOf(origin.x, origin.y, size.width, size.height) }

class PaneHostTest {
    private val text = TextStyle()

    /** A host filling a borderless window, so views can take the keys, running `cat` in every pane. */
    private fun host(): Pair<NSWindow, PaneHostView> {
        NSApplication.sharedApplication
        val window = NSWindow(NSMakeRect(0.0, 0.0, WIDTH, HEIGHT), NSWindowStyleMaskBorderless, NSBackingStoreBuffered, false)
        val host = PaneHostView(text, NSMakeRect(0.0, 0.0, WIDTH, HEIGHT), Settings.DEFAULT)
        host.program = "/bin/cat"
        window.contentView = host
        window.makeFirstResponder(host.focusedView)
        host.start()
        return window to host
    }

    private fun PaneHostView.typed(
        view: TerminalView,
        word: String,
    ) {
        view.insertText(word, NO_RANGE)
        awaitViews({ views }) { view.screenText.lines().first() == word }
    }

    @Test
    fun splitsKeepEveryShellAndTheLayoutFillsTheWindow() {
        val (window, host) = host()
        try {
            val first = host.focusedView
            host.typed(first, "kept")

            assertTrue(first.performKeyEquivalent(command(KEY_D, "d")), "⌘D splits")
            assertEquals(2, host.views.size)
            val right = host.views[1]
            assertSame(right, host.focusedView, "the new pane takes the focus")
            assertSame(right, window.firstResponder)
            // 400 points of room past the 1-point divider, cut in half.
            assertEquals(listOf(0.0, 0.0, 200.0, HEIGHT), first.frameValues())
            assertEquals(listOf(201.0, 0.0, 200.0, HEIGHT), right.frameValues())

            host.split(SplitAxis.Vertical)
            assertEquals(3, host.views.size)
            val below = host.focusedView
            assertEquals(listOf(201.0, 0.0, 200.0, 149.0), right.frameValues())
            assertEquals(listOf(201.0, 150.0, 200.0, 150.0), below.frameValues())
            host.typed(below, "below")

            assertTrue(below.performKeyEquivalent(command(KEY_RIGHT_BRACKET, "]")), "⌘] moves on")
            assertSame(first, host.focusedView, "the focus wraps round to the first pane")
            assertSame(first, window.firstResponder)

            assertTrue(first.performKeyEquivalent(command(KEY_W, "w")), "⌘W closes the focused pane")
            assertEquals(listOf(right, below), host.views)
            assertEquals(listOf(0.0, 0.0, WIDTH, 149.0), right.frameValues(), "the column takes the whole width")
            assertSame(right, host.focusedView, "the pane after the closed one takes the focus")

            // The survivors still run the programs they started with.
            assertEquals("below", below.screenText.lines().first())
            host.typed(right, "alive")
        } finally {
            host.stop()
        }
    }

    @Test
    fun aChildThatExitsClosesItsPaneAloneAndTheLastOneEmptiesTheTab() {
        val (_, host) = host()
        var emptied = 0
        host.onEmpty = { emptied++ }
        try {
            host.program = "/usr/bin/true"
            host.split(SplitAxis.Horizontal)
            awaitViews({ host.views }) { host.views.size == 1 }
            assertEquals(0, emptied, "a sibling's exit leaves the tab")
            host.typed(host.focusedView, "still")

            host.closePane(0)
            assertEquals(1, emptied, "closing the last pane empties the tab")
        } finally {
            host.stop()
        }
    }

    @Test
    fun theWindowClosesWithItsLastPaneAndPassesTabsAndWindowsOn() {
        val asked = ArrayList<Action>()
        var closed: TerminalWindow? = null
        val window =
            TerminalWindow(text, Settings.DEFAULT, "/bin/cat", onClosed = { closed = it }) { _, action ->
                asked += action
                true
            }
        window.start()
        try {
            val view = window.panes.focusedView
            assertTrue(view.performKeyEquivalent(command(KEY_T, "t")), "⌘T is the app's")
            assertEquals(listOf(Action.NewTab), asked)

            window.panes.program = "/usr/bin/true"
            window.panes.split(SplitAxis.Horizontal)
            awaitViews({ window.panes.views }) { window.panes.views.size == 1 }
            assertEquals(null, closed, "a pane's exit leaves the window open")

            assertTrue(view.performKeyEquivalent(command(KEY_W, "w")))
            assertSame(window, closed, "the last pane closes the window")
            assertEquals(emptyList<TerminalView>(), window.panes.views, "and ends its program")
        } finally {
            window.panes.stop()
            window.window.close()
        }
    }
}
