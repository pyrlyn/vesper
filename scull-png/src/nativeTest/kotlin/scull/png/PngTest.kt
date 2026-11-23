package scull.png

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val MIB = 1L shl 20

/** Adam7 as the PNG spec lays it out: first column and row, then the steps. */
private val PASSES =
    listOf(
        intArrayOf(0, 0, 8, 8),
        intArrayOf(4, 0, 8, 8),
        intArrayOf(0, 4, 4, 8),
        intArrayOf(2, 0, 4, 4),
        intArrayOf(0, 2, 2, 4),
        intArrayOf(1, 0, 2, 2),
        intArrayOf(0, 1, 1, 2),
    )

/** Packs [samples] at [depth] bits, most significant first, as a scanline holds them. */
private fun pack(
    samples: List<Int>,
    depth: Int,
): ByteArray {
    val out = ByteArray((samples.size * depth + 7) / 8)
    samples.forEachIndexed { i, s ->
        when (depth) {
            16 -> {
                out[2 * i] = (s shr 8).toByte()
                out[2 * i + 1] = s.toByte()
            }

            8 -> {
                out[i] = s.toByte()
            }

            else -> {
                val bit = i * depth
                out[bit / 8] = (out[bit / 8].toInt() or (s shl (8 - depth - bit % 8))).toByte()
            }
        }
    }
    return out
}

/** Filters scanline [raw] with [type] against the unfiltered one above, [prev]; the filter byte first. */
private fun filter(
    type: Int,
    raw: ByteArray,
    prev: ByteArray,
    bpp: Int,
): ByteArray {
    fun at(
        a: ByteArray,
        i: Int,
    ) = if (i < 0) 0 else a[i].toInt() and 0xFF
    val out = ByteArray(raw.size + 1)
    out[0] = type.toByte()
    for (i in raw.indices) {
        val left = at(raw, i - bpp)
        val up = at(prev, i)
        val predicted =
            when (type) {
                0 -> 0
                1 -> left
                2 -> up
                3 -> (left + up) / 2
                else -> paeth(left, up, at(prev, i - bpp))
            }
        out[i + 1] = (raw[i] - predicted).toByte()
    }
    return out
}

/**
 * A PNG of [width] by [height] pixels of [color] at [depth] holding [samples] (every channel of
 * every pixel, row after row), each scanline filtered by [filterOf] of its index, with [extra]
 * chunks before the image data.
 */
private fun build(
    width: Int,
    height: Int,
    depth: Int,
    color: Int,
    samples: List<Int>,
    interlaced: Boolean = false,
    extra: List<Pair<String, ByteArray>> = emptyList(),
    filterOf: (Int) -> Int = { 0 },
): ByteArray {
    val header = PngHeader(width, height, depth, color, interlaced)
    val channels = header.channels
    val raw = ArrayList<Byte>()
    var line = 0
    for (p in if (interlaced) PASSES else listOf(intArrayOf(0, 0, 1, 1))) {
        val xs = (p[0] until width step p[2]).toList()
        val ys = (p[1] until height step p[3]).toList()
        if (xs.isEmpty()) continue
        var prev = ByteArray(header.rowBytes(xs.size).toInt())
        for (y in ys) {
            val row = pack(xs.flatMap { x -> (0 until channels).map { samples[(y * width + x) * channels + it] } }, depth)
            raw += filter(filterOf(line++), row, prev, header.filterStride).toList()
            prev = row
        }
    }
    return pngFile(ihdr(width, height, depth, color, interlaced), extra + listOf("IDAT" to deflate(raw.toByteArray())))
}

private fun decode(png: ByteArray) = Png.decode(png, 64 * MIB)

/** The decoded pixels as `r g b a` quadruples. */
private fun pixels(png: ByteArray): List<List<Int>> = decode(png).rgba.map { it.toInt() and 0xFF }.chunked(RGBA)

