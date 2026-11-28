package vesper.macos

import platform.AppKit.NSApplication
import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSEventModifierFlagCommand
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowStyleMaskBorderless
import platform.Foundation.NSMakeRect
import vesper.host.Cells
import vesper.host.viewSize
import kotlin.test.Test
import kotlin.test.assertTrue

private const val KEY_UP: UShort = 0x7Eu
private const val KEY_DOWN: UShort = 0x7Du
private const val KEY_RETURN: UShort = 0x24u

class PromptJumpTest {
    @Test
    fun commandUpAndDownWalkTheShellsPrompts() {
        NSApplication.sharedApplication
        val text = TextStyle()
        val (width, height) = viewSize(Cells(20, 4), text.cell)
        val window = NSWindow(NSMakeRect(0.0, 0.0, width, height), NSWindowStyleMaskBorderless, NSBackingStoreBuffered, false)
        val view = TerminalView(text, NSMakeRect(0.0, 0.0, width, height))
        window.contentView = view
        window.makeFirstResponder(view)
        view.start(2.0, program = "/bin/sh")
        try {
            // The shell echoes the escapes as typed text; only printf's output carries the marks.
            val mark = "printf '\\033]133;A\\007%s\\n'"
            // Committed text carries no line break, so Return is a key of its own.
            view.insertText("$mark one; seq 8; $mark two; seq 8; echo done-$((6*7))", NO_RANGE)
            view.keyDown(keyEvent(KEY_RETURN, "\r"))
            awaitViews({ listOf(view) }) { view.screenText.lines().any { it == "done-42" } }

            assertTrue(view.performKeyEquivalent(keyEvent(KEY_UP, "\uF700", NSEventModifierFlagCommand)))
            awaitViews({ listOf(view) }) { view.screenText.startsWith("two") }
            assertTrue(view.performKeyEquivalent(keyEvent(KEY_UP, "\uF700", NSEventModifierFlagCommand)))
            awaitViews({ listOf(view) }) { view.screenText.startsWith("one") }
            assertTrue(view.performKeyEquivalent(keyEvent(KEY_DOWN, "\uF701", NSEventModifierFlagCommand)))
            awaitViews({ listOf(view) }) { view.screenText.startsWith("two") }
        } finally {
            view.stop()
        }
    }
}
