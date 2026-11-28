package vesper.windows

import vesper.host.Cells
import vesper.host.PADDING
import vesper.host.Palette
import vesper.host.RowSources
import vesper.host.viewSize
import vesper.term.Frame
import vesper.term.Terminal
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val ESC = "\u001b"
private const val SCALE = 2.0
private const val RGB = 0xFFFFFF

/** Feeds a terminal, renders its frame with Direct3D on WARP into an offscreen texture and reads it back. */
class RendererTest {
    private val gpu = Gpu.create(warp = true)
    private val fonts = FontSystem.create()
    private val text = assertNotNull(TextStyle.create(fonts))
    private val palette = Palette()
    private val renderer = D3DRenderer.create(gpu, text, palette)

    @AfterTest
    fun close() {
        renderer.close()
        text.close()
        fonts.close()
        gpu.close()
    }

    private inner class Screen(
        cols: Int,
        rows: Int,
    ) {
        val terminal = Terminal(cols, rows, 100)
        val frame = Frame()
        val sources = RowSources()
        val target: OffscreenTarget

        init {
            val (width, height) = viewSize(Cells(cols, rows), text.cell)
            target = OffscreenTarget.create(gpu, (width * SCALE).toInt(), (height * SCALE).toInt())
        }

        fun render(
            output: String,
            focused: Boolean = true,
        ): IntArray {
            terminal.feed(output.encodeToByteArray())
            terminal.updateFrame(frame)
            sources.absorb(frame.damage, frame.rows)
            renderer.render(frame, sources, target.view, target.width, target.height, SCALE, focused)
            return target.read()
        }

        /** The colour at ([dx], [dy]) across cell ([col], [row]), 0xRRGGBB. */
        fun IntArray.cell(
            col: Int,
            row: Int,
            dx: Double = 0.5,
            dy: Double = 0.5,
        ): Int {
            val x = ((PADDING + (col + dx) * text.cell.width) * SCALE).toInt()
            val y = ((PADDING + (row + dy) * text.cell.height) * SCALE).toInt()
            return this[y * target.width + x] and RGB
        }

        /** Every pixel of cell ([col], [row]). */
        fun IntArray.cellPixels(
            col: Int,
            row: Int,
        ): List<Int> {
            val left = ((PADDING + col * text.cell.width) * SCALE).toInt()
            val top = ((PADDING + row * text.cell.height) * SCALE).toInt()
            val w = (text.cell.width * SCALE).toInt()
            val h = (text.cell.height * SCALE).toInt()
            return (top until top + h).flatMap { y -> (left until left + w).map { x -> this[y * target.width + x] and RGB } }
        }
    }

    @Test
    fun panesDrawIntoTheirOwnViewportsOfOneTarget() {
        val (w, h) = viewSize(Cells(4, 2), text.cell)
        val width = (w * SCALE).toInt()
        val height = (h * SCALE).toInt()
        val gap = 2
        val left = renderer.paneQuads()
        val right = renderer.paneQuads()
        OffscreenTarget.create(gpu, width * 2 + gap, height).use { target ->
            try {
                fun pane(
                    quads: D3DRenderer.PaneQuads,
                    x: Int,
                    output: String,
                    focused: Boolean,
                ): PaneDraw {
                    val terminal = Terminal(4, 2, 100)
                    val frame = Frame()
                    val sources = RowSources()
                    terminal.feed(output.encodeToByteArray())
                    terminal.updateFrame(frame)
                    sources.absorb(frame.damage, frame.rows)
                    return PaneDraw(quads, frame, sources, x, 0, width, height, focused)
                }
                val draws =
                    listOf(
                        pane(left, 0, "$ESC[48;2;200;0;0m $ESC[0m", focused = false),
                        pane(right, width + gap, "$ESC[48;2;0;0;200m $ESC[0m", focused = true),
                    )
                renderer.render(target.view, target.width, target.height, SCALE, draws)
                val pixels = target.read()

                fun at(
                    x0: Int,
                    col: Int,
                ): Int {
                    val x = x0 + ((PADDING + (col + 0.5) * text.cell.width) * SCALE).toInt()
                    val y = ((PADDING + 0.5 * text.cell.height) * SCALE).toInt()
                    return pixels[y * target.width + x] and RGB
                }
                assertEquals(0xC80000, at(0, 0))
                assertEquals(0x0000C8, at(width + gap, 0), "the right pane starts at its own corner")
                assertEquals(palette.cursor, at(width + gap, 1), "the focused pane's cursor is a block")
                assertEquals(palette.background, at(0, 1), "the other pane's is an outline")
                assertEquals(palette.background, pixels[height / 2 * target.width + width] and RGB, "the divider")
            } finally {
                left.close()
                right.close()
            }
        }
    }

    @Test
    fun backgroundsAndTheCursorLandOnTheirCells() {
        with(Screen(10, 3)) {
            target.use {
                val pixels = render("$ESC[48;2;200;0;0m  $ESC[0m \r\n x")
                assertEquals(0xC80000, pixels.cell(0, 0))
                assertEquals(0xC80000, pixels.cell(1, 0, dx = 0.95))
                assertEquals(palette.background, pixels.cell(2, 0))
                // The focused cursor is a block after the x.
                assertEquals(palette.cursor, pixels.cell(2, 1))
                assertEquals(palette.background, pixels.cell(9, 2))
                assertEquals(palette.background, pixels[target.width + 1] and RGB, "the padding")
            }
        }
    }

    @Test
    fun glyphsLeaveInkInTheirColour() {
        with(Screen(6, 2)) {
            target.use {
                val pixels = render("$ESC[38;2;0;220;0mM$ESC[0m $ESC[38;2;0;0;220m中$ESC[0m")
                val green = pixels.cellPixels(0, 0)
                assertTrue(green.any { it shr 8 and 0xFF > 180 && it shr 16 and 0xFF < 60 }, "the M left no green ink")
                assertTrue(green.all { it and 0xFF <= (palette.background and 0xFF) + 1 }, "the M has no blue")
                // A character Consolas lacks comes from the fallback font, two cells wide.
                val han = pixels.cellPixels(2, 0) + pixels.cellPixels(3, 0)
                assertTrue(han.any { it and 0xFF > 180 }, "the 中 left no blue ink")
                assertEquals(palette.background, pixels.cell(5, 0, dx = 0.9))
            }
        }
    }

    @Test
    fun theGlyphUnderAFocusedCursorTakesTheBackgroundColour() {
        with(Screen(4, 2)) {
            target.use {
                val pixels = render("M\b")
                val cell = pixels.cellPixels(0, 0)
                assertTrue(cell.any { it == palette.cursor }, "the block")
                assertTrue(cell.any { it == palette.background }, "the M cut out of it")
            }
        }
    }

    @Test
    fun anUnfocusedCursorIsAnOutline() {
        with(Screen(4, 2)) {
            target.use {
                val pixels = render("", focused = false)
                assertEquals(palette.background, pixels.cell(0, 0))
                assertEquals(palette.cursor, pixels.cell(0, 0, dx = 0.02))
            }
        }
    }

    @Test
    fun aScrollMovesBuiltRowsAndRepaintsTheNewOne() {
        with(Screen(4, 3)) {
            target.use {
                render("\n\n$ESC[48;2;200;0;0m $ESC[0m")
                val scrolled = render("\r\n$ESC[48;2;0;0;200m $ESC[0m")
                assertEquals(0xC80000, scrolled.cell(0, 1))
                assertEquals(0x0000C8, scrolled.cell(0, 2))
                assertEquals(palette.background, scrolled.cell(0, 0))
            }
        }
    }
}
