package vesper.term

import vesper.png.Png
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val ST = "$ESC\\"

/** A 2 x 6 red sixel. */
private const val RED_SIXEL = "${ESC}Pq#0;2;100;0;0#0~~$ST"

/** A 2 x 6 raster with only its left column painted; [p2] picks what the right one shows. */
private fun halfSixel(p2: Int) = "${ESC}P0;$p2;0q\"1;1;2;6#0;2;100;0;0#0~$ST"

private val RGBA_3X2 = ByteArray(3 * 2 * 4) { if (it % 4 == 3) -1 else 0x40 }
private val PNG = Png.encode(3, 2, RGBA_3X2)

/** kitty RGBA, 2 x 2 pixels. */
private val KITTY_2X2 = ByteArray(2 * 2 * 4) { if (it % 4 == 3) -1 else 0x10 }

private fun iterm(args: String) = "$ESC]1337;File=$args:${Base64.encode(PNG)}\u0007"

private fun kitty(keys: String) = "${ESC}_G$keys;${Base64.encode(KITTY_2X2)}$ST"

/** Each placement as `row,col colsxrows`, its row counted from the screen's top (negative in history). */
private fun Terminal.placed(): List<String> {
    val top = grid.screenTopLine
    return images.placements.values.map { "${it.line - top},${it.column} ${it.columns}x${it.rows}" }
}

private fun Terminal.at(): Pair<Int, Int> = cursor.row to cursor.col

/** The pixel at [x], [y] of the only image, as `0xRRGGBBAA`. */
private fun Terminal.pixel(
    x: Int,
    y: Int,
): Long {
    val p = images.placements.values.single()
    val pixels = assertNotNull(images[p.imageId]).pixels
    val at = (y * pixels.width + x) * 4
    return (0 until 4).fold(0L) { acc, i -> (acc shl 8) or (pixels.rgba[at + i].toLong() and 0xFF) }
}

class ImagesTest {
    @Test
    fun sixelIsPaddedToCellsAndLeavesTheCursorUnderItsLeftEdge() {
        val t = Terminal(10, 4, 10)
        t.feed("abc$RED_SIXEL")
        assertEquals(listOf("0,3 1x1"), t.placed())
        assertEquals(1 to 3, t.at())
        val image =
            assertNotNull(
                t.images[
                    t.images.placements.values
                        .single()
                        .imageId,
                ],
            ).pixels
        assertEquals(8 to 16, image.width to image.height)
        assertEquals(0xFF0000FF, t.pixel(1, 5))
        assertEquals(0L, t.pixel(2, 0), "padding is transparent")
        assertEquals(0L, t.pixel(0, 6))
    }

    @Test
    fun sixelAtTheBottomScrollsAndTheImageKeepsItsLine() {
        val t = Terminal(10, 3, 10)
        t.feed("\n\n$RED_SIXEL")
        assertEquals(listOf("1,0 1x1"), t.placed(), "the screen scrolled one line under the image")
        assertEquals(2 to 0, t.at())
    }

    @Test
    fun sixelBackgroundIsOpaqueUnlessP2SaysTransparent() {
        val t = Terminal(10, 4, 10)
        t.feed(halfSixel(0))
        assertEquals(0xFF0000FF to 0x000000FFL, t.pixel(0, 0) to t.pixel(1, 0))
        t.feed("$ESC[2J${halfSixel(1)}")
        assertEquals(0xFF0000FF to 0L, t.pixel(0, 0) to t.pixel(1, 0))
    }

    @Test
    fun aCancelledSixelOrAnotherDcsShowsNothing() {
        val t = Terminal(10, 4, 10)
        t.feed("${ESC}Pq#0;2;100;0;0#0~~\u0018")
        t.feed("${ESC}P1\$qm$ST")
        assertTrue(t.placed().isEmpty())
        assertEquals(0 to 0, t.at())
    }

    @Test
    fun anImageDrawnOverAnotherReplacesIt() {
        val t = Terminal(10, 4, 10)
        t.feed("$RED_SIXEL$ESC[H$RED_SIXEL")
        assertEquals(listOf("0,0 1x1"), t.placed())
        assertEquals(1, t.images.imageCount, "the covered image went too")
        // Not covered whole: both stay.
        t.feed("$ESC[1;2H$RED_SIXEL")
        assertEquals(2, t.placed().size)
    }

    @Test
    fun itermLeavesTheCursorRightOfTheImage() {
        val t = Terminal(10, 4, 10)
        t.feed("ab${iterm("inline=1")}")
        assertEquals(listOf("0,2 1x1"), t.placed())
        assertEquals(0 to 3, t.at())
        t.feed(iterm("inline=1;width=4;height=2;preserveAspectRatio=0"))
        assertEquals("0,3 4x2", t.placed().last())
        assertEquals(1 to 7, t.at())
    }