private fun grey(
    v: Int,
    a: Int = 255,
) = listOf(v, v, v, a)

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

/** [png] with the byte at [at] flipped. */
private fun ByteArray.flip(at: Int) = copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }

/** Asserts that [png] is refused as malformed, not as too large. */
private fun refused(png: ByteArray) {
    val e = assertFailsWith<PngException> { decode(png) }
    assertFalse(e is PngTooLargeException, e.message)
}

class PngTest {
    @Test
    fun anEncodedImageDecodesToTheSamePixels() {
        val rgba = Random(7).nextBytes(13 * 9 * RGBA)
        val png = Png.encode(13, 9, rgba)
        val header = Png.header(png)
        assertEquals(listOf(13, 9, 8, COLOR_RGBA), listOf(header.width, header.height, header.bitDepth, header.colorType))
        assertEquals(13L * 9 * RGBA, header.rgbaBytes)
        val image = decode(png)
        assertEquals(13 to 9, image.width to image.height)
        assertContentEquals(rgba, image.rgba)
    }

    @Test
    fun anImageFromAnotherEncoderDecodes() {
        // macOS `sips` (ImageIO) from a 7x5 BMP: Sub and Paeth filters, sRGB, eXIf and pHYs chunks.
        val png =
            (
                "89504e470d0a1a0a0000000d494844520000000700000005080200000006f8618f000000017352474200aece1ce9" +
                    "00000078655849664d4d002a000000080004011a0005000000010000003e011b0005000000010000004601280003" +
                    "000000010002000087690004000000010000004e00000000000000480000000100000048000000010003a0010003" +
                    "0000000100010000a00200040000000100000007a00300040000000100000005000000009819b801000000097048" +
                    "597300000b1300000b1301009a9c180000004649444154081d6364603052656550e56750956450556650d5655035" +
                    "6760e19665e066e5e5e6e7e596e4e5968522b8a814b7a414b72c14218baa73cb42119aa809b72c10f90100a32e08" +
                    "32411ed3860000000049454e44ae426082"
            ).hexToByteArray()
        val want =
            (0 until 5).flatMap { y ->
                (0 until 7).map { x -> listOf((x * 37 + y * 11) % 256, (x * x * 5 + y * 29) % 256, (x * y * 13 + 50) % 256, 255) }
            }
        assertEquals(want, pixels(png))
    }

    @Test
    fun greyAtEveryDepthStretchesToTheFullRange() {
        for (depth in listOf(1, 2, 4, 8)) {
            val max = (1 shl depth) - 1
            // Nine pixels a row, so no depth below 8 fills its last byte.
            val samples = List(9 * 2) { it % (max + 1) }
            val want = samples.map { grey(it * 255 / max) }
            assertEquals(want, pixels(build(9, 2, depth, COLOR_GREY, samples)), "depth $depth")
        }
    }

    @Test
    fun sixteenBitSamplesAreRoundedToEightBits() {
        val samples = listOf(0, 65535, 25700, 33152)
        assertEquals(listOf(grey(0), grey(255), grey(100), grey(129)), pixels(build(4, 1, 16, COLOR_GREY, samples)))
        val rgba = listOf(65535, 0, 257, 32768)
        assertEquals(listOf(listOf(255, 0, 1, 128)), pixels(build(1, 1, 16, COLOR_RGBA, rgba)))
    }

    @Test
    fun greyAndRgbTransparencyKeyOneColourAtFullDepth() {
        val grey = build(3, 1, 4, COLOR_GREY, listOf(0, 5, 15), extra = listOf("tRNS" to bytes(0, 5)))
        assertEquals(listOf(grey(0), grey(85, 0), grey(255)), pixels(grey))
        val key = bytes(0x12, 0x34, 0, 1, 0xFF, 0xFF)
        val rgb = build(2, 1, 16, COLOR_RGB, listOf(0x1234, 1, 0xFFFF, 0x1234, 1, 0xFFFE), extra = listOf("tRNS" to key))
        assertEquals(listOf(listOf(18, 0, 255, 0), listOf(18, 0, 255, 255)), pixels(rgb), "16 bits compared, not 8")
    }

