package scull.png

private const val MAX_PALETTE = 256
private const val OPAQUE = 0xFF.toByte()
private const val MAX_16 = 65535
private const val FILTER_NONE = 0
private const val FILTER_SUB = 1
private const val FILTER_UP = 2
private const val FILTER_AVERAGE = 3
private const val FILTER_PAETH = 4

/** An Adam7 pass, or the whole image: the first pixel and the steps between pixels. */
private class Pass(
    val x0: Int,
    val y0: Int,
    val dx: Int,
    val dy: Int,
)

private val WHOLE = listOf(Pass(0, 0, 1, 1))
private val ADAM7 =
    listOf(
        Pass(0, 0, 8, 8),
        Pass(4, 0, 8, 8),
        Pass(0, 4, 4, 8),
        Pass(2, 0, 4, 4),
        Pass(0, 2, 2, 4),
        Pass(1, 0, 2, 2),
        Pass(0, 1, 1, 2),
    )

/** One decode of an image whose header passed the cap. */
internal class Decoder(
    private val h: PngHeader,
    private val chunks: Chunks,
) {
    /** RGBA per palette entry; tRNS lowers the alphas. */
    private var palette: ByteArray? = null
    private var paletteSize = 0

    /** The tRNS colour of a grey or RGB image, in raw samples; that colour is transparent. */
    private var key: IntArray? = null

    private val rgba = ByteArray(h.rgbaBytes.toInt())
    private val width = h.width
    private val bitDepth = h.bitDepth

    fun decode(): PngImage {
        val first = readToImageData()
        if (h.colorType == COLOR_PALETTE && palette == null) throw PngException("a palette image without PLTE")
        val rowCap = h.rowBytes(width).toInt() + 1
        var cur = ByteArray(rowCap)
        var prev = ByteArray(rowCap)
        Inflater(chunks, first).use { inflater ->
            for (pass in if (h.interlaced) ADAM7 else WHOLE) {
                val pw = ((width.toLong() - pass.x0 + pass.dx - 1) / pass.dx).toInt()
                val ph = ((h.height.toLong() - pass.y0 + pass.dy - 1) / pass.dy).toInt()
                // An empty pass has no scanlines, not even filter bytes.
                if (pw <= 0 || ph <= 0) continue
                val n = h.rowBytes(pw).toInt()
                prev.fill(0, 0, n + 1)
                for (y in 0 until ph) {
                    inflater.fill(cur, n + 1)
                    unfilter(cur, prev, n)
                    expand(cur, pw, pass, y)
                    cur = prev.also { prev = cur }
                }
            }
        }
        return PngImage(width, h.height, rgba)
    }

    /** Reads the chunks before the image data, keeping PLTE and tRNS; returns the first IDAT. */
    private fun readToImageData(): Chunk {
        while (true) {
            val chunk = chunks.next() ?: throw PngException("no image data")
            when (chunk.type) {
                IDAT -> {
                    return chunk
                }

                PLTE -> {
                    chunks.verify(chunk)
                    readPalette(chunk)
                }

                TRNS -> {
                    chunks.verify(chunk)
                    readTransparency(chunk)
                }

                IHDR, IEND -> {
                    throw PngException("no image data")
                }

                else -> {
                    if (chunk.isCritical) throw PngException("unknown critical chunk")
                }
            }
        }
    }

    private fun readPalette(chunk: Chunk) {
        if (palette != null) throw PngException("two PLTE chunks")
        if (h.colorType == COLOR_GREY || h.colorType == COLOR_GREY_ALPHA) throw PngException("PLTE in a grey image")
        val entries = chunk.length / 3
        val limit = if (h.colorType == COLOR_PALETTE) 1 shl bitDepth else MAX_PALETTE
        if (chunk.length % 3 != 0 || entries == 0 || entries > limit) throw PngException("bad PLTE length ${chunk.length}")
        // A true-colour image's palette only suggests colours for displays that need few.
        if (h.colorType != COLOR_PALETTE) return
        val png = chunks.png
        palette =
            ByteArray(entries * RGBA).also {
                for (i in 0 until entries) {
                    png.copyInto(it, i * RGBA, chunk.start + i * 3, chunk.start + i * 3 + 3)
                    it[i * RGBA + 3] = OPAQUE
                }
            }
        paletteSize = entries
    }

    private fun readTransparency(chunk: Chunk) {
        val png = chunks.png
        val s = chunk.start
        when (h.colorType) {
            COLOR_PALETTE -> {
                val table = palette ?: throw PngException("tRNS before PLTE")
                if (chunk.length > paletteSize) throw PngException("tRNS longer than the palette")
                for (i in 0 until chunk.length) table[i * RGBA + 3] = png[s + i]
            }

            COLOR_GREY, COLOR_RGB -> {
                val samples = if (h.colorType == COLOR_GREY) 1 else 3
                if (chunk.length != samples * 2) throw PngException("bad tRNS length ${chunk.length}")
                key = IntArray(samples) { ((png[s + 2 * it].toInt() and 0xFF) shl 8) or (png[s + 2 * it + 1].toInt() and 0xFF) }
            }

            // An image with an alpha channel has no use for it.
            else -> {}
        }
    }

    /** Undoes the filter of scanline [cur] (`n` bytes after its filter byte) against the one above, [prev]. */
    private fun unfilter(
        cur: ByteArray,
        prev: ByteArray,
        n: Int,
    ) {
        val bpp = h.filterStride
        when (cur[0].toInt()) {
            FILTER_NONE -> {}

            FILTER_SUB -> {
                for (i in 1 + bpp..n) cur[i] = (cur[i] + cur[i - bpp]).toByte()
            }

            FILTER_UP -> {
                for (i in 1..n) cur[i] = (cur[i] + prev[i]).toByte()
            }

            FILTER_AVERAGE -> {
                for (i in 1..n) {
                    val left = if (i > bpp) cur[i - bpp].toInt() and 0xFF else 0
                    cur[i] = (cur[i] + ((left + (prev[i].toInt() and 0xFF)) ushr 1)).toByte()
                }
            }

            FILTER_PAETH -> {
                for (i in 1..n) {
                    val a = if (i > bpp) cur[i - bpp].toInt() and 0xFF else 0
                    val b = prev[i].toInt() and 0xFF
                    val c = if (i > bpp) prev[i - bpp].toInt() and 0xFF else 0
                    cur[i] = (cur[i] + paeth(a, b, c)).toByte()
                }
            }

            else -> {
                throw PngException("unknown filter type ${cur[0]}")
            }
        }
    }

    /** Sample [i] of an unfiltered scanline, counted from the first after the filter byte. */
    private fun sample(
        row: ByteArray,
        i: Int,
    ): Int =
        when (bitDepth) {
            8 -> {
                row[1 + i].toInt() and 0xFF
            }

            16 -> {
                ((row[1 + 2 * i].toInt() and 0xFF) shl 8) or (row[2 + 2 * i].toInt() and 0xFF)
            }

            else -> {
                val bit = i * bitDepth
                val shift = 8 - bitDepth - bit % 8
                ((row[1 + bit / 8].toInt() and 0xFF) ushr shift) and ((1 shl bitDepth) - 1)
            }
        }

    /** A raw sample as an 8-bit one: low depths stretched to the full range, 16 bits scaled with rounding. */
    private fun to8(v: Int): Byte =
        when (bitDepth) {
            8 -> v
            16 -> (v * 255 + MAX_16 / 2) / MAX_16
            else -> v * 255 / ((1 shl bitDepth) - 1)
        }.toByte()

    /** Writes the [pw] pixels of scanline [y] of [pass] into the RGBA image. */
    private fun expand(
        row: ByteArray,
        pw: Int,
        pass: Pass,
        y: Int,
    ) {
        val base = (pass.y0 + y * pass.dy) * width
        val key = key
        for (x in 0 until pw) {
            val o = (base + pass.x0 + x * pass.dx) * RGBA
            when (h.colorType) {
                COLOR_GREY -> {
                    val g = sample(row, x)
                    val v = to8(g)
                    rgba[o] = v
                    rgba[o + 1] = v
                    rgba[o + 2] = v
                    rgba[o + 3] = if (key != null && key[0] == g) 0 else OPAQUE
                }

                COLOR_RGB -> {
                    val r = sample(row, 3 * x)
                    val g = sample(row, 3 * x + 1)
                    val b = sample(row, 3 * x + 2)
                    rgba[o] = to8(r)
                    rgba[o + 1] = to8(g)
                    rgba[o + 2] = to8(b)
                    rgba[o + 3] = if (key != null && key[0] == r && key[1] == g && key[2] == b) 0 else OPAQUE
                }

                COLOR_PALETTE -> {
                    val index = sample(row, x)
                    if (index >= paletteSize) throw PngException("palette index $index past the palette")
                    checkNotNull(palette).copyInto(rgba, o, index * RGBA, index * RGBA + RGBA)
                }

                COLOR_GREY_ALPHA -> {
                    val v = to8(sample(row, 2 * x))
                    rgba[o] = v
                    rgba[o + 1] = v
                    rgba[o + 2] = v
                    rgba[o + 3] = to8(sample(row, 2 * x + 1))
                }

                else -> {
                    for (c in 0 until RGBA) rgba[o + c] = to8(sample(row, RGBA * x + c))
                }
            }
        }
    }
}

/** The Paeth predictor: whichever of left, up and upper-left is nearest to left + up - upper-left. */
internal fun paeth(
    a: Int,
    b: Int,
    c: Int,
): Int {
    val p = a + b - c
    val pa = kotlin.math.abs(p - a)
    val pb = kotlin.math.abs(p - b)
    val pc = kotlin.math.abs(p - c)
    return if (pa <= pb && pa <= pc) {
        a
    } else if (pb <= pc) {
        b
    } else {
        c
    }
}