    @Test
    fun itermFitsTheImageInsideBothSizesAndTurnsPercentsAndPixelsIntoCells() {
        val t = Terminal(10, 8, 10)
        // 3 x 2 pixels into 32 x 64: 32 wide, so 21 high.
        t.feed(iterm("inline=1;width=4;height=4"))
        // Half of 10 columns of 8 pixels is 40 pixels, so 26 high.
        t.feed("$ESC[5;1H${iterm("inline=1;width=50%")}")
        t.feed("$ESC[8;1H${iterm("inline=1;width=17px;height=auto")}")
        assertEquals(listOf("0,0 4x2", "4,0 5x2", "7,0 3x1"), t.placed())
    }

    @Test
    fun itermDownloadsAndOtherOsc1337AreIgnored() {
        val t = Terminal(10, 4, 10)
        t.feed(iterm("inline=0"))
        t.feed("$ESC]1337;SetMark\u0007$ESC]1337;File=inline=1:!!\u0007")
        assertTrue(t.placed().isEmpty())
        assertEquals(0 to 0, t.at())
    }

    @Test
    fun theCellSizeTurnsPixelsIntoCellsAndRejectsNonsense() {
        val t = Terminal(10, 4, 10)
        assertFalse(t.setCellSize(0, 16))
        assertFalse(t.setCellSize(8, 5000))
        assertTrue(t.setCellSize(1, 2))
        t.feed(RED_SIXEL)
        assertEquals(listOf("0,0 2x3"), t.placed())
        assertEquals(3 to 0, t.at())
    }

    @Test
    fun kittyRepliesOkAndMovesTheCursorPastTheImage() {
        val t = Terminal(10, 4, 10)
        t.feed("ab${kitty("a=T,f=32,s=2,v=2,i=7")}")
        assertEquals("${ESC}_Gi=7;OK$ST", t.takeReplies().decodeToString())
        assertEquals(listOf("0,2 1x1"), t.placed())
        assertEquals(0 to 3, t.at())
        t.feed(kitty("a=T,f=32,s=2,v=2,i=8,c=3,r=2"))
        assertEquals("0,3 3x2", t.placed().last())
        assertEquals(1 to 6, t.at())
        t.takeReplies()
        t.feed(kitty("a=T,f=32,s=2,v=2,i=9,C=1,q=2"))
        assertEquals(1 to 6, t.at(), "C=1 keeps the cursor")
        assertEquals("", t.takeReplies().decodeToString(), "q=2 silences the reply")
    }

    @Test
    fun kittyErrorsAreRepliedAndPlaceNothing() {
        val t = Terminal(10, 4, 10)
        t.feed("${ESC}_Ga=T,f=32,s=3,v=2,i=1;${Base64.encode(KITTY_2X2)}$ST")
        assertTrue(t.takeReplies().decodeToString().startsWith("${ESC}_Gi=1;E"))
        assertTrue(t.placed().isEmpty())
        assertEquals(0 to 0, t.at())
        // A cancelled command runs nothing.
        t.feed("${ESC}_Ga=T,f=32,s=2,v=2,i=2;${Base64.encode(KITTY_2X2)}\u0018")
        assertEquals("", t.takeReplies().decodeToString())
        assertTrue(t.placed().isEmpty())
    }

    @Test
    fun kittyDeleteRemovesItsPlacements() {
        val t = Terminal(10, 4, 10)
        t.feed(kitty("a=T,f=32,s=2,v=2,i=1,q=2") + kitty("a=T,f=32,s=2,v=2,i=2,q=2"))
        assertEquals(2, t.placed().size)
        t.feed("${ESC}_Ga=d,d=I,i=1$ST")
        assertEquals(listOf("0,1 1x1"), t.placed())
    }

    @Test
    fun imagesKeepTheirLineWhileTheTextScrolls() {
        val t = Terminal(10, 3, 10)
        t.feed("${kitty("a=T,f=32,s=2,v=2,i=1,q=2")}\n\n\n\n")
        assertEquals(listOf("-2,0 1x1"), t.placed())
        val frame = Frame()
        t.updateFrame(frame)
        assertTrue(frame.placements.isEmpty(), "the image is above the viewport")
        t.scrollDisplay(2)
        t.updateFrame(frame)
        assertEquals(0, frame.placements.single().row)
    }

