package vesper.macos

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.AppKit.NSBitmapImageFileType
import platform.AppKit.NSBitmapImageRep
import platform.AppKit.representationUsingType
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import platform.CoreGraphics.kCGColorSpaceSRGB
import platform.Foundation.writeToFile
import platform.posix.getenv
import vesper.term.Frame
import vesper.term.Terminal
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

private const val ESC = "\u001b"
private const val SCALE = 2.0
private const val RGB = 0xFFFFFF

/** Writes [bitmap] as a PNG into the reports directory, when the build gives one. */
internal fun savePng(
    bitmap: Bitmap,
    name: String,
) {
    val dir = getenv("VESPER_REPORTS_DIR")?.toKString() ?: return
    val space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB)
    val image =
        bitmap.pixels.usePinned {
            val info = CGImageAlphaInfo.kCGImageAlphaPremultipliedFirst.value or kCGBitmapByteOrder32Little
            val context =
                CGBitmapContextCreate(
                    it.addressOf(0),
                    bitmap.width.toULong(),
                    bitmap.height.toULong(),
                    8u,
                    (bitmap.width * 4).toULong(),
                    space,
                    info,
                )
            CGBitmapContextCreateImage(context)
        } ?: fail("no image")
    val png =
        NSBitmapImageRep(image).representationUsingType(NSBitmapImageFileType.NSBitmapImageFileTypePNG, emptyMap<Any?, Any?>())
            ?: fail("no PNG")
    val path = "$dir/$name"
    if (!png.writeToFile(path, true)) fail("could not write $path")
    println("$name: ${bitmap.width}x${bitmap.height} pixels in $path")
}

/**
 * Feeds a terminal, renders its frame offscreen with Metal and reads pixels back. A runner without a
 * GPU (a VM) has no device: the tests skip there, as the app falls back to CoreText.
 */
class MetalRendererTest {
    private val text = TextStyle()
    private val palette = Palette()

    private class Fixture(
        val renderer: MetalRenderer,
        val terminal: Terminal,
        val frame: Frame = Frame(),
        val sources: RowSources = RowSources(),
    )

    private fun fixture(
        cols: Int,
        rows: Int,
    ): Fixture? {
        val renderer = MetalRenderer.create(text, palette) ?: return null.also { println("no Metal device: skipped") }
        return Fixture(renderer, Terminal(cols, rows, 100))
    }

    private fun Fixture.render(
        output: String,
        focused: Boolean = true,
    ): Bitmap {
        terminal.feed(output.encodeToByteArray())
        terminal.updateFrame(frame)
        sources.absorb(frame.damage, frame.rows)
        val (width, height) = viewSize(Cells(frame.cols, frame.rows), text.cell)
        return assertNotNull(renderer.render(frame, sources, width, height, SCALE, focused))
    }

    /** The colour at the middle of cell ([col], [row]). */
    private fun Bitmap.cell(
        col: Int,
        row: Int,
        dx: Double = 0.5,
        dy: Double = 0.5,
    ): Int {
        val x = ((PADDING + (col + dx) * text.cell.width) * SCALE).toInt()
        val y = ((PADDING + (row + dy) * text.cell.height) * SCALE).toInt()
        return pixel(x, y) and RGB
    }

    @Test
    fun backgroundsBlocksAndTheCursorLandOnTheirCells() {
        val f = fixture(10, 3) ?: return
        val bitmap = f.render("$ESC[48;2;200;0;0m  $ESC[0m $ESC[38;2;0;200;0m█$ESC[0m\r\n x")
        assertEquals(0xC80000, bitmap.cell(0, 0))
        assertEquals(0xC80000, bitmap.cell(1, 0))
        assertEquals(palette.background, bitmap.cell(2, 0))
        // A block element is a sprite filling the whole cell, edge to edge.
        assertEquals(0x00C800, bitmap.cell(3, 0))
        assertEquals(0x00C800, bitmap.cell(3, 0, dx = 0.05, dy = 0.05))
        assertEquals(0x00C800, bitmap.cell(3, 0, dx = 0.95, dy = 0.95))
        // The focused cursor is a block after the x.
        assertEquals(palette.cursor, bitmap.cell(2, 1))
        assertEquals(palette.background, bitmap.cell(9, 2))
        assertEquals(palette.background, bitmap.pixel(1, 1) and RGB)
    }

    @Test
    fun anUnfocusedCursorIsAnOutline() {
        val f = fixture(4, 2) ?: return
        val bitmap = f.render("", focused = false)
        assertEquals(palette.background, bitmap.cell(0, 0))
        assertEquals(palette.cursor, bitmap.cell(0, 0, dx = 0.02))
    }

    @Test
    fun aScrollMovesBuiltRowsAndRepaintsTheNewOne() {
        val f = fixture(4, 3) ?: return
        f.render("\n\n$ESC[48;2;200;0;0m $ESC[0m")
        val scrolled = f.render("\r\n$ESC[48;2;0;0;200m $ESC[0m")
        assertEquals(0xC80000, scrolled.cell(0, 1))
        assertEquals(0x0000C8, scrolled.cell(0, 2))
        assertEquals(palette.background, scrolled.cell(0, 0))
    }

