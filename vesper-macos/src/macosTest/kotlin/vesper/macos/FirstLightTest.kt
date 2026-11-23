package vesper.macos

import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import platform.AppKit.NSApplication
import platform.AppKit.NSBitmapImageFileType
import platform.AppKit.NSEvent
import platform.AppKit.NSEventTypeKeyDown
import platform.AppKit.representationUsingType
import platform.Foundation.NSDate
import platform.Foundation.NSMakePoint
import platform.Foundation.NSMakeRect
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.Foundation.writeToFile
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val RETURN = 0x24

// US-layout key codes of the characters the test types (kVK_ANSI_*).
private val KEY_CODES =
    mapOf(
        'e' to 0x0E,
        'c' to 0x08,
        'h' to 0x04,
        'o' to 0x1F,
        ' ' to 0x31,
        'v' to 0x09,
        's' to 0x01,
        'p' to 0x23,
        'r' to 0x0F,
        ';' to 0x29,
        'l' to 0x25,
        '/' to 0x2C,
    )

/**
 * The whole path in one view, offscreen: synthetic key events through the key map and the
 * session to a shell, its output back through the frame, and the renderer drawing the result to a PNG
 * (the T14 check's screenshot, under build/reports/first-light).
 */
class FirstLightTest {
    @Test
    fun typedCommandsRunAndTheirOutputIsDrawn() {
        NSApplication.sharedApplication
        val text = TextStyle()
        val (width, height) = viewSize(Cells(80, 24), text.cell)
        val view = TerminalView(text, NSMakeRect(0.0, 0.0, width, height))
        // sh rather than the login shell: a fresh CI account's zsh may stop at its setup wizard.
        view.start(2.0, program = "/bin/sh")
        try {
            for (char in "echo vesper; ls /") key(view, KEY_CODES.getValue(char), char.toString())
            key(view, RETURN, "\r")
            val end = TimeSource.Monotonic.markNow() + 10.seconds
            while (view.screenText.lines().none { it == "vesper" }) {
                if (end.hasPassedNow()) fail("no output; the screen shows:\n${view.screenText}")
                NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.05))
                view.refresh()
            }
            savePng(view)
        } finally {
            view.stop()
        }
    }

    private fun key(
        view: TerminalView,
        keyCode: Int,
        characters: String,
    ) {
        val event =
            NSEvent.keyEventWithType(
                NSEventTypeKeyDown,
                NSMakePoint(0.0, 0.0),
                0u,
                0.0,
                0,
                null,
                characters,
                characters,
                false,
                keyCode.toUShort(),
            ) ?: fail("no key event for $characters")
        view.keyDown(event)
    }

    private fun savePng(view: TerminalView) {
        // With a GPU the picture is what Metal draws; without one, the CoreText fallback.
        view.snapshot(2.0)?.let { return savePng(it, "first-light.png") }
        val bounds = view.bounds
        val image = view.bitmapImageRepForCachingDisplayInRect(bounds) ?: fail("no bitmap")
        view.cacheDisplayInRect(bounds, image)
        val png = image.representationUsingType(NSBitmapImageFileType.NSBitmapImageFileTypePNG, emptyMap<Any?, Any?>()) ?: fail("no PNG")
        val dir = getenv("VESPER_REPORTS_DIR")?.toKString() ?: return
        val path = "$dir/first-light.png"
        if (!png.writeToFile(path, true)) fail("could not write $path")
        val size = bounds.useContents { "${size.width.toInt()}x${size.height.toInt()}" }
        println("first light: $size points in $path")
    }
}
