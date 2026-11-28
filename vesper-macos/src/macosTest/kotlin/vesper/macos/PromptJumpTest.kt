package vesper.macos

import platform.AppKit.NSApplication
import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSEvent
import platform.AppKit.NSEventModifierFlagCommand
import platform.AppKit.NSEventTypeKeyDown
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowStyleMaskBorderless
import platform.Foundation.NSDate
import platform.Foundation.NSMakePoint
import platform.Foundation.NSMakeRect
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import vesper.host.Cells
import vesper.host.viewSize
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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
            view.keyDown(key(KEY_RETURN, "\r", 0u))
            view.await { it.lines().any { line -> line == "done-42" } }

            assertTrue(view.performKeyEquivalent(key(KEY_UP, "\uF700")))
            view.await { it.startsWith("two") }
            assertTrue(view.performKeyEquivalent(key(KEY_UP, "\uF700")))
            view.await { it.startsWith("one") }
            assertTrue(view.performKeyEquivalent(key(KEY_DOWN, "\uF701")))
            view.await { it.startsWith("two") }
        } finally {
            view.stop()
        }
    }

    private fun TerminalView.await(done: (String) -> Boolean) {
        val end = TimeSource.Monotonic.markNow() + 10.seconds
        while (!done(screenText)) {
            if (end.hasPassedNow()) fail("timed out; the screen shows:\n$screenText")
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.05))
            refresh()
        }
    }

    private fun key(
        keyCode: UShort,
        characters: String,
        flags: ULong = NSEventModifierFlagCommand,
    ): NSEvent =
        NSEvent.keyEventWithType(
            NSEventTypeKeyDown,
            NSMakePoint(0.0, 0.0),
            flags,
            0.0,
            0,
            null,
            characters,
            characters,
            false,
            keyCode,
        )!!
}