    @Test
    fun greyAlphaKeepsItsAlpha() {
        assertEquals(listOf(grey(10, 20), grey(200, 255)), pixels(build(2, 1, 8, COLOR_GREY_ALPHA, listOf(10, 20, 200, 255))))
        assertEquals(listOf(grey(255, 0)), pixels(build(1, 1, 16, COLOR_GREY_ALPHA, listOf(65535, 0))))
    }

    @Test
    fun paletteImagesAtEveryDepthTakeTheirAlphaFromTrns() {
        for (depth in listOf(1, 2, 4, 8)) {
            val entries = minOf(1 shl depth, 6)
            val plte = ByteArray(entries * 3) { (it * 40 + depth).toByte() }
            // tRNS may be shorter than the palette; the rest stays opaque.
            val trns = ByteArray(entries - 1) { (it * 50).toByte() }
            val samples = List(11) { it % entries }
            val png = build(11, 1, depth, COLOR_PALETTE, samples, extra = listOf("PLTE" to plte, "tRNS" to trns))
            val want =
                samples.map { i ->
                    (0 until 3).map { (plte[i * 3 + it].toInt() and 0xFF) } + if (i < entries - 1) i * 50 else 255
                }
            assertEquals(want, pixels(png), "depth $depth")
        }
    }

    @Test
    fun everyFilterUndoesToTheSamePixels() {
        val rng = Random(3)
        for ((depth, color) in listOf(8 to COLOR_RGB, 16 to COLOR_RGBA, 2 to COLOR_GREY)) {
            val channels = PngHeader(1, 1, depth, color, false).channels
            val samples = List(6 * 5 * channels) { rng.nextInt(1 shl depth) }
            val plain = pixels(build(6, 5, depth, color, samples))
            for (f in 1..4) assertEquals(plain, pixels(build(6, 5, depth, color, samples) { f }), "filter $f, depth $depth")
            assertEquals(plain, pixels(build(6, 5, depth, color, samples) { it % 5 }), "mixed, depth $depth")
        }
    }

    @Test
    fun anInterlacedImageMatchesItsPlainForm() {
        val rng = Random(5)
        // Sizes that leave some passes empty, and one that fills them all.
        for ((w, h) in listOf(1 to 1, 3 to 2, 5 to 1, 11 to 7, 17 to 13)) {
            for ((depth, color) in listOf(8 to COLOR_RGBA, 2 to COLOR_GREY)) {
                val channels = PngHeader(1, 1, depth, color, false).channels
                val samples = List(w * h * channels) { rng.nextInt(1 shl depth) }
                val plain = pixels(build(w, h, depth, color, samples))
                val interlaced = build(w, h, depth, color, samples, interlaced = true) { it % 5 }
                assertTrue(Png.header(interlaced).interlaced)
                assertEquals(plain, pixels(interlaced), "${w}x$h depth $depth")
            }
        }
    }

    @Test
    fun theCapIsCheckedFromTheHeaderBeforeDecoding() {
        val png = Png.encode(4, 3, ByteArray(4 * 3 * RGBA))
        val needed = 4L * 3 * RGBA + 2 * (4 * RGBA + 1)
        assertEquals(
            needed,
            Png
                .decode(png, needed)
                .rgba.size
                .toLong() + 2 * (4 * RGBA + 1),
        )
        val e = assertFailsWith<PngTooLargeException> { Png.decode(png, needed - 1) }
        assertEquals(needed to needed - 1, e.needed to e.limit)

        // A header alone claims 2^31-1 squared pixels: read without allocating, refused whatever the cap.
        val huge = pngFile(ihdr(Int.MAX_VALUE, Int.MAX_VALUE, 16, COLOR_RGBA), emptyList())
        assertEquals(Int.MAX_VALUE.toLong() * Int.MAX_VALUE * RGBA, Png.header(huge).rgbaBytes)
        assertFailsWith<PngTooLargeException> { Png.decode(huge, Long.MAX_VALUE) }
        assertFailsWith<PngTooLargeException> { Png.decode(pngFile(ihdr(1 shl 30, 1, 16, COLOR_RGBA), emptyList()), Long.MAX_VALUE) }
    }