    @Test
    fun anImageEvictedFromHistoryIsDroppedButATallOneStaysWhileItsBottomIsKept() {
        val t = Terminal(10, 2, 1)
        t.feed(kitty("a=T,f=32,s=2,v=2,i=1,q=2,C=1") + kitty("a=T,f=32,s=2,v=2,i=2,q=2,C=1,r=3"))
        t.feed("\n\n\n")
        assertEquals(listOf("-2,0 6x3"), t.placed(), "the short one left with its line, the tall one's bottom is on screen")
        t.feed("\n\n")
        assertTrue(t.placed().isEmpty())
    }

    @Test
    fun erasingTheHistoryOrTheScreenDropsOnlyTheirImages() {
        val t = Terminal(10, 2, 10)
        t.feed("${RED_SIXEL}\n\n$RED_SIXEL")
        assertEquals(listOf("-3,0 1x1", "0,0 1x1"), t.placed())
        t.feed("$ESC[3J")
        assertEquals(listOf("0,0 1x1"), t.placed())
        t.feed("$ESC[A$RED_SIXEL\n\n$ESC[2J")
        assertEquals(listOf("-2,0 1x1"), t.placed(), "ED 2 keeps what scrolled into history")
        assertEquals(1, t.images.imageCount, "sixel images go with their placements")
    }

    @Test
    fun aReflowMovesImagesWithTheirCell() {
        val t = Terminal(10, 4, 10)
        // "abcdefghij" wraps into "kl"; the image sits on the "l".
        t.feed("abcdefghijkl$ESC[D${kitty("a=T,f=32,s=2,v=2,i=1,q=2,C=1")}")
        assertEquals(listOf("1,1 1x1"), t.placed())
        t.resize(5, 4)
        assertEquals(listOf("2,1 1x1"), t.placed())
        t.resize(10, 4)
        assertEquals(listOf("1,1 1x1"), t.placed())
    }

    @Test
    fun aTruncatingResizeDropsAnImageWhoseCellIsCut() {
        val t = Terminal(10, 4, 10)
        t.feed("$ESC[?1049h$ESC[1;8H${kitty("a=T,f=32,s=2,v=2,i=1,q=2,C=1")}$ESC[1;1H$RED_SIXEL")
        t.resize(5, 4)
        assertEquals(listOf("0,0 1x1"), t.placed())
    }

    @Test
    fun theAlternateScreenKeepsItsOwnImagesAndDropsThemOnTheWayOut() {
        val t = Terminal(10, 4, 10)
        t.feed(RED_SIXEL)
        t.feed("$ESC[?1049h")
        assertTrue(t.placed().isEmpty())
        t.feed(kitty("a=T,f=32,s=2,v=2,i=1,q=2"))
        assertEquals(1, t.placed().size)
        t.feed("$ESC[?1049l")
        assertEquals(listOf("0,0 1x1"), t.placed(), "the main screen's image is back")
        t.feed("$ESC[?47h")
        assertTrue(t.placed().isEmpty(), "the alternate screen's images went when it was left")
    }

    @Test
    fun aResetDropsEveryImage() {
        val t = Terminal(10, 4, 10)
        t.feed("$RED_SIXEL$ESC[?1049h$RED_SIXEL${ESC}c")
        assertTrue(t.placed().isEmpty())
        t.feed("$ESC[?1049h")
        assertTrue(t.placed().isEmpty())
    }

    @Test
    fun theFrameCarriesPlacementsLowestZFirstAndRepaintsTheRowsUnderThem() {
        val t = Terminal(10, 6, 10)
        val frame = Frame()
        t.updateFrame(frame)
        t.feed("$ESC[3;3H${kitty("a=T,f=32,s=2,v=2,i=1,q=2,C=1,c=2,r=2,z=5")}${kitty("a=T,f=32,s=2,v=2,i=2,q=2,C=1,z=-1")}")
        t.updateFrame(frame)
        val (below, above) = frame.placements
        assertEquals(listOf(-1, 5), listOf(below.z, above.z))
        assertEquals(listOf(2, 2, 2, 2), listOf(above.row, above.col, above.cols, above.rows))
        assertEquals(listOf(0, 0, 2, 2), listOf(above.sourceX, above.sourceY, above.sourceWidth, above.sourceHeight))
        assertSame(
            t.images[
                t.images.placements.values
                    .first()
                    .imageId,
            ],
            above.image,
        )
        assertEquals(listOf(2, 3), frame.damage.dirty, "the rows under the new images")
        t.updateFrame(frame)
        assertTrue(frame.damage.dirty.isEmpty())
        t.feed("${ESC}_Ga=d,d=A$ST")
        t.updateFrame(frame)
        assertTrue(frame.placements.isEmpty())
        assertEquals(listOf(2, 3), frame.damage.dirty, "the rows the images left")
    }
}
