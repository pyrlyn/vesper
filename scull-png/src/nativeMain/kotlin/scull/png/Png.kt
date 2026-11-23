package scull.png

/**
 * A decoded image: [width] by [height] pixels of 8-bit sRGB RGBA, straight alpha, row after row.
 * Colour chunks (`gAMA`, `cHRM`, `sRGB`, `iCCP`) are not applied: samples are taken as sRGB, as
 * terminals show them.
 */
public class PngImage(
    public val width: Int,
    public val height: Int,
    public val rgba: ByteArray,
)

/** What the IHDR chunk says, read before anything is allocated. */
public class PngHeader internal constructor(
    public val width: Int,
    public val height: Int,
    public val bitDepth: Int,
    public val colorType: Int,
    public val interlaced: Boolean,
) {
    /** Bytes of the decoded RGBA image. */
    public val rgbaBytes: Long get() = width.toLong() * height * RGBA

    internal val channels: Int
        get() =
            when (colorType) {
                COLOR_GREY_ALPHA -> 2
                COLOR_RGB -> 3
                COLOR_RGBA -> 4
                else -> 1
            }

    internal val bitsPerPixel: Int get() = channels * bitDepth

    /** Bytes between a byte and the one the filters predict it from: a whole pixel, at least one byte. */
    internal val filterStride: Int get() = ((bitsPerPixel + 7) / 8)

    /** Bytes of one filtered scanline [pixels] wide, without its filter byte. */
    internal fun rowBytes(pixels: Int): Long = (pixels.toLong() * bitsPerPixel + 7) / 8

    /** Everything [Png.decode] allocates that grows with the image: the RGBA buffer and two scanlines. */
    internal val decodeBytes: Long get() = rgbaBytes + 2 * (rowBytes(width) + 1)
}

/** Input that is not a PNG this decoder reads. */
public open class PngException(
    message: String,
) : Exception(message)

/** An image whose decode would allocate [needed] bytes, past the caller's [limit]. Nothing was allocated. */
public class PngTooLargeException(
    public val needed: Long,
    public val limit: Long,
) : PngException("decoding needs $needed bytes, more than the $limit allowed")

/**
 * PNG decode for untrusted input, with a hard cap on what it allocates, and an RGBA encoder for
 * fixtures.
 */
public object Png {
    /**
     * Reads the signature and IHDR of [png] without decoding.
     *
     * @throws PngException when they are not a valid PNG header.
     */
    public fun header(png: ByteArray): PngHeader = Chunks(png).header()

    /**
     * Decodes [png] to RGBA. The header is checked first: an image whose RGBA buffer and two
     * scanlines need more than [maxBytes] fails before anything is allocated, and nothing else
     * grows with the input. Compressed data past the image's size is never inflated.
     *
     * @throws PngTooLargeException when the image needs more than [maxBytes].
     * @throws PngException for malformed or truncated input.
     */
    public fun decode(
        png: ByteArray,
        maxBytes: Long,
    ): PngImage {
        val chunks = Chunks(png)
        val header = chunks.header()
        val needed = header.decodeBytes
        // A Kotlin array holds at most Int.MAX_VALUE bytes, whatever the caller allows.
        if (needed > maxBytes || header.rgbaBytes > MAX_ARRAY || header.rowBytes(header.width) + 1 > MAX_ARRAY) {
            throw PngTooLargeException(needed, maxBytes)
        }
        return Decoder(header, chunks).decode()
    }

    /** Encodes [width] by [height] pixels of 8-bit RGBA [rgba] as a PNG, unfiltered. */
    public fun encode(
        width: Int,
        height: Int,
        rgba: ByteArray,
    ): ByteArray {
        require(width > 0 && height > 0) { "empty image $width x $height" }
        val stride = width.toLong() * RGBA
        require(rgba.size.toLong() == stride * height) { "${rgba.size} bytes for $width x $height RGBA" }
        val raw = ByteArray(((stride + 1) * height).toInt())
        for (y in 0 until height) rgba.copyInto(raw, y * (stride.toInt() + 1) + 1, y * stride.toInt(), (y + 1) * stride.toInt())
        return pngFile(ihdr(width, height, bitDepth = 8, colorType = COLOR_RGBA), listOf("IDAT" to deflate(raw)))
    }
}

internal const val RGBA = 4
internal const val COLOR_GREY = 0
internal const val COLOR_RGB = 2
internal const val COLOR_PALETTE = 3
internal const val COLOR_GREY_ALPHA = 4
internal const val COLOR_RGBA = 6
private const val MAX_ARRAY = Int.MAX_VALUE - 8L

internal val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

/** The 13 bytes of an IHDR chunk. */
internal fun ihdr(
    width: Int,
    height: Int,
    bitDepth: Int,
    colorType: Int,
    interlaced: Boolean = false,
): ByteArray =
    ByteArray(13).also {
        it.putInt(0, width)
        it.putInt(4, height)
        it[8] = bitDepth.toByte()
        it[9] = colorType.toByte()
        it[12] = if (interlaced) 1 else 0
    }

/** A PNG file: the signature, IHDR from [ihdr], then [chunks] by type and data, then IEND. */
internal fun pngFile(
    ihdr: ByteArray,
    chunks: List<Pair<String, ByteArray>>,
): ByteArray {
    val all = listOf("IHDR" to ihdr) + chunks + listOf("IEND" to ByteArray(0))
    val out = ByteArray(SIGNATURE.size + all.sumOf { it.second.size + 12 })
    SIGNATURE.copyInto(out)
    var at = SIGNATURE.size
    for ((type, data) in all) {
        out.putInt(at, data.size)
        type.encodeToByteArray().copyInto(out, at + 4)
        data.copyInto(out, at + 8)
        out.putInt(at + 8 + data.size, crc32(out, at + 4, data.size + 4).toInt())
        at += data.size + 12
    }
    return out
}

internal fun ByteArray.putInt(
    at: Int,
    value: Int,
) {
    for (i in 0 until 4) this[at + i] = (value ushr (24 - 8 * i)).toByte()
}

internal fun ByteArray.int(at: Int): Int =
    (this[at].toInt() and 0xFF shl 24) or (this[at + 1].toInt() and 0xFF shl 16) or
        (this[at + 2].toInt() and 0xFF shl 8) or (this[at + 3].toInt() and 0xFF)
