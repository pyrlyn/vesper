package scull.image

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val SIDE = 1000
private const val PIXEL_CAP = 1L shl 22

private fun decode(
    sixel: String,
    transparent: Boolean = true,
    background: Int = 0,
    maxWidth: Int = SIDE,
    maxHeight: Int = SIDE,
    maxPixelBytes: Long = PIXEL_CAP,
): RgbaImage {
    val bytes = sixel.encodeToByteArray()
    return SixelDecoder(transparent, maxWidth, maxHeight, maxPixelBytes, background).also { it.put(bytes, 0, bytes.size) }.finish()
}

/** Every pixel as `0xRRGGBBAA`, row after row. */
private fun RgbaImage.pixels(): List<Long> =
    rgba.toList().chunked(RGBA).map { p -> p.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) } }

private const val NONE = 0L
private const val RED = 0xFF0000FFL
private const val GREEN = 0x00FF00FFL
private const val BLUE = 0x0000FFFFL

class SixelTest {
    @Test
    fun aSixelIsSixPixelsOfOneColumnTopBitFirst() {
        val image = decode("#1;2;100;0;0#1~")
        assertEquals(1 to 6, image.width to image.height)
        assertEquals(List(6) { RED }, image.pixels())
        // `A` is bit 1 alone: the row above it stays unset, and the image ends at the lowest set row.
        assertEquals(listOf(NONE, RED), decode("#1;2;100;0;0#1A").pixels())
    }

    @Test
    fun repeatsCarriageReturnsAndNewLines() {
        assertEquals(List(5) { RED }, decode("#1;2;100;0;0#1!5@").pixels())
        assertEquals(listOf(RED), decode("#1;2;100;0;0#1!0@").pixels(), "a count of 0 draws once")
        // `$` returns to overprint the same band; `-` moves six rows down.
        val image = decode("#1;2;100;0;0#2;2;0;100;0#1@@\$#2?@-#1@")
        assertEquals(2 to 7, image.width to image.height)
        assertEquals(listOf(RED, GREEN) + List(10) { NONE } + listOf(RED, NONE), image.pixels())
    }

    @Test
    fun coloursComeFromTheVt340MapHlsAndRgb() {
        // Register 2 of the VT340 map is red at 80, 13 and 13 percent.
        assertEquals(listOf(0xCC2121FFL), decode("#2@").pixels())
        assertEquals(listOf(0x000000FFL), decode("@").pixels(), "register 0 is black and current at first")
        // DEC hue: blue at 0, red at 120, green at 240.
        assertEquals(listOf(BLUE, RED, GREEN), decode("#1;1;0;50;100@#1;1;120;50;100@#1;1;240;50;100@").pixels())
        assertEquals(listOf(0x808080FFL), decode("#3;1;77;50;0#3@").pixels(), "no saturation is grey")
        // Out-of-range components are clamped, and register 256 does not exist.
        assertEquals(listOf(RED, RED), decode("#1;2;900;0;0#1@#256;2;0;100;0#256@").pixels())
    }

    @Test
    fun aRedefinedColourLeavesDrawnPixelsAlone() {
        assertEquals(listOf(RED, GREEN), decode("#1;2;100;0;0#1@#1;2;0;100;0@").pixels())
    }

    @Test
    fun rasterAttributesSizeTheImageAndTheBackground() {
        val image = decode("\"1;1;3;2#1;2;0;0;100#1@", transparent = false, background = 0x102030)
        assertEquals(3 to 2, image.width to image.height)
        assertEquals(listOf(BLUE) + List(5) { 0x102030FFL }, image.pixels())
        assertEquals(listOf(BLUE, NONE, NONE, NONE, NONE, NONE), decode("\"1;1;3;2#1;2;0;0;100#1@").pixels())
        // Sixels past the declared size still count; raster attributes after a sixel do not.
        assertEquals(4 to 1, decode("\"1;1;2;1!4@").let { it.width to it.height })
        assertEquals(1 to 1, decode("@\"1;1;9;9").let { it.width to it.height })
    }

    @Test
    fun bytesOutsideTheCommandsAreIgnored() {
        assertEquals(decode("#1;2;100;0;0#1@@").pixels(), decode(" #1;2;100;0;0\n#1@\u0001%@").pixels())
    }

    @Test
    fun capsStopTheImageBeforeItGrowsPastThem() {
        assertFailsWith<ImageError.TooLarge> { decode("!11@", maxWidth = 10) }
        assertFailsWith<ImageError.TooLarge> { decode("-@", maxHeight = 6) }
        assertEquals(1, decode("@-?", maxHeight = 6).height, "a band with nothing set costs nothing")
        assertFailsWith<ImageError.TooLarge> { decode("\"1;1;11;1@", maxWidth = 10) }
        assertFailsWith<ImageError.TooLarge> { decode("!100~", maxPixelBytes = 100L * 5 * RGBA) }
        assertEquals(100 to 6, decode("!100~", maxPixelBytes = 100L * 6 * RGBA).let { it.width to it.height })
        // A repeat count past any image saturates instead of overflowing.
        assertFailsWith<ImageError.TooLarge> { decode("!99999999999999999999@") }
    }

    @Test
    fun anImageWithNothingDrawnIsRefused() {
        assertFailsWith<ImageError.Decode> { decode("") }
        assertFailsWith<ImageError.Decode> { decode("#1;2;0;0;0") }
        assertEquals(2 to 1, decode("\"1;1;2;1").let { it.width to it.height }, "raster attributes alone give a size")
    }

    @Test
    fun cuttingTheStreamNeverChangesTheImage() {
        val rng = Random(6)
        val alphabet = "0123456789;!\"#\$-?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[]^_`abc~ \n".encodeToByteArray()
        repeat(3000) { fuzzSixel(ByteArray(rng.nextInt(80)) { alphabet[rng.nextInt(alphabet.size)] }) }
        repeat(500) { fuzzSixel(rng.nextBytes(rng.nextInt(80))) }
    }
}
