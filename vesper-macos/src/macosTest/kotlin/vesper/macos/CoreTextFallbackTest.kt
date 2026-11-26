package vesper.macos

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.convert
import platform.AppKit.NSApplication
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextTranslateCTM
import platform.Foundation.NSDate
import platform.Foundation.NSMakeRect
import platform.Foundation.NSRunLoop
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.Foundation.writeToFile
import platform.posix.chmod
import platform.posix.getpid
import platform.posix.unlink
import vesper.host.Bitmap
import vesper.host.Cells
import vesper.host.PADDING
import vesper.host.viewSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val SCALE = 2.0
private const val BLUE = 0x285AA0
private const val GREY = 0x3C3C3C

/** The view's CoreText drawing, the one a Mac without a GPU sees, rendered into a bitmap. */
@OptIn(BetaInteropApi::class)
class CoreTextFallbackTest {
    /** The T28 check on the fallback: a Powerline separator meets both segments without a seam. */
    @Test
    fun aSeparatorIsDrawnFromTheCellAndMeetsItsSegments() {
        NSApplication.sharedApplication
        val text = TextStyle()
        val (width, height) = viewSize(Cells(12, 3), text.cell)
        val view = TerminalView(text, NSMakeRect(0.0, 0.0, width, height))
        // A script rather than typing into a shell, whose line editor may redraw what was typed ahead.
        // Blue " ab ", a solid separator in blue on grey (UTF-8 of U+E0B0), then grey " cd ".
        val prompt = "\\033[48;2;40;90;160m ab \\033[38;2;40;90;160;48;2;60;60;60m\\356\\202\\260\\033[0;48;2;60;60;60m cd \\033[0m"
        val script = NSTemporaryDirectory() + "vesper-powerline-${getpid()}.sh"
        assertTrue(
            NSString
                .create(
                    string = "#!/bin/sh\nprintf '$prompt\\n'\nexec sleep 30\n",
                ).writeToFile(script, true, NSUTF8StringEncoding, null),
        )
        chmod(script, 0x1ED.convert())
        view.start(SCALE, program = script)
        try {
            val end = TimeSource.Monotonic.markNow() + 10.seconds
            var row = -1
            while (row < 0) {
                if (end.hasPassedNow()) fail("no output; the screen shows:\n${view.screenText}")
                NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.05))
                view.refresh()
                row = view.screenText.lines().indexOfFirst { it.startsWith(" ab ") }
            }
            val bitmap =
                assertNotNull(
                    Bitmap.draw((width * SCALE).toInt(), (height * SCALE).toInt(), 0, 0, true) { context ->
                        // Top-down in points, as AppKit hands the flipped view its context.
                        CGContextTranslateCTM(context, 0.0, height * SCALE)
                        CGContextScaleCTM(context, SCALE, -SCALE)
                        view.drawCells(assertNotNull(context))
                    },
                )

            fun color(
                x: Double,
                y: Double,
            ) = bitmap.pixel((x * SCALE).toInt(), (y * SCALE).toInt()) and 0xFFFFFF
            val w = text.cell.width
            val top = PADDING + row * text.cell.height
            val separator = PADDING + 4 * w
            // Down the middle half of the row: the segment's last pixel and the separator's first are
            // the same blue, where a font's glyph would leave a sliver of the grey behind it.
            var y = top + text.cell.height / 4
            while (y < top + text.cell.height * 3 / 4) {
                assertEquals(BLUE, color(separator - 0.25, y), "segment at y $y")
                assertEquals(BLUE, color(separator + 0.25, y), "separator at y $y")
                y += 0.5
            }
            // The separator's corners away from its point show the next segment's grey.
            assertEquals(GREY, color(separator + w - 0.25, top + 0.25))
            assertEquals(GREY, color(separator + w - 0.25, top + text.cell.height - 0.25))
            assertEquals(GREY, color(separator + w + 0.25, top + text.cell.height / 2))
            savePng(bitmap, "macos-coretext-powerline.png")
        } finally {
            view.stop()
            unlink(script)
        }
    }
}