    @Test
    fun aPreeditIsUnderlinedAtTheBottomOfItsRow() {
        val f = fixture(6, 2) ?: return
        f.frame.setPreedit("ab")
        val bitmap = f.render("")
        // The row's last pixel row lies inside the underline and below any glyph's ink.
        val bottom = ((PADDING + text.cell.height) * SCALE).toInt() - 1
        val x = ((PADDING + 0.5 * text.cell.width) * SCALE).toInt()
        assertEquals(palette.foreground, bitmap.pixel(x, bottom) and RGB)
        assertEquals(palette.background, bitmap.cell(4, 0, dy = 0.98), "past the preedit there is no line")
        assertEquals(palette.cursor, bitmap.cell(2, 0), "the cursor waits after the preedit")
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun anImageCoversItsCells() {
        val f = fixture(6, 3) ?: return
        val blue = ByteArray(16) { if (it % 4 == 2 || it % 4 == 3) -1 else 0 }
        val bitmap = f.render("${ESC}_Ga=T,f=32,s=2,v=2,c=2,r=1;${Base64.encode(blue)}$ESC\\")
        assertEquals(0x0000FF, bitmap.cell(0, 0))
        assertEquals(0x0000FF, bitmap.cell(1, 0))
        assertEquals(palette.background, bitmap.cell(3, 1))
    }

    /** The T15 check's snapshot: text, colours, wide characters, emoji, box drawing and the cursor. */
    @Test
    fun snapshot() {
        val f = fixture(50, 8) ?: return
        val output =
            listOf(
                "$ESC[1mvesper$ESC[0m renders with $ESC[38;2;120;180;255mMetal$ESC[0m",
                "$ESC[31mred $ESC[32mgreen $ESC[34mblue $ESC[7minverse$ESC[0m $ESC[4munder$ESC[0m $ESC[4:3mcurl$ESC[0m $ESC[9mstrike$ESC[0m",
                "wide: 中文字 emoji: 😀🚀 ligature: -> != ==",
                "┌──────┬──────┐ ▁▂▃▄▅▆▇█ ░▒▓",
                "│ box  │ ╭─╮  │ ▌▐▀▄",
                "└──────┴─╰─╯──┘",
                "$ESC[3mitalic$ESC[0m $ESC[1;3mbold italic$ESC[0m $ESC[2mdim$ESC[0m",
                "$ ",
            ).joinToString("\r\n")
        val bitmap = f.render(output)
        // The bold v's stems cover its cell's middle row somewhere; a missing glyph would leave it blank.
        val glyphRow = ((PADDING + 0.6 * text.cell.height) * SCALE).toInt()
        val left = (PADDING * SCALE).toInt()
        val inked = (left until left + (text.cell.width * SCALE).toInt()).any { bitmap.pixel(it, glyphRow) and RGB != palette.background }
        assertEquals(true, inked, "the v left no ink")
        assertEquals(palette.cursor, bitmap.cell(2, 7))
        savePng(bitmap, "macos-renderer.png")
    }

    /** The T27 check: a Powerline prompt's icons draw from the bundled symbols, with no Nerd Font installed. */
    @Test
    fun powerlinePromptSnapshot() {
        val f = fixture(40, 2) ?: return
        val blue = "$ESC[38;2;255;255;255;48;2;40;90;160m"
        val grey = "$ESC[38;2;255;255;255;48;2;60;60;60m"
        val green = "$ESC[38;2;0;0;0;48;2;80;140;60m"
        // apple, folder and branch icons between solid separators; each icon is the second cell of its segment.
        val prompt =
            "$blue  user $ESC[38;2;40;90;160;48;2;60;60;60m" +
                "$grey  ~/src $ESC[38;2;60;60;60;48;2;80;140;60m" +
                "$green  main $ESC[0;38;2;80;140;60m$ESC[0m "
        val bitmap = f.render(prompt)
        val icons = mapOf(1 to 0x285AA0, 10 to 0x3C3C3C, 20 to 0x508C3C)
        for ((col, background) in icons) {
            assertEquals(background, bitmap.cell(col, 0, dx = 0.05, dy = 0.05), "col $col")
            val x0 = ((PADDING + col * text.cell.width) * SCALE).toInt()
            val y0 = (PADDING * SCALE).toInt()
            val inked =
                (x0 until x0 + (text.cell.width * SCALE).toInt()).sumOf { x ->
                    (y0 until y0 + (text.cell.height * SCALE).toInt()).count { y -> bitmap.pixel(x, y) and RGB != background }
                }
            // A missing glyph leaves the cell blank; a box of a fallback font would be inked too, so the
            // shaping test pins the font.
            assertTrue(inked > 20, "col $col left $inked inked pixels")
        }
        // The separator after "user" is a sprite: the blue reaches the cell's left edge on the grey.
        assertEquals(0x285AA0, bitmap.cell(8, 0, dx = 0.05))
        assertEquals(0x3C3C3C, bitmap.cell(8, 0, dx = 0.95, dy = 0.05))
        savePng(bitmap, "macos-powerline.png")
    }
}
