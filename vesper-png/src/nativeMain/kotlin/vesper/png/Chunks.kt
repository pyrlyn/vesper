package vesper.png

internal const val IHDR = 0x49484452
internal const val PLTE = 0x504C5445
internal const val IDAT = 0x49444154
internal const val IEND = 0x49454E44
internal const val TRNS = 0x74524E53

/** Bit 5 of a chunk type's first letter: clear for a chunk a decoder must understand. */
private const val ANCILLARY = 0x20
private const val IHDR_BYTES = 13
private const val MAX_CHUNK = Int.MAX_VALUE

/** One chunk of the input: its type and where its data lies. */
internal class Chunk(
    val type: Int,
    val start: Int,
    val length: Int,
) {
    val isCritical: Boolean get() = (type ushr 24) and ANCILLARY == 0
}

/** Walks the chunks of [png], checking that each fits in the input. */
internal class Chunks(
    val png: ByteArray,
) {
    private var at = 0

    /** Checks the signature and the IHDR chunk and leaves the walk after it. */
    fun header(): PngHeader {
        if (png.size < SIGNATURE.size || !SIGNATURE.indices.all { png[it] == SIGNATURE[it] }) throw PngException("not a PNG")
        at = SIGNATURE.size
        val ihdr = next() ?: throw PngException("no IHDR")
        if (ihdr.type != IHDR || ihdr.length != IHDR_BYTES) throw PngException("the first chunk is not IHDR")
        verify(ihdr)
        val s = ihdr.start
        val width = png.int(s)
        val height = png.int(s + 4)
        val depth = png[s + 8].toInt()
        val color = png[s + 9].toInt()
        val interlace = png[s + 12].toInt()
        // The spec caps both sides at 2^31-1, which a positive Int holds.
        if (width <= 0 || height <= 0) throw PngException("bad size $width x $height")
        if (depth !in depths(color)) throw PngException("bad depth $depth for colour type $color")
        if (png[s + 10].toInt() != 0 || png[s + 11].toInt() != 0) throw PngException("unknown compression or filter method")
        if (interlace !in 0..1) throw PngException("unknown interlace method $interlace")
        return PngHeader(width, height, depth, color, interlace == 1)
    }

    /** The next chunk, or null at the end of the input. */
    fun next(): Chunk? {
        if (at == png.size) return null
        if (png.size - at < 12) throw PngException("truncated chunk")
        val length = png.int(at)
        // A negative Int is a length past 2^31-1, which the spec forbids.
        if (length < 0 || length > MAX_CHUNK - 12 || png.size - at - 12 < length) throw PngException("truncated chunk")
        val chunk = Chunk(png.int(at + 4), at + 8, length)
        at += length + 12
        return chunk
    }

    /** Checks [chunk]'s CRC, which covers its type and data. */
    fun verify(chunk: Chunk) {
        val crc = png.int(chunk.start + chunk.length).toUInt()
        if (crc32(png, chunk.start - 4, chunk.length + 4) != crc) throw PngException("bad CRC")
    }
}

private fun depths(colorType: Int): Set<Int> =
    when (colorType) {
        COLOR_GREY -> setOf(1, 2, 4, 8, 16)
        COLOR_PALETTE -> setOf(1, 2, 4, 8)
        COLOR_RGB, COLOR_GREY_ALPHA, COLOR_RGBA -> setOf(8, 16)
        else -> emptySet()
    }
