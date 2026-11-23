package scull.image

// Sixel graphics as the VT330/VT340 Programmer Reference defines them, chapter 14
// (https://vt100.net/docs/vt3xx-gp/chapter14.html):
//   DCS P1 ; P2 ; P3 q <data> ST
//   ? .. ~      six pixels of one column, bit 0 on top, in the current colour
//   ! Pn c      sixel c repeated Pn times
//   " Pan;Pad;Ph;Pv   aspect ratio and image size, before any sixel
//   # Pc        select colour register Pc;  # Pc;Pu;Px;Py;Pz  define it (1 HLS, 2 RGB, in percent)
//   $  -        back to the left edge; the same, six rows down
// The aspect ratio (P1, Pan/Pad) is not applied: today's encoders send 1:1, and the terminals
// that read sixel for them (xterm, foot, WezTerm) draw square pixels.

/** Colour registers: Table 14-1 of the reference allows 0 to 255. */
private const val REGISTERS = 256
private const val MAX_PARAMS = 5
private const val PARAM_CAP = 1 shl 24
private const val BAND = 6
private const val OPAQUE = 0xFF.toByte()

private const val GROUND = 0
private const val REPEAT = 1
private const val RASTER = 2
private const val COLOR = 3

/** The VT340 colour map (reference chapter 2, Table 2-3), red, green and blue in percent. */
private val VT340 =
    intArrayOf(
        0,
        0,
        0,
        20,
        20,
        80,
        80,
        13,
        13,
        20,
        80,
        20,
        80,
        20,
        80,
        20,
        80,
        80,
        80,
        80,
        20,
        53,
        53,
        53,
        26,
        26,
        26,
        33,
        33,
        60,
        60,
        26,
        26,
        33,
        60,
        33,
        60,
        33,
        60,
        33,
        60,
        60,
        60,
        60,
        33,
        80,
        80,
        80,
    )

/**
 * Decodes one sixel image streamed in through [put], the DCS payload after `q`.
 *
 * Pixels no sixel sets stay transparent when [transparentBackground] (P2 = 1); otherwise they
 * take [background], `0xRRGGBB`, the terminal's background colour. The image grows as sixels
 * arrive, never past [maxWidth] by [maxHeight] pixels or [maxPixelBytes] of RGBA; past them the
 * rest of the stream is ignored and [finish] throws [ImageError.TooLarge].
 */