    @Test
    fun dataPastTheImageIsNeverInflated() {
        // 64 MiB of zeros in a few KiB: one pixel's worth is read, and the decode stays inside its cap.
        val bomb = deflate(ByteArray(64 * MIB.toInt()))
        val png = pngFile(ihdr(1, 1, 8, COLOR_GREY), listOf("IDAT" to bomb))
        assertEquals(listOf(grey(0)), pixels(png))
    }

    @Test
    fun malformedInputIsRefused() {
        val good = build(2, 2, 8, COLOR_GREY, listOf(1, 2, 3, 4))
        refused(ByteArray(0))
        refused(good.flip(0))
        refused(good.flip(20)) // IHDR's data, against its CRC
        refused(good.flip(good.size - 13)) // IDAT's CRC
        refused(good.copyOf(good.size - 20)) // cut inside IDAT
        refused(good.copyOf(33)) // nothing after IHDR
        refused(pngFile(ihdr(0, 1, 8, COLOR_GREY), emptyList()))
        refused(pngFile(ihdr(1, 1, 4, COLOR_RGB), emptyList()))
        refused(pngFile(ihdr(1, 1, 8, COLOR_GREY), listOf("IDAT" to deflate(bytes(0)))))
        refused(pngFile(ihdr(1, 1, 8, COLOR_GREY), listOf("IDAT" to deflate(bytes(5, 0)))))
        refused(pngFile(ihdr(1, 1, 8, COLOR_GREY), listOf("IDAT" to bytes(1, 2, 3, 4))))
        refused(build(1, 1, 8, COLOR_GREY, listOf(1), extra = listOf("ABCD" to ByteArray(0))))
        refused(build(1, 1, 8, COLOR_PALETTE, listOf(0)))
        refused(build(1, 1, 8, COLOR_GREY, listOf(1), extra = listOf("PLTE" to ByteArray(3))))
        refused(build(1, 1, 8, COLOR_PALETTE, listOf(1), extra = listOf("PLTE" to ByteArray(3))))
        refused(build(1, 1, 8, COLOR_PALETTE, listOf(0), extra = listOf("tRNS" to ByteArray(1), "PLTE" to ByteArray(3))))
        refused(build(1, 1, 1, COLOR_PALETTE, listOf(0), extra = listOf("PLTE" to ByteArray(9))))
        refused(build(1, 1, 8, COLOR_GREY, listOf(1), extra = listOf("tRNS" to ByteArray(1))))
    }

    @Test
    fun ancillaryChunksAreSkippedAndImageDataMaySpanChunks() {
        val data = deflate(bytes(0, 7, 0, 9))
        val split = listOf("IDAT" to data.copyOfRange(0, 3), "IDAT" to ByteArray(0), "IDAT" to data.copyOfRange(3, data.size))
        val png = pngFile(ihdr(1, 2, 8, COLOR_GREY), listOf("gAMA" to ByteArray(4), "abCD" to ByteArray(2)) + split)
        assertEquals(listOf(grey(7), grey(9)), pixels(png))
        // Colour palettes are only suggestions for a true-colour image.
        assertEquals(
            listOf(listOf(1, 2, 3, 255)),
            pixels(build(1, 1, 8, COLOR_RGB, listOf(1, 2, 3), extra = listOf("PLTE" to ByteArray(3)))),
        )
    }
}