public class SixelDecoder(
    private val transparentBackground: Boolean,
    private val maxWidth: Int,
    private val maxHeight: Int,
    private val maxPixelBytes: Long,
    private val background: Int = 0,
) {
    private val palette =
        IntArray(REGISTERS) { i ->
            if (i * 3 >= VT340.size) 0 else (percent(VT340[i * 3]) shl 16) or (percent(VT340[i * 3 + 1]) shl 8) or percent(VT340[i * 3 + 2])
        }
    private var color = 0

    private var state = GROUND
    private val params = IntArray(MAX_PARAMS)
    private var paramCount = 0

    private var x = 0
    private var band = 0
    private var width = 0
    private var height = 0
    private var rasterWidth = 0
    private var rasterHeight = 0
    private var drawn = false

    private var pixels = ByteArray(0)
    private var stride = 0
    private var rows = 0
    private var failure: ImageError? = null

    /** Feeds `bytes[start until end]`. */
    public fun put(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ) {
        for (i in start until end) {
            if (failure != null) return
            byte(bytes[i].toInt() and 0xFF)
        }
    }

    /**
     * The decoded image, as large as the sixels drawn or the raster attributes asked for.
     *
     * @throws ImageError.TooLarge when the stream went past a cap.
     * @throws ImageError.Decode when it drew nothing and gave no size.
     */
    public fun finish(): RgbaImage {
        // A command at the very end has no next byte to end it; a repeat without its sixel draws nothing.
        if (state != GROUND && state != REPEAT && failure == null) apply(state)
        state = GROUND
        failure?.let { throw it }
        val w = maxOf(width, rasterWidth)
        val h = maxOf(height, rasterHeight)
        if (w == 0 || h == 0) throw ImageError.Decode("empty sixel image")
        // Each side passed its own cap, but a raster size on one axis and sixels on the other can
        // still make a canvas past the byte cap.
        if (w.toLong() * h * RGBA > maxPixelBytes) throw ImageError.TooLarge(w.toLong() * h * RGBA, maxPixelBytes)
        val out = ByteArray(w * h * RGBA)
        for (y in 0 until minOf(h, rows)) {
            pixels.copyInto(out, y * w * RGBA, y * stride * RGBA, (y * stride + minOf(w, stride)) * RGBA)
        }
        if (!transparentBackground) {
            for (o in out.indices step RGBA) {
                if (out[o + 3] != 0.toByte()) continue
                out[o] = (background shr 16).toByte()
                out[o + 1] = (background shr 8).toByte()
                out[o + 2] = background.toByte()
                out[o + 3] = OPAQUE
            }
        }
        return RgbaImage(w, h, out)
    }

    private fun byte(b: Int) {
        if (state != GROUND) {
            when (b) {
                in '0'.code..'9'.code -> {
                    if (paramCount == 0) paramCount = 1
                    val i = paramCount - 1
                    params[i] = minOf(params[i] * 10 + (b - '0'.code), PARAM_CAP)
                    return
                }

                ';'.code -> {
                    if (paramCount == 0) paramCount = 1
                    if (paramCount < MAX_PARAMS) params[paramCount++] = 0
                    return
                }

                else -> {
                    val command = state
                    state = GROUND
                    // A repeat ends with the sixel it repeats; the other commands end before the next byte.
                    if (command == REPEAT) {
                        if (b in '?'.code..'~'.code) return sixel(b - '?'.code, maxOf(params[0], 1))
                    } else {
                        apply(command)
                    }
                }
            }
        }
        when (b) {
            in '?'.code..'~'.code -> {
                sixel(b - '?'.code, 1)
            }

            '!'.code, '"'.code, '#'.code -> {
                state =
                    when (b) {
                        '!'.code -> REPEAT
                        '"'.code -> RASTER
                        else -> COLOR
                    }
                params.fill(0)
                paramCount = 0
            }

            '$'.code -> {
                x = 0
            }

            '-'.code -> {
                x = 0
                band++
            }
        }
    }

    private fun apply(command: Int) {
        when (command) {
            RASTER -> {
                // The size only counts before the first sixel, as the reference requires.
                if (drawn || paramCount < 4) return
                val (w, h) = params[2] to params[3]
                if (w > maxWidth || h > maxHeight || w.toLong() * h * RGBA > maxPixelBytes) {
                    return fail(w.toLong() * h * RGBA)
                }
                rasterWidth = w
                rasterHeight = h
            }

            COLOR -> {
                val register = params[0]
                if (register >= REGISTERS) return
                if (paramCount >= MAX_PARAMS) {
                    val (a, b, c) = Triple(params[2], params[3], params[4])
                    palette[register] =
                        when (params[1]) {
                            1 -> hls(minOf(a, 360), minOf(b, 100), minOf(c, 100))
                            2 -> (percent(minOf(a, 100)) shl 16) or (percent(minOf(b, 100)) shl 8) or percent(minOf(c, 100))
                            else -> return
                        }
                }
                color = register
            }
        }
    }

    private fun sixel(
        bits: Int,
        count: Int,
    ) {
        drawn = true
        if (x.toLong() + count > maxWidth) return fail((x.toLong() + count) * maxOf(height, 1) * RGBA)
        if (bits != 0) {
            val top = band.toLong() * BAND
            val bottom = top + (Int.SIZE_BITS - bits.countLeadingZeroBits())
            if (bottom > maxHeight) return fail((x.toLong() + count) * bottom * RGBA)
            if (!ensure(x + count, bottom.toInt())) return
            val rgb = palette[color]
            for (bit in 0 until BAND) {
                if (bits and (1 shl bit) == 0) continue
                var o = ((top.toInt() + bit) * stride + x) * RGBA
                repeat(count) {
                    pixels[o] = (rgb shr 16).toByte()
                    pixels[o + 1] = (rgb shr 8).toByte()
                    pixels[o + 2] = rgb.toByte()
                    pixels[o + 3] = OPAQUE
                    o += RGBA
                }
            }
            height = maxOf(height, bottom.toInt())
        }
        x += count
        width = maxOf(width, x)
    }

    /** Grows the pixel buffer to at least [w] by [h], doubling within the caps; false when it failed. */
    private fun ensure(
        w: Int,
        h: Int,
    ): Boolean {
        if (w <= stride && h <= rows) return true
        val needW = maxOf(w, stride)
        val needH = maxOf(h, rows)
        if (needW.toLong() * needH * RGBA > maxPixelBytes) {
            fail(needW.toLong() * needH * RGBA)
            return false
        }
        var newW = maxOf(needW, minOf(stride * 2, maxWidth))
        var newH = maxOf(needH, minOf(rows * 2, maxHeight))
        // Doubling must not cost the cap an image that fits it exactly.
        if (newW.toLong() * newH * RGBA > maxPixelBytes) {
            newW = needW
            newH = needH
        }
        val grown = ByteArray(newW * newH * RGBA)
        for (y in 0 until rows) pixels.copyInto(grown, y * newW * RGBA, y * stride * RGBA, (y + 1) * stride * RGBA)
        pixels = grown
        stride = newW
        rows = newH
        return true
    }

    private fun fail(needed: Long) {
        failure = ImageError.TooLarge(needed, maxPixelBytes)
    }
}

private fun percent(p: Int): Int = (p * 255 + 50) / 100

/**
 * A DEC HLS colour as `0xRRGGBB`. DEC puts blue at hue 0, red at 120 and green at 240, so the
 * hue turns by 240 degrees into the usual HLS where red is 0.
 */
private fun hls(
    hue: Int,
    lightness: Int,
    saturation: Int,
): Int {
    val h = ((hue + 240) % 360) / 360.0
    val l = lightness / 100.0
    val s = saturation / 100.0
    if (s == 0.0) return percent(lightness).let { (it shl 16) or (it shl 8) or it }
    val q = if (l < 0.5) l * (1 + s) else l + s - l * s
    val p = 2 * l - q

    fun channel(t0: Double): Int {
        val t = (t0 + 1) % 1.0
        val v =
            when {
                t < 1.0 / 6 -> p + (q - p) * 6 * t
                t < 1.0 / 2 -> q
                t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                else -> p
            }
        return (v * 255 + 0.5).toInt().coerceIn(0, 255)
    }
    return (channel(h + 1.0 / 3) shl 16) or (channel(h) shl 8) or channel(h - 1.0 / 3)
}
